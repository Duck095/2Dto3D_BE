package com.exe.morphia3d.service.ai;

import com.exe.morphia3d.entity.AiGenerationJob;
import com.exe.morphia3d.enums.AiGenerationStatus;
import com.exe.morphia3d.repository.AiGenerationJobRepository;
import com.exe.morphia3d.service.MediaValidationException;
import com.exe.morphia3d.service.MediaValidationService;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

@Component
public class AiGenerationWorker {
    private final AiGenerationJobRepository jobs;
    private final AiGenerationProvider provider;
    private final AiGenerationArtifactStore artifacts;
    private final AiGenerationProperties properties;
    private final MediaValidationService mediaValidation;

    public AiGenerationWorker(AiGenerationJobRepository jobs, AiGenerationProvider provider,
                              AiGenerationArtifactStore artifacts, AiGenerationProperties properties,
                              MediaValidationService mediaValidation) {
        this.jobs = jobs;
        this.provider = provider;
        this.artifacts = artifacts;
        this.properties = properties;
        this.mediaValidation = mediaValidation;
    }

    public boolean processOne() {
        if (!properties.isEnabled() || !properties.isWorkerEnabled() || !provider.isConfigured()
                || !provider.supportsIdempotency() || !artifacts.isConfigured()) return false;

        Instant now = Instant.now();
        jobs.failExpiredExhaustedLeases(now, properties.getMaxAttempts());
        String leaseOwner = "ai-worker-" + UUID.randomUUID();
        Instant leaseUntil = now.plusMillis(Math.max(properties.getProviderTimeoutMillis() * 2, 60_000));
        if (jobs.claimNextPending(leaseOwner, leaseUntil, now, properties.getMaxAttempts()) == 0) return false;

        AiGenerationJob job = jobs.findFirstByProcessingLeaseOwnerAndStatus(leaseOwner, AiGenerationStatus.PROCESSING)
                .orElse(null);
        if (job == null) return false;

        String resultKey = null;
        try {
            AiGenerationInput input = new AiGenerationInput(job.getId(), job.getPrompt(), job.getStyle(),
                    job.getInputMode(), job.getInputArtifactKey());
            AiGenerationOutput output = CompletableFuture.supplyAsync(() -> provider.generate(input, job.getId()))
                    .orTimeout(properties.getProviderTimeoutMillis(), TimeUnit.MILLISECONDS).join();
            ValidatedGlb validated = mediaValidation.validateGlb(output.glbBytes(), "result.glb", "model/gltf-binary");
            StoredArtifact stored = artifacts.storeResult(job.getId(), validated.bytes());
            resultKey = stored.key();

            AiGenerationJob current = ownedLease(job, leaseOwner);
            current.setResultArtifactKey(resultKey);
            current.setStatus(AiGenerationStatus.COMPLETED);
            current.setErrorCode(null);
            current.setErrorMessage(null);
            current.setFinishedAt(Instant.now());
            current.setProcessingLeaseOwner(null);
            current.setProcessingLeaseUntil(null);
            jobs.saveAndFlush(current);
            return true;
        } catch (RuntimeException failure) {
            if (resultKey != null) {
                try { artifacts.delete(resultKey); } catch (RuntimeException compensationFailure) { failure.addSuppressed(compensationFailure); }
            }
            completeFailure(job, leaseOwner, failure);
            return true;
        }
    }

    private AiGenerationJob ownedLease(AiGenerationJob claimed, String leaseOwner) {
        return jobs.findByIdAndProcessingLeaseOwner(claimed.getId(), leaseOwner)
                .orElseThrow(() -> new AiGenerationException("AI_JOB_LEASE_LOST"));
    }

    private void completeFailure(AiGenerationJob job, String leaseOwner, Throwable failure) {
        AiGenerationJob current = jobs.findByIdAndProcessingLeaseOwner(job.getId(), leaseOwner).orElse(null);
        if (current == null) return;
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        boolean timeout = cause instanceof java.util.concurrent.TimeoutException;
        boolean retryable = timeout || (cause instanceof AiGenerationException generationFailure
                && generationFailure.isRetryable());
        if (retryable && provider.supportsIdempotency() && current.getAttemptCount() < properties.getMaxAttempts()) {
            current.setStatus(AiGenerationStatus.PENDING);
            current.setNextAttemptAt(Instant.now().plusMillis(properties.getRetryBackoffMillis()));
            current.setErrorCode(timeout ? "AI_PROVIDER_TIMEOUT_RETRYING" : safeCode(cause));
            current.setErrorMessage("Generation will be retried safely.");
        } else {
            current.setStatus(AiGenerationStatus.FAILED);
            current.setFinishedAt(Instant.now());
            current.setErrorCode(cause instanceof MediaValidationException
                    ? "AI_INVALID_PROVIDER_OUTPUT" : timeout ? "AI_PROVIDER_TIMEOUT" : safeCode(cause));
            current.setErrorMessage("The generation request could not be completed.");
        }
        current.setProcessingLeaseOwner(null);
        current.setProcessingLeaseUntil(null);
        jobs.saveAndFlush(current);
    }

    private String safeCode(Throwable failure) {
        if (failure instanceof AiGenerationException generationFailure
                && generationFailure.getCode().matches("AI_[A-Z0-9_]{1,60}")) return generationFailure.getCode();
        return "AI_PROVIDER_FAILED";
    }
}
