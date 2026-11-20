package com.exe.morphia3d.design;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptNormalizationServiceTest {
    private final PromptNormalizationService service = new PromptNormalizationService(new StyleProfileCatalog());

    @Test
    void normalizesDeterministicallyAndPreservesOriginalUnicodeIdeaExactly() {
        String idea = "  Mèo Mây – màu xanh ngọc, nhựa resin mờ; giữ chữ ‘Mây’ và ngôi sao nhỏ!\n";

        NormalizedPrompt first = service.normalize(idea, ProductType.KEYCHAIN, KeychainVariant.ORDINARY, "cute-mascot");
        NormalizedPrompt second = service.normalize(idea, ProductType.KEYCHAIN, KeychainVariant.ORDINARY, "cute-mascot");

        assertThat(first.originalIdea()).isEqualTo(idea);
        assertThat(first.normalizedText()).isEqualTo(second.normalizedText());
        assertThat(first.normalizedText()).contains(idea, "cute-mascot", "keychain", "v1");
        assertThat(first.normalizedText()).isEqualTo("Create a keychain design in the cute-mascot style (profile v1). "
                + "Rounded cute mascot with friendly expression and simplified details. "
                + "Keep a compact keychain body and a practical attachment point.  Preserve the customer's concept exactly, including subject identity, colors, materials, names, and requested details. "
                + "Customer's original idea follows verbatim:\n" + idea);
        assertThat(first.profileVersion()).isEqualTo("v1");
    }

    @Test
    void supportsImageProjectWithoutAnIdea() {
        NormalizedPrompt prompt = service.normalize(null, ProductType.NIGHT_LIGHT, null, "minimalist-glow");
        assertThat(prompt.originalIdea()).isNull();
        assertThat(prompt.normalizedText()).contains("minimalist-glow", "night-light");
    }

    @Test
    void v2PromptUsesEveryStructuredProfileFieldAndKeepsV1AsDefault() {
        String idea = "Mèo Mây – giữ nguyên chữ ‘Mây’, màu khách nêu và nét mặt bình tĩnh.\nKhông thêm phụ kiện.";
        StyleProfile profile = new StyleProfileCatalog().require(ProductType.KEYCHAIN, KeychainVariant.ORDINARY,
                "chibi", "v2");
        NormalizedPrompt prompt = service.normalize(idea, ProductType.KEYCHAIN, KeychainVariant.ORDINARY, "chibi", "v2");

        assertThat(prompt.profileVersion()).isEqualTo("v2");
        assertThat(prompt.originalIdea()).isEqualTo(idea);
        assertThat(prompt.normalizedText()).contains(profile.visualDirection(), profile.proportions(), profile.geometry(),
                profile.expression(), profile.signatureDetails(), profile.complexity(), profile.productConstraints(),
                profile.noUnrequestedChanges(), idea);
        assertThat(prompt.normalizedText()).contains("PROPORTIONS", "GEOMETRY", "EXPRESSION", "SIGNATURE DETAILS",
                "COMPLEXITY", "PRODUCT CONSTRAINTS", "DO NOT ADD");
        assertThat(service.normalize(idea, ProductType.KEYCHAIN, KeychainVariant.ORDINARY, "chibi").profileVersion())
                .isEqualTo("v1");
    }

    @Test
    void everyApprovedStyleHasAnExplicitV2Prompt() {
        StyleProfileCatalog catalog = new StyleProfileCatalog();
        assertThat(catalog.all("v2")).hasSize(20);
        for (StyleProfile profile : catalog.all("v2")) {
            NormalizedPrompt prompt = service.normalize("subject as specified", profile.productType(),
                    profile.keychainVariant(), profile.styleId(), "v2");
            assertThat(prompt.styleId()).isEqualTo(profile.styleId());
            assertThat(prompt.profileVersion()).isEqualTo("v2");
            assertThat(prompt.normalizedText()).contains(profile.visualDirection(), profile.proportions(), profile.geometry(),
                    profile.expression(), profile.signatureDetails(), profile.complexity(), profile.productConstraints(),
                    profile.noUnrequestedChanges());
        }
    }

    @Test
    void sameCharacterIdeaProducesDistinctChibiFantasyAndLowPolySpecifications() {
        String idea = "A small guardian character with a calm expression; preserve the original face and pose.";
        NormalizedPrompt chibi = service.normalize(idea, ProductType.KEYCHAIN, KeychainVariant.ORDINARY, "chibi", "v2");
        NormalizedPrompt fantasy = service.normalize(idea, ProductType.FIGURE, null, "fantasy-sculpture", "v2");
        NormalizedPrompt lowPoly = service.normalize(idea, ProductType.FIGURE, null, "low-poly-sculpture", "v2");

        assertThat(chibi.normalizedText()).contains("60–70%", "short torso", "rounded");
        assertThat(fantasy.normalizedText()).contains("requested species", "theme-appropriate sculpted planes", "Do not add weapons");
        assertThat(fantasy.normalizedText()).doesNotContain("60–70%", "chibi proportions");
        assertThat(lowPoly.normalizedText()).contains("polygon faces", "facet edges must shape the volume", "silhouette");
        assertThat(lowPoly.normalizedText()).doesNotContain("texture only");
        assertThat(chibi.normalizedText()).isNotEqualTo(fantasy.normalizedText()).isNotEqualTo(lowPoly.normalizedText());
    }

    @Test
    void v2PreservesVerbatimIdeaAndDoesNotInventAccessoriesOrPalette() {
        String idea = "  Mây & Mực — giữ nguyên ‘Mây’!\nDòng hai: không thêm đồ.\n";
        NormalizedPrompt prompt = service.normalize(idea, ProductType.KEYCHAIN, KeychainVariant.ORDINARY,
                "minimalist", "v2");

        assertThat(prompt.originalIdea()).isEqualTo(idea);
        assertThat(prompt.normalizedText()).contains(idea, "Customer idea (verbatim)", "do not rewrite", "source of truth");
        assertThat(prompt.normalizedText()).contains("verified against the available filament catalog before production approval")
                .contains("does not validate color availability");
        assertThat(prompt.normalizedText()).doesNotContain("#", "HEX palette", "filament available");
        assertThat(prompt.normalizedText()).doesNotContain("Add a ring", "Add a bow", "Add a weapon", "Add a new character");
    }

    @Test
    void v2IncludesConsistentFourViewAndProductSpecificManufacturingGuidance() {
        NormalizedPrompt ordinary = service.normalize("cloud mascot", ProductType.KEYCHAIN, KeychainVariant.ORDINARY,
                "cute-mascot", "v2");
        NormalizedPrompt click = service.normalize("cloud mascot", ProductType.KEYCHAIN, KeychainVariant.CLICK,
                "mini-mascot", "v2");
        NormalizedPrompt lamp = service.normalize("cloud lamp", ProductType.NIGHT_LIGHT, null,
                "minimalist-glow", "v2");
        NormalizedPrompt figure = service.normalize("cloud character", ProductType.FIGURE, null,
                "anime-figure", "v2");

        for (NormalizedPrompt prompt : new NormalizedPrompt[]{ordinary, click, lamp, figure}) {
            assertThat(prompt.normalizedText()).contains("FRONT", "LEFT", "RIGHT", "BACK", "same subject", "same proportions",
                    "same identity", "do not add details between views");
            assertThat(prompt.normalizedText()).contains("must be validated");
            assertThat(prompt.normalizedText()).doesNotContain("consistency verified", "manufacturing verified", "printability verified");
        }
        assertThat(ordinary.normalizedText()).contains("keychain", "attachment");
        assertThat(click.normalizedText()).contains("click", "button", "moving interface");
        assertThat(lamp.normalizedText()).contains("lamp shell", "diffusing region", "cavity");
        assertThat(figure.normalizedText()).contains("pose", "support", "print readiness");
    }

    @Test
    void rejectsUnknownProfileVersion() {
        assertThatThrownBy(() -> service.normalize("idea", ProductType.KEYCHAIN, KeychainVariant.ORDINARY,
                "chibi", "v3")).isInstanceOf(IllegalArgumentException.class);
    }
}
