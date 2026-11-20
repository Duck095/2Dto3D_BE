package com.exe.morphia3d.entity;

import com.exe.morphia3d.design.DesignVersionStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import java.time.Instant;
import java.util.UUID;

@Getter @Setter @Entity @Table(name = "design_reference_set_versions") @NoArgsConstructor
public class DesignReferenceSetVersion {
    @Id @Column(name = "reference_version_id", nullable = false, updatable = false) private UUID id;
    @Column(name = "reference_set_id", nullable = false, updatable = false) private UUID referenceSetId;
    @Column(name = "project_id", nullable = false, updatable = false) private UUID projectId;
    @Column(name = "owner_user_id", nullable = false, updatable = false) private Integer ownerUserId;
    @Column(name = "version_number", nullable = false, updatable = false) private Integer versionNumber;
    @Column(name = "parent_version_id", updatable = false) private UUID parentVersionId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) private DesignVersionStatus status;
    @Column(name = "normalized_prompt", nullable = false, columnDefinition = "text", updatable = false) private String normalizedPrompt;
    @Column(name = "prompt_profile_version", nullable = false, length = 16, updatable = false) private String promptProfileVersion;
    @Column(name = "revision_feedback", columnDefinition = "text", updatable = false) private String revisionFeedback;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
}
