package com.exe.morphia3d.design;

import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class PromptNormalizationService {
    private final StyleProfileCatalog catalog;

    public PromptNormalizationService(StyleProfileCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog);
    }

    public NormalizedPrompt normalize(String originalIdea, ProductType productType,
                                      KeychainVariant keychainVariant, String styleId) {
        return normalize(originalIdea, productType, keychainVariant, styleId, StyleProfileCatalog.PROFILE_VERSION);
    }

    public NormalizedPrompt normalize(String originalIdea, ProductType productType,
                                      KeychainVariant keychainVariant, String styleId, String profileVersion) {
        StyleProfile profile = catalog.require(productType, keychainVariant, styleId, profileVersion);
        String idea = originalIdea == null ? "" : originalIdea;
        String normalized = StyleProfileCatalog.PROFILE_VERSION.equals(profile.version())
                ? normalizeV1(profile, idea)
                : normalizeV2(profile, idea);
        return new NormalizedPrompt(originalIdea, normalized, profile.styleId(), profile.version());
    }

    private String normalizeV1(StyleProfile profile, String idea) {
        return "Create a " + profile.productType().apiValue() + " design in the " + profile.styleId()
                + " style (profile " + profile.version() + "). " + profile.visualDirection() + ". "
                + profile.productConstraints() + " Preserve the customer's concept exactly, including subject identity, colors, materials, names, and requested details."
                + (idea.isEmpty() ? "" : " Customer's original idea follows verbatim:\n" + idea);
    }

    private String normalizeV2(StyleProfile profile, String idea) {
        StringBuilder prompt = new StringBuilder()
                .append("Create a ").append(profile.productType().apiValue()).append(" design in the ")
                .append(profile.styleId()).append(" style (profile ").append(profile.version()).append(").\n")
                .append("STYLE DIRECTION: ").append(profile.visualDirection()).append("\n")
                .append("PROPORTIONS: ").append(profile.proportions()).append("\n")
                .append("GEOMETRY: ").append(profile.geometry()).append("\n")
                .append("EXPRESSION: ").append(profile.expression()).append("\n")
                .append("SIGNATURE DETAILS: ").append(profile.signatureDetails()).append("\n")
                .append("COMPLEXITY: ").append(profile.complexity()).append("\n")
                .append("PRODUCT CONSTRAINTS: ").append(profile.productConstraints()).append("\n")
                .append("DO NOT ADD: ").append(profile.noUnrequestedChanges()).append("\n")
                .append("Customer idea (verbatim) — source of truth:\n")
                .append(idea)
                .append("\ndo not rewrite the customer idea. Preserve its subject, identity, requested details, colors, materials, names, pose, and expression. If style guidance conflicts with the customer idea, follow the idea; do not silently remove or replace its requirements.")
                .append("\nFOUR-VIEW CONSISTENCY: Show FRONT, LEFT, RIGHT, and BACK views of the same subject, same identity, same proportions, expression, pose, signature details, colors, and materials across views. do not add details between views. This is prompt guidance and does not verify generated image consistency.")
                .append("\nCOLOR CHECK: Requested colors must be verified against the available filament catalog before production approval; this prompt does not validate color availability.")
                .append("\nPRODUCTION VALIDATION: The product constraints above are design guidance. Dimensions, clearances, fit, structural strength, and print readiness must be validated in CAD or by physical review; this prompt does not claim those checks have passed.");
        return prompt.toString();
    }
}
