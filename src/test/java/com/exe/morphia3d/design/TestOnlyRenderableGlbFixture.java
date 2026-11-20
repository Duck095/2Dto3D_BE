package com.exe.morphia3d.design;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Deterministic test-only GLB with one triangle in the default scene. */
final class TestOnlyRenderableGlbFixture {
    private TestOnlyRenderableGlbFixture() {}

    static byte[] validTriangle() {
        String json = """
                {"asset":{"version":"2.0","generator":"TEST_ONLY AI Studio CI"},
                 "scene":0,"scenes":[{"nodes":[0]}],"nodes":[{"mesh":0}],
                 "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}],
                 "buffers":[{"byteLength":36}],
                 "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36,"target":34962}],
                 "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3",
                               "min":[0,0,0],"max":[1,1,0]}]}
                """.replaceAll("\\s+", " ").trim();
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        int jsonPaddedLength = (jsonBytes.length + 3) & ~3;
        ByteBuffer positions = ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN);
        for (float coordinate : new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0}) positions.putFloat(coordinate);
        byte[] positionBytes = positions.array();
        int totalLength = 12 + 8 + jsonPaddedLength + 8 + positionBytes.length;
        ByteBuffer glb = ByteBuffer.allocate(totalLength).order(ByteOrder.LITTLE_ENDIAN);
        glb.putInt(0x46546c67).putInt(2).putInt(totalLength);
        glb.putInt(jsonPaddedLength).putInt(0x4e4f534a).put(jsonBytes);
        while (glb.position() < 20 + jsonPaddedLength) glb.put((byte) ' ');
        glb.putInt(positionBytes.length).putInt(0x004e4942).put(positionBytes);
        return glb.array();
    }
}
