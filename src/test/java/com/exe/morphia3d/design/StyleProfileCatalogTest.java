package com.exe.morphia3d.design;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StyleProfileCatalogTest {
    private final StyleProfileCatalog catalog = new StyleProfileCatalog();

    @Test
    void exposesEveryApprovedStyleTuple() {
        assertThat(catalog.all()).extracting(StyleProfile::styleId).containsExactlyInAnyOrderElementsOf(List.of(
                "cute-mascot", "chibi", "cartoon", "minimalist", "low-poly",
                "mini-chibi", "mini-mascot", "minimalist-click", "pixel-voxel",
                "minimalist-glow", "kawaii-night-light", "fantasy-glow", "geometric-glow", "architectural-miniature",
                "chibi-collectible", "anime-figure", "cartoon-sculpture", "semi-realistic", "fantasy-sculpture", "low-poly-sculpture"));
        assertThat(catalog.all()).extracting(StyleProfile::styleId).doesNotHaveDuplicates().hasSize(20);
        assertThat(catalog.all()).allSatisfy(profile -> assertThat(profile.version()).isEqualTo("v1"));
    }

    @Test
    void preservesEachApprovedProductAndVariantMapping() {
        assertThat(catalog.all()).extracting(profile -> tuple(profile.productType(), profile.keychainVariant(), profile.styleId()))
                .containsExactlyInAnyOrder(
                        "keychain/ordinary/cute-mascot", "keychain/ordinary/chibi", "keychain/ordinary/cartoon",
                        "keychain/ordinary/minimalist", "keychain/ordinary/low-poly",
                        "keychain/click/mini-chibi", "keychain/click/mini-mascot", "keychain/click/minimalist-click",
                        "keychain/click/pixel-voxel",
                        "night-light/-/minimalist-glow", "night-light/-/kawaii-night-light", "night-light/-/fantasy-glow",
                        "night-light/-/geometric-glow", "night-light/-/architectural-miniature",
                        "figure/-/chibi-collectible", "figure/-/anime-figure", "figure/-/cartoon-sculpture",
                        "figure/-/semi-realistic", "figure/-/fantasy-sculpture", "figure/-/low-poly-sculpture");
    }

    @Test
    void versionAwareLookupKeepsV1AndExposesTwentyV2Profiles() {
        assertThat(catalog.require(ProductType.KEYCHAIN, KeychainVariant.ORDINARY, "chibi", "v1"))
                .isEqualTo(catalog.require(ProductType.KEYCHAIN, KeychainVariant.ORDINARY, "chibi"));

        List<StyleProfile> v2Profiles = catalog.all("v2");
        assertThat(v2Profiles).hasSize(20).extracting(StyleProfile::styleId).doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(catalog.all().stream().map(StyleProfile::styleId).toList());
        assertThat(v2Profiles).extracting(StyleProfile::visualDirection).doesNotHaveDuplicates();
        assertThat(v2Profiles).extracting(StyleProfile::proportions).doesNotHaveDuplicates();
        assertThat(v2Profiles).extracting(StyleProfile::geometry).doesNotHaveDuplicates();
        assertThat(v2Profiles).extracting(StyleProfile::expression).doesNotHaveDuplicates();
        assertThat(v2Profiles).extracting(StyleProfile::signatureDetails).doesNotHaveDuplicates();
        assertThat(v2Profiles).extracting(StyleProfile::complexity).doesNotHaveDuplicates();
        assertThat(v2Profiles).extracting(StyleProfile::productConstraints).doesNotHaveDuplicates();
        assertThat(v2Profiles).extracting(StyleProfile::noUnrequestedChanges).doesNotHaveDuplicates();
        assertThat(v2Profiles).extracting(profile -> tuple(profile.productType(), profile.keychainVariant(), profile.styleId()))
                .containsExactlyInAnyOrderElementsOf(catalog.all().stream()
                        .map(profile -> tuple(profile.productType(), profile.keychainVariant(), profile.styleId())).toList());
        assertThat(v2Profiles).allSatisfy(profile -> {
            assertThat(profile.version()).isEqualTo("v2");
            assertThat(profile.visualDirection()).isNotBlank();
            assertThat(profile.proportions()).isNotBlank();
            assertThat(profile.geometry()).isNotBlank();
            assertThat(profile.expression()).isNotBlank();
            assertThat(profile.signatureDetails()).isNotBlank();
            assertThat(profile.complexity()).isNotBlank();
            assertThat(profile.productConstraints()).isNotBlank();
            assertThat(profile.noUnrequestedChanges()).isNotBlank().contains("Do not");
        });
        assertThatThrownBy(() -> catalog.require(ProductType.KEYCHAIN, KeychainVariant.ORDINARY, "chibi", "v3"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void chibiFantasyAndLowPolyCarryDistinctVisualAndGeometricRules() {
        StyleProfile chibi = catalog.require(ProductType.KEYCHAIN, KeychainVariant.ORDINARY, "chibi", "v2");
        StyleProfile fantasy = catalog.require(ProductType.FIGURE, null, "fantasy-sculpture", "v2");
        StyleProfile lowPoly = catalog.require(ProductType.FIGURE, null, "low-poly-sculpture", "v2");

        assertThat(chibi.proportions()).contains("head", "60–70%");
        assertThat(chibi.proportions().toLowerCase()).doesNotContain("natural anatomy");
        assertThat(fantasy.proportions().toLowerCase()).contains("species", "pose");
        assertThat(fantasy.visualDirection().toLowerCase()).contains("fantasy");
        assertThat(chibi.noUnrequestedChanges().toLowerCase()).contains("accessor");
        assertThat(fantasy.noUnrequestedChanges().toLowerCase()).contains("weapon", "wing", "horn");
        assertThat(lowPoly.geometry().toLowerCase()).contains("polygon", "facet");
        assertThat(lowPoly.proportions().toLowerCase()).contains("silhouette");
        assertThat(lowPoly.geometry().toLowerCase()).doesNotContain("texture only");
    }

    @Test
    void v1PromptNormalizationOutputRemainsUnchanged() {
        PromptNormalizationService normalization = new PromptNormalizationService(catalog);
        assertThat(normalization.normalize(null, ProductType.KEYCHAIN, KeychainVariant.ORDINARY, "cute-mascot").normalizedText())
                .isEqualTo("Create a keychain design in the cute-mascot style (profile v1). Rounded cute mascot with friendly expression and simplified details. "
                        + "Keep a compact keychain body and a practical attachment point.  Preserve the customer's concept exactly, including subject identity, colors, materials, names, and requested details.");
    }

    @Test
    void validatesProductVariantAndStyleTogether() {
        assertThat(catalog.require(ProductType.KEYCHAIN, KeychainVariant.ORDINARY, "cute-mascot").styleId())
                .isEqualTo("cute-mascot");
        assertThatThrownBy(() -> catalog.require(ProductType.KEYCHAIN, null, "cute-mascot"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> catalog.require(ProductType.FIGURE, KeychainVariant.CLICK, "chibi-collectible"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> catalog.require(ProductType.FIGURE, null, "cute-mascot"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private String tuple(ProductType type, KeychainVariant variant, String styleId) {
        return type.apiValue() + "/" + (variant == null ? "-" : variant.apiValue()) + "/" + styleId;
    }

}
