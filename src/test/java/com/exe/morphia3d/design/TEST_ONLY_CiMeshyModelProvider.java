package com.exe.morphia3d.design;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic, in-process provider used only by the ai-studio-ci Spring profile. */
public final class TEST_ONLY_CiMeshyModelProvider implements MeshyModelProvider {
    private final AtomicInteger submitCalls = new AtomicInteger();
    private final Set<UUID> submittedJobIds = ConcurrentHashMap.newKeySet();

    @Override public String submit(ModelRequest request) {
        submitCalls.incrementAndGet();
        submittedJobIds.add(request.jobId());
        return "TEST_ONLY_CI_" + request.jobId();
    }

    @Override public TaskStatus status(String reference) { return TaskStatus.SUCCEEDED; }

    @Override public GeneratedModel result(String reference) {
        return new GeneratedModel("model/gltf-binary", TestOnlyRenderableGlbFixture.validTriangle(), 1L);
    }

    public int submitCallsFor(Set<UUID> jobIds) {
        return (int) submittedJobIds.stream().filter(jobIds::contains).count();
    }
}
