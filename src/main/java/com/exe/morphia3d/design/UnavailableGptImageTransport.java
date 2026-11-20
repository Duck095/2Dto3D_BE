package com.exe.morphia3d.design;

public class UnavailableGptImageTransport implements GptImageTransport {
    @Override public java.util.List<ReferenceImageProvider.GeneratedReferenceImage> generate(ReferenceImageProvider.ReferenceImageRequest request) {
        throw new ProviderUnavailableException("GPT_IMAGE_TRANSPORT_UNAVAILABLE");
    }
}
