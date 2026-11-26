package com.exe.morphia3d.service;

import com.cloudinary.Cloudinary;
import com.exe.morphia3d.entity.MediaAsset;
import com.exe.morphia3d.enums.MediaAssetState;
import com.exe.morphia3d.enums.MediaKind;
import com.exe.morphia3d.enums.MediaProvider;
import com.exe.morphia3d.repository.MediaAssetRepository;
import com.exe.morphia3d.repository.Model3dRepository;
import com.exe.morphia3d.repository.ModelImageRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Short database transactions for durable upload and cleanup state transitions. */
@Service
public class MediaAssetLifecycleService {

    private static final String CLOUDINARY_DELIVERY_HOST = "res.cloudinary.com";

    private final MediaAssetRepository assets;
    private final Model3dRepository models;
    private final ModelImageRepository images;
    private final Cloudinary cloudinary;
    private final MediaStorageProperties storageProperties;
    private final MediaLifecycleProperties lifecycleProperties;
    private final Clock clock;

    @PersistenceContext
    private EntityManager entityManager;

    public MediaAssetLifecycleService(MediaAssetRepository assets,
                                      Model3dRepository models,
                                      ModelImageRepository images,
                                      Cloudinary cloudinary,
                                      MediaStorageProperties storageProperties,
                                      MediaLifecycleProperties lifecycleProperties,
                                      Clock clock) {
        this.assets = assets;
        this.models = models;
        this.images = images;
        this.cloudinary = cloudinary;
        this.storageProperties = storageProperties;
        this.lifecycleProperties = lifecycleProperties;
        this.clock = clock;
        lifecycleProperties.validate(storageProperties.getRequestTimeoutMillis());
    }

    @Transactional
    public MediaAssetOperationLease createUploadIntents(UUID operationId, List<MediaAssetIntentSpec> specifications) {
        Objects.requireNonNull(operationId, "operation ID is required");
        if (specifications == null || specifications.isEmpty()) {
            throw new IllegalArgumentException("At least one media upload intent is required");
        }
        if (assets.existsByOperationId(operationId)) {
            throw new IllegalStateException("Operation ID has already been used for media intents");
        }

        UUID operationToken = UUID.randomUUID();
        String operationOwner = UUID.randomUUID().toString();
        Instant leaseUntil = clock.instant().plusMillis(lifecycleProperties.getUploadLeaseMillis());
        List<MediaAsset> created = new ArrayList<>(specifications.size());
        for (MediaAssetIntentSpec specification : specifications) {
            Objects.requireNonNull(specification, "media upload specification is required");
            String format = normalizeFormat(specification.format());
            if (!validFormat(specification.mediaKind(), format)) {
                throw new IllegalArgumentException("Expected media format is invalid");
            }
            MediaAsset asset = MediaAsset.builder()
                    .operationId(operationId)
                    .mediaKind(specification.mediaKind())
                    .provider(MediaProvider.CLOUDINARY)
                    .cloudName(cloudName())
                    .publicId("pending/" + operationId + "/" + UUID.randomUUID())
                    .resourceType(resourceType(specification.mediaKind()))
                    .deliveryType("upload")
                    .format(format)
                    .byteCount(specification.byteCount())
                    .state(MediaAssetState.PENDING_UPLOAD)
                    .leaseOwner(operationOwner)
                    .leaseToken(operationToken)
                    .leaseUntil(leaseUntil)
                    .retryCount(0)
                    .build();
            asset = assets.saveAndFlush(asset);
            asset.setPublicId(intendedPublicId(asset));
            created.add(asset);
        }
        assets.flush();
        List<Long> ids = created.stream().map(MediaAsset::getId).toList();
        return new MediaAssetOperationLease(operationId, operationToken, operationOwner, ids);
    }

