package com.exe.morphia3d.entity;

import com.exe.morphia3d.design.DesignVersionStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Getter @Setter @Entity @Table(name = "design_model_versions") @NoArgsConstructor
public class DesignModelVersion {
    @Id @Column(name = "model_version_id", nullable = false, updatable = false) private UUID id;
    @Column(name = "project_id", nullable = false, updatable = false) private UUID projectId;
    @Column(name = "owner_user_id", nullable = false, updatable = false) private Integer ownerUserId;
    @Column(name = "reference_version_id", nullable = false, updatable = false) private UUID referenceVersionId;
    @Column(name = "version_number", nullable = false, updatable = false) private Integer versionNumber;
    @Column(name = "supersedes_model_version_id", updatable = false) private UUID supersedesModelVersionId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) private DesignVersionStatus status;
    @Column(name = "artifact_key", length = 512) private String artifactKey;
    @Column(name = "mime_type", length = 64) private String mimeType;
    @Column(name = "byte_size") private Long byteSize;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "sha256", length = 64) private String sha256;
    @Column(name = "provider_task_ref", length = 256) private String providerTaskRef;
    @Column(name = "revision_feedback", columnDefinition = "text", updatable = false) private String revisionFeedback;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
}
