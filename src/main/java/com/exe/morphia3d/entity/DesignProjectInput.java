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

@Getter @Setter @Entity @Table(name = "design_project_inputs") @NoArgsConstructor
public class DesignProjectInput {
    @Id @Column(name = "input_id", nullable = false, updatable = false) private UUID id;
    @Column(name = "project_id", nullable = false, updatable = false) private UUID projectId;
    @Column(name = "owner_user_id", nullable = false, updatable = false) private Integer ownerUserId;
    @Column(name = "input_order", nullable = false, updatable = false) private Integer inputOrder;
    @Column(name = "artifact_key", nullable = false, length = 512, updatable = false) private String artifactKey;
    @Column(name = "mime_type", nullable = false, length = 64, updatable = false) private String mimeType;
    @Column(name = "byte_size", nullable = false, updatable = false) private Long byteSize;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "sha256", nullable = false, length = 64, updatable = false) private String sha256;
    @Column(name = "client_slot", length = 64, updatable = false) private String clientSlot;
    @Column(name = "view_hint", length = 16, updatable = false) private String viewHint;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
}
