package com.exe.morphia3d.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.io.Serializable;
import java.util.UUID;

@Getter @Setter @Entity @Table(name = "design_reference_set_views") @IdClass(DesignReferenceSetView.Key.class) @NoArgsConstructor
public class DesignReferenceSetView {
    @Id @Column(name = "reference_version_id", nullable = false, updatable = false) private UUID referenceVersionId;
    @Id @Column(name = "view_slot", nullable = false, length = 16, updatable = false) private String viewSlot;
    @Column(name = "reference_set_id", nullable = false, updatable = false) private UUID referenceSetId;
    @Column(name = "project_id", nullable = false, updatable = false) private UUID projectId;
    @Column(name = "image_version_id", nullable = false, updatable = false) private UUID imageVersionId;
    @Column(name = "owner_user_id", nullable = false, updatable = false) private Integer ownerUserId;

    @Getter @Setter @NoArgsConstructor
    public static class Key implements Serializable {
        private UUID referenceVersionId;
        private String viewSlot;
        public Key(UUID referenceVersionId, String viewSlot) { this.referenceVersionId = referenceVersionId; this.viewSlot = viewSlot; }
        @Override public boolean equals(Object other) { return other instanceof Key key && java.util.Objects.equals(referenceVersionId, key.referenceVersionId) && java.util.Objects.equals(viewSlot, key.viewSlot); }
        @Override public int hashCode() { return java.util.Objects.hash(referenceVersionId, viewSlot); }
    }
}
