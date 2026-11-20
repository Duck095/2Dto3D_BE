package com.exe.morphia3d.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;

public record CreateAiGenerationRequest(
        @NotBlank @Size(max = 255) String prompt,
        @NotBlank @Size(max = 60) String style,
        @NotBlank @Pattern(regexp = "text|image") String inputMode) {
}
