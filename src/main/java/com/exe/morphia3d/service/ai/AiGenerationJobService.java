package com.exe.morphia3d.service.ai;

import com.exe.morphia3d.dto.request.CreateAiGenerationRequest;
import com.exe.morphia3d.dto.response.AiGenerationJobResponse;
import com.exe.morphia3d.entity.AiGenerationJob;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.AiGenerationStatus;
import com.exe.morphia3d.repository.AiGenerationJobRepository;
import com.exe.morphia3d.repository.UserRepository;
import com.exe.morphia3d.security.UserPrincipal;
import com.exe.morphia3d.service.MediaValidationService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.io.InputStream;

@Service
public class AiGenerationJobService {
    private final AiGenerationJobRepository jobs;
    private final UserRepository users;
    private final AiGenerationProvider provider;
    private final AiGenerationArtifactStore artifacts;
    private final AiGenerationProperties properties;
    private final MediaValidationService mediaValidation;
    private final Map<Integer, ArrayDeque<Instant>> requestTimesByOwner = new ConcurrentHashMap<>();

    public AiGenerationJobService(AiGenerationJobRepository jobs, UserRepository users,
                                  AiGenerationProvider provider, AiGenerationArtifactStore artifacts,
                                  AiGenerationProperties properties, MediaValidationService mediaValidation) {
        this.jobs = jobs;
        this.users = users;
        this.provider = provider;
        this.artifacts = artifacts;
        this.properties = properties;
        this.mediaValidation = mediaValidation;
    }

    public AiGenerationJobResponse create(UserPrincipal principal, CreateAiGenerationRequest request,
                                          MultipartFile image, String idempotencyKey) {
        Integer ownerId = requireOwner(principal);
        idempotencyKey = idempotencyKey == null ? null : idempotencyKey.trim();
        validateRequest(request, image, idempotencyKey);
        byte[] imageBytes = readImage(image);
        String hash = requestHash(request, imageBytes);

        synchronized (quotaLock(ownerId)) {
            var existing = jobs.findByUser_IdAndIdempotencyKey(ownerId, idempotencyKey);
            if (existing.isPresent()) {
                AiGenerationJob prior = existing.get();
                if (!matchesCreateReplay(prior, request, imageBytes, hash)) {
                    throw new AiGenerationException("AI_IDEMPOTENCY_CONFLICT");
                }
                return toResponse(prior);
            }
            requireReady();
            checkAndRecordRateLimit(ownerId);
        }

        User user = users.findById(ownerId)
                .orElseThrow(() -> new AiGenerationException("AI_OWNER_NOT_FOUND"));
        UUID id = UUID.randomUUID();
        String inputKey = null;
        try {
            if (imageBytes != null) {
                inputKey = artifacts.storeInput(id, image.getContentType(), imageBytes);
            }
            AiGenerationJob job = AiGenerationJob.builder()
                    .id(id)
                    .user(user)
                    .idempotencyKey(idempotencyKey)
                    .requestHash(hash)
                    .prompt(request.prompt().trim())
                    .style(request.style().trim())
                    .quality(null)
                    .inputMode(request.inputMode())
                    .inputArtifactKey(inputKey)
                    .status(AiGenerationStatus.PENDING)
                    .attemptCount(0)
                    .build();
            return toResponse(jobs.saveAndFlush(job));
        } catch (DataIntegrityViolationException concurrentReplay) {
            deleteInput(inputKey, concurrentReplay);
            AiGenerationJob prior = jobs.findByUser_IdAndIdempotencyKey(ownerId, idempotencyKey).orElse(null);
            if (prior != null && matchesCreateReplay(prior, request, imageBytes, hash)) return toResponse(prior);
            if (prior != null) throw new AiGenerationException("AI_IDEMPOTENCY_CONFLICT");
            throw concurrentReplay;
        } catch (RuntimeException failure) {
            deleteInput(inputKey, failure);
            throw failure;
        }
    }