    /** Atomically claims one pending intent; a null result means another request already claimed it. */
    @Transactional
    public MediaUploadIntent claimUpload(MediaAssetOperationLease operation, long mediaAssetId) {
        List<MediaAsset> operationAssets = lockOperationAssets(operation.operationId(), operation.mediaAssetIds());
        MediaAsset asset = operationAssets.stream().filter(candidate -> candidate.getId() == mediaAssetId)
                .findFirst().orElseThrow(() -> new IllegalStateException("Asset is not part of this operation"));
        Instant now = clock.instant();
        if (asset.getState() != MediaAssetState.PENDING_UPLOAD || !operation.owns(mediaAssetId)
                || !operationLeaseMatches(asset, operation) || !leaseActive(asset, now)
                || operationAssets.stream().filter(row -> isUploadOperationState(row.getState()))
                    .anyMatch(row -> !operationLeaseMatches(row, operation) || !leaseActive(row, now))
                || operationAssets.stream().anyMatch(other -> other.getState() == MediaAssetState.UPLOADING)) {
            return null;
        }
        int claimed = assets.claimUploadIfPending(asset.getId(), operation.operationId(),
                MediaAssetState.PENDING_UPLOAD, MediaAssetState.UPLOADING,
                operation.leaseToken(), operation.leaseOwner(), now);
        if (claimed != 1) {
            return null;
        }
        return MediaUploadIntent.create(asset.getId(), asset.getOperationId(), operation.leaseToken(), asset.getMediaKind());
    }

    @Transactional
    public void refreshUploadLease(MediaUploadIntent intent) {
        List<MediaAsset> operationAssets = assets.findAllByOperationIdForUpdate(intent.operationId());
        Instant now = clock.instant();
        MediaAsset claimed = operationAssets.stream().filter(asset -> asset.getId() == intent.mediaAssetId())
                .findFirst().orElseThrow(() -> new IllegalStateException("Upload lease is stale or not owned"));
        if (claimed.getState() != MediaAssetState.UPLOADING || !intent.leaseToken().equals(claimed.getLeaseToken())
                || !leaseActive(claimed, now)
                || operationAssets.stream().anyMatch(asset -> asset.getState() == MediaAssetState.CLEANUP_PENDING
                        || asset.getState() == MediaAssetState.CLEANUP_LEASED
                        || asset.getState() == MediaAssetState.DELETED
                        || !intent.leaseToken().equals(asset.getLeaseToken()))) {
            throw new IllegalStateException("Upload lease is stale or not owned by this operation");
        }
        Instant refreshedUntil = now.plusMillis(lifecycleProperties.getUploadLeaseMillis());
        operationAssets.stream().filter(asset -> isUploadOperationState(asset.getState()))
                .forEach(asset -> asset.setLeaseUntil(refreshedUntil));
        assets.flush();
    }

    @Transactional
    public void recordUpload(MediaUploadIntent intent, MediaUploadResult result) {
        MediaAsset asset = requireUploadLease(intent, true);
        validateProviderResult(asset, intent, result);
        asset.setProvider(result.provider());
        asset.setCloudName(result.cloudName());
        asset.setProviderAssetId(result.providerAssetId());
        asset.setPublicId(result.publicId());
        asset.setResourceType(result.resourceType());
        asset.setDeliveryType(result.deliveryType());
        asset.setFormat(normalizeFormat(result.format()));
        asset.setByteCount(result.byteCount());
        asset.setProviderVersion(result.providerVersion());
        asset.setSecureUrl(result.secureUrl());
        asset.setState(MediaAssetState.READY);
        assets.flush();
    }

