package com.exe.morphia3d.entity;

import com.exe.morphia3d.design.DesignProjectStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Getter @Setter @Entity @Table(name = "design_projects") @NoArgsConstructor
public class DesignProject {
    @Id @Column(name = "project_id", nullable = false, updatable = false) private UUID id;
    @Column(name = "owner_user_id", nullable = false, updatable = false) private Integer ownerUserId;
    @Column(name = "product_type", nullable = false, length = 24, updatable = false) private String productType;
    @Column(name = "keychain_variant", length = 16, updatable = false) private String keychainVariant;
    @Column(name = "style_id", nullable = false, length = 64, updatable = false) private String styleId;
    @Column(name = "input_mode", nullable = false, length = 16, updatable = false) private String inputMode;
    @Column(name = "original_idea", columnDefinition = "text", updatable = false) private String originalIdea;
    @Column(name = "normalized_prompt", nullable = false, columnDefinition = "text") private String normalizedPrompt;
    @Column(name = "prompt_profile_version", nullable = false, length = 16) private String promptProfileVersion;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private DesignProjectStatus status;
    @Column(name = "create_idempotency_key", nullable = false, length = 128, updatable = false) private String createIdempotencyKey;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "request_fingerprint", nullable = false, length = 64, updatable = false) private String requestFingerprint;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version @Column(name = "row_version", nullable = false) private Long rowVersion;
}
