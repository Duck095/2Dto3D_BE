package com.exe.morphia3d.design;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateDesignProjectRequest(
        @NotBlank @Size(max = 24) String productType,
        @Size(max = 16) String keychainVariant,
        @NotBlank @Size(max = 64) String styleId,
        @NotBlank @Size(max = 16) String inputMode,
        @Size(max = 4000) String originalIdea,
        List<@Size(max = 64) String> clientSlots,
        List<@Size(max = 16) String> viewHints) { }
