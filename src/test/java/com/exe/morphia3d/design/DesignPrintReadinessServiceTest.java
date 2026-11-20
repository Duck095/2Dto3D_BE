package com.exe.morphia3d.design;

import com.exe.morphia3d.entity.DesignModelApproval;
import com.exe.morphia3d.entity.DesignModelVersion;
import com.exe.morphia3d.entity.DesignProject;
import com.exe.morphia3d.repository.DesignModelApprovalRepository;
import com.exe.morphia3d.repository.DesignModelVersionRepository;
import com.exe.morphia3d.repository.DesignProjectRepository;
import com.exe.morphia3d.service.MediaUploadProperties;
import com.exe.morphia3d.service.MediaValidationService;
import com.exe.morphia3d.service.glb.GlbStructureParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DesignPrintReadinessServiceTest {
    private final DesignProjectRepository projects = mock(DesignProjectRepository.class);
    private final DesignModelVersionRepository models = mock(DesignModelVersionRepository.class);
    private final DesignModelApprovalRepository approvals = mock(DesignModelApprovalRepository.class);
    private final DesignArtifactStore artifacts = mock(DesignArtifactStore.class);
    private final UUID projectId = UUID.randomUUID();
    private final UUID modelId = UUID.randomUUID();
    private final Integer owner = 31;
    private final byte[] glb = validGlb(true);
    private DesignProject project;
    private DesignModelVersion model;

    @BeforeEach void setUp() throws Exception {
        reset(projects, models, approvals, artifacts);
        project = new DesignProject(); project.setId(projectId); project.setOwnerUserId(owner); project.setStatus(DesignProjectStatus.COMPLETED);
        model = new DesignModelVersion(); model.setId(modelId); model.setOwnerUserId(owner); model.setProjectId(projectId);
        model.setVersionNumber(1); model.setStatus(DesignVersionStatus.APPROVED); model.setArtifactKey("dsa2:31:" + projectId + ":" + UUID.randomUUID());
        model.setMimeType("model/gltf-binary"); model.setByteSize((long) glb.length); model.setSha256(hash(glb));
        when(projects.findByIdAndOwnerUserId(projectId, owner)).thenReturn(Optional.of(project));
        when(models.findByIdAndOwnerUserId(modelId, owner)).thenReturn(Optional.of(model));
        when(models.findTopByProjectIdAndOwnerUserIdOrderByVersionNumberDesc(projectId, owner)).thenReturn(Optional.of(model));
        DesignModelApproval approval = new DesignModelApproval(); approval.setDecision(ApprovalDecision.APPROVED);
        when(approvals.findTopByModelVersionIdAndOwnerUserIdOrderByCreatedAtDesc(modelId, owner)).thenReturn(Optional.of(approval));
        when(artifacts.read(owner, projectId, model.getArtifactKey())).thenReturn(glb);
    }

    @Test void noTrustedEvidenceFailsClosedAndReadDoesNotMutate() throws Exception {
        var result = service((id, digest) -> Optional.empty(), 1d, 300d).get(owner, projectId, modelId);
        assertThat(result.printReadiness()).isEqualTo("NOT_READY");
        assertThat(result.reasonCode()).isEqualTo("DIMENSION_EVIDENCE_MISSING");
        assertThat(result.xMm()).isNull();
        verify(artifacts, times(1)).read(owner, projectId, model.getArtifactKey());
    }

    @Test void exactBoundEvidenceWithinExplicitLimitsCanReturnReady() throws Exception {
        var evidence = new VerifiedModelDimensionEvidenceSource.Evidence(modelId, hash(glb), 12.5, 34.0, 56.75,
                "mm", "trusted-cad", "caliper", Instant.parse("2026-11-19T00:00:00Z"), "measurement-abc", false, false);
        var result = service((id, digest) -> Optional.of(evidence), 1d, 100d).get(owner, projectId, modelId);
        assertThat(result.printReadiness()).isEqualTo("READY");
        assertThat(result.xMm()).isEqualTo(12.5);
        assertThat(result.measurementModelVersionId()).isEqualTo(modelId);
        assertThat(result.measurementArtifactSha256()).isEqualTo(hash(glb));
        assertThat(result.provenanceId()).isEqualTo("measurement-abc");
        assertThat(result.reasonCode()).isNull();
    }

    @Test void headerOnlyMalformedJsonAndUnreferencedGeometryCannotBecomeReady() throws Exception {
        byte[] headerOnly = new byte[]{'g','l','T','F',2,0,0,0,12,0,0,0};
        assertArtifactInvalid(headerOnly);
        assertArtifactInvalid(glb("{invalid", new byte[0]));
        assertArtifactInvalid(validGlb(false));
    }

    @Test void pointLineAndSingleVertexPrimitivesCannotBecomeReady() throws Exception {
        assertArtifactInvalid(glbWithGeometry(1, null, true));
        assertArtifactInvalid(glbWithGeometry(3, 0, true));
        assertArtifactInvalid(glbWithGeometry(3, 1, true));
    }

    @Test void triangleTopologiesNeedEnoughElementsAndVerifiedSurfaceGeometry() throws Exception {
        assertArtifactInvalid(glbWithGeometry(2, 4, true));
        assertArtifactInvalid(glbWithGeometry(2, 5, true));
        assertArtifactInvalid(glbWithGeometry(2, 6, true));
        float[] triangleVertices = {0,0,0, 1,0,0, 0,1,0};
        assertArtifactInvalid(glbWithPositions(triangleVertices, 4, new byte[]{0,1}, true));
        assertArtifactInvalid(glbWithPositions(triangleVertices, 4, new byte[]{0,1,2,0}, true));
        assertArtifactInvalid(glbWithPositions(triangleVertices, 5, new byte[]{0,1}, true));
        assertArtifactInvalid(glbWithPositions(triangleVertices, 6, new byte[]{0,1}, true));
        assertArtifactInvalid(glbWithPositions(new float[]{0,0,0, 0,0,0, 1,0,0}, 4, null, true));
        assertArtifactInvalid(glbWithPositions(new float[]{0,0,0, 1,0,0, 2,0,0}, 4, null, true));
        assertArtifactInvalid(glbWithPositions(new float[]{0,0,0, Float.NaN,0,0, 0,1,0}, 4, null, true));
        assertArtifactInvalid(glbWithPositions(new float[]{0,0,0, 1,0,0, 0,1,0}, 4,
                new byte[]{0,1,3}, true));
        assertArtifactInvalid(glbWithPositions(new float[]{0,0,0, 1,0,0, 0,1,0}, 4,
                new byte[]{0,0,1}, true));
        assertArtifactInvalid(glbWithPositions(new float[]{0,0,0, 1,0,0, 0,1,0}, 4,
                new byte[]{0,1,2}, true, 5120, 36));
        assertArtifactInvalid(glbWithPositions(new float[]{0,0,0, 1,0,0, 0,1,0}, 4,
                new byte[]{0,1,2}, true, 5121, 136));
    }

    @Test void indexedAndTriangleStripFanSurfacesCanReturnReadyWithVerifiedEvidence() throws Exception {
        assertArtifactReady(glbWithPositions(new float[]{0,0,0, 1,0,0, 0,1,0}, 4,
                new byte[]{0,1,2}, true));
        assertArtifactReady(glbWithPositions(new float[]{0,0,0, 1,0,0, 0,1,0}, 5, null, true));
        assertArtifactReady(glbWithPositions(new float[]{0,0,0, 1,0,0, 0,1,0}, 6, null, true));
    }

    @Test void onlyGeometryInTheSelectedActiveSceneCanReturnReady() throws Exception {
        assertArtifactInvalid(glbWithGeometryOnlyInNonActiveScene());
        assertArtifactInvalid(glbWithNoSelectedScene());
        assertArtifactInvalid(glbWithValidAndUnverifiableActivePrimitives());
    }

    @Test void externalAndSparsePositionBuffersFailClosed() throws Exception {
        assertArtifactInvalid(glbWithExternalPositionBuffer());
        assertArtifactInvalid(glbWithSparsePositionBuffer());
    }

    @Test void provenanceFieldsAndMeasurementTimeAreRequiredForReady() throws Exception {
        for (var evidence : java.util.List.of(
                evidence("", "method", "prov", Instant.now()),
                evidence("source", "  ", "prov", Instant.now()),
                evidence("source", "method", null, Instant.now()),
                evidence("source", "method", "prov", null),
                evidence("source", "method", "prov", Instant.now().plusSeconds(60)))) {
            assertThat(service((id, digest) -> Optional.of(evidence), 1d, 100d).get(owner, projectId, modelId).reasonCode())
                    .isEqualTo("DIMENSION_EVIDENCE_INVALID");
        }
    }

    @Test void wrongVersionSupersededConflictAndOutOfBoundsEvidenceAreRejected() throws Exception {
        var wrong = new VerifiedModelDimensionEvidenceSource.Evidence(UUID.randomUUID(), hash(glb), 10, 10, 10,
                "mm", "trusted-cad", "caliper", Instant.now(), "x", false, false);
        assertThat(service((id, digest) -> Optional.of(wrong), 1d, 100d).get(owner, projectId, modelId).reasonCode())
                .isEqualTo("DIMENSION_EVIDENCE_MISMATCH");
        var old = new VerifiedModelDimensionEvidenceSource.Evidence(modelId, hash(glb), 10, 10, 10,
                "mm", "trusted-cad", "caliper", Instant.now(), "x", true, false);
        assertThat(service((id, digest) -> Optional.of(old), 1d, 100d).get(owner, projectId, modelId).reasonCode())
                .isEqualTo("DIMENSION_EVIDENCE_CONFLICT");
        var outOfBounds = new VerifiedModelDimensionEvidenceSource.Evidence(modelId, hash(glb), 10, 10, 1000,
                "mm", "trusted-cad", "caliper", Instant.now(), "x", false, false);
        assertThat(service((id, digest) -> Optional.of(outOfBounds), 1d, 100d).get(owner, projectId, modelId).reasonCode())
                .isEqualTo("DIMENSION_EVIDENCE_INVALID");
        for (double value : new double[]{0d, -1d, Double.NaN, Double.POSITIVE_INFINITY}) {
            var invalidValue = new VerifiedModelDimensionEvidenceSource.Evidence(modelId, hash(glb), value, 10, 10,
                    "mm", "trusted-cad", "caliper", Instant.now(), "x", false, false);
            assertThat(service((id, digest) -> Optional.of(invalidValue), 1d, 100d).get(owner, projectId, modelId).reasonCode())
                    .as("dimension value %s", value).isEqualTo("DIMENSION_EVIDENCE_INVALID");
        }
    }

    @Test void notCompletedUnapprovedSupersededAndChecksumMismatchFailClosed() throws Exception {
        project.setStatus(DesignProjectStatus.MODEL_REVIEW);
        assertThat(service((id, digest) -> Optional.empty(), 1d, 100d).get(owner, projectId, modelId).reasonCode())
                .isEqualTo("PROJECT_NOT_COMPLETED");
        project.setStatus(DesignProjectStatus.COMPLETED); model.setStatus(DesignVersionStatus.READY_FOR_REVIEW);
        assertThat(service((id, digest) -> Optional.empty(), 1d, 100d).get(owner, projectId, modelId).reasonCode())
                .isEqualTo("MODEL_NOT_APPROVED");
        model.setStatus(DesignVersionStatus.APPROVED); DesignModelVersion superseding = new DesignModelVersion();
        superseding.setId(UUID.randomUUID());
        when(models.findTopByProjectIdAndOwnerUserIdOrderByVersionNumberDesc(projectId, owner)).thenReturn(Optional.of(superseding));
        assertThat(service((id, digest) -> Optional.empty(), 1d, 100d).get(owner, projectId, modelId).reasonCode())
                .isEqualTo("MODEL_VERSION_SUPERSEDED");
        when(models.findTopByProjectIdAndOwnerUserIdOrderByVersionNumberDesc(projectId, owner)).thenReturn(Optional.of(model));
        model.setSha256("0".repeat(64));
        assertThat(service((id, digest) -> Optional.empty(), 1d, 100d).get(owner, projectId, modelId).reasonCode())
                .isEqualTo("ARTIFACT_CHECKSUM_MISMATCH");
    }

    @Test void missingUnreadableAndMalformedArtifactsFailClosed() throws Exception {
        model.setArtifactKey(null);
        assertThat(service((id, digest) -> Optional.empty(), 1d, 100d).get(owner, projectId, modelId).reasonCode())
                .isEqualTo("ARTIFACT_MISSING");
        model.setArtifactKey("dsa2:31:" + projectId + ":artifact");
        doThrow(new java.io.IOException("private")).when(artifacts).read(owner, projectId, model.getArtifactKey());
        assertThat(service((id, digest) -> Optional.empty(), 1d, 100d).get(owner, projectId, modelId).reasonCode())
                .isEqualTo("ARTIFACT_UNREADABLE");
        doReturn(new byte[]{1, 2, 3}).when(artifacts).read(owner, projectId, model.getArtifactKey());
        assertThat(service((id, digest) -> Optional.empty(), 1d, 100d).get(owner, projectId, modelId).reasonCode())
                .isEqualTo("ARTIFACT_INVALID");
    }

    private DesignPrintReadinessService service(VerifiedModelDimensionEvidenceSource source, Double min, Double max) {
        return new DesignPrintReadinessService(projects, models, approvals, artifacts,
                new MediaValidationService(new MediaUploadProperties()),
                new GlbStructureParser(new MediaUploadProperties()), source, min, max);
    }
    private VerifiedModelDimensionEvidenceSource.Evidence evidence(String source, String method, String provenance, Instant measuredAt) {
        return new VerifiedModelDimensionEvidenceSource.Evidence(modelId, model.getSha256(), 12.5, 34.0, 56.75,
                "mm", source, method, measuredAt, provenance, false, false);
    }
    private void assertArtifactInvalid(byte[] bytes) throws Exception {
        model.setByteSize((long) bytes.length); model.setSha256(hash(bytes));
        doReturn(bytes).when(artifacts).read(owner, projectId, model.getArtifactKey());
        var result = service((id, digest) -> Optional.of(evidence("source", "method", "prov", Instant.now())), 1d, 100d)
                .get(owner, projectId, modelId);
        assertThat(result.printReadiness()).isEqualTo("NOT_READY");
        assertThat(result.reasonCode()).isEqualTo("ARTIFACT_INVALID");
        assertThat(result.xMm()).isNull();
    }
    private void assertArtifactReady(byte[] bytes) throws Exception {
        model.setByteSize((long) bytes.length); model.setSha256(hash(bytes));
        doReturn(bytes).when(artifacts).read(owner, projectId, model.getArtifactKey());
        var result = service((id, digest) -> Optional.of(evidence("source", "method", "prov", Instant.now())), 1d, 100d)
                .get(owner, projectId, modelId);
        assertThat(result.printReadiness()).isEqualTo("READY");
        assertThat(result.reasonCode()).isNull();
    }
    private static byte[] validGlb(boolean sceneReferencesNode) {
        return glbWithGeometry(3, null, sceneReferencesNode);
    }
    private static byte[] glbWithGeometry(int vertexCount, Integer mode, boolean sceneReferencesNode) {
        float[] positions = new float[vertexCount * 3];
        if (vertexCount >= 3) positions = new float[]{0,0,0, 1,0,0, 0,1,0};
        return glbWithPositions(positions, mode == null ? 4 : mode, null, sceneReferencesNode);
    }
    private static byte[] glbWithPositions(float[] coordinates, int mode, byte[] indices, boolean sceneReferencesNode) {
        return glbWithPositions(coordinates, mode, indices, sceneReferencesNode, 5121, coordinates.length * Float.BYTES);
    }
    private static byte[] glbWithPositions(float[] coordinates, int mode, byte[] indices, boolean sceneReferencesNode,
                                           int indexComponentType, int indexViewOffset) {
        int vertexCount = coordinates.length / 3;
        byte[] positionBytes = new byte[coordinates.length * Float.BYTES];
        ByteBuffer positionBuffer = ByteBuffer.wrap(positionBytes).order(ByteOrder.LITTLE_ENDIAN);
        for (float coordinate : coordinates) positionBuffer.putFloat(coordinate);
        int positionAccessorBytes = positionBytes.length;
        byte[] bin = positionBytes;
        String indexJson = "";
        String accessorJson = "{\"bufferView\":0,\"componentType\":5126,\"count\":" + vertexCount + ",\"type\":\"VEC3\"}";
        String viewJson = "{\"buffer\":0,\"byteLength\":" + positionAccessorBytes + "}";
        if (indices != null) {
            bin = java.util.Arrays.copyOf(positionBytes, positionBytes.length + indices.length);
            System.arraycopy(indices, 0, bin, positionBytes.length, indices.length);
            indexJson = ",\"indices\":1";
            accessorJson += ",{\"bufferView\":1,\"componentType\":" + indexComponentType + ",\"count\":"
                    + indices.length + ",\"type\":\"SCALAR\"}";
            viewJson += ",{\"buffer\":0,\"byteOffset\":" + indexViewOffset + ",\"byteLength\":" + indices.length + "}";
        }
        String roots = sceneReferencesNode ? "[0]" : "[]";
        String json = "{\"asset\":{\"version\":\"2.0\"},\"scene\":0,\"scenes\":[{\"nodes\":" + roots + "}],"
                + "\"nodes\":[{\"mesh\":0}],\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0}"
                + indexJson + ",\"mode\":" + mode + "}]}],\"accessors\":[" + accessorJson + "],\"bufferViews\":["
                + viewJson + "],\"buffers\":[{\"byteLength\":" + bin.length + "}]}";
        return glb(json, bin);
    }
    private static byte[] glbWithGeometryOnlyInNonActiveScene() {
        float[] coordinates = {0,0,0, 1,0,0, 0,1,0};
        byte[] positions = new byte[coordinates.length * Float.BYTES];
        ByteBuffer data = ByteBuffer.wrap(positions).order(ByteOrder.LITTLE_ENDIAN);
        for (float coordinate : coordinates) data.putFloat(coordinate);
        String json = "{\"asset\":{\"version\":\"2.0\"},\"scene\":0,\"scenes\":[{\"nodes\":[0]},{\"nodes\":[1]}],"
                + "\"nodes\":[{}, {\"mesh\":0}],\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0}}]}],"
                + "\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"}],"
                + "\"bufferViews\":[{\"buffer\":0,\"byteLength\":" + positions.length + "}],\"buffers\":[{\"byteLength\":" + positions.length + "}]}";
        return glb(json, positions);
    }
    private static byte[] glbWithNoSelectedScene() {
        float[] coordinates = {0,0,0, 1,0,0, 0,1,0};
        byte[] positions = new byte[coordinates.length * Float.BYTES];
        ByteBuffer data = ByteBuffer.wrap(positions).order(ByteOrder.LITTLE_ENDIAN);
        for (float coordinate : coordinates) data.putFloat(coordinate);
        String json = "{\"asset\":{\"version\":\"2.0\"},\"scenes\":[{\"nodes\":[0]}],"
                + "\"nodes\":[{\"mesh\":0}],\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0}}]}],"
                + "\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"}],"
                + "\"bufferViews\":[{\"buffer\":0,\"byteLength\":" + positions.length + "}],\"buffers\":[{\"byteLength\":" + positions.length + "}]}";
        return glb(json, positions);
    }
    private static byte[] glbWithValidAndUnverifiableActivePrimitives() {
        float[] coordinates = {0,0,0, 1,0,0, 0,1,0};
        byte[] positions = new byte[coordinates.length * Float.BYTES];
        ByteBuffer data = ByteBuffer.wrap(positions).order(ByteOrder.LITTLE_ENDIAN);
        for (float coordinate : coordinates) data.putFloat(coordinate);
        String json = "{\"asset\":{\"version\":\"2.0\"},\"scene\":0,\"scenes\":[{\"nodes\":[0]}],"
                + "\"nodes\":[{\"mesh\":0}],\"meshes\":[{\"primitives\":["
                + "{\"attributes\":{\"POSITION\":0}},{\"attributes\":{\"POSITION\":0},\"mode\":0}]}],"
                + "\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"}],"
                + "\"bufferViews\":[{\"buffer\":0,\"byteLength\":" + positions.length + "}],\"buffers\":[{\"byteLength\":" + positions.length + "}]}";
        return glb(json, positions);
    }
    private static byte[] glbWithExternalPositionBuffer() {
        String json = "{\"asset\":{\"version\":\"2.0\"},\"scene\":0,\"scenes\":[{\"nodes\":[0]}],"
                + "\"nodes\":[{\"mesh\":0}],\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0}}]}],"
                + "\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"}],"
                + "\"bufferViews\":[{\"buffer\":0,\"byteLength\":36}],\"buffers\":[{\"uri\":\"mesh.bin\",\"byteLength\":36}]}";
        return glbWithoutBin(json);
    }
    private static byte[] glbWithSparsePositionBuffer() {
        byte[] bin = new byte[75];
        ByteBuffer data = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN);
        data.putFloat(0).putFloat(0).putFloat(0).putFloat(1).putFloat(0).putFloat(0)
                .putFloat(0).putFloat(1).putFloat(0);
        data.put((byte) 0).put((byte) 1).put((byte) 2);
        data.putFloat(0).putFloat(0).putFloat(0).putFloat(1).putFloat(0).putFloat(0)
                .putFloat(0).putFloat(1).putFloat(0);
        String json = "{\"asset\":{\"version\":\"2.0\"},\"scene\":0,\"scenes\":[{\"nodes\":[0]}],"
                + "\"nodes\":[{\"mesh\":0}],\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0}}]}],"
                + "\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\","
                + "\"sparse\":{\"count\":3,\"indices\":{\"bufferView\":1,\"componentType\":5121},"
                + "\"values\":{\"bufferView\":2}}}],\"bufferViews\":[{\"buffer\":0,\"byteLength\":36},"
                + "{\"buffer\":0,\"byteOffset\":36,\"byteLength\":3},{\"buffer\":0,\"byteOffset\":39,\"byteLength\":36}],"
                + "\"buffers\":[{\"byteLength\":75}]}";
        return glb(json, bin);
    }
    private static byte[] glbWithoutBin(String json) {
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        int paddedJson = (jsonBytes.length + 3) & ~3;
        int total = 12 + 8 + paddedJson;
        ByteBuffer out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        out.put("glTF".getBytes(StandardCharsets.US_ASCII)).putInt(2).putInt(total);
        out.putInt(paddedJson).putInt(0x4e4f534a).put(jsonBytes);
        while (out.hasRemaining()) out.put((byte) ' ');
        return out.array();
    }
    private static byte[] glb(String json, byte[] bin) {
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        int paddedJson = (jsonBytes.length + 3) & ~3;
        int paddedBin = (bin.length + 3) & ~3;
        int total = 12 + 8 + paddedJson + 8 + paddedBin;
        ByteBuffer out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        out.put("glTF".getBytes(StandardCharsets.US_ASCII)).putInt(2).putInt(total);
        out.putInt(paddedJson).putInt(0x4e4f534a).put(jsonBytes);
        while (out.position() < 20 + paddedJson) out.put((byte) ' ');
        out.putInt(paddedBin).putInt(0x004e4942).put(bin);
        while (out.hasRemaining()) out.put((byte) 0);
        return out.array();
    }
    private static String hash(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
