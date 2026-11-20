package com.exe.morphia3d.repository;

import com.exe.morphia3d.entity.DesignProject;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.Optional;
import java.util.UUID;

public interface DesignProjectRepository extends JpaRepository<DesignProject, UUID> {
    Page<DesignProject> findByOwnerUserId(Integer ownerUserId, Pageable pageable);
    Optional<DesignProject> findByIdAndOwnerUserId(UUID id, Integer ownerUserId);
    Optional<DesignProject> findByOwnerUserIdAndCreateIdempotencyKey(Integer ownerUserId, String key);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from DesignProject p where p.id = :id and p.ownerUserId = :ownerId")
    Optional<DesignProject> findLockedByIdAndOwner(@Param("id") UUID id, @Param("ownerId") Integer ownerId);
}
