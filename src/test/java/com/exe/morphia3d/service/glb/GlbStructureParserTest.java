package com.exe.morphia3d.service.glb;

import com.exe.morphia3d.service.MediaUploadProperties;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GlbStructureParserTest {

    private final GlbStructureParser parser = new GlbStructureParser(new MediaUploadProperties());

    @Test
    void parsesScenesInstancedMeshesPrimitivesMaterialsAndTexturesWithoutChangingBytes() {
        byte[] glb = glb(validJson(), new byte[]{1, 2, 3, 4});
        byte[] original = glb.clone();

        GlbStructure result = parser.parse(new ByteArrayInputStream(glb));

        assertThat(result.scenes()).hasSize(2);
        assertThat(result.nodes()).hasSize(2);
        assertThat(result.nodes().get(0).sceneIndices()).containsExactly(0);
        assertThat(result.nodes().get(1).sceneIndices()).containsExactly(1);
        assertThat(result.meshes()).hasSize(1);
        assertThat(result.meshes().get(0).primitives()).hasSize(2);
        assertThat(result.materials()).hasSize(2);
        assertThat(result.textures()).hasSize(1);
        assertThat(result.images()).hasSize(1);
        assertThat(result.primitiveOccurrences()).hasSize(4)
                .extracting(GlbStructure.PrimitiveOccurrence::nodeIndex)
                .containsExactly(0, 0, 1, 1);
        assertThat(result.primitiveOccurrences())
                .extracting(GlbStructure.PrimitiveOccurrence::primitiveIndex)
                .containsExactly(0, 1, 0, 1);
        assertThat(result.primitiveOccurrences().get(0).originalName()).isEqualTo("PartA");
        assertThat(glb).containsExactly(original);
        assertThatThrownBy(() -> result.nodes().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void oneNodeIncludedInSeveralScenesHasOneNodeIdentityAndSeveralSceneMemberships() {
        String json = validJson().replace("{\"nodes\":[1]}", "{\"nodes\":[0]}");

        GlbStructure result = parser.parse(glb(json, new byte[]{1, 2, 3, 4}));

        assertThat(result.nodes().get(0).sceneIndices()).containsExactly(0, 1);
        assertThat(result.nodes().get(1).sceneIndices()).isEmpty();
        assertThat(result.primitiveOccurrences()).hasSize(4)
                .extracting(GlbStructure.PrimitiveOccurrence::nodeIndex)
                .containsExactly(0, 0, 1, 1);
    }

    @Test
    void sceneMembershipIncludesDescendantsAndNodeNameFallsBackToMeshName() {
        String json = validJson()
                .replace("{\"nodes\":[0]},{\"nodes\":[1]}", "{\"nodes\":[0]}")
                .replace("{\"name\":\"PartA\",\"mesh\":0},{\"name\":\"PartB\",\"mesh\":0}",
                        "{\"mesh\":0,\"children\":[1]},{\"mesh\":0}");

        GlbStructure result = parser.parse(glb(json, new byte[]{1, 2, 3, 4}));

        assertThat(result.nodes().get(1).sceneIndices()).containsExactly(0);
        assertThat(result.primitiveOccurrences().get(0).originalName()).isEqualTo("SharedMesh");
    }

    @Test
    void duplicateNamesDoNotCollapseNodeOrPrimitiveIdentity() {
        String json = validJson().replace("PartB", "PartA");

        GlbStructure result = parser.parse(glb(json, new byte[]{1, 2, 3, 4}));

        assertThat(result.nodes()).extracting(GlbStructure.Node::name)
                .containsExactly("PartA", "PartA");
        assertThat(result.primitiveOccurrences())
                .extracting(GlbStructure.PrimitiveOccurrence::nodeIndex)
                .containsExactly(0, 0, 1, 1);
    }

    @Test
    void rejectsHeaderOnlyAndMalformedOrUnalignedChunks() {
        assertRejected(new byte[]{'g', 'l', 'T', 'F', 2, 0, 0, 0, 12, 0, 0, 0});
        assertRejected(glbWithJsonChunkLength(validJson(), 0xffff_ffffL));
        assertRejected(glbWithJsonChunkLength(validJson(), 1));
        assertRejected(glbWithBinChunkLength(validJson(), 3));
        byte[] totalLengthMismatch = glb(validJson(), new byte[]{1, 2, 3, 4});
        ByteBuffer.wrap(totalLengthMismatch).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(8, totalLengthMismatch.length - 4);
        assertRejected(totalLengthMismatch);
        assertRejected(glbWithChunkType(validJson(), 0x004e4942)); // BIN before JSON
        assertRejected(glbWithTwoJsonChunks(validJson()));
        assertRejected(glb(validJson(), new byte[]{1, 2, 3, 4, 5}) /* buffer length mismatch */);
    }

    @Test
    void rejectsInvalidHeaderJsonAndDuplicateJsonProperties() {
        byte[] valid = glb(validJson(), new byte[]{1, 2, 3, 4});
        byte[] badMagic = valid.clone();
        badMagic[0] = 'x';
        assertRejected(badMagic);
        byte[] badVersion = valid.clone();
        ByteBuffer.wrap(badVersion).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 1);
        assertRejected(badVersion);
        assertRejected(glb("{invalid", new byte[]{1, 2, 3, 4}));
        assertRejected(glb("{\"asset\":{\"version\":\"2.0\"},\"asset\":{\"version\":\"2.0\"}}", null));
    }

    @Test
    void enforcesJsonDepthAndStructuralResourceLimits() {
        GlbStructureParser shallow = new GlbStructureParser(limits().withMaxJsonDepth(4));
        assertThatThrownBy(() -> shallow.parse(glb(validJson(), new byte[]{1, 2, 3, 4})))
                .isInstanceOf(GlbStructureException.class)
                .hasMessageContaining("JSON depth");

        assertThatThrownBy(() -> new GlbStructureParser(limits().withMaxNodes(1))
                .parse(glb(validJson(), new byte[]{1, 2, 3, 4})))
                .isInstanceOf(GlbStructureException.class)
                .hasMessageContaining("nodes limit");
        assertThatThrownBy(() -> new GlbStructureParser(limits().withMaxPrimitives(1))
                .parse(glb(validJson(), new byte[]{1, 2, 3, 4})))
                .isInstanceOf(GlbStructureException.class)
                .hasMessageContaining("primitive limit");
        assertThatThrownBy(() -> new GlbStructureParser(limits().withMaxMaterials(1))
                .parse(glb(validJson(), new byte[]{1, 2, 3, 4})))
                .isInstanceOf(GlbStructureException.class)
                .hasMessageContaining("materials limit");
        assertThatThrownBy(() -> new GlbStructureParser(limits().withMaxTextures(0))
                .parse(glb(validJson(), new byte[]{1, 2, 3, 4})))
                .isInstanceOf(GlbStructureException.class)
                .hasMessageContaining("textures limit");
        assertThatThrownBy(() -> new GlbStructureParser(limits().withMaxImages(0))
                .parse(glb(validJson(), new byte[]{1, 2, 3, 4})))
                .isInstanceOf(GlbStructureException.class)
                .hasMessageContaining("images limit");
        assertThatThrownBy(() -> new GlbStructureParser(limits().withMaxMeshes(0))
                .parse(glb(validJson(), new byte[]{1, 2, 3, 4})))
                .isInstanceOf(GlbStructureException.class)
                .hasMessageContaining("meshes limit");
        assertThatThrownBy(() -> new GlbStructureParser(limits().withMaxScenes(1))
                .parse(glb(validJson(), new byte[]{1, 2, 3, 4})))
                .isInstanceOf(GlbStructureException.class)
                .hasMessageContaining("scenes limit");
        assertThatThrownBy(() -> new GlbStructureParser(limits().withMaxArrayItems(1))
                .parse(glb(validJson(), new byte[]{1, 2, 3, 4})))
                .isInstanceOf(GlbStructureException.class)
                .hasMessageContaining("scenes limit");
        String deepHierarchy = validJson()
                .replace("{\"nodes\":[0]},{\"nodes\":[1]}", "{\"nodes\":[0]}")
                .replace("{\"name\":\"PartA\",\"mesh\":0},{\"name\":\"PartB\",\"mesh\":0}",
                        "{\"mesh\":0,\"children\":[1]},{\"mesh\":0}");
        assertThatThrownBy(() -> new GlbStructureParser(limits().withMaxNodeHierarchyDepth(1))
                .parse(glb(deepHierarchy, new byte[]{1, 2, 3, 4})))
                .isInstanceOf(GlbStructureException.class)
                .hasMessageContaining("hierarchy depth");
    }

    @Test
    void rejectsNodeMeshPrimitiveMaterialTextureAndSceneReferenceErrors() {
        assertRejected(glb(validJson().replace("\"mesh\":0", "\"mesh\":9"), new byte[]{1, 2, 3, 4}));
        assertRejected(glb(validJson().replace("\"material\":1", "\"material\":9"), new byte[]{1, 2, 3, 4}));
        assertRejected(glb(validJson().replace("\"POSITION\":0", "\"POSITION\":9"), new byte[]{1, 2, 3, 4}));
        assertRejected(glb(validJson().replace("\"bufferView\":0", "\"bufferView\":9"), new byte[]{1, 2, 3, 4}));
        assertRejected(glb(validJson().replace("\"index\":0", "\"index\":9"), new byte[]{1, 2, 3, 4}));
        assertRejected(glb(validJson().replace("\"nodes\":[1]", "\"nodes\":[9]"), new byte[]{1, 2, 3, 4}));
        String cyclicHierarchy = validJson().replace(
                "\"nodes\":[{\"name\":\"PartA\",\"mesh\":0},{\"name\":\"PartB\",\"mesh\":0}]",
                "\"nodes\":[{\"mesh\":0,\"children\":[1]},{\"mesh\":0,\"children\":[0]}]");
        assertRejected(glb(cyclicHierarchy, new byte[]{1, 2, 3, 4}));
    }

    @Test
    void enforcesConfiguredFileAndJsonByteCaps() {
        byte[] valid = glb(validJson(), new byte[]{1, 2, 3, 4});
        assertThatThrownBy(() -> new GlbStructureParser(limits().withMaxFileBytes(valid.length - 1L))
                .parse(valid)).isInstanceOf(GlbStructureException.class)
                .hasMessageContaining("file byte limit");
        assertThatThrownBy(() -> new GlbStructureParser(limits().withMaxJsonBytes(64))
                .parse(valid)).isInstanceOf(GlbStructureException.class)
                .hasMessageContaining("JSON byte limit");
    }

    @Test
    void rejectsSmallGlbWhoseInstancedMeshWouldExpandPastOccurrenceLimit() {
        // A small document still multiplies the shared mesh's primitive list once per node.
        String json = instancedMeshJson(4, 2, 0, 0);

        assertThat(json.getBytes(StandardCharsets.UTF_8).length).isLessThan(1024);
        assertThatThrownBy(() -> new GlbStructureParser(limits().withMaxPrimitiveOccurrences(5))
                .parse(glb(json, null)))
                .isInstanceOf(GlbStructureException.class)
                .hasMessageContaining("primitive occurrence limit");
        assertThat(new GlbStructureParser(limits().withMaxPrimitiveOccurrences(8))
                .parse(glb(json, null)).primitiveOccurrences()).hasSize(8);
    }

    @Test
    void rejectsSceneNodeRelationExpansionBeforeMaterializingAllMemberships() {
        String json = instancedMeshJson(2, 1, 2, 2);

        assertThatThrownBy(() -> new GlbStructureParser(limits().withMaxSceneNodeMemberships(3))
                .parse(glb(json, null)))
                .isInstanceOf(GlbStructureException.class)
                .hasMessageContaining("scene-node relation limit");
    }

    @Test
    void boundsTraversalWorkEvenWhenSceneMembershipLimitIsHigher() {
        String json = instancedMeshJson(1, 1, 2, 1);

        assertThatThrownBy(() -> new GlbStructureParser(limits()
                .withMaxSceneNodeMemberships(10).withMaxTraversalOperations(3)).parse(glb(json, null)))
                .isInstanceOf(GlbStructureException.class)
                .hasMessageContaining("traversal work limit");
    }

    @Test
    void parsesEmbeddedBasisAndWebpImagesOnlyWithMatchingExtensionMimeTypes() {
        String basisJson = validJson()
                .replace("\"textures\":[{\"source\":0}]",
                        "\"textures\":[{\"source\":0,\"extensions\":{\"KHR_texture_basisu\":{\"source\":1}}}]")
                .replace("\"images\":[{\"uri\":\"texture.png\"}]",
                        "\"images\":[{\"bufferView\":0,\"mimeType\":\"image/png\"},"
                                + "{\"bufferView\":0,\"mimeType\":\"image/ktx2\"}]");
        String webpJson = validJson()
                .replace("\"textures\":[{\"source\":0}]",
                        "\"textures\":[{\"source\":0,\"extensions\":{\"EXT_texture_webp\":{\"source\":1}}}]")
                .replace("\"images\":[{\"uri\":\"texture.png\"}]",
                        "\"images\":[{\"bufferView\":0,\"mimeType\":\"image/png\"},"
                                + "{\"bufferView\":0,\"mimeType\":\"image/webp\"}]");

        assertThat(parser.parse(glb(basisJson, new byte[]{1, 2, 3, 4})).textures().get(0).sourceImageIndices())
                .containsExactly(0, 1);
        assertThat(parser.parse(glb(webpJson, new byte[]{1, 2, 3, 4})).textures().get(0).sourceImageIndices())
                .containsExactly(0, 1);
    }

    @Test
    void rejectsEmbeddedExtensionImagesWhenDeclaredMimeTypeDoesNotMatch() {
        String basisJson = validJson()
                .replace("\"textures\":[{\"source\":0}]",
                        "\"textures\":[{\"extensions\":{\"KHR_texture_basisu\":{\"source\":0}}}]")
                .replace("\"images\":[{\"uri\":\"texture.png\"}]",
                        "\"images\":[{\"bufferView\":0,\"mimeType\":\"image/png\"}]");
        String webpJson = basisJson
                .replace("KHR_texture_basisu", "EXT_texture_webp")
                .replace("image/png", "image/ktx2");

        assertRejected(glb(basisJson, new byte[]{1, 2, 3, 4}));
        assertRejected(glb(webpJson, new byte[]{1, 2, 3, 4}));
    }

    @Test
    void acceptsExtensionUriWithoutMimeButDoesNotFetchOrInferItsPayload() {
        String json = validJson()
                .replace("\"textures\":[{\"source\":0}]",
                        "\"textures\":[{\"extensions\":{\"KHR_texture_basisu\":{\"source\":0}}}]")
                .replace("\"images\":[{\"uri\":\"texture.png\"}]",
                        "\"images\":[{\"uri\":\"texture.ktx2\"}]");

        GlbStructure result = parser.parse(glb(json, new byte[]{1, 2, 3, 4}));

        assertThat(result.images().get(0).uriSource()).isTrue();
        assertThat(result.images().get(0).mimeType()).isNull();
    }

    @Test
    void rejectsExtensionUriWhenOptionalDeclaredMimeContradictsTheExtension() {
        String json = validJson()
                .replace("\"textures\":[{\"source\":0}]",
                        "\"textures\":[{\"extensions\":{\"KHR_texture_basisu\":{\"source\":0}}}]")
                .replace("\"images\":[{\"uri\":\"texture.png\"}]",
                        "\"images\":[{\"uri\":\"texture.ktx2\",\"mimeType\":\"image/webp\"}]");

        assertRejected(glb(json, new byte[]{1, 2, 3, 4}));
    }

    private String instancedMeshJson(int nodeCount, int primitiveCount, int sceneCount, int rootsPerScene) {
        StringBuilder json = new StringBuilder(128 + nodeCount * 12 + sceneCount * rootsPerScene * 7);
        json.append("{\"asset\":{\"version\":\"2.0\"},\"nodes\":[");
        for (int i = 0; i < nodeCount; i++) {
            if (i > 0) json.append(',');
            json.append("{\"mesh\":0}");
        }
        json.append("],\"meshes\":[{\"primitives\":[");
        for (int i = 0; i < primitiveCount; i++) {
            if (i > 0) json.append(',');
            json.append("{\"attributes\":{\"POSITION\":0}}");
        }
        json.append("]}],\"accessors\":[{\"count\":1,\"componentType\":5126,\"type\":\"SCALAR\"}]");
        if (sceneCount > 0) {
            json.append(",\"scenes\":[");
            for (int scene = 0; scene < sceneCount; scene++) {
                if (scene > 0) json.append(',');
                json.append("{\"nodes\":[");
                for (int node = 0; node < rootsPerScene; node++) {
                    if (node > 0) json.append(',');
                    json.append(node);
                }
                json.append("]}");
            }
            json.append(']');
        }
        return json.append('}').toString();
    }

    private void assertRejected(byte[] bytes) {
        assertThatThrownBy(() -> parser.parse(bytes)).isInstanceOf(GlbStructureException.class);
    }

    private GlbStructureParser.Limits limits() {
        return GlbStructureParser.Limits.defaults(1024 * 1024);
    }

    private String validJson() {
        return """
                {
                  "asset":{"version":"2.0"},
                  "scene":0,
                  "scenes":[{"nodes":[0]},{"nodes":[1]}],
                  "nodes":[{"name":"PartA","mesh":0},{"name":"PartB","mesh":0}],
                  "meshes":[{"name":"SharedMesh","primitives":[
                    {"attributes":{"POSITION":0},"material":0},
                    {"attributes":{"POSITION":0},"material":1}
                  ]}],
                  "accessors":[
                    {"bufferView":0,"componentType":5126,"count":1,"type":"SCALAR"},
                    {"bufferView":0,"componentType":5126,"count":1,"type":"SCALAR"}
                  ],
                  "bufferViews":[{"buffer":0,"byteLength":4}],
                  "materials":[
                    {"name":"Red","pbrMetallicRoughness":{"baseColorTexture":{"index":0}}},
                    {"name":"Blue","normalTexture":{"index":0}}
                  ],
                  "textures":[{"source":0}],
                  "images":[{"uri":"texture.png"}],
                  "buffers":[{"byteLength":4}]
                }
                """;
    }

    private byte[] glb(String json, byte[] bin) {
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        int paddedJsonLength = align4(jsonBytes.length);
        int binLength = bin == null ? 0 : align4(bin.length);
        int total = 12 + 8 + paddedJsonLength + (bin == null ? 0 : 8 + binLength);
        ByteBuffer buffer = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("glTF".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(2).putInt(total);
        buffer.putInt(paddedJsonLength).putInt(0x4e4f534a);
        buffer.put(jsonBytes);
        while (buffer.position() < 20 + paddedJsonLength) buffer.put((byte) 0x20);
        if (bin != null) {
            buffer.putInt(binLength).putInt(0x004e4942).put(bin);
            while (buffer.position() < total) buffer.put((byte) 0);
        }
        return buffer.array();
    }

    private byte[] glbWithJsonChunkLength(String json, long chunkLength) {
        byte[] bytes = glb(json, new byte[]{1, 2, 3, 4});
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(12, (int) chunkLength);
        return bytes;
    }

    private byte[] glbWithBinChunkLength(String json, int chunkLength) {
        byte[] bytes = glb(json, new byte[]{1, 2, 3, 4});
        int jsonLength = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(12);
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(20 + jsonLength, chunkLength);
        return bytes;
    }

    private byte[] glbWithChunkType(String json, int chunkType) {
        byte[] bytes = glb(json, new byte[]{1, 2, 3, 4});
        int jsonLength = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(12);
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(16, chunkType);
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(24 + jsonLength, 0x4e4f534a);
        return bytes;
    }

    private byte[] glbWithTwoJsonChunks(String json) {
        byte[] first = glb(json, null);
        int length = first.length;
        byte[] expanded = new byte[length + 8];
        System.arraycopy(first, 0, expanded, 0, length);
        ByteBuffer buffer = ByteBuffer.wrap(expanded).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(8, expanded.length).putInt(length, 0).putInt(length + 4, 0x4e4f534a);
        return expanded;
    }

    private int align4(int value) {
        return (value + 3) & ~3;
    }
}
