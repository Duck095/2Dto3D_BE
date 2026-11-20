package com.exe.morphia3d.entity;

import com.exe.morphia3d.design.ApprovalDecision;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Getter @Setter @Entity @Table(name = "design_reference_approvals") @NoArgsConstructor
public class DesignReferenceApproval {
    @Id @Column(name = "approval_id", nullable = false, updatable = false) private UUID id;
    @Column(name = "reference_version_id", nullable = false, updatable = false) private UUID referenceVersionId;
    @Column(name = "owner_user_id", nullable = false, updatable = false) private Integer ownerUserId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24, updatable = false) private ApprovalDecision decision;
    @Column(columnDefinition = "text", updatable = false) private String feedback;
    @Column(name = "idempotency_key", nullable = false, length = 128, updatable = false) private String idempotencyKey;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "request_fingerprint", nullable = false, length = 64, updatable = false) private String requestFingerprint;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
}