    /** Call from the same transaction that inserts model/image references. */
    @Transactional
    public void attach(MediaAssetOperationLease operation, Collection<Long> mediaAssetIds) {
        List<MediaAsset> selected = lockOperationAssets(operation.operationId(), mediaAssetIds);
        Instant now = clock.instant();
        entityManager.flush();
        if (!sameIds(operation.mediaAssetIds(), mediaAssetIds)
                || selected.size() != operation.mediaAssetIds().size()) {
            throw new IllegalStateException("All media assets from the operation must attach together");
        }
        for (MediaAsset asset : selected) {
            if (asset.getState() != MediaAssetState.READY || !operationLeaseMatches(asset, operation)
                    || !leaseActive(asset, now) || !hasReference(asset.getId())) {
                throw new IllegalStateException("Only referenced READY media assets from this operation can attach");
            }
        }
        for (MediaAsset asset : selected) {
            if (assets.attachIfReady(asset.getId(), operation.operationId(), MediaAssetState.READY,
                    MediaAssetState.ATTACHED, operation.leaseToken(), operation.leaseOwner(), now) != 1) {
                throw new IllegalStateException("Media asset changed while attachment was being committed");
            }
        }
    }

    /** Requests cleanup for safe assets; active uploads, attached assets, and terminal rows are left untouched. */
    @Transactional
    public int requestCleanup(MediaAssetOperationLease operation, Collection<Long> mediaAssetIds) {
        List<MediaAsset> selected = lockOperationAssets(operation.operationId(), mediaAssetIds);
        Instant now = clock.instant();
        if (!sameIds(operation.mediaAssetIds(), mediaAssetIds)
                || selected.size() != operation.mediaAssetIds().size()) {
            throw new IllegalStateException("Cleanup request must cover the whole operation");
        }
        boolean liveOwnerFound = selected.stream().anyMatch(asset -> operationLeaseMatches(asset, operation)
                && leaseActive(asset, now)
                && (asset.getState() == MediaAssetState.PENDING_UPLOAD || asset.getState() == MediaAssetState.UPLOADING
                    || asset.getState() == MediaAssetState.READY));
        boolean staleOrForeignAsset = selected.stream().filter(asset -> isUploadOperationState(asset.getState()))
                .anyMatch(asset -> !operationLeaseMatches(asset, operation) || !leaseActive(asset, now));
        if (!liveOwnerFound || staleOrForeignAsset || selected.stream().anyMatch(asset -> asset.getState() == MediaAssetState.ATTACHED
                || hasReference(asset.getId()))) {
            throw new IllegalStateException("Operation lease is stale or its media is referenced");
        }
        int changed = 0;
        for (MediaAsset asset : selected) {
            if (asset.getState() == MediaAssetState.CLEANUP_PENDING) {
                continue;
            }
            if (asset.getState() == MediaAssetState.PENDING_UPLOAD || asset.getState() == MediaAssetState.READY) {
                Instant notBefore = asset.getLeaseUntil().plusMillis(lifecycleProperties.getSafetyGraceMillis());
                if (assets.requestCleanupIfOwned(asset.getId(), operation.operationId(), asset.getState(),
                        MediaAssetState.CLEANUP_PENDING, operation.leaseToken(), operation.leaseOwner(),
                        now, notBefore) != 1) {
                    throw new IllegalStateException("Media asset changed while cleanup was being requested");
                }
                changed++;
            }
        }
        return changed;
    }

