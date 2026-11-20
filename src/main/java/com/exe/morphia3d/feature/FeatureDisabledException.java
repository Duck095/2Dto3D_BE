package com.exe.morphia3d.feature;

public final class FeatureDisabledException extends IllegalStateException {
    private final FeatureFamily family;

    public FeatureDisabledException(FeatureFamily family) {
        super("Feature is disabled");
        this.family = family;
    }

    public FeatureFamily getFamily() {
        return family;
    }
}
