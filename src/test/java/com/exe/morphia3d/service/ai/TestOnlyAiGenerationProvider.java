package com.exe.morphia3d.service.ai;

import java.util.UUID;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** TEST_ONLY deterministic provider. Never register outside test configurations. */
public final class TestOnlyAiGenerationProvider implements AiGenerationProvider {
    @Override public boolean isConfigured() { return true; }
    @Override public boolean supportsIdempotency() { return true; }
    @Override public AiGenerationOutput generate(AiGenerationInput input, UUID jobId) {
        return new AiGenerationOutput(testGlb(), "TEST_ONLY generated model");
    }

    private byte[] testGlb() {
        String json = "{\"asset\":{\"version\":\"2.0\",\"generator\":\"TEST_ONLY provider\"},"
                + "\"scene\":0,\"scenes\":[{\"nodes\":[0]}],\"nodes\":[{\"mesh\":0}],"
                + "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0},\"indices\":1}]}],"
                + "\"buffers\":[{\"byteLength\":44}],\"bufferViews\":["
                + "{\"buffer\":0,\"byteOffset\":0,\"byteLength\":36,\"target\":34962},"
                + "{\"buffer\":0,\"byteOffset\":36,\"byteLength\":6,\"target\":34963}],"
                + "\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\","
                + "\"min\":[-0.5,0,-0.5],\"max\":[0.5,1,0.5]},"
                + "{\"bufferView\":1,\"componentType\":5123,\"count\":3,\"type\":\"SCALAR\"}]}";
        byte[] rawJson = json.getBytes(StandardCharsets.UTF_8);
        int jsonLength = (rawJson.length + 3) & ~3;
        byte[] binary = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                .putFloat(-0.5f).putFloat(0).putFloat(-0.5f)
                .putFloat(0.5f).putFloat(0).putFloat(-0.5f)
                .putFloat(0).putFloat(1).putFloat(0.5f)
                .putShort((short) 0).putShort((short) 1).putShort((short) 2)
                .putShort((short) 0).array();
        ByteBuffer glb = ByteBuffer.allocate(12 + 8 + jsonLength + 8 + binary.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        glb.put((byte) 'g').put((byte) 'l').put((byte) 'T').put((byte) 'F');
        glb.putInt(2).putInt(glb.capacity());
        glb.putInt(jsonLength).putInt(0x4E4F534A).put(rawJson);
        while (glb.position() < 20 + jsonLength) glb.put((byte) 0x20);
        glb.putInt(binary.length).putInt(0x004E4942).put(binary);
        return glb.array();
    }
}