    /** Moves only expired, unreferenced upload intents to cleanup; provider calls are never made here. */
    @Transactional
    public int recoverStaleUploadIntents() {
        Instant now = clock.instant();
        Instant expiredBefore = now.minusMillis(lifecycleProperties.getSafetyGraceMillis());
        List<Long> ids = assets.findExpiredOperationAssetIds(
                List.of(MediaAssetState.PENDING_UPLOAD, MediaAssetState.UPLOADING, MediaAssetState.READY),
                expiredBefore, PageRequest.of(0, lifecycleProperties.getBatchSize()));
        Set<UUID> operationIds = new java.util.LinkedHashSet<>();
        for (Long id : ids) {
            assets.findById(id).map(MediaAsset::getOperationId).ifPresent(operationIds::add);
        }

        int recovered = 0;
        for (UUID operationId : operationIds) {
            List<MediaAsset> operationAssets = assets.findAllByOperationIdForUpdate(operationId);
            if (operationAssets.isEmpty() || operationAssets.stream().anyMatch(asset ->
                    asset.getState() == MediaAssetState.ATTACHED || hasReference(asset.getId()))) {
                continue;
            }
            boolean operationExpired = operationAssets.stream()
                    .filter(asset -> isOperationLivenessState(asset.getState()))
                    .allMatch(asset -> asset.getLeaseToken() != null && asset.getLeaseUntil() != null
                            && !asset.getLeaseUntil().isAfter(expiredBefore));
            if (!operationExpired) {
                continue;
            }
            for (MediaAsset asset : operationAssets) {
                if (isOperationLivenessState(asset.getState())) {
                    asset.setState(MediaAssetState.CLEANUP_PENDING);
                    asset.setLastErrorCode("UPLOAD_OPERATION_EXPIRED");
                    clearLease(asset);
                    asset.setLeaseUntil(now);
                    recovered++;
                } else if (asset.getState() == MediaAssetState.CLEANUP_PENDING) {
                    asset.setLastErrorCode(asset.getLastErrorCode() == null
                            ? "UPLOAD_OPERATION_EXPIRED" : asset.getLastErrorCode());
                    clearLeaseOwnerAndToken(asset);
                    asset.setLeaseUntil(now);
                }
            }
        }
        assets.flush();
        return recovered;
    }

    /** Conditional row-lock claim; the returned token belongs to exactly one cleanup worker. */
    @Transactional
    public MediaAssetCleanupLease claimCleanup(long mediaAssetId, String owner) {
        requireOwner(owner);
        MediaAsset candidate = assets.findById(mediaAssetId)
                .orElseThrow(() -> new IllegalStateException("Media asset does not exist"));
        List<MediaAsset> operationAssets = assets.findAllByOperationIdForUpdate(candidate.getOperationId());
        MediaAsset asset = operationAssets.stream().filter(row -> row.getId() == mediaAssetId)
                .findFirst().orElseThrow(() -> new IllegalStateException("Media asset does not exist"));
        Instant now = clock.instant();
        if (asset.getState() != MediaAssetState.CLEANUP_PENDING || !isOperationClearForCleanup(operationAssets)
                || hasReferenceInOperation(operationAssets) || !isExpectedIdentity(asset)
                || asset.getRetryCount() >= lifecycleProperties.getMaxCleanupAttempts()
                || asset.getLeaseUntil() == null || asset.getLeaseUntil().isAfter(now)) {
            return null;
        }
        UUID token = UUID.randomUUID();
        if (assets.claimCleanupIfDue(asset.getId(), MediaAssetState.CLEANUP_PENDING,
                MediaAssetState.CLEANUP_LEASED, owner, token,
                now.plusMillis(lifecycleProperties.getCleanupLeaseMillis()),
                lifecycleProperties.getMaxCleanupAttempts(), now) != 1) {
            return null;
        }
        return new MediaAssetCleanupLease(asset.getId(), asset.getOperationId(), token);
    }

