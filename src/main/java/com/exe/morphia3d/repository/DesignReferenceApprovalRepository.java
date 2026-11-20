package com.exe.morphia3d.repository;

import com.exe.morphia3d.entity.DesignReferenceApproval;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface DesignReferenceApprovalRepository extends JpaRepository<DesignReferenceApproval, UUID> {
    Optional<DesignReferenceApproval> findByOwnerUserIdAndIdempotencyKey(Integer ownerUserId, String idempotencyKey);
    Optional<DesignReferenceApproval> findTopByReferenceVersionIdAndOwnerUserIdOrderByCreatedAtDesc(UUID referenceVersionId, Integer ownerUserId);
    boolean existsByReferenceVersionIdAndOwnerUserIdAndDecision(UUID referenceVersionId, Integer ownerUserId, com.exe.morphia3d.design.ApprovalDecision decision);
    List<DesignReferenceApproval> findByReferenceVersionIdInAndOwnerUserIdOrderByCreatedAtAsc(List<UUID> referenceVersionIds, Integer ownerUserId);
}
