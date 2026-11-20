package com.exe.morphia3d.design;

public record StyleProfile(ProductType productType, KeychainVariant keychainVariant, String styleId,
                           String version, String visualDirection, String productConstraints,
                           String proportions, String geometry, String expression, String signatureDetails,
                           String complexity, String noUnrequestedChanges) {
    /** Keeps the original v1 construction contract; structured fields belong to v2 profiles. */
    public StyleProfile(ProductType productType, KeychainVariant keychainVariant, String styleId,
                        String version, String visualDirection, String productConstraints) {
        this(productType, keychainVariant, styleId, version, visualDirection, productConstraints,
                null, null, null, null, null, null);
    }
}
