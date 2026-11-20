package com.exe.morphia3d.repository;

import com.exe.morphia3d.entity.DesignUsageBudgetWindow;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public interface DesignUsageBudgetWindowRepository extends JpaRepository<DesignUsageBudgetWindow, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<DesignUsageBudgetWindow> findByScopeKeyAndWindowDate(String scopeKey, LocalDate windowDate);

    @Modifying @Transactional
    @Query(value = "INSERT INTO design_usage_budget_windows (budget_window_id, scope_key, window_date, limit_units, reserved_units, settled_units, updated_at) VALUES (:id, :scope, :day, :limitUnits, 0, 0, CURRENT_TIMESTAMP) ON CONFLICT (scope_key, window_date) DO NOTHING", nativeQuery = true)
    int insertWindowIfAbsent(@Param("id") UUID id, @Param("scope") String scope,
                             @Param("day") LocalDate day, @Param("limitUnits") long limitUnits);
}
