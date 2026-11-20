package com.exe.morphia3d.design;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record ModelGenerationCommand(@NotNull UUID referenceVersionId,
                                     @Size(max = 2000) String revisionFeedback) { }
