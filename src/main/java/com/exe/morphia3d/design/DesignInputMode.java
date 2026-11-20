package com.exe.morphia3d.design;

import java.util.Arrays;

public enum DesignInputMode {
    TEXT("text"), IMAGE("image"), MULTI("multi");

    private final String apiValue;

    DesignInputMode(String apiValue) { this.apiValue = apiValue; }

    public String apiValue() { return apiValue; }

    public static DesignInputMode fromApiValue(String value) {
        return Arrays.stream(values()).filter(mode -> mode.apiValue.equals(value)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported inputMode"));
    }
}
