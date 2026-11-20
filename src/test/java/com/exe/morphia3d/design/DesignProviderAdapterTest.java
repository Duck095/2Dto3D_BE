package com.exe.morphia3d.design;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DesignProviderAdapterTest {
    @Test
    void gptImageAdapterReturnsExactlyRequestedSlotsAndRejectsDuplicates() {
        var request = new ReferenceImageProvider.ReferenceImageRequest(UUID.randomUUID(), "stable-job-key",
                "local prompt", "v1", List.of(), ReferenceViewSlot.canonicalOrder());
        var adapter = new GptImageReferenceProviderAdapter(sent -> new TEST_ONLY_FakeReferenceImageProvider().generate(sent));
        assertThat(adapter.generate(request)).extracting(ReferenceImageProvider.GeneratedReferenceImage::slot)
                .containsExactlyElementsOf(ReferenceViewSlot.canonicalOrder());
        assertThatThrownBy(() -> adapter.generate(new ReferenceImageProvider.ReferenceImageRequest(
                request.jobId(), "key", "prompt", "v1", List.of(), List.of(ReferenceViewSlot.FRONT, ReferenceViewSlot.FRONT))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unavailableTransportDoesNotInvokeInjectedPaidTransport() {
        AtomicBoolean called = new AtomicBoolean();
        var adapter = new GptImageReferenceProviderAdapter(request -> {
            called.set(true); throw new AssertionError("transport must be unavailable by default");
        });
        assertThatThrownBy(() -> new UnavailableGptImageTransport().generate(null))
                .isInstanceOf(ProviderUnavailableException.class);
        assertThat(called).isFalse();
    }

    @Test
    void meshyAdapterRequiresFourApprovedReferencesAndTracksAsyncTask() {
        var fake = new TEST_ONLY_FakeMeshyModelProvider();
        fake.setTaskStatuses(MeshyModelProvider.TaskStatus.PROCESSING);
        var provider = new MeshyModelProviderAdapter(new MeshyTransport() {
            @Override public String submit(MeshyModelProvider.ModelRequest request) { return fake.submit(request); }
            @Override public MeshyModelProvider.TaskStatus status(String reference) { return fake.status(reference); }
            @Override public MeshyModelProvider.GeneratedModel result(String reference) { return fake.result(reference); }
        });
        var references = ReferenceViewSlot.canonicalOrder().stream()
                .map(slot -> new MeshyModelProvider.ApprovedReference(slot.apiValue(), UUID.randomUUID().toString(), "a".repeat(64), new byte[]{1})).toList();
        var request = new MeshyModelProvider.ModelRequest(UUID.randomUUID(), "stable", "prompt", references, null);
        String taskReference = provider.submit(request);
        assertThat(taskReference).isEqualTo(fake.submittedTaskReference());
        assertThat(fake.submittedRequest()).isSameAs(request);
        assertThat(provider.status(taskReference)).isEqualTo(MeshyModelProvider.TaskStatus.PROCESSING);
        assertThat(provider.result(taskReference).reportedUsageUnits()).isEqualTo(1L);
        assertThat(fake.submitCalls()).isEqualTo(1);
        assertThat(fake.statusCalls()).isEqualTo(1);
        assertThat(fake.resultCalls()).isEqualTo(1);

        assertThatThrownBy(() -> provider.submit(new MeshyModelProvider.ModelRequest(
                UUID.randomUUID(), "empty", "prompt", List.of(), null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provider.submit(new MeshyModelProvider.ModelRequest(
                UUID.randomUUID(), "incomplete", "prompt", references.subList(0, 3), null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(fake.submitCalls()).isEqualTo(1);
    }
}
