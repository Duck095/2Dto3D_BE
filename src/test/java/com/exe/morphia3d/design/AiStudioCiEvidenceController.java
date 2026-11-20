package com.exe.morphia3d.design;

import com.exe.morphia3d.repository.DesignProjectRepository;
import com.exe.morphia3d.security.UserPrincipal;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Test-classpath-only, owner-scoped counters for the real HTTP CI harness. */
@RestController
@Profile("ai-studio-ci")
@RequestMapping("/api/ci-test/projects")
public class AiStudioCiEvidenceController {
    private final JdbcTemplate jdbc;
    private final DesignProjectRepository projects;
    private final TEST_ONLY_FakeReferenceImageProvider referenceProvider;
    private final TEST_ONLY_CiMeshyModelProvider modelProvider;

    public AiStudioCiEvidenceController(JdbcTemplate jdbc, DesignProjectRepository projects,
                                        TEST_ONLY_FakeReferenceImageProvider referenceProvider,
                                        TEST_ONLY_CiMeshyModelProvider modelProvider) {
        this.jdbc = jdbc;
        this.projects = projects;
        this.referenceProvider = referenceProvider;
        this.modelProvider = modelProvider;
    }

    @GetMapping("/{projectId}/evidence")
    public Evidence evidence(@PathVariable UUID projectId, @AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null || projects.findByIdAndOwnerUserId(projectId, principal.getId()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        List<JobEvidence> jobs = jdbc.query("SELECT job_id, stage, status, attempt_count FROM design_pipeline_jobs WHERE project_id=? ORDER BY created_at",
                (row, index) -> new JobEvidence(row.getObject("job_id", UUID.class), row.getString("stage"),
                        row.getString("status"), row.getInt("attempt_count")), projectId);
        Set<UUID> jobIds = jobs.stream().map(JobEvidence::jobId).collect(Collectors.toSet());
        int referenceCalls = (int) referenceProvider.invocationJobIds().stream().filter(jobIds::contains).count();
        return new Evidence(jobs.stream().map(job -> new PublicJobEvidence(job.stage(), job.status(), job.attemptCount())).toList(),
                referenceCalls, modelProvider.submitCallsFor(jobIds));
    }

    private record JobEvidence(UUID jobId, String stage, String status, int attemptCount) { }
    public record PublicJobEvidence(String stage, String status, int attemptCount) { }
    public record Evidence(List<PublicJobEvidence> sqlJobs, int referenceProviderCalls, int modelProviderSubmitCalls) { }
}
