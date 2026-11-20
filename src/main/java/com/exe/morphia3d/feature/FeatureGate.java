package com.exe.morphia3d.feature;

public final class FeatureGate {
    private final FeatureGateProperties properties;

    public FeatureGate(FeatureGateProperties properties) {
        this.properties = properties;
    }

    public boolean isEnabled(FeatureFamily family) {
        return properties.isEnabled(family);
    }

    public boolean isAdminProductSnapshotUploadEnabled() {
        return properties.isAdminProductSnapshotUploadEnabled();
    }

    public void requireEnabled(FeatureFamily family) {
        if (!isEnabled(family)) throw new FeatureDisabledException(family);
    }
}
