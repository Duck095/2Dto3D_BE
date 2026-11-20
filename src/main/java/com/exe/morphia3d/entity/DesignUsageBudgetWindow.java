package com.exe.morphia3d.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Getter @Setter @Entity @Table(name = "design_usage_budget_windows") @NoArgsConstructor
public class DesignUsageBudgetWindow {
    @Id @Column(name = "budget_window_id", nullable = false, updatable = false) private UUID id;
    @Column(name = "scope_key", nullable = false, length = 64, updatable = false) private String scopeKey;
    @Column(name = "window_date", nullable = false, updatable = false) private LocalDate windowDate;
    @Column(name = "limit_units", nullable = false, updatable = false) private Long limitUnits;
    @Column(name = "reserved_units", nullable = false) private Long reservedUnits;
    @Column(name = "settled_units", nullable = false) private Long settledUnits;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false) private Instant updatedAt;
}
