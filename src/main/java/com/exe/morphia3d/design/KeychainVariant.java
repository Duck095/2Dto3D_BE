package com.exe.morphia3d.design;

import java.util.Arrays;

public enum KeychainVariant {
    ORDINARY("ordinary"), CLICK("click");

    private final String apiValue;

    KeychainVariant(String apiValue) { this.apiValue = apiValue; }

    public String apiValue() { return apiValue; }

    public static KeychainVariant fromApiValue(String value) {
        return Arrays.stream(values()).filter(variant -> variant.apiValue.equals(value)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported keychainVariant"));
    }
}