    /** Fresh transaction immediately before provider I/O; uncertainty or a reference fails closed. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MediaAssetIdentity verifyCleanupLease(MediaAssetCleanupLease lease) {
        MediaAsset asset = assets.findById(lease.mediaAssetId())
                .orElseThrow(() -> new IllegalStateException("Media asset does not exist"));
        List<MediaAsset> operationAssets = assets.findAllByOperationIdForUpdate(asset.getOperationId());
        asset = operationAssets.stream().filter(row -> row.getId().equals(lease.mediaAssetId()))
                .findFirst().orElseThrow(() -> new IllegalStateException("Media asset does not exist"));
        if (!ownsCleanupLease(asset, lease) || asset.getLeaseUntil() == null
                || !asset.getLeaseUntil().isAfter(clock.instant()) || !isOperationClearForCleanup(operationAssets)
                || hasReferenceInOperation(operationAssets)
                || !isExpectedIdentity(asset)) {
            throw new IllegalStateException("Cleanup lease is no longer safe to use");
        }
        return new MediaAssetIdentity(asset.getProvider(), asset.getCloudName(), asset.getProviderAssetId(),
                asset.getPublicId(), asset.getMediaKind(), asset.getResourceType(), asset.getDeliveryType());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finishCleanup(MediaAssetCleanupLease lease, DeleteResult result) {
        if (result != DeleteResult.DELETED && result != DeleteResult.NOT_FOUND) {
            throw new IllegalArgumentException("A successful or idempotent provider result is required");
        }
        MediaAsset asset = assets.findById(lease.mediaAssetId())
                .orElseThrow(() -> new IllegalStateException("Media asset does not exist"));
        List<MediaAsset> operationAssets = assets.findAllByOperationIdForUpdate(asset.getOperationId());
        asset = operationAssets.stream().filter(row -> row.getId().equals(lease.mediaAssetId()))
                .findFirst().orElseThrow(() -> new IllegalStateException("Media asset does not exist"));
        if (!hasActiveCleanupLease(asset, lease) || !isOperationClearForCleanup(operationAssets)
                || hasReferenceInOperation(operationAssets) || !isExpectedIdentity(asset)) {
            throw new IllegalStateException("Cleanup lease is no longer safe to finish");
        }
        asset.setState(MediaAssetState.DELETED);
        asset.setLastErrorCode(null);
        clearLease(asset);
        assets.flush();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failCleanup(MediaAssetCleanupLease lease, String safeErrorCode) {
        MediaAsset asset = lock(lease.mediaAssetId());
        if (!hasActiveCleanupLease(asset, lease)) {
            throw new IllegalStateException("Cleanup lease is no longer owned by this worker");
        }
        asset.setState(MediaAssetState.CLEANUP_PENDING);
        asset.setLastErrorCode(normalizeFailureCode(safeErrorCode));
        clearLeaseOwnerAndToken(asset);
        asset.setLeaseUntil(clock.instant().plusMillis(cleanupRetryDelay(asset.getRetryCount())));
        assets.flush();
    }

    @Transactional
    public int recoverExpiredCleanupLeases() {
        Instant now = clock.instant();
        List<Long> ids = assets.findExpiredOperationAssetIds(List.of(MediaAssetState.CLEANUP_LEASED), now,
                PageRequest.of(0, lifecycleProperties.getBatchSize()));
        int recovered = 0;
        for (Long id : ids) {
            MediaAsset asset = lock(id);
            if (asset.getState() != MediaAssetState.CLEANUP_LEASED || asset.getLeaseUntil() == null
                    || asset.getLeaseUntil().isAfter(now)) {
                continue;
            }
            asset.setState(MediaAssetState.CLEANUP_PENDING);
            asset.setLastErrorCode(hasReference(asset.getId()) ? "REFERENCE_FOUND" : "CLEANUP_LEASE_EXPIRED");
            clearLeaseOwnerAndToken(asset);
            asset.setLeaseUntil(now.plusMillis(lifecycleProperties.getSafetyGraceMillis()));
            recovered++;
        }
        assets.flush();
        return recovered;
    }

    @Transactional(readOnly = true)
    public List<Long> cleanupCandidateIds() {
        return assets.findEligibleCleanupIds(MediaAssetState.CLEANUP_PENDING,
                lifecycleProperties.getMaxCleanupAttempts(), clock.instant(),
                PageRequest.of(0, lifecycleProperties.getBatchSize()));
    }

    private MediaAsset requireUploadLease(MediaUploadIntent intent, boolean requireUnexpired) {
        Objects.requireNonNull(intent, "upload intent is required");
        List<MediaAsset> operationAssets = assets.findAllByOperationIdForUpdate(intent.operationId());
        MediaAsset asset = operationAssets.stream().filter(row -> row.getId().equals(intent.mediaAssetId()))
                .findFirst().orElseThrow(() -> new IllegalStateException("Upload lease is stale or not owned"));
        if (asset.getState() != MediaAssetState.UPLOADING || !intent.operationId().equals(asset.getOperationId())
                || !intent.leaseToken().equals(asset.getLeaseToken())
                || !intent.mediaKind().equals(asset.getMediaKind())
                || operationAssets.stream().anyMatch(row -> row.getState() == MediaAssetState.CLEANUP_PENDING
                        || row.getState() == MediaAssetState.CLEANUP_LEASED || row.getState() == MediaAssetState.DELETED)
                || (requireUnexpired && (asset.getLeaseUntil() == null || !asset.getLeaseUntil().isAfter(clock.instant())))) {
            throw new IllegalStateException("Upload lease is stale or not owned by this request");
        }
        return asset;
    }

    private void validateProviderResult(MediaAsset asset, MediaUploadIntent intent, MediaUploadResult result) {
        if (result == null || result.provider() != MediaProvider.CLOUDINARY
                || !cloudName().equals(result.cloudName()) || !intent.publicId().equals(result.publicId())
                || !asset.getPublicId().equals(result.publicId()) || !asset.getResourceType().equals(result.resourceType())
                || !asset.getDeliveryType().equals(result.deliveryType()) || result.mediaKind() != asset.getMediaKind()
                || !normalizeFormat(asset.getFormat()).equals(normalizeFormat(result.format()))
                || result.byteCount() != asset.getByteCount() || result.providerVersion() <= 0
                || result.providerAssetId() == null || result.providerAssetId().isBlank()
                || !isTrustedSecureUrl(result)) {
            throw new IllegalArgumentException("Provider upload metadata does not match the durable intent");
        }
    }

    private boolean isTrustedSecureUrl(MediaUploadResult result) {
        try {
            URI uri = URI.create(result.secureUrl());
            String suffix = result.mediaKind() == MediaKind.IMAGE ? "." + result.format() : "";
            String expectedPath = "/" + cloudName() + "/" + result.resourceType() + "/" + result.deliveryType()
                    + "/v" + result.providerVersion() + "/" + result.publicId() + suffix;
            return "https".equalsIgnoreCase(uri.getScheme())
                    && CLOUDINARY_DELIVERY_HOST.equalsIgnoreCase(uri.getHost())
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null
                    && expectedPath.equals(uri.getPath());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private List<MediaAsset> lockOperationAssets(UUID operationId, Collection<Long> ids) {
        Objects.requireNonNull(operationId, "operation ID is required");
        if (ids == null || ids.isEmpty() || new HashSet<>(ids).size() != ids.size()) {
            throw new IllegalArgumentException("Unique media asset IDs are required");
        }
        List<MediaAsset> operationAssets = assets.findAllByOperationIdForUpdate(operationId);
        List<Long> actualIds = operationAssets.stream().map(MediaAsset::getId).toList();
        if (!sameIds(actualIds, ids)) {
            throw new IllegalStateException("All assets belonging to this operation must be coordinated together");
        }
        return operationAssets;
    }

    private MediaAsset lock(long id) {
        return assets.findByIdForUpdate(id).orElseThrow(() -> new IllegalStateException("Media asset does not exist"));
    }

    private boolean hasReference(Long id) {
        return models.existsByMediaAsset_Id(id) || images.existsByMediaAsset_Id(id);
    }

    private boolean ownsCleanupLease(MediaAsset asset, MediaAssetCleanupLease lease) {
        return asset.getState() == MediaAssetState.CLEANUP_LEASED
                && lease.operationId().equals(asset.getOperationId())
                && lease.leaseToken().equals(asset.getLeaseToken());
    }

    private boolean hasActiveCleanupLease(MediaAsset asset, MediaAssetCleanupLease lease) {
        return ownsCleanupLease(asset, lease) && asset.getLeaseUntil() != null
                && asset.getLeaseUntil().isAfter(clock.instant());
    }

    private long cleanupRetryDelay(int attempts) {
        long backoff = lifecycleProperties.getRetryBaseDelayMillis();
        int exponent = Math.max(0, attempts - 1);
        for (int i = 0; i < exponent && backoff < lifecycleProperties.getRetryMaxDelayMillis(); i++) {
            long cap = lifecycleProperties.getRetryMaxDelayMillis();
            backoff = backoff > cap / 2 ? cap : Math.min(cap, backoff * 2);
        }
        return Math.max(lifecycleProperties.getSafetyGraceMillis(), backoff);
    }

    private boolean sameIds(Collection<Long> expected, Collection<Long> actual) {
        return expected != null && actual != null && expected.size() == actual.size()
                && new HashSet<>(expected).equals(new HashSet<>(actual));
    }

    private boolean operationLeaseMatches(MediaAsset asset, MediaAssetOperationLease operation) {
        return operation.operationId().equals(asset.getOperationId())
                && operation.leaseToken().equals(asset.getLeaseToken())
                && operation.leaseOwner().equals(asset.getLeaseOwner());
    }

    private boolean leaseActive(MediaAsset asset, Instant now) {
        return asset.getLeaseUntil() != null && asset.getLeaseUntil().isAfter(now);
    }

    private boolean isUploadOperationState(MediaAssetState state) {
        return state == MediaAssetState.PENDING_UPLOAD || state == MediaAssetState.UPLOADING
                || state == MediaAssetState.READY;
    }

    private boolean isOperationLivenessState(MediaAssetState state) {
        return isUploadOperationState(state);
    }

    private boolean isOperationClearForCleanup(List<MediaAsset> operationAssets) {
        return operationAssets.stream().noneMatch(asset -> isUploadOperationState(asset.getState())
                || asset.getState() == MediaAssetState.ATTACHED);
    }

    private boolean hasReferenceInOperation(List<MediaAsset> operationAssets) {
        return operationAssets.stream().anyMatch(asset -> hasReference(asset.getId()));
    }

    private boolean isExpectedIdentity(MediaAsset asset) {
        return asset.getProvider() == MediaProvider.CLOUDINARY && cloudName().equals(asset.getCloudName())
                && resourceType(asset.getMediaKind()).equals(asset.getResourceType())
                && "upload".equals(asset.getDeliveryType())
                && intendedPublicId(asset).equals(asset.getPublicId());
    }

    private String intendedPublicId(MediaAsset asset) {
        return MediaUploadIntent.create(asset.getId(), asset.getOperationId(), UUID.randomUUID(), asset.getMediaKind())
                .publicId();
    }

    private boolean validFormat(MediaKind kind, String format) {
        if (kind == MediaKind.MODEL_3D) {
            return "glb".equals(format) || "stl".equals(format);
        }
        return List.of("jpeg", "png", "webp").contains(format);
    }

    private String normalizeFormat(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String format = value.trim().toLowerCase(java.util.Locale.ROOT);
        return "jpg".equals(format) ? "jpeg" : format;
    }

    private String resourceType(MediaKind kind) {
        return kind == MediaKind.MODEL_3D ? "raw" : "image";
    }

    private String cloudName() {
        if (cloudinary.config.cloudName == null || cloudinary.config.cloudName.isBlank()) {
            throw new IllegalStateException("Cloudinary cloud name is not configured");
        }
        return cloudinary.config.cloudName;
    }

    private void requireOwner(String owner) {
        if (owner == null || owner.isBlank() || owner.length() > 128) {
            throw new IllegalArgumentException("Lease owner must contain 1 to 128 characters");
        }
    }

    private void clearLease(MediaAsset asset) {
        asset.setLeaseOwner(null);
        asset.setLeaseToken(null);
        asset.setLeaseUntil(null);
    }

    private void clearLeaseOwnerAndToken(MediaAsset asset) {
        asset.setLeaseOwner(null);
        asset.setLeaseToken(null);
    }

    private String normalizeFailureCode(String code) {
        if (code == null || !code.matches("[A-Z0-9_]{1,128}")) {
            return "PROVIDER_FAILURE";
        }
        return code;
    }
}
