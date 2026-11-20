package com.exe.morphia3d.repository;

import com.exe.morphia3d.entity.DesignReferenceSetVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DesignReferenceSetVersionRepository extends JpaRepository<DesignReferenceSetVersion, UUID> {
    Optional<DesignReferenceSetVersion> findByIdAndOwnerUserId(UUID id, Integer ownerUserId);
    Optional<DesignReferenceSetVersion> findTopByReferenceSetIdOrderByVersionNumberDesc(UUID referenceSetId);
    Optional<DesignReferenceSetVersion> findTopByReferenceSetIdAndOwnerUserIdOrderByVersionNumberDesc(UUID referenceSetId, Integer ownerUserId);
    List<DesignReferenceSetVersion> findByReferenceSetIdAndOwnerUserIdOrderByVersionNumberAsc(UUID referenceSetId, Integer ownerUserId);
    List<DesignReferenceSetVersion> findByProjectIdAndOwnerUserIdOrderByVersionNumberAsc(UUID projectId, Integer ownerUserId);
    @Query("select v from DesignReferenceSetVersion v where v.ownerUserId = :ownerId and v.projectId in :projectIds "
            + "and v.versionNumber = (select max(latest.versionNumber) from DesignReferenceSetVersion latest "
            + "where latest.projectId = v.projectId and latest.ownerUserId = :ownerId)")
    List<DesignReferenceSetVersion> findLatestByProjectIdsAndOwnerUserId(@Param("ownerId") Integer ownerId,
                                                                         @Param("projectIds") List<UUID> projectIds);
}
