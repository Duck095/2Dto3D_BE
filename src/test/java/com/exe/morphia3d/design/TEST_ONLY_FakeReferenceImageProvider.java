package com.exe.morphia3d.design;

import java.util.List;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

public final class TEST_ONLY_FakeReferenceImageProvider implements ReferenceImageProvider {
    private final AtomicInteger invocations = new AtomicInteger();
    private final ConcurrentLinkedQueue<java.util.UUID> invokedJobIds = new ConcurrentLinkedQueue<>();
    private volatile List<ReferenceViewSlot> lastRequestedSlots = List.of();
    private volatile boolean invalidOutput;

    @Override public List<GeneratedReferenceImage> generate(ReferenceImageRequest request) {
        invocations.incrementAndGet();
        invokedJobIds.add(request.jobId());
        lastRequestedSlots = List.copyOf(request.slots());
        byte[] content = invalidOutput ? new byte[]{1, 2, 3} : validPng();
        return request.slots().stream().map(slot -> new GeneratedReferenceImage(slot, "image/png", content, 1L)).toList();
    }

    public int invocationCount() { return invocations.get(); }
    public List<java.util.UUID> invocationJobIds() { return List.copyOf(invokedJobIds); }
    public List<ReferenceViewSlot> lastRequestedSlots() { return lastRequestedSlots; }
    public void setInvalidOutput(boolean invalidOutput) { this.invalidOutput = invalidOutput; }

    private byte[] validPng() {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xff336699);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) throw new IllegalStateException("PNG writer unavailable");
            return output.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("PNG fixture generation failed", impossible);
        } finally {
            image.flush();
        }
    }
}
