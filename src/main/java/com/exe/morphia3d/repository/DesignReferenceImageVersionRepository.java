package com.exe.morphia3d.repository;

import com.exe.morphia3d.entity.DesignReferenceImageVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface DesignReferenceImageVersionRepository extends JpaRepository<DesignReferenceImageVersion, UUID> {
    Optional<DesignReferenceImageVersion> findTopByReferenceSetIdAndViewSlotOrderByVersionNumberDesc(UUID referenceSetId, String viewSlot);
    Optional<DesignReferenceImageVersion> findByIdAndOwnerUserId(UUID id, Integer ownerUserId);
    List<DesignReferenceImageVersion> findByIdInAndOwnerUserId(Collection<UUID> ids, Integer ownerUserId);
    boolean existsByOwnerUserIdAndProjectIdAndArtifactKey(Integer ownerUserId, UUID projectId, String artifactKey);
}
