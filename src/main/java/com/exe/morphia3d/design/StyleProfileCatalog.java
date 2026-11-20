package com.exe.morphia3d.design;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

@Component
public class StyleProfileCatalog {
    public static final String PROFILE_VERSION = "v1";
    public static final String PROFILE_VERSION_V2 = "v2";
    private final Map<String, Map<String, StyleProfile>> profilesByVersion;

    public StyleProfileCatalog() {
        List<StyleProfile> v1 = Stream.of(
                profiles(ProductType.KEYCHAIN, KeychainVariant.ORDINARY,
                        "cute-mascot|Rounded cute mascot with friendly expression and simplified details",
                        "chibi|Chibi proportions with oversized head and compact body",
                        "cartoon|Bold cartoon shapes, clean silhouette, and expressive features",
                        "minimalist|Minimal smooth forms and restrained surface detail",
                        "low-poly|Readable low-poly facets with deliberate geometric planes"),
                profiles(ProductType.KEYCHAIN, KeychainVariant.CLICK,
                        "mini-chibi|Mini chibi proportions adapted to a compact click keychain",
                        "mini-mascot|Compact mascot silhouette with a distinct friendly face",
                        "minimalist-click|Minimal smooth compact forms designed around a click keychain body",
                        "pixel-voxel|Voxel-inspired stepped forms with crisp pixel geometry"),
                profiles(ProductType.NIGHT_LIGHT, null,
                        "minimalist-glow|Minimal lamp silhouette with a calm diffused light surface",
                        "kawaii-night-light|Soft kawaii shapes and a gentle child-friendly glow",
                        "fantasy-glow|Whimsical fantasy forms with controlled luminous accents",
                        "geometric-glow|Clear geometric volumes with evenly distributed light openings",
                        "architectural-miniature|Architectural miniature forms with layered illuminated openings"),
                profiles(ProductType.FIGURE, null,
                        "chibi-collectible|Collectible chibi proportions with a stable display pose",
                        "anime-figure|Anime-inspired sculpted forms, expressive face, and clear silhouette",
                        "cartoon-sculpture|Sculptural cartoon shapes with readable expression and volume",
                        "semi-realistic|Balanced semi-realistic anatomy and sculpted material detail",
                        "fantasy-sculpture|Fantasy character sculpt with coherent forms and display presence",
                        "low-poly-sculpture|Sculpture built from intentional, readable low-poly facets")
        ).flatMap(List::stream).toList();

        List<StyleProfile> v2 = List.of(
                profileV2(ProductType.KEYCHAIN, KeychainVariant.ORDINARY, "cute-mascot",
                        "A friendly rounded mascot that remains identifiable from its outer contour.",
                        "For a full figure, make the head about 55–65% of visible height and keep the body short; retain the customer's subject silhouette.",
                        "Build joined rounded primary masses with soft transitions and broad facial forms; avoid narrow floating projections.",
                        "When the idea includes a face, use a friendly open gaze and small smile while retaining the requested mood; do not add a face to an inanimate subject.",
                        "Use simplified facial forms and one clear mascot contour; cheeks or tiny marks appear only when specified.",
                        "Low to medium: a few broad volumes and shallow details that stay legible at keychain scale.",
                        "Keep the requested keychain outline and a clear attachment zone; validate any hole or mating fit in CAD without assuming dimensions.",
                        "Do not invent a ring, chain, bow, hat, clothing, badge, or other accessory; preserve the requested colors, materials, and subject details."),
                profileV2(ProductType.KEYCHAIN, KeychainVariant.ORDINARY, "chibi",
                        "A cute, intentionally disproportionate character with a dominant head and compact body.",
                        "For a full character, the head is roughly 60–70% of total height, with a short torso and abbreviated limbs; keep pose and identity from the idea.",
                        "Use a broad head mass, compact torso, and short rounded limbs with connected transitions; do not distort the requested silhouette into a sphere.",
                        "Use a sweet, readable expression only when a face belongs to the requested subject; match the requested emotion rather than defaulting to a smile.",
                        "The head-to-body contrast is the signature; small hands and feet remain attached and readable.",
                        "Low to medium: emphasize large forms, with only a few robust facial or clothing details from the brief.",
                        "Preserve a compact keychain body and a clear attachment zone; verify any opening or fit in CAD, with no assumed size or tolerance.",
                        "Do not add accessories, a base, costume, animal ears, or props unless requested; do not turn a non-character concept into a character."),
                profileV2(ProductType.KEYCHAIN, KeychainVariant.ORDINARY, "cartoon",
                        "A bold, elastic cartoon interpretation with a crisp, instantly readable contour.",
                        "Keep the source subject's head-to-body relationship unless exaggeration is explicit; for a person, enlarge the head only moderately, about 40–55% of height.",
                        "Use a few strong convex and concave masses, clear overlap, and controlled tapers; separate limbs or features with sculpted shapes, not linework alone.",
                        "Facial features may be lively and slightly exaggerated when the subject has a face; preserve the requested age, mood, and identity.",
                        "A strong contour, graphic eye/mouth forms, and one or two broad gesture shapes define the look.",
                        "Medium: a small set of high-contrast forms, with fine surface marks kept secondary.",
                        "Keep the cartoon contour within the requested keychain form and leave the attachment region unobstructed; test the final fit separately.",
                        "Do not add speech symbols, stars, motion marks, props, or extra costume details that the customer did not request."),
                profileV2(ProductType.KEYCHAIN, KeychainVariant.ORDINARY, "minimalist",
                        "A quiet, reduced object whose identity comes from proportion and silhouette rather than ornament.",
                        "Retain the idea's original width-to-height balance and dominant mass; reduce minor forms without changing the subject's recognizable proportions.",
                        "Resolve the object into a few smooth, connected volumes with broad transitions and almost no shallow relief; keep edges deliberate.",
                        "Do not add a face; if a face is part of the idea, retain only its essential requested features and expression.",
                        "The signature is a clean uninterrupted outline and restrained depth changes, not decorative trim.",
                        "Low: use only the major silhouette and a minimal number of structural forms.",
                        "Retain the requested keychain outline and reserve a clear attachment zone; any opening size and physical fit require CAD validation.",
                        "Do not invent a logo, face, border, texture, accent part, or accessory; keep every omitted detail omitted unless requested."),
                profileV2(ProductType.KEYCHAIN, KeychainVariant.ORDINARY, "low-poly",
                        "A faceted object with a stable, recognizable silhouette and intentional planar surfaces.",
                        "Keep the subject's original proportion and pose; use facet scale to describe volume instead of enlarging or shrinking body parts.",
                        "Construct visible polygon planes with controlled changes in plane direction, readable edges, and facets following the form; do not simulate polygons with a surface image.",
                        "For a character, keep the requested expression legible with a few sculpted planes; for a non-character, do not add a face.",
                        "The defining detail is the transition between broad planar facets, including on the silhouette and major volumes.",
                        "Low to medium: use a deliberate facet count, keeping small faces out of tiny keychain details.",
                        "Maintain a robust keychain silhouette and clear attachment zone; validate the physical opening and fit in CAD without assumed tolerances.",
                        "Do not add polygon decals, color bands, props, or accessories; low-poly geometry must not alter the customer's subject or pose."),
                profileV2(ProductType.KEYCHAIN, KeychainVariant.CLICK, "mini-chibi",
                        "A miniature chibi character sized visually around a compact click-keychain body.",
                        "Use a head about 60–70% of character height, short torso, and tucked limbs; keep the overall motif compact without prescribing mechanism dimensions.",
                        "Group the head and body into connected rounded masses; keep relief shallow and keep projections away from the button and attachment interfaces.",
                        "Use the requested mood on an existing face; do not turn symbols or objects into smiling characters.",
                        "The small-scale head/body contrast and tucked silhouette distinguish this style from the fuller ordinary-keychain chibi.",
                        "Low: broad face and body forms only; omit thin details that cannot remain robust at the chosen scale.",
                        "Keep the specified click mechanism, button travel region, and attachment interface unobstructed; final clearances and fit must be verified in CAD.",
                        "Do not add a key ring, chain, costume, extra button, or decoration; do not assume click-mechanism dimensions or change the requested character."),
                profileV2(ProductType.KEYCHAIN, KeychainVariant.CLICK, "mini-mascot",
                        "A compact mascot with a balanced, recognizable body and a small friendly focal feature.",
                        "Keep head and body closer to the subject's natural ratio than chibi, with the head generally under half the visible height for a full figure.",
                        "Use a continuous compact body mass, a distinct face plane where appropriate, and rounded transitions that leave the click mechanism clear.",
                        "A gentle expression may be shown when the concept calls for a face; preserve the requested emotion and gaze direction.",
                        "A compact torso contour with a clearly separated face focal area, rather than a dominant oversized head.",
                        "Low to medium: a few solid volumes and broad facial details, avoiding fragile whiskers or spikes.",
                        "Keep the specified click button, moving interface, and attachment area clear; validate mechanism clearances and fit in CAD.",
                        "Do not append ears, tail, clothing, ring, chain, or extra controls unless they are part of the customer's request."),
                profileV2(ProductType.KEYCHAIN, KeychainVariant.CLICK, "minimalist-click",
                        "A restrained click-keychain shell whose identity is carried by its clean massing and mechanism layout.",
                        "Preserve the requested shell width-to-height balance and keep the control area visually subordinate but accessible.",
                        "Use smooth, simple outer planes with a deliberate button boundary and only the openings required by the supplied design; avoid decorative seams.",
                        "No face by default; retain a requested face only if it does not obscure the control surface.",
                        "The clear control outline and uninterrupted shell surface are the defining features.",
                        "Low: minimal pieces and shallow transitions; do not imply a mechanical fit before dimensional validation.",
                        "Preserve the stated click function and attachment interface; button travel, clearances, and tolerances require CAD or physical validation.",
                        "Do not invent a logo, extra button, indicator light, ring, chain, or housing feature absent from the customer's request."),
                profileV2(ProductType.KEYCHAIN, KeychainVariant.CLICK, "pixel-voxel",
                        "A block-built pixel/voxel object with stepped geometry that reads in three dimensions.",
                        "Maintain the subject's overall width, height, and pose while quantizing small contours into visible block steps.",
                        "Build orthogonal voxel masses and stepped contour changes; expose actual block faces and depth, not merely a pixel texture on a smooth surface.",
                        "For a character, use a block-positioned expression that matches the idea; do not add a face to an object.",
                        "The stepped silhouette, aligned block faces, and visible depth define the style.",
                        "Medium: a controlled number of joined blocks; merge tiny protrusions into larger solids where needed for fabrication review.",
                        "Keep the click control and attachment interfaces clear of voxel overhangs; validate dimensions and moving clearances in CAD.",
                        "Do not add a pixel screen, game symbols, extra colors, ring, or chain unless requested; do not replace the specified subject with a generic game avatar."),
                profileV2(ProductType.NIGHT_LIGHT, null, "minimalist-glow",
                        "A calm lamp object with a simple body and one clearly defined diffusing region.",
                        "Preserve the requested lamp's tall/wide balance; let the diffuser occupy the intended focal area without expanding the body arbitrarily.",
                        "Use smooth continuous shell masses and a clean boundary between opaque structure and luminous surface; avoid unnecessary cutouts.",
                        "No facial expression by default; if the requested lamp character has a face, keep its requested calm expression legible on the diffuser.",
                        "A quiet silhouette, plain transitions, and a focused light-diffusing panel.",
                        "Low: a small number of large surfaces and no ornamental relief unless present in the idea.",
                        "Keep the lamp shell coherent and the specified light region clear; LED, cavity, ventilation, electrical, and thermal dimensions require separate engineering validation.",
                        "Do not add a switch, cable, stars, stand, face, or extra luminous windows unless requested; do not claim unverified lighting or safety performance."),
                profileV2(ProductType.NIGHT_LIGHT, null, "kawaii-night-light",
                        "A soft, welcoming night light with rounded massing and a gentle visual focal point.",
                        "For a character-shaped lamp, keep the head/body ratio soft and compact; otherwise follow the lamp's supplied silhouette without adding character anatomy.",
                        "Use broad rounded shell forms and smooth transitions around the intended diffuser; avoid thin appendages or sharp decorative points.",
                        "A gentle expression is used only when the brief calls for a face; keep eyes and mouth simple and consistent with the requested mood.",
                        "A soft silhouette and a controlled friendly focal area, not a collection of cute accessories.",
                        "Low to medium: broad shapes and only a few shallow facial or surface features explicitly in the design.",
                        "Keep the requested light-diffusing surface and enclosure continuous; validate internal light-source, cavity, thermal, and electrical requirements separately.",
                        "Do not add cheeks, ears, bows, stars, a face, or extra light apertures unless requested; do not invent device dimensions or performance."),
                profileV2(ProductType.NIGHT_LIGHT, null, "fantasy-glow",
                        "A themed luminous object whose fantasy character comes from the customer's stated subject.",
                        "Keep the requested focal mass dominant and preserve the source object's proportion; use a secondary relief only where the concept supports it.",
                        "Use sculpted layered contours or an organic frame around the luminous region when they match the brief; keep the shell connected and openings intentional.",
                        "If the subject is a character, retain its specified expression; do not make it mysterious, fierce, or cute by default.",
                        "A clear light-bearing focal form framed by theme-specific sculptural relief, rather than generic magical symbols.",
                        "Medium: a few layered masses with readable depth; keep fine relief away from small light openings.",
                        "Preserve the lamp enclosure and specified luminous region; cavity, light-source, thermal, and electrical fit must be validated separately.",
                        "Do not add runes, stars, moon, crystals, weapon, wings, horns, or a new character unless the customer requests them."),
                profileV2(ProductType.NIGHT_LIGHT, null, "geometric-glow",
                        "A lamp formed from deliberate geometric volumes around a controlled luminous area.",
                        "Keep the supplied height-to-width balance and arrange the main geometric mass around the requested light center.",
                        "Use actual planar faces, repeated geometric sections, and consistent transitions; any apertures are structural openings, not printed patterns.",
                        "No expression by default; a requested character face must be expressed through the existing geometric surfaces without changing its mood.",
                        "The rhythm of polygonal planes and evenly considered negative space defines this style.",
                        "Medium: repeat a small, coherent set of geometric forms instead of adding many unrelated facets.",
                        "Keep the enclosing body stable and light openings intentional; opening sizes, internal clearance, thermal behavior, and electrical fit need engineering validation.",
                        "Do not introduce a face, extra windows, decorative lattice, or a new symmetry that changes the customer's concept; no unverified dimensions."),
                profileV2(ProductType.NIGHT_LIGHT, null, "architectural-miniature",
                        "A compact architectural object whose façade layers and opening rhythm follow the requested building.",
                        "Respect the source building's width-to-height relationship and hierarchy of base, body, and top; do not add floors to fill space.",
                        "Model façade planes, recesses, and layered openings as real volumes; use arches, columns, or cornices only where the source design contains them.",
                        "Not applicable to an inanimate building; if the requested subject includes a character, keep its stated expression separate from the architecture.",
                        "A legible façade hierarchy and deliberate depth around the specified illuminated openings.",
                        "Medium: preserve a few recognizable façade layers and avoid fine relief that crowds the miniature silhouette.",
                        "Maintain a coherent lamp shell and the requested illuminated openings; internal cavity, light-source, ventilation, and thermal design require validation.",
                        "Do not add balconies, towers, roof ornaments, doors, windows, or signage absent from the idea; do not assume scale or lighting dimensions."),
                profileV2(ProductType.FIGURE, null, "chibi-collectible",
                        "A collectible character with a dominant head, short body, and a compact display pose.",
                        "Make the head roughly 60–70% of figure height, with short torso and limbs; preserve the character's identity, age cues, and requested pose.",
                        "Use rounded head and body masses with simplified attached limbs and a low, compact silhouette; do not hide anatomy beneath invented costume.",
                        "Use a cute expression only if it matches the requested character and mood; preserve recognizable eyes, mouth, and gaze.",
                        "Head-to-body contrast and a compact pose; a base is used only when specified or required by a separately reviewed stability decision.",
                        "Low to medium: broad sculpted forms and a few robust face details; fine accessories remain out unless specified.",
                        "Keep the figure's pose and contact points coherent for display; do not assert printability or stability until orientation and support are reviewed.",
                        "Do not add a base, pet, prop, hat, weapon, costume, or extra character unless requested; do not convert other proportions into chibi."),
                profileV2(ProductType.FIGURE, null, "anime-figure",
                        "A stylized character sculpt with clear anime-influenced facial planes and an intentional pose.",
                        "Use character-appropriate head/body proportions from the brief; eye scale may be stylized, but do not automatically enlarge every head or eye.",
                        "Shape hair as grouped sculpted locks, define facial planes cleanly, and separate limbs through overlap and pose rather than painted lines alone.",
                        "Preserve the character's stated age, emotion, eye direction, and identity; expressive does not mean always cheerful.",
                        "Crisp face framing and grouped hair/garment forms integrated into the requested silhouette.",
                        "Medium to high: several readable sculpted forms, with fine facial marks subordinate to the physical silhouette.",
                        "Preserve a coherent full-figure pose and contact points; anatomy, supports, and fabrication readiness require independent review.",
                        "Do not add a uniform, weapon, pet, base, extra hair ornament, or signature pose unless requested; do not change identity or age cues."),
                profileV2(ProductType.FIGURE, null, "cartoon-sculpture",
                        "A volumetric cartoon figure built from bold shapes that preserve the supplied subject.",
                        "Exaggerate only the proportions named or implied by the reference; keep head, torso, and limb relationships consistent with the chosen pose.",
                        "Use broad sculptural masses, clear overlaps, and rounded or tapered transitions; ensure the silhouette reads without relying on texture.",
                        "Sculpt an expressive face only for a subject with a face and match the requested emotion and distinctive features.",
                        "A strong contour with a small number of exaggerated but connected forms.",
                        "Medium: broad volumes and selected facial/garment relief; avoid thin unsupported details.",
                        "Maintain a coherent display pose and identifiable contact points; support strategy and manufacturability require separate assessment.",
                        "Do not introduce props, speech marks, extra limbs, costume pieces, or a base unless requested; do not replace the subject's proportions wholesale."),
                profileV2(ProductType.FIGURE, null, "semi-realistic",
                        "A restrained sculpt that balances natural form transitions with the exact requested subject.",
                        "For an adult human, use a natural proportion near 7–8 head lengths only when consistent with the reference; for other subjects, follow their anatomy and the supplied image.",
                        "Model continuous anatomical planes, believable joints, and material transitions; avoid both chibi compression and ornamental over-sculpting.",
                        "Retain the specified expression, age cues, and gaze; do not substitute a generic neutral or smiling face.",
                        "Subtle form transitions and carefully prioritized anatomy rather than surface embellishment.",
                        "Medium to high: prioritize major anatomy and a few defining material folds; keep micro-detail subordinate to silhouette.",
                        "Keep the pose and support contacts coherent; anatomy accuracy, structural support, and print readiness require review against references and fabrication process.",
                        "Do not add wrinkles, scars, costume, tools, base, or age changes unless requested; do not claim anatomical or print validation without evidence."),
                profileV2(ProductType.FIGURE, null, "fantasy-sculpture",
                        "A fantasy sculpt whose non-real forms are anchored to the customer's named character and theme.",
                        "Let the requested species and pose determine proportion; use elongated, compact, or asymmetric anatomy only when the concept calls for it.",
                        "Use theme-appropriate sculpted planes and layered forms tied to the stated concept; keep silhouette and joints readable instead of adding generic ornament.",
                        "Preserve the requested character emotion and identity; fantasy styling does not imply a fierce or mysterious expression.",
                        "A subject-specific sculptural motif integrated into the body, not a stock set of fantasy appendages.",
                        "Medium to high: a controlled hierarchy of primary anatomy, theme-specific secondary forms, and limited surface details.",
                        "Maintain coherent pose and support contacts; unusual anatomy, thin projections, structural strength, and printability need independent review.",
                        "Do not add weapons, wings, horns, tail, armor, runes, companion, or base unless requested; do not turn a non-fantasy subject into a generic fantasy character."),
                profileV2(ProductType.FIGURE, null, "low-poly-sculpture",
                        "A three-dimensional sculpture whose angular planes describe form while preserving the source silhouette.",
                        "Keep the reference silhouette, body proportions, pose, and major landmarks; vary facet size by form, not by changing anatomy.",
                        "Build readable polygon faces with visible plane breaks across head, torso, and limbs; facet edges must shape the volume, not exist only as a texture.",
                        "Keep the requested face and emotion readable through a small number of sculpted planes; do not add a face to a non-character.",
                        "A consistent facet language from outer contour through major anatomical planes.",
                        "Medium: use enough planes to describe the pose and major forms without fragmenting small features.",
                        "Preserve a coherent figure silhouette and contact points; facet thickness, structural strength, and print orientation require separate fabrication review.",
                        "Do not add polygon decals, props, armor, extra facets that alter identity, or accessories absent from the request; do not treat low-poly as color alone.")
        );

        profilesByVersion = Map.of(
                PROFILE_VERSION, index(v1),
                PROFILE_VERSION_V2, index(v2));
    }

