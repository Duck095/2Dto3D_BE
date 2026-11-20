package com.exe.morphia3d.controller;

import com.exe.morphia3d.Morphia3dApplication;
import com.exe.morphia3d.design.ApprovalDecision;
import com.exe.morphia3d.design.DesignPipelineJobStatus;
import com.exe.morphia3d.design.DesignProjectStatus;
import com.exe.morphia3d.design.DesignStage;
import com.exe.morphia3d.design.DesignVersionStatus;
import com.exe.morphia3d.design.DesignArtifactDescriptor;
import com.exe.morphia3d.design.DesignArtifactStore;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import com.exe.morphia3d.entity.*;
import com.exe.morphia3d.enums.Role;
import com.exe.morphia3d.enums.UserStatus;
import com.exe.morphia3d.repository.*;
import com.exe.morphia3d.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.persistence.EntityManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.file.NoSuchFileException;
import java.time.Instant;
import java.sql.Timestamp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = Morphia3dApplication.class)
@ActiveProfiles("test")
@TestPropertySource(properties = {"app.features.ai.enabled=true", "app.design.worker.enabled=false",
        "media.upload.image-max-bytes=128"})
@Import(DesignProjectHistoryHttpIntegrationTest.ArtifactConfiguration.class)
@Transactional
class DesignProjectHistoryHttpIntegrationTest {
    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository users;
    @Autowired private DesignProjectRepository projects;
    @Autowired private DesignProjectInputRepository inputs;
    @Autowired private DesignReferenceSetRepository referenceSets;
    @Autowired private DesignReferenceSetVersionRepository referenceVersions;
    @Autowired private DesignReferenceImageVersionRepository imageVersions;
    @Autowired private DesignReferenceSetViewRepository views;
    @Autowired private DesignReferenceApprovalRepository referenceApprovals;
    @Autowired private DesignModelVersionRepository modelVersions;
    @Autowired private DesignModelApprovalRepository modelApprovals;
    @Autowired private DesignPipelineJobRepository jobs;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @Autowired private TestDesignArtifactStore artifactStore;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockMvc mvc;
    private User ownerA;
    private User ownerB;
    private Fixture projectA;
    private Fixture projectB;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        artifactStore.reset();
        ownerA = saveUser("owner-a");
        ownerB = saveUser("owner-b");
        projectA = saveProject(ownerA, "a");
        projectB = saveProject(ownerB, "b");
    }

    @Test
    void createRejectsDeclaredPngWithDifferentContentAndLeavesNoProjectOrArtifact() throws Exception {
        Counts before = counts();
        var response = mvc.perform(multipart("/api/ai-studio/projects")
                        .file(dataPart("image", "A small blue cat"))
                        .file(new MockMultipartFile("images", "cat.png", "image/png", "not a png".getBytes()))
                        .header("Idempotency-Key", "image-mismatch-" + UUID.randomUUID())
                        .with(authenticate(ownerA)))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("DESIGN_MEDIA_CONTENT_MISMATCH"))
                .andExpect(jsonPath("$.message").value("The uploaded image content does not match its declared format."))
                .andReturn().getResponse().getContentAsString();

        assertSanitizedError(response);
        assertNoCreationSideEffects(before);
    }

    @Test
    void createRejectsMalformedPngAndMissingImageAsBadRequestWithoutWrites() throws Exception {
        Counts before = counts();
        byte[] malformedPng = new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0};
        String malformed = mvc.perform(multipart("/api/ai-studio/projects")
                        .file(dataPart("image", "A malformed image"))
                        .file(new MockMultipartFile("images", "broken.png", "image/png", malformedPng))
                        .header("Idempotency-Key", "malformed-image-" + UUID.randomUUID())
                        .with(authenticate(ownerA)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DESIGN_MEDIA_MALFORMED_IMAGE"))
                .andExpect(jsonPath("$.message").value("The uploaded image is malformed or unreadable."))
                .andReturn().getResponse().getContentAsString();
        assertSanitizedError(malformed);
        assertNoCreationSideEffects(before);

        String missing = mvc.perform(multipart("/api/ai-studio/projects")
                        .file(dataPart("image", "An image is required"))
                        .header("Idempotency-Key", "missing-image-" + UUID.randomUUID())
                        .with(authenticate(ownerA)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DESIGN_INPUT_MODE_MISMATCH"))
                .andReturn().getResponse().getContentAsString();
        assertSanitizedError(missing);
        assertNoCreationSideEffects(before);

        String missingData = mvc.perform(multipart("/api/ai-studio/projects")
                        .file(new MockMultipartFile("images", "cat.png", "image/png", validPng()))
                        .header("Idempotency-Key", "missing-data-" + UUID.randomUUID())
                        .with(authenticate(ownerA)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DESIGN_INVALID_REQUEST"))
                .andReturn().getResponse().getContentAsString();
        assertSanitizedError(missingData);
        assertNoCreationSideEffects(before);
    }

    @Test
    void createRejectsImageOverConfiguredLimitAsPayloadTooLargeWithoutWrites() throws Exception {
        Counts before = counts();
        byte[] oversized = new byte[129];
        var response = mvc.perform(multipart("/api/ai-studio/projects")
                        .file(dataPart("image", "An oversized image"))
                        .file(new MockMultipartFile("images", "large.png", "image/png", oversized))
                        .header("Idempotency-Key", "oversized-image-" + UUID.randomUUID())
                        .with(authenticate(ownerA)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("DESIGN_MEDIA_TOO_LARGE"))
                .andExpect(jsonPath("$.message").value("The uploaded media exceeds the configured limits."))
                .andReturn().getResponse().getContentAsString();

        assertSanitizedError(response);
        assertNoCreationSideEffects(before);
    }

    @Test
    void createStoresValidPngAndSameIdempotencyKeyDoesNotDuplicateRowsOrArtifact() throws Exception {
        Counts before = counts();
        String key = "valid-image-" + UUID.randomUUID();
        byte[] image = validPng();
        String first = postProject(key, "A small blue cat", image).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode firstBody = objectMapper.readTree(first);
        UUID projectId = UUID.fromString(firstBody.path("projectId").asText());
        assertThat(firstBody.path("status").asText()).isEqualTo("DRAFT");
        assertThat(projects.findByIdAndOwnerUserId(projectId, ownerA.getId())).isPresent();
        assertThat(artifactStore.putCount()).isEqualTo(1);

        String replay = postProject(key, "A small blue cat", image).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(replay).path("projectId").asText()).isEqualTo(projectId.toString());
        assertThat(counts()).isEqualTo(before.plusOneProjectWithInputAndReferenceSet());
        assertThat(artifactStore.putCount()).isEqualTo(1);

        String conflict = mvc.perform(multipart("/api/ai-studio/projects")
                        .file(dataPart("image", "A different blue cat"))
                        .file(new MockMultipartFile("images", "cat.png", "image/png", image))
                        .header("Idempotency-Key", key).with(authenticate(ownerA)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DESIGN_IDEMPOTENCY_CONFLICT"))
                .andReturn().getResponse().getContentAsString();
        assertSanitizedError(conflict);
        assertThat(counts()).isEqualTo(before.plusOneProjectWithInputAndReferenceSet());
        assertThat(artifactStore.putCount()).isEqualTo(1);
    }

    @Test
    void createStillRequiresAuthenticationAndUnsupportedRequestMediaTypeHasStable415Response() throws Exception {
        Counts before = counts();
        mvc.perform(multipart("/api/ai-studio/projects")
                        .file(dataPart("image", "A small blue cat"))
                        .file(new MockMultipartFile("images", "cat.png", "image/png", validPng()))
                        .header("Idempotency-Key", "unauthenticated-" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());

        String unsupported = mvc.perform(post("/api/ai-studio/projects")
                        .contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header("Idempotency-Key", "unsupported-type-" + UUID.randomUUID())
                        .with(authenticate(ownerA)))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("DESIGN_UNSUPPORTED_REQUEST_MEDIA_TYPE"))
                .andExpect(jsonPath("$.message").value("The design project endpoint requires multipart form data."))
                .andReturn().getResponse().getContentAsString();
        assertSanitizedError(unsupported);
        assertNoCreationSideEffects(before);
    }

    private org.springframework.test.web.servlet.ResultActions postProject(String key, String idea, byte[] image)
            throws Exception {
        return mvc.perform(multipart("/api/ai-studio/projects")
                .file(dataPart("image", idea))
                .file(new MockMultipartFile("images", "cat.png", "image/png", image))
                .header("Idempotency-Key", key).with(authenticate(ownerA)));
    }

    private MockMultipartFile dataPart(String mode, String idea) throws Exception {
        String json = objectMapper.writeValueAsString(Map.of(
                "productType", "figure", "styleId", "chibi-collectible", "inputMode", mode, "originalIdea", idea));
        return new MockMultipartFile("data", "", MediaType.APPLICATION_JSON_VALUE,
                json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private byte[] validPng() throws IOException {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } finally {
            image.flush();
        }
    }

    private Counts counts() {
        entityManager.flush();
        return new Counts(projects.count(), inputs.count(), referenceSets.count(), jobs.count());
    }

    private void assertNoCreationSideEffects(Counts before) {
        assertThat(counts()).isEqualTo(before);
        assertThat(artifactStore.putCount()).isZero();
        assertThat(artifactStore.deleteCount()).isZero();
    }

    private void assertSanitizedError(String response) {
        assertThat(response).contains("\"code\"", "\"message\"")
                .doesNotContain("Uploaded media failed validation", "MediaValidationException", "java.", "D:\\");
    }

    private record Counts(long projects, long inputs, long referenceSets, long jobs) {
        Counts plusOneProjectWithInputAndReferenceSet() {
            return new Counts(projects + 1, inputs + 1, referenceSets + 1, jobs);
        }
    }

    @Test
    void historyIsOwnerScopedPagedAndRejectsOversizedPage() throws Exception {
        var latestVersions = addDuplicateHistoryRows(ownerA, projectA);
        var response = mvc.perform(get("/api/ai-studio/projects?page=0&size=20").with(authenticate(ownerA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].projectId").value(projectA.project().getId().toString()))
                .andExpect(jsonPath("$.content[0].productType").value("figure"))
                .andExpect(jsonPath("$.content[0].styleId").value("cute"))
                .andExpect(jsonPath("$.content[0].originalIdea").value("idea-a"))
                .andExpect(jsonPath("$.content[0].latestReferenceVersionId").value(latestVersions.referenceId().toString()))
                .andExpect(jsonPath("$.content[0].latestModelVersionId").value(latestVersions.modelId().toString()))
                .andReturn().getResponse().getContentAsString();
        JsonNode historySummary = objectMapper.readTree(response).path("content").get(0);
        assertThat(historySummary.path("keychainVariant").isNull()).isTrue();
        assertThat(historySummary.path("inputMode").asText()).isEqualTo("image");
        assertThat(response).doesNotContain("internal-image-key", "internal-model-key", "provider-secret");

        mvc.perform(get("/api/ai-studio/projects?page=99&size=20").with(authenticate(ownerA)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());
        mvc.perform(get("/api/ai-studio/projects?page=0&size=101").with(authenticate(ownerA)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DESIGN_INVALID_PAGINATION"));
        mvc.perform(get("/api/ai-studio/projects?page=0&size=20"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void timelineAndExistingReadEndpointsResumeFromPersistedRecordsWithoutCreatingJobs() throws Exception {
        long before = jobs.count();
        Instant createdAt = Instant.parse("2020-01-01T00:00:00Z");
        Instant updatedAt = Instant.parse("2025-01-01T00:00:00Z");
        jdbc.update("UPDATE design_projects SET created_at=?, updated_at=? WHERE project_id=?",
                Timestamp.from(createdAt), Timestamp.from(updatedAt), projectA.project().getId());
        entityManager.clear();
        Instant updatedAtBeforeRead = projects.findByIdAndOwnerUserId(projectA.project().getId(), ownerA.getId())
                .orElseThrow().getUpdatedAt();
        String timeline = mvc.perform(get("/api/ai-studio/projects/{id}/timeline", projectA.project().getId())
                        .with(authenticate(ownerA)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode body = objectMapper.readTree(timeline);
        assertThat(body.path("projectId").asText()).isEqualTo(projectA.project().getId().toString());
        JsonNode projectCreated = findEvent(body, "PROJECT_CREATED");
        JsonNode projectStatus = findEvent(body, "PROJECT_STATUS");
        assertThat(Instant.parse(projectCreated.path("occurredAt").asText())).isEqualTo(createdAt);
        assertThat(Instant.parse(projectStatus.path("occurredAt").asText())).isEqualTo(updatedAt);
        assertThat(projectCreated.path("eventId").asText()).isNotEqualTo(projectStatus.path("eventId").asText());
        JsonNode referenceApproval = findEvent(body, "REFERENCE_APPROVAL");
        assertThat(referenceApproval.path("decision").asText()).isEqualTo("CHANGES_REQUESTED");
        assertThat(referenceApproval.path("feedback").asText()).isEqualTo("adjust front");
        assertThat(findEvent(body, "MODEL_APPROVAL").path("decision").asText()).isEqualTo("APPROVED");
        JsonNode referenceVersion = findEvent(body, "REFERENCE_VERSION");
        assertThat(referenceVersion.path("feedback").asText()).isEqualTo("revise front");
        assertThat(referenceVersion.path("referenceViews").get(0).path("imageVersionId").asText())
                .isEqualTo(projectA.image().getId().toString());
        assertThat(referenceVersion.path("referenceViews").get(0).path("viewSlot").asText()).isEqualTo("front");
        assertThat(findEvent(body, "MODEL_VERSION").path("artifactUrl").asText())
                .isEqualTo("/api/ai-studio/projects/" + projectA.project().getId() + "/models/"
                        + projectA.model().getId() + "/artifact");
        assertThat(findRecord(body, "JOB_STATUS_SNAPSHOT").path("status").asText()).isEqualTo("COMPLETED");
        assertThat(timeline).doesNotContain("internal-image-key", "internal-model-key", "provider-secret");

        mvc.perform(get("/api/ai-studio/projects/{id}", projectA.project().getId()).with(authenticate(ownerA)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.projectId").value(projectA.project().getId().toString()));
        mvc.perform(get("/api/ai-studio/projects/{id}/pipeline", projectA.project().getId()).with(authenticate(ownerA)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.projectId").value(projectA.project().getId().toString()));
        mvc.perform(get("/api/ai-studio/projects/{projectId}/references/{versionId}/views/front",
                        projectA.project().getId(), projectA.reference().getId()).with(authenticate(ownerA)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/ai-studio/projects/{projectId}/models/{modelId}/artifact",
                        projectA.project().getId(), projectA.model().getId()).with(authenticate(ownerA)))
                .andExpect(status().isOk());
        JsonNode repeatedTimeline = objectMapper.readTree(mvc.perform(
                        get("/api/ai-studio/projects/{id}/timeline", projectA.project().getId()).with(authenticate(ownerA)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(repeatedTimeline.path("events").findValuesAsText("eventType"))
                .filteredOn("PROJECT_CREATED"::equals).hasSize(1);
        assertThat(findEvent(repeatedTimeline, "PROJECT_CREATED").path("eventId").asText())
                .isEqualTo(projectCreated.path("eventId").asText());
        assertThat(jobs.count()).isEqualTo(before);
        assertThat(projects.findByIdAndOwnerUserId(projectA.project().getId(), ownerA.getId()).orElseThrow().getUpdatedAt())
                .isEqualTo(updatedAtBeforeRead);
    }

    @Test
    void ownerCannotReadAnotherOwnersHistoryTimelineOrArtifacts() throws Exception {
        mvc.perform(get("/api/ai-studio/projects/{id}/timeline", projectB.project().getId()).with(authenticate(ownerA)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DESIGN_PROJECT_NOT_FOUND"));
        mvc.perform(get("/api/ai-studio/projects/{projectId}/models/{modelId}/artifact",
                        projectB.project().getId(), projectB.model().getId()).with(authenticate(ownerA)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DESIGN_ARTIFACT_NOT_FOUND"));
    }

    @Test
    void printReadinessIsAuthenticatedOwnerScopedNoStoreAndReadOnly() throws Exception {
        String path = "/api/ai-studio/projects/" + projectA.project().getId() + "/models/"
                + projectA.model().getId() + "/print-readiness";
        long jobsBefore = jobs.count(); long modelsBefore = modelVersions.count(); long approvalsBefore = modelApprovals.count();
        long referenceVersionsBefore = jdbc.queryForObject("SELECT count(*) FROM design_reference_set_versions", Long.class);
        long reservationsBefore = jdbc.queryForObject("SELECT count(*) FROM design_usage_reservations", Long.class);
        Instant updatedBefore = projects.findByIdAndOwnerUserId(projectA.project().getId(), ownerA.getId()).orElseThrow().getUpdatedAt();
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).with(authenticate(ownerA))).andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(projectA.project().getId().toString()))
                .andExpect(jsonPath("$.modelVersionId").value(projectA.model().getId().toString()))
                .andExpect(jsonPath("$.printReadiness").value("NOT_READY"))
                .andExpect(jsonPath("$.reasonCode").value("PROJECT_NOT_COMPLETED"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        mvc.perform(get("/api/ai-studio/projects/{projectId}/models/{modelId}/print-readiness",
                        projectB.project().getId(), projectB.model().getId()).with(authenticate(ownerA)))
                .andExpect(status().isNotFound());
        mvc.perform(get(path).with(authenticate(ownerA))).andExpect(status().isOk());
        assertThat(jobs.count()).isEqualTo(jobsBefore);
        assertThat(modelVersions.count()).isEqualTo(modelsBefore);
        assertThat(modelApprovals.count()).isEqualTo(approvalsBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_reference_set_versions", Long.class)).isEqualTo(referenceVersionsBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_usage_reservations", Long.class)).isEqualTo(reservationsBefore);
        assertThat(projects.findByIdAndOwnerUserId(projectA.project().getId(), ownerA.getId()).orElseThrow().getUpdatedAt())
                .isEqualTo(updatedBefore);
    }

    private User saveUser(String suffix) {
        return users.saveAndFlush(User.builder().name("Design " + suffix)
                .email("design-history-" + suffix + "-" + UUID.randomUUID() + "@example.invalid")
                .password("TEST_ONLY").role(Role.USER).status(UserStatus.ACTIVE).build());
    }

    private Fixture saveProject(User owner, String suffix) {
        UUID projectId = UUID.randomUUID();
        DesignProject project = new DesignProject(); project.setId(projectId); project.setOwnerUserId(owner.getId());
        project.setProductType("figure"); project.setStyleId("cute"); project.setInputMode("image");
        project.setOriginalIdea("idea-" + suffix); project.setNormalizedPrompt("normalized");
        project.setPromptProfileVersion("v1"); project.setStatus(DesignProjectStatus.MODEL_REVIEW);
        project.setCreateIdempotencyKey("project-key-" + suffix); project.setRequestFingerprint("a".repeat(64));
        project = projects.saveAndFlush(project);

        DesignProjectInput input = new DesignProjectInput(); input.setId(UUID.randomUUID()); input.setProjectId(projectId);
        input.setOwnerUserId(owner.getId()); input.setInputOrder(0); input.setArtifactKey("internal-input-key");
        input.setMimeType("image/png"); input.setByteSize(12L); input.setSha256("b".repeat(64));
        inputs.saveAndFlush(input);

        UUID setId = UUID.randomUUID();
        DesignReferenceSet set = new DesignReferenceSet(); set.setId(setId); set.setProjectId(projectId);
        set.setOwnerUserId(owner.getId()); referenceSets.saveAndFlush(set);
        DesignReferenceSetVersion reference = new DesignReferenceSetVersion(); reference.setId(UUID.randomUUID());
        reference.setReferenceSetId(setId); reference.setProjectId(projectId); reference.setOwnerUserId(owner.getId());
        reference.setVersionNumber(1); reference.setStatus(DesignVersionStatus.CHANGES_REQUESTED);
        reference.setNormalizedPrompt("normalized ref"); reference.setPromptProfileVersion("v1");
        reference.setRevisionFeedback("revise front"); reference = referenceVersions.saveAndFlush(reference);
        DesignReferenceImageVersion image = new DesignReferenceImageVersion(); image.setId(UUID.randomUUID());
        image.setReferenceSetId(setId); image.setProjectId(projectId); image.setOwnerUserId(owner.getId());
        image.setViewSlot("front"); image.setVersionNumber(1); image.setArtifactKey("internal-image-key");
        image.setMimeType("image/png"); image.setByteSize(12L); image.setSha256("c".repeat(64));
        image.setNormalizedPrompt("normalized ref"); image.setPromptProfileVersion("v1");
        image = imageVersions.saveAndFlush(image);
        DesignReferenceSetView view = new DesignReferenceSetView(); view.setReferenceVersionId(reference.getId());
        view.setViewSlot("front"); view.setReferenceSetId(setId); view.setProjectId(projectId);
        view.setImageVersionId(image.getId()); view.setOwnerUserId(owner.getId()); views.saveAndFlush(view);

        DesignReferenceApproval referenceApproval = new DesignReferenceApproval();
        referenceApproval.setId(UUID.randomUUID()); referenceApproval.setReferenceVersionId(reference.getId());
        referenceApproval.setOwnerUserId(owner.getId()); referenceApproval.setDecision(ApprovalDecision.CHANGES_REQUESTED);
        referenceApproval.setFeedback("adjust front"); referenceApproval.setIdempotencyKey("ref-approval-" + suffix);
        referenceApproval.setRequestFingerprint("d".repeat(64)); referenceApprovals.saveAndFlush(referenceApproval);

        DesignModelVersion model = new DesignModelVersion(); model.setId(UUID.randomUUID()); model.setProjectId(projectId);
        model.setOwnerUserId(owner.getId()); model.setReferenceVersionId(reference.getId()); model.setVersionNumber(1);
        model.setStatus(DesignVersionStatus.READY_FOR_REVIEW); model.setArtifactKey("internal-model-key");
        model.setMimeType("model/gltf-binary"); model.setByteSize(100L); model.setSha256("e".repeat(64));
        model = modelVersions.saveAndFlush(model);
        DesignModelApproval modelApproval = new DesignModelApproval(); modelApproval.setId(UUID.randomUUID());
        modelApproval.setModelVersionId(model.getId()); modelApproval.setOwnerUserId(owner.getId());
        modelApproval.setDecision(ApprovalDecision.APPROVED); modelApproval.setFeedback("looks good");
        modelApproval.setIdempotencyKey("model-approval-" + suffix); modelApproval.setRequestFingerprint("f".repeat(64));
        modelApprovals.saveAndFlush(modelApproval);

        DesignPipelineJob job = new DesignPipelineJob(); job.setId(UUID.randomUUID()); job.setProjectId(projectId);
        job.setOwnerUserId(owner.getId()); job.setStage(DesignStage.MODEL_MESH); job.setStatus(DesignPipelineJobStatus.COMPLETED);
        job.setModelVersionId(model.getId()); job.setIdempotencyKey("job-" + suffix); job.setRequestFingerprint("1".repeat(64));
        job.setProviderTaskRef("provider-secret"); job.setAttemptCount(1); jobs.saveAndFlush(job);
        return new Fixture(project, reference, image, model);
    }

    private LatestVersions addDuplicateHistoryRows(User owner, Fixture fixture) {
        DesignReferenceSetVersion nextReference = new DesignReferenceSetVersion();
        nextReference.setId(UUID.randomUUID()); nextReference.setReferenceSetId(fixture.reference().getReferenceSetId());
        nextReference.setProjectId(fixture.project().getId()); nextReference.setOwnerUserId(owner.getId());
        nextReference.setVersionNumber(2); nextReference.setStatus(DesignVersionStatus.FAILED);
        nextReference.setNormalizedPrompt("normalized ref v2"); nextReference.setPromptProfileVersion("v1");
        referenceVersions.saveAndFlush(nextReference);

        DesignModelVersion nextModel = new DesignModelVersion(); nextModel.setId(UUID.randomUUID());
        nextModel.setProjectId(fixture.project().getId()); nextModel.setOwnerUserId(owner.getId());
        nextModel.setReferenceVersionId(nextReference.getId()); nextModel.setVersionNumber(2);
        nextModel.setStatus(DesignVersionStatus.FAILED); nextModel.setSupersedesModelVersionId(fixture.model().getId());
        modelVersions.saveAndFlush(nextModel);

        DesignPipelineJob retry = new DesignPipelineJob(); retry.setId(UUID.randomUUID());
        retry.setProjectId(fixture.project().getId()); retry.setOwnerUserId(owner.getId());
        retry.setStage(DesignStage.REFERENCE_IMAGE); retry.setStatus(DesignPipelineJobStatus.FAILED);
        retry.setReferenceVersionId(nextReference.getId()); retry.setIdempotencyKey("history-retry-" + UUID.randomUUID());
        retry.setRequestFingerprint("2".repeat(64)); retry.setAttemptCount(2); jobs.saveAndFlush(retry);
        return new LatestVersions(nextReference.getId(), nextModel.getId());
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor authenticate(User user) {
        UserPrincipal principal = new UserPrincipal(user);
        return authentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }

    private JsonNode findEvent(JsonNode body, String eventType) {
        for (JsonNode event : body.path("events")) {
            if (eventType.equals(event.path("eventType").asText())) return event;
        }
        throw new AssertionError("Missing timeline event " + eventType);
    }

    private JsonNode findRecord(JsonNode body, String recordType) {
        for (JsonNode event : body.path("events")) {
            if (recordType.equals(event.path("recordType").asText())) return event;
        }
        throw new AssertionError("Missing timeline record " + recordType);
    }

    private record Fixture(DesignProject project, DesignReferenceSetVersion reference,
                           DesignReferenceImageVersion image, DesignModelVersion model) { }
    private record LatestVersions(UUID referenceId, UUID modelId) { }

    @TestConfiguration
    static class ArtifactConfiguration {
        @Bean @Primary
        TestDesignArtifactStore testArtifactStore() {
            return new TestDesignArtifactStore();
        }
    }

    static class TestDesignArtifactStore implements DesignArtifactStore {
        private final Map<String, byte[]> written = new ConcurrentHashMap<>();
        private int puts;
        private int deletes;

        @Override
        public synchronized DesignArtifactDescriptor put(Integer ownerId, UUID projectId, String mimeType, byte[] content) {
            String key = "internal-upload-" + UUID.randomUUID();
            byte[] stored = content.clone();
            written.put(key, stored);
            puts++;
            return new DesignArtifactDescriptor(key, mimeType, stored.length, sha256(stored));
        }

        @Override
        public byte[] read(Integer ownerId, UUID projectId, String artifactKey) throws IOException {
            if (artifactKey == null || !artifactKey.startsWith("internal-")) throw new NoSuchFileException("artifact");
            byte[] stored = written.get(artifactKey);
            return stored == null ? new byte[]{1, 2, 3} : stored.clone();
        }

        @Override
        public synchronized void delete(Integer ownerId, UUID projectId, String artifactKey) {
            written.remove(artifactKey);
            deletes++;
        }

        synchronized int putCount() { return puts; }
        synchronized int deleteCount() { return deletes; }

        synchronized void reset() {
            written.clear();
            puts = 0;
            deletes = 0;
        }

        private static String sha256(byte[] bytes) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }
}
