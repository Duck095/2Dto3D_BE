package com.exe.morphia3d.design;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class ReferenceSnapshotAssembler {
    public Map<ReferenceViewSlot, UUID> revise(Map<ReferenceViewSlot, UUID> prior,
                                                ReferenceViewSlot changedSlot, UUID replacementImageVersionId) {
        Objects.requireNonNull(prior, "prior snapshot is required");
        Objects.requireNonNull(changedSlot, "changed slot is required");
        Objects.requireNonNull(replacementImageVersionId, "replacement image version is required");
        if (prior.size() != ReferenceViewSlot.canonicalOrder().size()
                || !prior.keySet().containsAll(ReferenceViewSlot.canonicalOrder())
                || prior.values().stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Prior reference snapshot must contain all four views");
        }
        EnumMap<ReferenceViewSlot, UUID> next = new EnumMap<>(ReferenceViewSlot.class);
        next.putAll(prior);
        next.put(changedSlot, replacementImageVersionId);
        return Map.copyOf(next);
    }
}
