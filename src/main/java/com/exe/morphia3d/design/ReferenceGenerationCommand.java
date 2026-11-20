package com.exe.morphia3d.design;

import jakarta.validation.constraints.Size;

public record ReferenceGenerationCommand(@Size(max = 16) String viewSlot,
                                         @Size(max = 2000) String revisionFeedback) { }
