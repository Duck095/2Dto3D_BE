package com.exe.morphia3d.design;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record DesignApprovalCommand(@NotNull ApprovalDecision decision, @Size(max = 2000) String feedback) { }
