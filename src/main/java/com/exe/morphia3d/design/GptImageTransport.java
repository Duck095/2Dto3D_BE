package com.exe.morphia3d.design;

import java.util.List;

public interface GptImageTransport {
    List<ReferenceImageProvider.GeneratedReferenceImage> generate(ReferenceImageProvider.ReferenceImageRequest request);
}
