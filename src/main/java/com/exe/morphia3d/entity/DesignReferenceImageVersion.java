package com.exe.morphia3d.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Getter @Setter @Entity @Table(name = "design_reference_image_versions") @NoArgsConstructor
public class DesignReferenceImageVersion {
    @Id @Column(name = "image_version_id", nullable = false, updatable = false) private UUID id;
    @Column(name = "reference_set_id", nullable = false, updatable = false) private UUID referenceSetId;
    @Column(name = "project_id", nullable = false, updatable = false) private UUID projectId;
    @Column(name = "owner_user_id", nullable = false, updatable = false) private Integer ownerUserId;
    @Column(name = "view_slot", nullable = false, length = 16, updatable = false) private String viewSlot;
    @Column(name = "version_number", nullable = false, updatable = false) private Integer versionNumber;
    @Column(name = "supersedes_image_version_id", updatable = false) private UUID supersedesImageVersionId;
    @Column(name = "artifact_key", nullable = false, length = 512, updatable = false) private String artifactKey;
    @Column(name = "mime_type", nullable = false, length = 64, updatable = false) private String mimeType;
    @Column(name = "byte_size", nullable = false, updatable = false) private Long byteSize;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "sha256", nullable = false, length = 64, updatable = false) private String sha256;
    @Column(name = "normalized_prompt", nullable = false, columnDefinition = "text", updatable = false) private String normalizedPrompt;
    @Column(name = "prompt_profile_version", nullable = false, length = 16, updatable = false) private String promptProfileVersion;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
}
