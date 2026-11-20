package com.exe.morphia3d.repository;

import com.exe.morphia3d.entity.DesignModelVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.UUID;

public interface DesignModelVersionRepository extends JpaRepository<DesignModelVersion, UUID> {
    Optional<DesignModelVersion> findByIdAndOwnerUserId(UUID id, Integer ownerUserId);
    boolean existsByOwnerUserIdAndProjectIdAndArtifactKey(Integer ownerUserId, UUID projectId, String artifactKey);
    Optional<DesignModelVersion> findTopByProjectIdOrderByVersionNumberDesc(UUID projectId);
    Optional<DesignModelVersion> findTopByProjectIdAndOwnerUserIdOrderByVersionNumberDesc(UUID projectId, Integer ownerUserId);
    java.util.List<DesignModelVersion> findByProjectIdAndOwnerUserIdOrderByVersionNumberAsc(UUID projectId, Integer ownerUserId);
    @Query("select v from DesignModelVersion v where v.ownerUserId = :ownerId and v.projectId in :projectIds "
            + "and v.versionNumber = (select max(latest.versionNumber) from DesignModelVersion latest "
            + "where latest.projectId = v.projectId and latest.ownerUserId = :ownerId)")
    java.util.List<DesignModelVersion> findLatestByProjectIdsAndOwnerUserId(@Param("ownerId") Integer ownerId,
                                                                           @Param("projectIds") java.util.List<UUID> projectIds);
}
