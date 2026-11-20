package com.exe.morphia3d.service.ai;

import java.util.UUID;

public record AiGenerationInput(
        UUID jobId,
        String prompt,
        String style,
        String inputMode,
        String inputArtifactKey) {
}
