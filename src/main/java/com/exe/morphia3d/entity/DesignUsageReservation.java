package com.exe.morphia3d.entity;

import com.exe.morphia3d.design.DesignUsageReservationStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import java.time.Instant;
import java.util.UUID;

@Getter @Setter @Entity @Table(name = "design_usage_reservations") @NoArgsConstructor
public class DesignUsageReservation {
    @Id @Column(name = "reservation_id", nullable = false, updatable = false) private UUID id;
    @Column(name = "job_id", nullable = false, updatable = false, unique = true) private UUID jobId;
    @Column(name = "owner_user_id", nullable = false, updatable = false) private Integer ownerUserId;
    @Column(name = "global_budget_window_id", nullable = false, updatable = false) private UUID globalBudgetWindowId;
    @Column(name = "owner_budget_window_id", nullable = false, updatable = false) private UUID ownerBudgetWindowId;
    @Column(name = "reserved_units", nullable = false, updatable = false) private Long reservedUnits;
    @Column(name = "settled_units") private Long settledUnits;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private DesignUsageReservationStatus status;
    @Column(name = "provider_usage_reference", length = 256) private String providerUsageReference;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "settled_at") private Instant settledAt;
}
