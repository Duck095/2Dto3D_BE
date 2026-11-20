package com.exe.morphia3d.design;

import java.util.Arrays;

public enum ProductType {
    KEYCHAIN("keychain"), NIGHT_LIGHT("night-light"), FIGURE("figure");

    private final String apiValue;

    ProductType(String apiValue) { this.apiValue = apiValue; }

    public String apiValue() { return apiValue; }

    public static ProductType fromApiValue(String value) {
        return Arrays.stream(values()).filter(type -> type.apiValue.equals(value)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported productType"));
    }
}
