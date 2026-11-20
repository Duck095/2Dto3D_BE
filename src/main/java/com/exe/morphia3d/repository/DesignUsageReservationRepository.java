package com.exe.morphia3d.repository;

import com.exe.morphia3d.entity.DesignUsageReservation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.UUID;

public interface DesignUsageReservationRepository extends JpaRepository<DesignUsageReservation, UUID> {
    Optional<DesignUsageReservation> findByJobId(UUID jobId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from DesignUsageReservation r where r.jobId = :jobId")
    Optional<DesignUsageReservation> findLockedByJobId(@Param("jobId") UUID jobId);
}
