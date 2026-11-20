package com.exe.morphia3d.repository;

import com.exe.morphia3d.entity.DesignModelApproval;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface DesignModelApprovalRepository extends JpaRepository<DesignModelApproval, UUID> {
    Optional<DesignModelApproval> findByOwnerUserIdAndIdempotencyKey(Integer ownerUserId, String idempotencyKey);
    Optional<DesignModelApproval> findTopByModelVersionIdAndOwnerUserIdOrderByCreatedAtDesc(UUID modelVersionId, Integer ownerUserId);
    List<DesignModelApproval> findByModelVersionIdInAndOwnerUserIdOrderByCreatedAtAsc(List<UUID> modelVersionIds, Integer ownerUserId);
}
