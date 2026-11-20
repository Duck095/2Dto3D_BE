package com.exe.morphia3d.design;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class TEST_ONLY_FakeMeshyModelProvider implements MeshyModelProvider {
    private final AtomicInteger submitCalls = new AtomicInteger();
    private final AtomicInteger statusCalls = new AtomicInteger();
    private final AtomicInteger resultCalls = new AtomicInteger();
    private volatile ModelRequest submittedRequest;
    private volatile String submittedTaskReference;
    private volatile List<TaskStatus> taskStatuses = List.of(TaskStatus.SUCCEEDED);
    private volatile boolean invalidResult;
    private volatile boolean failStatusReadOnce;
    private volatile boolean failResultReadOnce;

    @Override
    public String submit(ModelRequest request) {
        submittedRequest = request;
        submittedTaskReference = "test-task-" + request.jobId();
        submitCalls.incrementAndGet();
        return submittedTaskReference;
    }

    @Override
    public TaskStatus status(String providerTaskReference) {
        statusCalls.incrementAndGet();
        if (failStatusReadOnce) { failStatusReadOnce = false; throw new DesignProviderReadRetryException("TEST_STATUS_READ_RETRY"); }
        if (!providerTaskReference.equals(submittedTaskReference)) {
            throw new IllegalArgumentException("Unexpected fake provider task reference");
        }
        int statusIndex = Math.min(statusCalls.get() - 1, taskStatuses.size() - 1);
        return taskStatuses.get(statusIndex);
    }

    @Override
    public GeneratedModel result(String providerTaskReference) {
        resultCalls.incrementAndGet();
        if (failResultReadOnce) { failResultReadOnce = false; throw new DesignProviderReadRetryException("TEST_RESULT_READ_RETRY"); }
        if (!providerTaskReference.equals(submittedTaskReference)) {
            throw new IllegalArgumentException("Unexpected fake provider task reference");
        }
        return new GeneratedModel("model/gltf-binary", invalidResult ? new byte[12] : validGlb(), 1L);
    }

    public void setTaskStatuses(TaskStatus... statuses) {
        if (statuses == null || statuses.length == 0) throw new IllegalArgumentException("At least one task status is required");
        taskStatuses = List.of(statuses.clone());
    }

    public void setInvalidResult(boolean invalidResult) { this.invalidResult = invalidResult; }
    public void failStatusReadOnce() { failStatusReadOnce = true; }
    public void failResultReadOnce() { failResultReadOnce = true; }
    public int submitCalls() { return submitCalls.get(); }
    public int statusCalls() { return statusCalls.get(); }
    public int resultCalls() { return resultCalls.get(); }
    public ModelRequest submittedRequest() { return submittedRequest; }
    public String submittedTaskReference() { return submittedTaskReference; }

    private byte[] validGlb() {
        byte[] json = "{\"asset\":{\"version\":\"2.0\"}}".getBytes(StandardCharsets.UTF_8);
        int paddedJsonLength = (json.length + 3) & ~3;
        int totalLength = 12 + 8 + paddedJsonLength;
        ByteBuffer glb = ByteBuffer.allocate(totalLength).order(ByteOrder.LITTLE_ENDIAN);
        glb.putInt(0x46546c67); // glTF magic
        glb.putInt(2);
        glb.putInt(totalLength);
        glb.putInt(paddedJsonLength);
        glb.putInt(0x4e4f534a); // JSON chunk type
        glb.put(json);
        while (glb.position() < totalLength) glb.put((byte) ' ');
        return glb.array();
    }
}
