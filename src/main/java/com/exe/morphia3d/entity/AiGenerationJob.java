package com.exe.morphia3d.entity;

import com.exe.morphia3d.enums.AiGenerationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "ai_generation_jobs")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiGenerationJob {
    @Id
    @Column(name = "generation_job_id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "retry_of")
    private AiGenerationJob retryOf;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "result_model_id")
    private Model3d resultModel;

    @Column(name = "idempotency_key", nullable = false, length = 128, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64, updatable = false)
    private String requestHash;

    @Column(name = "prompt", nullable = false, length = 255, updatable = false)
    private String prompt;

    @Column(name = "style", nullable = false, length = 60, updatable = false)
    private String style;

    @Column(name = "quality", length = 60, updatable = false)
    private String quality;

    @Column(name = "input_mode", nullable = false, length = 16, updatable = false)
    private String inputMode;

    @Column(name = "input_artifact_key", length = 512, updatable = false)
    private String inputArtifactKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private AiGenerationStatus status = AiGenerationStatus.PENDING;

    @Column(name = "provider_reference", length = 255)
    private String providerReference;

    @Column(name = "attempt_count", nullable = false)
    @Builder.Default
    private Integer attemptCount = 0;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "result_artifact_key", length = 512)
    private String resultArtifactKey;

    @Column(name = "processing_lease_owner", length = 128)
    private String processingLeaseOwner;

    @Column(name = "processing_lease_until")
    private Instant processingLeaseUntil;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;
}
