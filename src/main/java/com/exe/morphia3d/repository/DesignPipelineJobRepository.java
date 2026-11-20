package com.exe.morphia3d.repository;

import com.exe.morphia3d.design.DesignPipelineJobStatus;
import com.exe.morphia3d.design.DesignStage;
import com.exe.morphia3d.entity.DesignPipelineJob;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DesignPipelineJobRepository extends JpaRepository<DesignPipelineJob, UUID> {
    Optional<DesignPipelineJob> findByOwnerUserIdAndStageAndIdempotencyKey(Integer ownerUserId, DesignStage stage, String idempotencyKey);
    List<DesignPipelineJob> findByProjectIdAndOwnerUserIdOrderByCreatedAtAsc(UUID projectId, Integer ownerUserId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from DesignPipelineJob j where j.status = :status and (j.nextAttemptAt is null or j.nextAttemptAt <= :now) and (j.leaseUntil is null or j.leaseUntil < :now) order by j.createdAt")
    List<DesignPipelineJob> lockClaimable(@Param("status") DesignPipelineJobStatus status, @Param("now") Instant now,
                                          org.springframework.data.domain.Pageable pageable);
}