    private static Map<String, StyleProfile> index(List<StyleProfile> profiles) {
        return profiles.stream().collect(Collectors.toUnmodifiableMap(StyleProfile::styleId, Function.identity()));
    }

    private static List<StyleProfile> profiles(ProductType productType, KeychainVariant variant, String... entries) {
        return Stream.of(entries).map(entry -> {
            String[] parts = entry.split("\\|", 2);
            return new StyleProfile(productType, variant, parts[0], PROFILE_VERSION, parts[1], constraints(productType, variant));
        }).toList();
    }

    private static StyleProfile profileV2(ProductType type, KeychainVariant variant, String styleId,
            String visualDirection, String proportions, String geometry, String expression, String signatureDetails,
            String complexity, String productConstraints, String noUnrequestedChanges) {
        return new StyleProfile(type, variant, styleId, PROFILE_VERSION_V2, visualDirection, productConstraints,
                proportions, geometry, expression, signatureDetails, complexity, noUnrequestedChanges);
    }

    private static String constraints(ProductType type, KeychainVariant variant) {
        return switch (type) {
            case KEYCHAIN -> variant == KeychainVariant.CLICK
                    ? "Keep the compact click-keychain mechanism and attachment geometry clear."
                    : "Keep a compact keychain body and a practical attachment point. ";
            case NIGHT_LIGHT -> "Keep the lamp body stable, enclosed, and suitable for a soft light source.";
            case FIGURE -> "Keep the figure coherent as a single display object with a stable base or pose.";
        };
    }

