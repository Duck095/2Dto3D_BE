package com.exe.morphia3d.design;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Maps the domain contract to a future GPT Image transport; this adapter contains no network client. */
public class GptImageReferenceProviderAdapter implements ReferenceImageProvider {
    private final GptImageTransport transport;
    public GptImageReferenceProviderAdapter(GptImageTransport transport) { this.transport = Objects.requireNonNull(transport); }

    @Override public List<GeneratedReferenceImage> generate(ReferenceImageRequest request) {
        if (request == null || request.jobId() == null || request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("A stable generation job reference is required");
        }
        if (request.slots() == null || request.slots().isEmpty() || request.slots().size() > 4
                || EnumSet.copyOf(request.slots()).size() != request.slots().size()) {
            throw new IllegalArgumentException("Requested view slots must be non-empty and unique");
        }
        List<GeneratedReferenceImage> outputs = transport.generate(request);
        Set<ReferenceViewSlot> returnedSlots = outputs == null ? Set.of() : outputs.stream()
                .map(GeneratedReferenceImage::slot).collect(Collectors.toSet());
        if (outputs == null || outputs.size() != request.slots().size() || !returnedSlots.equals(Set.copyOf(request.slots()))) {
            throw new ProviderUnavailableException("REFERENCE_OUTPUT_CONTRACT_INVALID");
        }
        return List.copyOf(outputs);
    }
}
