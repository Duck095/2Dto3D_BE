package com.exe.morphia3d.repository;

import com.exe.morphia3d.entity.DesignProjectInput;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface DesignProjectInputRepository extends JpaRepository<DesignProjectInput, UUID> {
    List<DesignProjectInput> findByProjectIdAndOwnerUserIdOrderByInputOrder(UUID projectId, Integer ownerUserId);
    java.util.Optional<DesignProjectInput> findByIdAndProjectIdAndOwnerUserId(UUID id, UUID projectId, Integer ownerUserId);
    boolean existsByOwnerUserIdAndProjectIdAndArtifactKey(Integer ownerUserId, UUID projectId, String artifactKey);
}
