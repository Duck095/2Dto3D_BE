package com.exe.morphia3d.entity;

import com.exe.morphia3d.design.DesignPipelineJobStatus;
import com.exe.morphia3d.design.DesignStage;
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

@Getter @Setter @Entity @Table(name = "design_pipeline_jobs") @NoArgsConstructor
public class DesignPipelineJob {
    @Id @Column(name = "job_id", nullable = false, updatable = false) private UUID id;
    @Column(name = "project_id", nullable = false, updatable = false) private UUID projectId;
    @Column(name = "owner_user_id", nullable = false, updatable = false) private Integer ownerUserId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24, updatable = false) private DesignStage stage;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) private DesignPipelineJobStatus status;
    @Column(name = "reference_version_id", updatable = false) private UUID referenceVersionId;
    @Column(name = "model_version_id", updatable = false) private UUID modelVersionId;
    @Column(name = "requested_view_slot", length = 16, updatable = false) private String requestedViewSlot;
    @Column(name = "idempotency_key", nullable = false, length = 128, updatable = false) private String idempotencyKey;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "request_fingerprint", nullable = false, length = 64, updatable = false) private String requestFingerprint;
    @Column(name = "provider_task_ref", length = 256) private String providerTaskRef;
    @Column(name = "attempt_count", nullable = false) private Integer attemptCount;
    @Column(name = "lease_until") private Instant leaseUntil;
    @Column(name = "next_attempt_at") private Instant nextAttemptAt;
    @Column(name = "safe_error_code", length = 64) private String safeErrorCode;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false) private Instant updatedAt;
}