    public AiGenerationJobResponse findForOwner(UUID id, Integer ownerId) {
        return jobs.findByIdAndUser_Id(id, ownerId).map(this::toResponse)
                .orElseThrow(() -> new AiGenerationException("AI_GENERATION_NOT_FOUND"));
    }

    public Page<AiGenerationJobResponse> history(Integer ownerId, Pageable pageable) {
        return jobs.findByUser_IdOrderByCreatedAtDescIdDesc(ownerId, pageable).map(this::toResponse);
    }

    public AiGenerationJobResponse retry(UUID id, UserPrincipal principal, String idempotencyKey) {
        Integer ownerId = requireOwner(principal);
        idempotencyKey = idempotencyKey == null ? null : idempotencyKey.trim();
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new AiGenerationException("AI_INVALID_IDEMPOTENCY_KEY");
        }
        AiGenerationJob source = jobs.findByIdAndUser_Id(id, ownerId)
                .orElseThrow(() -> new AiGenerationException("AI_GENERATION_NOT_FOUND"));
        if (source.getStatus() != AiGenerationStatus.FAILED) {
            throw new AiGenerationException("AI_RETRY_NOT_ALLOWED");
        }
        synchronized (quotaLock(ownerId)) {
            AiGenerationJob existing = jobs.findByUser_IdAndIdempotencyKey(ownerId, idempotencyKey).orElse(null);
            if (existing != null) {
                if (!matchesRetryReplay(existing, source)) {
                    throw new AiGenerationException("AI_IDEMPOTENCY_CONFLICT");
                }
                return toResponse(existing);
            }
            requireReady();
            checkAndRecordRateLimit(ownerId);
        }
        AiGenerationJob retry = AiGenerationJob.builder()
                .id(UUID.randomUUID())
                .user(source.getUser())
                .retryOf(source)
                .idempotencyKey(idempotencyKey)
                .requestHash(source.getRequestHash())
                .prompt(source.getPrompt())
                .style(source.getStyle())
                .quality(source.getQuality())
                .inputMode(source.getInputMode())
                .inputArtifactKey(source.getInputArtifactKey())
                .status(AiGenerationStatus.PENDING)
                .attemptCount(0)
                .build();
        try {
            return toResponse(jobs.saveAndFlush(retry));
        } catch (DataIntegrityViolationException concurrentReplay) {
            AiGenerationJob existing = jobs.findByUser_IdAndIdempotencyKey(ownerId, idempotencyKey).orElse(null);
            if (existing != null && matchesRetryReplay(existing, source)) return toResponse(existing);
            if (existing != null) throw new AiGenerationException("AI_IDEMPOTENCY_CONFLICT");
            throw concurrentReplay;
        }
    }

    public InputStream openResultForOwner(UUID id, Integer ownerId) {
        AiGenerationJob job = jobs.findByIdAndUser_Id(id, ownerId)
                .orElseThrow(() -> new AiGenerationException("AI_GENERATION_NOT_FOUND"));
        if (job.getStatus() != AiGenerationStatus.COMPLETED || job.getResultArtifactKey() == null) {
            throw new AiGenerationException("AI_RESULT_NOT_READY");
        }
        return artifacts.openResult(job.getResultArtifactKey());
    }

    AiGenerationJobResponse toResponse(AiGenerationJob job) {
        return new AiGenerationJobResponse(job.getId(), job.getStatus(), job.getCreatedAt(), job.getUpdatedAt(),
                job.getFinishedAt(), job.getErrorCode(), job.getErrorMessage(),
                job.getResultModel() == null ? null : job.getResultModel().getId(),
                job.getStatus() == AiGenerationStatus.COMPLETED
                        ? "/api/ai-studio/generations/" + job.getId() + "/result" : null,
                job.getRetryOf() == null ? null : job.getRetryOf().getId());
    }

    private void validateRequest(CreateAiGenerationRequest request, MultipartFile image, String key) {
        if (request == null || request.prompt() == null || request.prompt().isBlank()
                || request.prompt().trim().length() > properties.getMaxPromptChars()
                || request.style() == null || request.style().isBlank() || request.style().trim().length() > 60) {
            throw new AiGenerationException("AI_INVALID_REQUEST");
        }
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new AiGenerationException("AI_INVALID_IDEMPOTENCY_KEY");
        }
        if ("text".equals(request.inputMode())) {
            if (image != null && !image.isEmpty()) throw new AiGenerationException("AI_UNEXPECTED_IMAGE");
        } else if ("image".equals(request.inputMode())) {
            if (image == null || image.isEmpty()) throw new AiGenerationException("AI_IMAGE_REQUIRED");
            if (image.getSize() > properties.getMaxImageBytes()) throw new AiGenerationException("AI_IMAGE_TOO_LARGE");
            try {
                mediaValidation.validateImages(java.util.List.of(image));
            } catch (RuntimeException invalid) {
                throw new AiGenerationException("AI_INVALID_IMAGE");
            }
        } else {
            throw new AiGenerationException("AI_INVALID_INPUT_MODE");
        }
    }

    private void requireReady() {
        if (!properties.isEnabled() || !properties.isWorkerEnabled()
                || !provider.isConfigured() || !artifacts.isConfigured() || !provider.supportsIdempotency()) {
            throw new AiGenerationUnavailableException("AI_PROVIDER_NOT_CONFIGURED");
        }
    }

    private byte[] readImage(MultipartFile image) {
        if (image == null || image.isEmpty()) return null;
        try { return image.getBytes(); }
        catch (IOException exception) { throw new AiGenerationException("AI_IMAGE_READ_FAILED"); }
    }

    private String requestHash(CreateAiGenerationRequest request, byte[] image) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(request.prompt().trim().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(request.style().trim().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(request.inputMode().getBytes(StandardCharsets.UTF_8));
            if (image != null) {
                digest.update((byte) 0);
                digest.update(image);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private boolean matchesRequest(AiGenerationJob prior, CreateAiGenerationRequest request, byte[] image, String hash) {
        if (prior.getRequestHash().equals(hash)) return true;
        String legacyQuality = prior.getQuality();
        return legacyQuality != null && prior.getRequestHash().equals(legacyRequestHash(request, legacyQuality, image));
    }

    private boolean matchesCreateReplay(AiGenerationJob prior, CreateAiGenerationRequest request, byte[] image, String hash) {
        return prior.getRetryOf() == null && matchesRequest(prior, request, image, hash);
    }

    private boolean matchesRetryReplay(AiGenerationJob existing, AiGenerationJob source) {
        return existing.getRetryOf() != null
                && existing.getRetryOf().getId().equals(source.getId())
                && existing.getRequestHash().equals(source.getRequestHash());
    }

    private String legacyRequestHash(CreateAiGenerationRequest request, String quality, byte[] image) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(request.prompt().trim().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(request.style().trim().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(quality.trim().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(request.inputMode().getBytes(StandardCharsets.UTF_8));
            if (image != null) {
                digest.update((byte) 0);
                digest.update(image);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private Object quotaLock(Integer ownerId) { return requestTimesByOwner.computeIfAbsent(ownerId, ignored -> new ArrayDeque<>()); }

    private void deleteInput(String inputKey, RuntimeException failure) {
        if (inputKey == null) return;
        try { artifacts.delete(inputKey); }
        catch (RuntimeException compensationFailure) { failure.addSuppressed(compensationFailure); }
    }

    private void checkAndRecordRateLimit(Integer ownerId) {
        ArrayDeque<Instant> times = requestTimesByOwner.get(ownerId);
        Instant now = Instant.now();
        Instant cutoff = now.minusSeconds(60);
        while (!times.isEmpty() && times.peekFirst().isBefore(cutoff)) times.removeFirst();
        if (times.size() >= properties.getRequestsPerMinutePerOwner()) {
            throw new AiGenerationException("AI_RATE_LIMITED");
        }
        times.addLast(now);
    }

    private Integer requireOwner(UserPrincipal principal) {
        if (principal == null || principal.getId() == null) throw new AiGenerationException("AI_AUTHENTICATION_REQUIRED");
        return principal.getId();
    }
}
