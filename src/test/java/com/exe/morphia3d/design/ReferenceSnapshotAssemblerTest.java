package com.exe.morphia3d.design;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReferenceSnapshotAssemblerTest {
    private final ReferenceSnapshotAssembler assembler = new ReferenceSnapshotAssembler();

    @Test
    void replacingOneAngleRetainsTheOtherThreeImmutableImageIds() {
        UUID front = UUID.randomUUID(), left = UUID.randomUUID(), right = UUID.randomUUID(), back = UUID.randomUUID();
        UUID replacement = UUID.randomUUID();
        Map<ReferenceViewSlot, UUID> next = assembler.revise(Map.of(
                ReferenceViewSlot.FRONT, front, ReferenceViewSlot.LEFT, left,
                ReferenceViewSlot.RIGHT, right, ReferenceViewSlot.BACK, back), ReferenceViewSlot.LEFT, replacement);

        assertThat(next).containsEntry(ReferenceViewSlot.FRONT, front).containsEntry(ReferenceViewSlot.LEFT, replacement)
                .containsEntry(ReferenceViewSlot.RIGHT, right).containsEntry(ReferenceViewSlot.BACK, back);
    }

    @Test
    void refusesIncompletePriorSnapshots() {
        assertThatThrownBy(() -> assembler.revise(Map.of(ReferenceViewSlot.FRONT, UUID.randomUUID()),
                ReferenceViewSlot.FRONT, UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
    }
}