    public StyleProfile require(ProductType productType, KeychainVariant keychainVariant, String styleId) {
        return require(productType, keychainVariant, styleId, PROFILE_VERSION);
    }

    public StyleProfile require(ProductType productType, KeychainVariant keychainVariant, String styleId, String version) {
        Objects.requireNonNull(productType, "productType is required");
        if (productType == ProductType.KEYCHAIN && keychainVariant == null) {
            throw new IllegalArgumentException("keychainVariant is required for keychain");
        }
        if (productType != ProductType.KEYCHAIN && keychainVariant != null) {
            throw new IllegalArgumentException("keychainVariant is only valid for keychain");
        }
        Map<String, StyleProfile> versionProfiles = profilesByVersion.get(version);
        if (versionProfiles == null) throw new IllegalArgumentException("Unsupported style profile version");
        StyleProfile profile = versionProfiles.get(styleId);
        if (profile == null || profile.productType() != productType || profile.keychainVariant() != keychainVariant) {
            throw new IllegalArgumentException("Unsupported styleId for productType/keychainVariant");
        }
        return profile;
    }

    public List<StyleProfile> all() { return all(PROFILE_VERSION); }

    public List<StyleProfile> all(String version) {
        Map<String, StyleProfile> versionProfiles = profilesByVersion.get(version);
        if (versionProfiles == null) throw new IllegalArgumentException("Unsupported style profile version");
        return List.copyOf(versionProfiles.values());
    }
}
