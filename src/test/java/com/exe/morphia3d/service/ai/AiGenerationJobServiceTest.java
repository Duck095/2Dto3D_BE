package com.exe.morphia3d.service.ai;

import com.exe.morphia3d.dto.request.CreateAiGenerationRequest;
import com.exe.morphia3d.entity.AiGenerationJob;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.AiGenerationStatus;
import com.exe.morphia3d.enums.Role;
import com.exe.morphia3d.repository.AiGenerationJobRepository;
import com.exe.morphia3d.repository.UserRepository;
import com.exe.morphia3d.security.UserPrincipal;
import com.exe.morphia3d.service.MediaUploadProperties;
import com.exe.morphia3d.service.MediaValidationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.UUID;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiGenerationJobServiceTest {
    @Mock private AiGenerationJobRepository jobs;
    @Mock private UserRepository users;
    @Mock private AiGenerationProvider provider;
    @Mock private AiGenerationArtifactStore artifacts;

    private AiGenerationProperties properties;
    private AiGenerationJobService service;
    private UserPrincipal owner;

    @BeforeEach
    void setUp() {
        properties = new AiGenerationProperties();
        properties.setEnabled(true);
        properties.setWorkerEnabled(true);
        properties.setRequestsPerMinutePerOwner(3);
        service = new AiGenerationJobService(jobs, users, provider, artifacts, properties,
                new MediaValidationService(new MediaUploadProperties()));
        owner = new UserPrincipal(User.builder().id(41).role(Role.USER).build());
        lenient().when(provider.isConfigured()).thenReturn(true);
        lenient().when(provider.supportsIdempotency()).thenReturn(true);
        lenient().when(artifacts.isConfigured()).thenReturn(true);
        lenient().when(users.findById(41)).thenReturn(Optional.of(owner.getUser()));
    }

    @Test
    void failsClosedBeforeSavingWhenProviderOrStoreIsNotConfigured() {
        when(provider.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> service.create(owner, textRequest(), null, "request-001"))
                .isInstanceOf(AiGenerationUnavailableException.class)
                .extracting("code").isEqualTo("AI_PROVIDER_NOT_CONFIGURED");

        verify(jobs, never()).saveAndFlush(any());
        verify(artifacts, never()).storeInput(any(), any(), any());
    }

    @Test
    void rejectsImageModeWithoutImageBeforeSaving() {
        CreateAiGenerationRequest request = new CreateAiGenerationRequest("a lamp", "modern", "image");

        assertThatThrownBy(() -> service.create(owner, request, null, "request-001"))
                .isInstanceOf(AiGenerationException.class)
                .hasMessage("AI_IMAGE_REQUIRED");

        verify(jobs, never()).saveAndFlush(any());
    }

    @Test
    void acceptsTextJobWithOwnerScopedIdempotencyAndPendingStatus() {
        when(jobs.findByUser_IdAndIdempotencyKey(41, "request-001")).thenReturn(Optional.empty());
        when(jobs.saveAndFlush(any(AiGenerationJob.class))).thenAnswer(invocation -> {
            AiGenerationJob job = invocation.getArgument(0);
            job.setCreatedAt(Instant.parse("2026-11-12T00:00:00Z"));
            job.setUpdatedAt(Instant.parse("2026-11-12T00:00:00Z"));
            return job;
        });

        var response = service.create(owner, textRequest(), null, "request-001");

        ArgumentCaptor<AiGenerationJob> saved = ArgumentCaptor.forClass(AiGenerationJob.class);
        verify(jobs).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getUser().getId()).isEqualTo(41);
        assertThat(saved.getValue().getStatus()).isEqualTo(AiGenerationStatus.PENDING);
        assertThat(saved.getValue().getInputArtifactKey()).isNull();
        assertThat(saved.getValue().getQuality()).isNull();
        assertThat(saved.getValue().getRequestHash()).hasSize(64);
        assertThat(response.status()).isEqualTo(AiGenerationStatus.PENDING);
    }

    @Test
    void refusesAConflictingIdempotencyReplay() {
        AiGenerationJob prior = AiGenerationJob.builder().id(UUID.randomUUID()).user(owner.getUser())
                .idempotencyKey("request-001").requestHash("0".repeat(64)).prompt("other")
                .style("modern").quality("standard").inputMode("text").status(AiGenerationStatus.PENDING).build();
        when(jobs.findByUser_IdAndIdempotencyKey(41, "request-001")).thenReturn(Optional.of(prior));

        assertThatThrownBy(() -> service.create(owner, textRequest(), null, "request-001"))
                .isInstanceOf(AiGenerationException.class)
                .hasMessage("AI_IDEMPOTENCY_CONFLICT");

        verify(jobs, never()).saveAndFlush(any());
    }

    @Test
    void sameOwnerSamePayloadReplayReturnsPriorJobWithoutSavingAnother() {
        UUID existingId = UUID.randomUUID();
        when(jobs.findByUser_IdAndIdempotencyKey(41, "request-001")).thenReturn(Optional.empty());
        when(jobs.saveAndFlush(any(AiGenerationJob.class))).thenAnswer(invocation -> {
            AiGenerationJob created = invocation.getArgument(0);
            created.setCreatedAt(Instant.parse("2026-11-12T00:00:00Z"));
            created.setUpdatedAt(Instant.parse("2026-11-12T00:00:00Z"));
            return created;
        });
        var first = service.create(owner, textRequest(), null, "request-001");
        ArgumentCaptor<AiGenerationJob> created = ArgumentCaptor.forClass(AiGenerationJob.class);
        verify(jobs).saveAndFlush(created.capture());
        AiGenerationJob prior = AiGenerationJob.builder().id(existingId).user(owner.getUser())
                .idempotencyKey("request-001").requestHash("unused").prompt("a lamp")
                .style("modern").quality("standard").inputMode("text").status(AiGenerationStatus.PENDING).build();
        prior.setRequestHash(created.getValue().getRequestHash());
        when(jobs.findByUser_IdAndIdempotencyKey(41, "request-001")).thenReturn(Optional.of(prior));

        var replay = service.create(owner, textRequest(), null, "request-001");

        assertThat(replay.id()).isEqualTo(existingId);
        assertThat(replay.status()).isEqualTo(AiGenerationStatus.PENDING);
        assertThat(first.id()).isNotEqualTo(replay.id());
        verify(jobs, org.mockito.Mockito.times(1)).saveAndFlush(any(AiGenerationJob.class));
    }

    @Test
    void legacyFingerprintReplayReturnsPriorJobBeforeCheckingProviderReadiness() throws Exception {
        UUID legacyId = UUID.randomUUID();
        AiGenerationJob prior = AiGenerationJob.builder().id(legacyId).user(owner.getUser())
                .idempotencyKey("request-001").prompt("a lamp").style("modern").quality("standard")
                .inputMode("text").status(AiGenerationStatus.PENDING).build();
        prior.setRequestHash(legacyFingerprint("a lamp", "modern", "standard", "text"));
        when(jobs.findByUser_IdAndIdempotencyKey(41, "request-001")).thenReturn(Optional.of(prior));

        var replay = service.create(owner, textRequest(), null, "request-001");

        assertThat(replay.id()).isEqualTo(legacyId);
        verify(jobs, never()).saveAndFlush(any());
        verify(provider, never()).isConfigured();
    }

    @Test
    void retryPreservesLegacyQualityAndInputMetadataAndLinksToSource() {
        UUID sourceId = UUID.randomUUID();
        AiGenerationJob source = AiGenerationJob.builder().id(sourceId).user(owner.getUser())
                .idempotencyKey("original-key").requestHash("c".repeat(64)).prompt("a lamp")
                .style("modern").quality("legacy quality").inputMode("image")
                .inputArtifactKey("inputs/legacy.glb").status(AiGenerationStatus.FAILED)
                .attemptCount(2).build();
        when(jobs.findByIdAndUser_Id(sourceId, 41)).thenReturn(Optional.of(source));
        when(jobs.findByUser_IdAndIdempotencyKey(41, "retry-key")).thenReturn(Optional.empty());
        when(jobs.saveAndFlush(any(AiGenerationJob.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.retry(sourceId, owner, "retry-key");

        ArgumentCaptor<AiGenerationJob> saved = ArgumentCaptor.forClass(AiGenerationJob.class);
        verify(jobs).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getRetryOf()).isSameAs(source);
        assertThat(saved.getValue().getQuality()).isEqualTo("legacy quality");
        assertThat(saved.getValue().getInputArtifactKey()).isEqualTo("inputs/legacy.glb");
        assertThat(saved.getValue().getRequestHash()).isEqualTo(source.getRequestHash());
    }

    @Test
    void retryReplayWithSameSourceAndKeyReturnsExistingRetry() {
        UUID sourceId = UUID.randomUUID();
        String fingerprint = "d".repeat(64);
        AiGenerationJob source = failedSource(sourceId, fingerprint);
        AiGenerationJob existingRetry = AiGenerationJob.builder().id(UUID.randomUUID()).user(owner.getUser())
                .retryOf(source).idempotencyKey("retry-key").requestHash(fingerprint)
                .prompt(source.getPrompt()).style(source.getStyle()).quality(source.getQuality())
                .inputMode(source.getInputMode()).status(AiGenerationStatus.PENDING).attemptCount(0).build();
        when(jobs.findByIdAndUser_Id(sourceId, 41)).thenReturn(Optional.of(source));
        when(jobs.findByUser_IdAndIdempotencyKey(41, "retry-key")).thenReturn(Optional.of(existingRetry));

        var replay = service.retry(sourceId, owner, "retry-key");

        assertThat(replay.id()).isEqualTo(existingRetry.getId());
        verify(jobs, never()).saveAndFlush(any());
    }

    @Test
    void retryReplayWithDifferentSourceAndSameFingerprintConflicts() {
        String fingerprint = "e".repeat(64);
        AiGenerationJob requestedSource = failedSource(UUID.randomUUID(), fingerprint);
        AiGenerationJob otherSource = failedSource(UUID.randomUUID(), fingerprint);
        AiGenerationJob existingRetry = AiGenerationJob.builder().id(UUID.randomUUID()).user(owner.getUser())
                .retryOf(otherSource).idempotencyKey("retry-key").requestHash(fingerprint)
                .prompt(otherSource.getPrompt()).style(otherSource.getStyle()).quality(otherSource.getQuality())
                .inputMode(otherSource.getInputMode()).status(AiGenerationStatus.PENDING).attemptCount(0).build();
        when(jobs.findByIdAndUser_Id(requestedSource.getId(), 41)).thenReturn(Optional.of(requestedSource));
        when(jobs.findByUser_IdAndIdempotencyKey(41, "retry-key")).thenReturn(Optional.of(existingRetry));

        assertThatThrownBy(() -> service.retry(requestedSource.getId(), owner, "retry-key"))
                .isInstanceOf(AiGenerationException.class)
                .hasMessage("AI_IDEMPOTENCY_CONFLICT");
        verify(jobs, never()).saveAndFlush(any());
    }

    @Test
    void createAndRetryOperationsConflictWhenTheyReuseTheSameKey() throws Exception {
        String fingerprint = requestFingerprint("a lamp", "modern", "text");
        AiGenerationJob source = failedSource(UUID.randomUUID(), fingerprint);
        AiGenerationJob retryOperation = AiGenerationJob.builder().id(UUID.randomUUID()).user(owner.getUser())
                .retryOf(source).idempotencyKey("operation-key").requestHash(fingerprint)
                .prompt("a lamp").style("modern").quality(null).inputMode("text")
                .status(AiGenerationStatus.PENDING).attemptCount(0).build();
        when(jobs.findByUser_IdAndIdempotencyKey(41, "operation-key")).thenReturn(Optional.of(retryOperation));

        assertThatThrownBy(() -> service.create(owner, textRequest(), null, "operation-key"))
                .isInstanceOf(AiGenerationException.class)
                .hasMessage("AI_IDEMPOTENCY_CONFLICT");

        AiGenerationJob createOperation = AiGenerationJob.builder().id(UUID.randomUUID()).user(owner.getUser())
                .idempotencyKey("operation-key-2").requestHash(fingerprint).prompt("a lamp")
                .style("modern").inputMode("text").status(AiGenerationStatus.PENDING).build();
        when(jobs.findByIdAndUser_Id(source.getId(), 41)).thenReturn(Optional.of(source));
        when(jobs.findByUser_IdAndIdempotencyKey(41, "operation-key-2")).thenReturn(Optional.of(createOperation));

        assertThatThrownBy(() -> service.retry(source.getId(), owner, "operation-key-2"))
                .isInstanceOf(AiGenerationException.class)
                .hasMessage("AI_IDEMPOTENCY_CONFLICT");
        verify(jobs, never()).saveAndFlush(any());
    }

    @Test
    void concurrentRetryReplayReturnsOnlyWhenTheSourceLineageMatches() {
        String fingerprint = "f".repeat(64);
        AiGenerationJob source = failedSource(UUID.randomUUID(), fingerprint);
        AiGenerationJob concurrentRetry = AiGenerationJob.builder().id(UUID.randomUUID()).user(owner.getUser())
                .retryOf(source).idempotencyKey("retry-key").requestHash(fingerprint)
                .prompt(source.getPrompt()).style(source.getStyle()).quality(source.getQuality())
                .inputMode(source.getInputMode()).status(AiGenerationStatus.PENDING).attemptCount(0).build();
        when(jobs.findByIdAndUser_Id(source.getId(), 41)).thenReturn(Optional.of(source));
        when(jobs.findByUser_IdAndIdempotencyKey(41, "retry-key"))
                .thenReturn(Optional.empty(), Optional.of(concurrentRetry));
        when(jobs.saveAndFlush(any(AiGenerationJob.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key"));

        var replay = service.retry(source.getId(), owner, "retry-key");

        assertThat(replay.id()).isEqualTo(concurrentRetry.getId());
        assertThat(replay.retryOf()).isEqualTo(source.getId());
    }

    @Test
    void concurrentRetryReplayWithDifferentSourceConflictsEvenWhenFingerprintMatches() {
        String fingerprint = "1".repeat(64);
        AiGenerationJob source = failedSource(UUID.randomUUID(), fingerprint);
        AiGenerationJob otherSource = failedSource(UUID.randomUUID(), fingerprint);
        AiGenerationJob concurrentRetry = AiGenerationJob.builder().id(UUID.randomUUID()).user(owner.getUser())
                .retryOf(otherSource).idempotencyKey("retry-key").requestHash(fingerprint)
                .prompt(otherSource.getPrompt()).style(otherSource.getStyle()).quality(otherSource.getQuality())
                .inputMode(otherSource.getInputMode()).status(AiGenerationStatus.PENDING).attemptCount(0).build();
        when(jobs.findByIdAndUser_Id(source.getId(), 41)).thenReturn(Optional.of(source));
        when(jobs.findByUser_IdAndIdempotencyKey(41, "retry-key"))
                .thenReturn(Optional.empty(), Optional.of(concurrentRetry));
        when(jobs.saveAndFlush(any(AiGenerationJob.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> service.retry(source.getId(), owner, "retry-key"))
                .isInstanceOf(AiGenerationException.class)
                .hasMessage("AI_IDEMPOTENCY_CONFLICT");
    }

    @Test
    void concurrentCreateCannotTreatRetryAsCreateReplay() throws Exception {
        String fingerprint = requestFingerprint("a lamp", "modern", "text");
        AiGenerationJob source = failedSource(UUID.randomUUID(), fingerprint);
        AiGenerationJob concurrentRetry = AiGenerationJob.builder().id(UUID.randomUUID()).user(owner.getUser())
                .retryOf(source).idempotencyKey("operation-key").requestHash(fingerprint)
                .prompt("a lamp").style("modern").inputMode("text")
                .status(AiGenerationStatus.PENDING).attemptCount(0).build();
        when(jobs.findByUser_IdAndIdempotencyKey(41, "operation-key"))
                .thenReturn(Optional.empty(), Optional.of(concurrentRetry));
        when(jobs.saveAndFlush(any(AiGenerationJob.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> service.create(owner, textRequest(), null, "operation-key"))
                .isInstanceOf(AiGenerationException.class)
                .hasMessage("AI_IDEMPOTENCY_CONFLICT");
    }

    @Test
    void concurrentSameKeyRequestsResolveToOnePersistedJob() throws Exception {
        var storedByKey = new ConcurrentHashMap<String, AiGenerationJob>();
        var initialLookups = new AtomicInteger();
        var bothLookedUp = new CountDownLatch(1);
        when(jobs.findByUser_IdAndIdempotencyKey(41, "request-001")).thenAnswer(invocation -> {
            if (initialLookups.incrementAndGet() <= 2) {
                if (initialLookups.get() == 2) bothLookedUp.countDown();
                return Optional.empty();
            }
            return Optional.ofNullable(storedByKey.get("request-001"));
        });
        when(users.findById(41)).thenAnswer(invocation -> {
            assertThat(bothLookedUp.await(5, TimeUnit.SECONDS)).isTrue();
            return Optional.of(owner.getUser());
        });
        doAnswer(invocation -> {
            AiGenerationJob candidate = invocation.getArgument(0);
            AiGenerationJob existing = storedByKey.putIfAbsent(candidate.getIdempotencyKey(), candidate);
            if (existing != null) throw new org.springframework.dao.DataIntegrityViolationException("duplicate key");
            candidate.setCreatedAt(Instant.now());
            candidate.setUpdatedAt(Instant.now());
            return candidate;
        }).when(jobs).saveAndFlush(any(AiGenerationJob.class));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> service.create(owner, textRequest(), null, "request-001"));
            var second = executor.submit(() -> service.create(owner, textRequest(), null, "request-001"));

            assertThat(first.get(5, TimeUnit.SECONDS).id()).isEqualTo(second.get(5, TimeUnit.SECONDS).id());
            assertThat(storedByKey).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void appliesPerOwnerRequestRateLimit() {
        when(jobs.findByUser_IdAndIdempotencyKey(org.mockito.ArgumentMatchers.eq(41), any()))
                .thenReturn(Optional.empty());
        when(jobs.saveAndFlush(any(AiGenerationJob.class))).thenAnswer(invocation -> {
            AiGenerationJob job = invocation.getArgument(0);
            job.setCreatedAt(Instant.now());
            job.setUpdatedAt(Instant.now());
            return job;
        });

        service.create(owner, textRequest(), null, "request-001");
        service.create(owner, textRequest(), null, "request-002");
        service.create(owner, textRequest(), null, "request-003");

        assertThatThrownBy(() -> service.create(owner, textRequest(), null, "request-004"))
                .isInstanceOf(AiGenerationException.class)
                .hasMessage("AI_RATE_LIMITED");
    }

    private CreateAiGenerationRequest textRequest() {
        return new CreateAiGenerationRequest("a lamp", "modern", "text");
    }

    private AiGenerationJob failedSource(UUID id, String fingerprint) {
        return AiGenerationJob.builder().id(id).user(owner.getUser()).idempotencyKey("source-key")
                .requestHash(fingerprint).prompt("a lamp").style("modern").quality("legacy quality")
                .inputMode("text").status(AiGenerationStatus.FAILED).attemptCount(1).build();
    }

    private String requestFingerprint(String prompt, String style, String inputMode) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(prompt.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(style.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(inputMode.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest());
    }

    private String legacyFingerprint(String prompt, String style, String quality, String inputMode) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(prompt.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(style.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(quality.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(inputMode.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest());
    }

    private MockMultipartFile validGlb() {
        byte[] bytes = new byte[12];
        bytes[0] = 'g'; bytes[1] = 'l'; bytes[2] = 'T'; bytes[3] = 'F';
        bytes[4] = 2; bytes[8] = 12;
        return new MockMultipartFile("image", "input.glb", "model/gltf-binary", bytes);
    }
}
