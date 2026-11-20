package com.exe.morphia3d.repository;

import com.exe.morphia3d.entity.DesignReferenceSet;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface DesignReferenceSetRepository extends JpaRepository<DesignReferenceSet, UUID> {
    Optional<DesignReferenceSet> findByProjectIdAndOwnerUserId(UUID projectId, Integer ownerUserId);
}
