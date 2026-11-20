package com.exe.morphia3d.design;

import java.util.Objects;

/** Maps the asynchronous Meshy contract; transport implementation is supplied only by a separately approved integration. */
public class MeshyModelProviderAdapter implements MeshyModelProvider {
    private final MeshyTransport transport;
    public MeshyModelProviderAdapter(MeshyTransport transport) { this.transport = Objects.requireNonNull(transport); }
    @Override public String submit(ModelRequest request) {
        if (request == null || request.jobId() == null || request.idempotencyKey() == null || request.idempotencyKey().isBlank()
                || request.approvedReferences() == null || request.approvedReferences().size() != 4) {
            throw new IllegalArgumentException("Model generation requires one idempotent job and four approved views");
        }
        String task = transport.submit(request);
        if (task == null || task.isBlank()) throw new ProviderUnavailableException("MODEL_PROVIDER_TASK_REFERENCE_MISSING");
        return task;
    }
    @Override public TaskStatus status(String providerTaskReference) {
        requireTask(providerTaskReference);
        return Objects.requireNonNull(transport.status(providerTaskReference));
    }
    @Override public GeneratedModel result(String providerTaskReference) {
        requireTask(providerTaskReference);
        return Objects.requireNonNull(transport.result(providerTaskReference));
    }
    private void requireTask(String reference) {
        if (reference == null || reference.isBlank()) throw new IllegalArgumentException("Provider task reference is required");
    }
}
