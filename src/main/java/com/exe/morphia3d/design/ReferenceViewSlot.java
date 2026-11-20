package com.exe.morphia3d.design;

import java.util.Arrays;
import java.util.List;

public enum ReferenceViewSlot {
    FRONT("front"), LEFT("left"), RIGHT("right"), BACK("back");

    private static final List<ReferenceViewSlot> CANONICAL_ORDER = List.of(FRONT, LEFT, RIGHT, BACK);
    private final String apiValue;

    ReferenceViewSlot(String apiValue) { this.apiValue = apiValue; }

    public String apiValue() { return apiValue; }
    public static List<ReferenceViewSlot> canonicalOrder() { return CANONICAL_ORDER; }

    public static ReferenceViewSlot fromApiValue(String value) {
        return Arrays.stream(values()).filter(slot -> slot.apiValue.equals(value)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported reference view slot"));
    }
}
