package com.exe.morphia3d.repository;

import com.exe.morphia3d.entity.DesignReferenceSetView;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface DesignReferenceSetViewRepository extends JpaRepository<DesignReferenceSetView, DesignReferenceSetView.Key> {
    List<DesignReferenceSetView> findByReferenceVersionIdAndOwnerUserId(UUID referenceVersionId, Integer ownerUserId);
    List<DesignReferenceSetView> findByReferenceVersionIdInAndOwnerUserId(List<UUID> referenceVersionIds, Integer ownerUserId);
}
