package com.exe.morphia3d.service.glb;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.exe.morphia3d.service.MediaUploadProperties;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Bounded, read-only GLB 2.0 structural parser. This class is intentionally not wired into upload validation;
 * callers must supply server-owned bytes or a server-owned stream.
 */
public final class GlbStructureParser {
    private static final long UINT32_MAX = 0xffff_ffffL;
    private static final int HEADER_BYTES = 12;
    private static final int CHUNK_HEADER_BYTES = 8;
    private static final int JSON_CHUNK = 0x4e4f534a;
    private static final int BIN_CHUNK = 0x004e4942;
    private static final long MAX_CONFIGURED_OCCURRENCES = 500_000;
    private static final long MAX_CONFIGURED_SCENE_RELATIONS = 500_000;
    private static final long MAX_CONFIGURED_TRAVERSAL_OPERATIONS = 1_000_000;
    private static final ObjectMapper STRICT_JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final Limits limits;

    public GlbStructureParser(MediaUploadProperties uploadProperties) {
        if (uploadProperties == null) throw new IllegalArgumentException("Upload properties are required");
        this.limits = Limits.defaults(uploadProperties.getModelMaxBytes());
    }

    public GlbStructureParser(Limits limits) {
        if (limits == null) throw new IllegalArgumentException("Parser limits are required");
        this.limits = limits;
    }

    public GlbStructure parse(InputStream input) {
        if (input == null) throw invalid("GLB stream is required");
        BoundedByteArrayOutputStream bytes = readBounded(input);
        return parse(bytes.buffer(), bytes.size());
    }

    public GlbStructure parse(byte[] bytes) {
        if (bytes == null) throw invalid("GLB header is incomplete");
        return parse(bytes, bytes.length);
    }

    private GlbStructure parse(byte[] bytes, int length) {
        if (length < HEADER_BYTES) throw invalid("GLB header is incomplete");
        if (length > limits.maxFileBytes()) throw invalid("GLB file byte limit exceeded");
        Container container = readContainer(bytes, length);
        JsonNode root = parseJson(bytes, container.jsonOffset(), container.jsonLength());
        validateStructureShape(root);
        preflightMeshPrimitiveDefinitions(root);
        long occurrenceCount = preflightPrimitiveOccurrences(root);
        preflightRelationshipInput(root);
        return buildStructure(root, bytes, container.binOffset(), container.binLength(), occurrenceCount);
    }

    private BoundedByteArrayOutputStream readBounded(InputStream input) {
        BoundedByteArrayOutputStream output = new BoundedByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        try {
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read == 0) {
                    int one = input.read();
                    if (one == -1) break;
                    if (++total > limits.maxFileBytes()) throw invalid("GLB file byte limit exceeded");
                    output.write(one);
                    continue;
                }
                total = Math.addExact(total, read);
                if (total > limits.maxFileBytes()) throw invalid("GLB file byte limit exceeded");
                output.write(buffer, 0, read);
            }
        } catch (IOException | ArithmeticException exception) {
            throw new GlbStructureException("GLB stream could not be read", exception);
        }
        return output;
    }

    private Container readContainer(byte[] bytes, int length) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes, 0, length).slice().order(ByteOrder.LITTLE_ENDIAN);
        if (buffer.get() != 'g' || buffer.get() != 'l' || buffer.get() != 'T' || buffer.get() != 'F') {
            throw invalid("GLB magic is invalid");
        }
        long version = uint32(buffer.getInt());
        long declaredLength = uint32(buffer.getInt());
        if (version != 2) throw invalid("Only GLB version 2 is supported");
        if (declaredLength != length || (declaredLength & 3) != 0) {
            throw invalid("GLB declared length is invalid");
        }

        int jsonOffset = -1;
        int jsonLength = 0;
        int binOffset = -1;
        int binLength = 0;
        int chunkIndex = 0;
        while (buffer.hasRemaining()) {
            if (buffer.remaining() < CHUNK_HEADER_BYTES) throw invalid("GLB chunk header is truncated");
            long chunkLength = uint32(buffer.getInt());
            int chunkType = buffer.getInt();
            if ((chunkLength & 3) != 0) throw invalid("GLB chunk length is not four-byte aligned");
            if (chunkLength > buffer.remaining()) throw invalid("GLB chunk exceeds the declared container bounds");
            int chunkBytes = Math.toIntExact(chunkLength);
            int chunkOffset = buffer.position();
            buffer.position(Math.addExact(chunkOffset, chunkBytes));

            if (chunkIndex == 0 && chunkType != JSON_CHUNK) throw invalid("The first GLB chunk must be JSON");
            if (chunkType == JSON_CHUNK) {
                if (jsonOffset >= 0 || chunkIndex != 0) throw invalid("GLB JSON chunk is duplicated or out of order");
                if (chunkLength == 0 || chunkLength > limits.maxJsonBytes()) {
                    throw invalid("GLB JSON byte limit exceeded");
                }
                jsonOffset = chunkOffset;
                jsonLength = chunkBytes;
            } else if (chunkType == BIN_CHUNK) {
                if (binOffset >= 0 || chunkIndex != 1 || jsonOffset < 0) {
                    throw invalid("GLB BIN chunk is duplicated or out of order");
                }
                binOffset = chunkOffset;
                binLength = chunkBytes;
            }
            chunkIndex++;
        }
        if (jsonOffset < 0) throw invalid("GLB JSON chunk is missing");
        return new Container(jsonOffset, jsonLength, binOffset, binLength);
    }

    private JsonNode parseJson(byte[] bytes, int offset, int length) {
        for (int i = offset; i < offset + length; i++) {
            if (bytes[i] == 0) throw invalid("GLB JSON padding is invalid");
        }
        enforceJsonDepth(bytes, offset, length);
        try (JsonParser parser = STRICT_JSON.getFactory().createParser(bytes, offset, length)) {
            JsonNode root = STRICT_JSON.readTree(parser);
            if (root == null || !root.isObject()) throw invalid("GLB JSON root must be an object");
            if (parser.nextToken() != null) throw invalid("GLB JSON contains trailing tokens");
            return root;
        } catch (IOException exception) {
            throw new GlbStructureException("GLB JSON is malformed", exception);
        }
    }

    private void enforceJsonDepth(byte[] bytes, int offset, int length) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        int end = Math.addExact(offset, length);
        for (int i = offset; i < end; i++) {
            int c = Byte.toUnsignedInt(bytes[i]);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') inString = true;
            else if (c == '{' || c == '[') {
                if (++depth > limits.maxJsonDepth()) throw invalid("GLB JSON depth limit exceeded");
            } else if (c == '}' || c == ']') {
                depth--;
            }
        }
    }

    private void validateStructureShape(JsonNode root) {
        JsonNode asset = requiredObject(root, "asset");
        if (!"2.0".equals(requiredText(asset, "version"))) throw invalid("glTF asset version must be 2.0");
        checkArrayCount(root, "scenes", limits.maxScenes());
        checkArrayCount(root, "nodes", limits.maxNodes());
        checkArrayCount(root, "meshes", limits.maxMeshes());
        checkArrayCount(root, "materials", limits.maxMaterials());
        checkArrayCount(root, "textures", limits.maxTextures());
        checkArrayCount(root, "images", limits.maxImages());
        checkArrayCount(root, "accessors", limits.maxArrayItems());
        checkArrayCount(root, "bufferViews", limits.maxArrayItems());
        checkArrayCount(root, "buffers", limits.maxArrayItems());
        checkArrayCount(root, "samplers", limits.maxArrayItems());
        validateBuffers(root);
    }

    private GlbStructure buildStructure(JsonNode root, byte[] sourceBytes, int binOffset, int binLength,
                                        long occurrenceCount) {
        JsonNode accessors = arrayOrEmpty(root, "accessors");
        JsonNode bufferViews = arrayOrEmpty(root, "bufferViews");
        JsonNode buffers = arrayOrEmpty(root, "buffers");
        JsonNode samplers = arrayOrEmpty(root, "samplers");
        JsonNode imageNodes = arrayOrEmpty(root, "images");
        JsonNode textureNodes = arrayOrEmpty(root, "textures");
        JsonNode materialNodes = arrayOrEmpty(root, "materials");
        JsonNode meshNodes = arrayOrEmpty(root, "meshes");
        JsonNode nodeNodes = arrayOrEmpty(root, "nodes");
        JsonNode sceneNodes = arrayOrEmpty(root, "scenes");

        List<GlbStructure.Image> images = parseImages(imageNodes, bufferViews.size());
        List<GlbStructure.Texture> textures = parseTextures(textureNodes, images, samplers.size());
        List<GlbStructure.Material> materials = parseMaterials(materialNodes, textures.size());
        validateAccessors(accessors, bufferViews.size());
        List<GlbStructure.Mesh> meshes = parseMeshes(meshNodes, accessors, bufferViews, buffers, materials, textures,
                sourceBytes, binOffset, binLength);
        List<GlbStructure.Scene> scenes = parseScenes(sceneNodes, nodeNodes.size());
        List<GlbStructure.Node> nodes = parseNodes(nodeNodes, meshes.size());
        validateNodeGraph(nodes);
        nodes = addSceneMembership(nodes, scenes);
        validateDefaultScene(root, scenes.size());
        validateBinBuffer(buffers, sourceBytes, binOffset, binLength);

        List<GlbStructure.PrimitiveOccurrence> occurrences = new ArrayList<>(Math.toIntExact(occurrenceCount));
        for (GlbStructure.Node node : nodes) {
            if (node.meshIndex() == null) continue;
            GlbStructure.Mesh mesh = meshes.get(node.meshIndex());
            String originalName = nonblank(node.name()) ? node.name()
                    : nonblank(mesh.name()) ? mesh.name() : null;
            for (GlbStructure.Primitive primitive : mesh.primitives()) {
                occurrences.add(new GlbStructure.PrimitiveOccurrence(node.index(), mesh.index(),
                        primitive.index(), originalName, primitive.materialIndex()));
            }
        }
        return new GlbStructure(root.path("scene").isIntegralNumber() ? root.path("scene").intValue() : -1,
                scenes, nodes, meshes, materials, textures, images, occurrences);
    }

    private long preflightPrimitiveOccurrences(JsonNode root) {
        JsonNode meshNodes = arrayOrEmpty(root, "meshes");
        JsonNode nodeNodes = arrayOrEmpty(root, "nodes");
        long occurrenceCount = 0;
        for (JsonNode node : nodeNodes) {
            JsonNode meshValue = node.path("mesh");
            if (meshValue.isMissingNode() || meshValue.isNull()) continue;
            int meshIndex = requireIndexValue(meshValue, meshNodes.size(), "node mesh");
            JsonNode primitiveNodes = requiredArray(requireObject(meshNodes.get(meshIndex), "mesh"), "primitives");
            occurrenceCount = checkedAdd(occurrenceCount, primitiveNodes.size(),
                    limits.maxPrimitiveOccurrences(), "primitive occurrence");
        }
        return occurrenceCount;
    }

    private void preflightMeshPrimitiveDefinitions(JsonNode root) {
        long primitiveCount = 0;
        for (JsonNode mesh : arrayOrEmpty(root, "meshes")) {
            JsonNode primitives = requiredArray(requireObject(mesh, "mesh"), "primitives");
            checkListSize(primitives.size(), limits.maxArrayItems(), "mesh primitives");
            primitiveCount = checkedAdd(primitiveCount, primitives.size(), limits.maxPrimitives(), "primitive");
        }
    }

    private void preflightRelationshipInput(JsonNode root) {
        JsonNode nodes = arrayOrEmpty(root, "nodes");
        long totalReferences = checkedAdd(0, nodes.size(), limits.maxTraversalOperations(), "traversal input");
        for (JsonNode node : nodes) {
            JsonNode children = optionalArray(requireObject(node, "node"), "children");
            if (children != null) {
                checkListSize(children.size(), limits.maxArrayItems(), "node children");
                totalReferences = checkedAdd(totalReferences, children.size(), limits.maxTraversalOperations(),
                        "traversal input");
            }
        }
        JsonNode scenes = arrayOrEmpty(root, "scenes");
        for (JsonNode scene : scenes) {
            JsonNode roots = optionalArray(requireObject(scene, "scene"), "nodes");
            if (roots != null) {
                checkListSize(roots.size(), limits.maxArrayItems(), "scene nodes");
                totalReferences = checkedAdd(totalReferences, roots.size(), limits.maxTraversalOperations(),
                        "traversal input");
            }
        }
    }

    private List<GlbStructure.Scene> parseScenes(JsonNode sceneNodes, int nodeCount) {
        List<GlbStructure.Scene> result = new ArrayList<>(sceneNodes.size());
        for (int i = 0; i < sceneNodes.size(); i++) {
            JsonNode scene = requireObject(sceneNodes.get(i), "scene");
            List<Integer> roots = indexArray(scene, "nodes", nodeCount, true);
            ensureUnique(roots, "scene root node");
            result.add(new GlbStructure.Scene(i, optionalText(scene, "name"), roots));
        }
        return result;
    }

    private List<GlbStructure.Node> parseNodes(JsonNode nodeNodes, int meshCount) {
        List<GlbStructure.Node> result = new ArrayList<>(nodeNodes.size());
        for (int i = 0; i < nodeNodes.size(); i++) {
            JsonNode node = requireObject(nodeNodes.get(i), "node");
            Integer mesh = optionalIndex(node, "mesh", meshCount);
            List<Integer> children = indexArray(node, "children", nodeNodes.size(), true);
            ensureUnique(children, "node child");
            result.add(new GlbStructure.Node(i, optionalText(node, "name"), mesh, children, List.of()));
        }
        return result;
    }

    private List<GlbStructure.Node> addSceneMembership(List<GlbStructure.Node> nodes,
                                                       List<GlbStructure.Scene> scenes) {
        List<Set<Integer>> memberships = new ArrayList<>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) memberships.add(new java.util.TreeSet<>());
        long relationCount = 0;
        long traversalCount = 0;
        for (GlbStructure.Scene scene : scenes) {
            Deque<Integer> pending = new ArrayDeque<>();
            for (int root : scene.rootNodeIndices()) {
                traversalCount = checkedAdd(traversalCount, 1, limits.maxTraversalOperations(), "traversal work");
                pending.addLast(root);
            }
            while (!pending.isEmpty()) {
                traversalCount = checkedAdd(traversalCount, 1, limits.maxTraversalOperations(), "traversal work");
                int nodeIndex = pending.removeLast();
                if (!memberships.get(nodeIndex).add(scene.index())) continue;
                relationCount = checkedAdd(relationCount, 1, limits.maxSceneNodeMemberships(),
                        "scene-node relation");
                for (int child : nodes.get(nodeIndex).childIndices()) {
                    traversalCount = checkedAdd(traversalCount, 1, limits.maxTraversalOperations(), "traversal work");
                    pending.addLast(child);
                }
            }
        }
        List<GlbStructure.Node> result = new ArrayList<>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
            GlbStructure.Node node = nodes.get(i);
            result.add(new GlbStructure.Node(node.index(), node.name(), node.meshIndex(), node.childIndices(),
                    List.copyOf(memberships.get(i))));
        }
        return result;
    }

    private List<GlbStructure.Mesh> parseMeshes(JsonNode meshNodes, JsonNode accessors, JsonNode bufferViews,
                                                JsonNode buffers,
                                                List<GlbStructure.Material> materials,
                                                List<GlbStructure.Texture> textures, byte[] sourceBytes,
                                                int binOffset, int binLength) {
        List<GlbStructure.Mesh> result = new ArrayList<>(meshNodes.size());
        long remainingSurfaceOperations = limits.maxTraversalOperations();
        for (int meshIndex = 0; meshIndex < meshNodes.size(); meshIndex++) {
            JsonNode mesh = requireObject(meshNodes.get(meshIndex), "mesh");
            JsonNode primitiveNodes = requiredArray(mesh, "primitives");
            checkListSize(primitiveNodes.size(), limits.maxArrayItems(), "mesh primitives");
            if (primitiveNodes.isEmpty()) throw invalid("Mesh must have at least one primitive");
            List<GlbStructure.Primitive> primitives = new ArrayList<>(primitiveNodes.size());
            for (int primitiveIndex = 0; primitiveIndex < primitiveNodes.size(); primitiveIndex++) {
                JsonNode primitive = requireObject(primitiveNodes.get(primitiveIndex), "mesh primitive");
                JsonNode attributes = requiredObject(primitive, "attributes");
                checkListSize(attributes.size(), limits.maxArrayItems(), "primitive attributes");
                if (attributes.isEmpty()) throw invalid("Primitive attributes are required");
                List<String> attributeNames = new ArrayList<>();
                attributes.fields().forEachRemaining(entry -> {
                    requireIndexValue(entry.getValue(), accessors.size(), "primitive attribute accessor");
                    attributeNames.add(entry.getKey());
                });
                Integer positionAccessor = optionalIndex(attributes, "POSITION", accessors.size());
                long positionCount = positionAccessor == null ? 0
                        : requiredNonNegativeInteger(accessors.get(positionAccessor), "count");
                Integer indicesAccessor = optionalIndex(primitive, "indices", accessors.size());
                Long indexCount = indicesAccessor == null ? null
                        : requiredNonNegativeInteger(accessors.get(indicesAccessor), "count");
                Integer material = optionalIndex(primitive, "material", materials.size());
                Integer mode = optionalInteger(primitive, "mode", 0, 6);
                int resolvedMode = mode == null ? 4 : mode;
                validateTargets(primitive, accessors.size());
                List<Integer> textureIndices = material == null ? List.of()
                        : materials.get(material).textureIndices();
                if (textureIndices.stream().anyMatch(textureIndex -> textureIndex >= textures.size())) {
                    throw invalid("Material texture reference is out of range");
                }
                long elementCount = indexCount == null ? positionCount : indexCount;
                boolean withinSurfaceBudget = positionCount <= remainingSurfaceOperations
                        && elementCount <= remainingSurfaceOperations - positionCount;
                if (withinSurfaceBudget) remainingSurfaceOperations -= positionCount + elementCount;
                else remainingSurfaceOperations = 0;
                primitives.add(new GlbStructure.Primitive(primitiveIndex, material, attributeNames,
                        textureIndices, resolvedMode, positionCount, indexCount,
                        withinSurfaceBudget && verifySurface(primitive, attributes, accessors, bufferViews, buffers,
                                sourceBytes, binOffset, binLength, resolvedMode)));
            }
            result.add(new GlbStructure.Mesh(meshIndex, optionalText(mesh, "name"), primitives));
        }
        return result;
    }

    private boolean verifySurface(JsonNode primitive, JsonNode attributes, JsonNode accessors,
                                  JsonNode bufferViews, JsonNode buffers, byte[] sourceBytes,
                                  int binOffset, int binLength, int mode) {
        if (mode < 4 || mode > 6) return false;
        Integer positionIndex = optionalIndex(attributes, "POSITION", accessors.size());
        if (positionIndex == null) return false;
        JsonNode positionAccessor = accessors.get(positionIndex);
        if (positionAccessor.has("sparse") || !"VEC3".equals(positionAccessor.path("type").asText())
                || positionAccessor.path("componentType").asInt(-1) != 5126
                || positionAccessor.path("normalized").asBoolean(false)) return false;
        AccessorLayout positions = accessorLayout(positionAccessor, 12, 4, false, bufferViews, buffers,
                sourceBytes, binOffset, binLength);
        if (positions == null || positions.count() < 3) return false;

        Integer indicesIndex = optionalIndex(primitive, "indices", accessors.size());
        AccessorLayout indices = null;
        if (indicesIndex != null) {
            JsonNode indexAccessor = accessors.get(indicesIndex);
            int componentType = indexAccessor.path("componentType").asInt(-1);
            int componentBytes = switch (componentType) {
                case 5121 -> 1;
                case 5123 -> 2;
                case 5125 -> 4;
                default -> 0;
            };
            if (indexAccessor.has("sparse") || componentBytes == 0
                    || !"SCALAR".equals(indexAccessor.path("type").asText())
                    || indexAccessor.path("normalized").asBoolean(false)) return false;
            indices = accessorLayout(indexAccessor, componentBytes, componentBytes, true, bufferViews,
                    buffers, sourceBytes, binOffset, binLength);
            if (indices == null) return false;
        }

        long elementCount = indices == null ? positions.count() : indices.count();
        if (elementCount < 3 || (mode == 4 && elementCount % 3 != 0)) return false;
        if (elementCount > limits.maxTraversalOperations()
                || positions.count() > limits.maxTraversalOperations() - elementCount) return false;

        ByteBuffer data = ByteBuffer.wrap(sourceBytes).order(ByteOrder.LITTLE_ENDIAN);
        for (long vertex = 0; vertex < positions.count(); vertex++) {
            if (!finitePosition(data, positions, vertex)) return false;
        }

        long first = -1;
        long previous = -1;
        for (long element = 0; element < elementCount; element++) {
            long vertexIndex = indices == null ? element : readIndex(data, indices, element);
            if (vertexIndex < 0 || vertexIndex >= positions.count()) return false;
            if (mode == 4) {
                switch ((int) (element % 3)) {
                    case 0 -> first = vertexIndex;
                    case 1 -> previous = vertexIndex;
                    case 2 -> {
                        if (!nonDegenerateTriangle(data, positions, first, previous, vertexIndex)) return false;
                    }
                    default -> throw new IllegalStateException("Unreachable triangle-list index");
                }
            } else if (mode == 5) {
                if (element == 0) first = vertexIndex;
                else if (element == 1) previous = vertexIndex;
                else {
                    long a = (element & 1) == 0 ? first : previous;
                    long b = (element & 1) == 0 ? previous : first;
                    if (!nonDegenerateTriangle(data, positions, a, b, vertexIndex)) return false;
                    first = previous;
                    previous = vertexIndex;
                }
            } else {
                if (element == 0) first = vertexIndex;
                else if (element == 1) previous = vertexIndex;
                else {
                    if (!nonDegenerateTriangle(data, positions, first, previous, vertexIndex)) return false;
                    previous = vertexIndex;
                }
            }
        }
        return true;
    }

    private AccessorLayout accessorLayout(JsonNode accessor, int elementBytes, int componentBytes,
                                          boolean indexAccessor, JsonNode bufferViews, JsonNode buffers,
                                          byte[] sourceBytes, int binOffset, int binLength) {
        Integer viewIndex = optionalIndex(accessor, "bufferView", bufferViews.size());
        if (viewIndex == null || binOffset < 0) return null;
        JsonNode view = bufferViews.get(viewIndex);
        int bufferIndex = requiredIndex(view, "buffer", buffers.size());
        JsonNode buffer = buffers.get(bufferIndex);
        if (buffer.has("uri") || bufferIndex != 0) return null;
        long bufferByteLength = requiredNonNegativeInteger(buffer, "byteLength");
        long viewOffset = optionalNonNegativeInteger(view, "byteOffset", 0);
        long viewLength = requiredNonNegativeInteger(view, "byteLength");
        long accessorOffset = optionalNonNegativeInteger(accessor, "byteOffset", 0);
        long count = requiredNonNegativeInteger(accessor, "count");
        Long configuredStride = optionalBoxedNonNegativeInteger(view, "byteStride");
        if (indexAccessor && configuredStride != null) return null;
        long stride = configuredStride == null ? elementBytes : configuredStride;
        if (stride < elementBytes || stride % componentBytes != 0
                || (viewOffset + accessorOffset) % componentBytes != 0
                || accessorOffset > viewLength || viewOffset > bufferByteLength
                || viewLength > bufferByteLength - viewOffset) return null;
        long extent;
        long absoluteOffset;
        try {
            extent = Math.addExact(Math.multiplyExact(count - 1, stride), elementBytes);
            absoluteOffset = Math.addExact(Math.addExact((long) binOffset, viewOffset), accessorOffset);
        } catch (ArithmeticException overflow) {
            return null;
        }
        if (extent > viewLength - accessorOffset || absoluteOffset < 0
                || absoluteOffset > sourceBytes.length || extent > sourceBytes.length - absoluteOffset
                || absoluteOffset < binOffset || absoluteOffset + extent > (long) binOffset + binLength) return null;
        return new AccessorLayout(count, (int) absoluteOffset, stride, elementBytes, componentBytes,
                accessor.path("componentType").asInt(-1));
    }

    private boolean finitePosition(ByteBuffer data, AccessorLayout positions, long index) {
        long offset = positions.offset() + index * positions.stride();
        return Float.isFinite(data.getFloat(Math.toIntExact(offset)))
                && Float.isFinite(data.getFloat(Math.toIntExact(offset + 4)))
                && Float.isFinite(data.getFloat(Math.toIntExact(offset + 8)));
    }

    private long readIndex(ByteBuffer data, AccessorLayout indices, long index) {
        int offset = Math.toIntExact(indices.offset() + index * indices.stride());
        return switch (indices.componentType()) {
            case 5121 -> Byte.toUnsignedLong(data.get(offset));
            case 5123 -> Short.toUnsignedLong(data.getShort(offset));
            case 5125 -> Integer.toUnsignedLong(data.getInt(offset));
            default -> -1;
        };
    }

    private boolean nonDegenerateTriangle(ByteBuffer data, AccessorLayout positions,
                                          long a, long b, long c) {
        if (a == b || b == c || a == c) return false;
        long ao = positions.offset() + a * positions.stride();
        long bo = positions.offset() + b * positions.stride();
        long co = positions.offset() + c * positions.stride();
        double abx = data.getFloat(Math.toIntExact(bo)) - data.getFloat(Math.toIntExact(ao));
        double aby = data.getFloat(Math.toIntExact(bo + 4)) - data.getFloat(Math.toIntExact(ao + 4));
        double abz = data.getFloat(Math.toIntExact(bo + 8)) - data.getFloat(Math.toIntExact(ao + 8));
        double acx = data.getFloat(Math.toIntExact(co)) - data.getFloat(Math.toIntExact(ao));
        double acy = data.getFloat(Math.toIntExact(co + 4)) - data.getFloat(Math.toIntExact(ao + 4));
        double acz = data.getFloat(Math.toIntExact(co + 8)) - data.getFloat(Math.toIntExact(ao + 8));
        double crossX = aby * acz - abz * acy;
        double crossY = abz * acx - abx * acz;
        double crossZ = abx * acy - aby * acx;
        return Double.isFinite(crossX) && Double.isFinite(crossY) && Double.isFinite(crossZ)
                && (crossX != 0 || crossY != 0 || crossZ != 0);
    }

    private record AccessorLayout(long count, int offset, long stride, int elementBytes,
                                  int componentBytes, int componentType) {}

    private List<GlbStructure.Material> parseMaterials(JsonNode materialNodes, int textureCount) {
        List<GlbStructure.Material> result = new ArrayList<>(materialNodes.size());
        for (int i = 0; i < materialNodes.size(); i++) {
            JsonNode material = requireObject(materialNodes.get(i), "material");
            List<Integer> textureIndices = new ArrayList<>();
            JsonNode pbr = optionalObject(material, "pbrMetallicRoughness");
            if (pbr != null) {
                addTextureInfo(pbr, "baseColorTexture", textureCount, textureIndices);
                addTextureInfo(pbr, "metallicRoughnessTexture", textureCount, textureIndices);
            }
            addTextureInfo(material, "normalTexture", textureCount, textureIndices);
            addTextureInfo(material, "occlusionTexture", textureCount, textureIndices);
            addTextureInfo(material, "emissiveTexture", textureCount, textureIndices);
            result.add(new GlbStructure.Material(i, optionalText(material, "name"),
                    distinct(textureIndices)));
        }
        return result;
    }

    private List<GlbStructure.Texture> parseTextures(JsonNode textureNodes,
                                                     List<GlbStructure.Image> images,
                                                     int samplerCount) {
        // These extension links are parsed structurally only; image bytes are neither fetched nor decoded here.
        int imageCount = images.size();
        List<GlbStructure.Texture> result = new ArrayList<>(textureNodes.size());
        boolean[] basisuImages = new boolean[imageCount];
        boolean[] webpImages = new boolean[imageCount];
        for (int i = 0; i < textureNodes.size(); i++) {
            JsonNode texture = requireObject(textureNodes.get(i), "texture");
            Integer sampler = optionalIndex(texture, "sampler", samplerCount);
            List<Integer> sources = new ArrayList<>();
            Integer source = optionalIndex(texture, "source", imageCount);
            if (source != null) {
                String mime = images.get(source).mimeType();
                if ("image/ktx2".equals(mime) || "image/webp".equals(mime)) {
                    throw invalid("KTX2/WebP images must be referenced through their texture extension");
                }
                sources.add(source);
            }
            JsonNode extensions = optionalObject(texture, "extensions");
            if (extensions != null) {
                for (String extension : List.of("KHR_texture_basisu", "EXT_texture_webp")) {
                    JsonNode extensionNode = optionalObject(extensions, extension);
                    if (extensionNode != null) {
                        Integer extensionSource = requiredIndex(extensionNode, "source", imageCount);
                        validateExtensionImageMime(extensionSource, extension.equals("KHR_texture_basisu")
                                ? "image/ktx2" : "image/webp", extension.equals("KHR_texture_basisu")
                                ? basisuImages : webpImages, images);
                        sources.add(extensionSource);
                    }
                }
            }
            if (sources.isEmpty()) throw invalid("Texture must reference an image source");
            result.add(new GlbStructure.Texture(i, distinct(sources), sampler));
        }
        for (int i = 0; i < imageCount; i++) {
            String mime = images.get(i).mimeType();
            if ("image/ktx2".equals(mime) && !basisuImages[i]) {
                throw invalid("KTX2 image must be referenced by KHR_texture_basisu");
            }
            if ("image/webp".equals(mime) && !webpImages[i]) {
                throw invalid("WebP image must be referenced by EXT_texture_webp");
            }
        }
        return result;
    }

    private void validateExtensionImageMime(int imageIndex, String expectedMime,
                                            boolean[] correspondingExtensionSources,
                                            List<GlbStructure.Image> images) {
        GlbStructure.Image image = images.get(imageIndex);
        if (image.bufferViewIndex() != null && !expectedMime.equals(image.mimeType())) {
            throw invalid("Embedded extension image requires MIME type " + expectedMime);
        }
        if (image.uriSource() && image.mimeType() != null && !expectedMime.equals(image.mimeType())) {
            throw invalid("Extension image MIME type does not match its texture extension");
        }
        correspondingExtensionSources[imageIndex] = true;
    }

    private List<GlbStructure.Image> parseImages(JsonNode imageNodes, int bufferViewCount) {
        List<GlbStructure.Image> result = new ArrayList<>(imageNodes.size());
        for (int i = 0; i < imageNodes.size(); i++) {
            JsonNode image = requireObject(imageNodes.get(i), "image");
            String uri = optionalText(image, "uri");
            if (uri != null && uri.isBlank()) throw invalid("Image URI must not be blank");
            Integer bufferView = optionalIndex(image, "bufferView", bufferViewCount);
            if ((uri == null) == (bufferView == null)) throw invalid("Image must have exactly one source");
            String mime = optionalText(image, "mimeType");
            if (bufferView != null && mime == null) throw invalid("Buffer-view image requires mimeType");
            if (mime != null && !Set.of("image/png", "image/jpeg", "image/ktx2", "image/webp").contains(mime)) {
                throw invalid("Image mimeType is unsupported");
            }
            result.add(new GlbStructure.Image(i, optionalText(image, "name"), mime, uri != null, bufferView));
        }
        return result;
    }

    private void validateBuffers(JsonNode root) {
        JsonNode buffers = arrayOrEmpty(root, "buffers");
        for (JsonNode buffer : buffers) {
            requireObject(buffer, "buffer");
            requiredNonNegativeInteger(buffer, "byteLength");
            if (buffer.has("uri")) {
                String uri = requiredText(buffer, "uri");
                if (uri.isBlank()) throw invalid("Buffer URI must not be blank");
            }
        }
        JsonNode views = arrayOrEmpty(root, "bufferViews");
        for (JsonNode view : views) {
            requireObject(view, "bufferView");
            int bufferIndex = requiredIndex(view, "buffer", buffers.size());
            long byteLength = requiredNonNegativeInteger(view, "byteLength");
            long byteOffset = optionalNonNegativeInteger(view, "byteOffset", 0);
            long bufferLength = requiredNonNegativeInteger(buffers.get(bufferIndex), "byteLength");
            if (byteLength == 0 || byteOffset > UINT32_MAX - byteLength
                    || byteOffset > bufferLength || byteLength > bufferLength - byteOffset) {
                throw invalid("Buffer view range is invalid");
            }
            Long stride = optionalBoxedNonNegativeInteger(view, "byteStride");
            if (stride != null && (stride < 4 || stride > 252 || (stride & 3) != 0)) {
                throw invalid("Buffer view byteStride is invalid");
            }
            Integer target = optionalInteger(view, "target", 34962, 34963);
        }
    }

    private void validateAccessors(JsonNode accessors, int bufferViewCount) {
        for (JsonNode accessor : accessors) {
            requireObject(accessor, "accessor");
            optionalIndex(accessor, "bufferView", bufferViewCount);
            optionalNonNegativeInteger(accessor, "byteOffset", 0);
            long count = requiredNonNegativeInteger(accessor, "count");
            if (count == 0) throw invalid("Accessor count must be positive");
            int componentType = optionalInteger(accessor, "componentType", 5120, 5126) == null
                    ? -1 : accessor.path("componentType").intValue();
            if (!Set.of(5120, 5121, 5122, 5123, 5125, 5126).contains(componentType)) {
                throw invalid("Accessor componentType is unsupported");
            }
            String type = requiredText(accessor, "type");
            if (!Set.of("SCALAR", "VEC2", "VEC3", "VEC4", "MAT2", "MAT3", "MAT4").contains(type)) {
                throw invalid("Accessor type is unsupported");
            }
            JsonNode sparse = optionalObject(accessor, "sparse");
            if (sparse != null) {
                long sparseCount = requiredNonNegativeInteger(sparse, "count");
                if (sparseCount == 0 || sparseCount > count) throw invalid("Accessor sparse count is invalid");
                JsonNode indices = requiredObject(sparse, "indices");
                requiredIndex(indices, "bufferView", bufferViewCount);
                int sparseComponentType = optionalInteger(indices, "componentType", 5121, 5125) == null
                        ? -1 : indices.path("componentType").intValue();
                if (!Set.of(5121, 5123, 5125).contains(sparseComponentType)) {
                    throw invalid("Accessor sparse componentType is invalid");
                }
                optionalNonNegativeInteger(indices, "byteOffset", 0);
                JsonNode values = requiredObject(sparse, "values");
                requiredIndex(values, "bufferView", bufferViewCount);
                optionalNonNegativeInteger(values, "byteOffset", 0);
            }
        }
    }

    private void validateBinBuffer(JsonNode buffers, byte[] sourceBytes, int binOffset, int binLength) {
        int glbBufferIndex = -1;
        for (int i = 0; i < buffers.size(); i++) {
            if (!buffers.get(i).has("uri")) {
                if (glbBufferIndex >= 0) throw invalid("GLB may contain only one URI-less buffer");
                glbBufferIndex = i;
            }
        }
        if (binOffset < 0) {
            if (glbBufferIndex >= 0) throw invalid("GLB BIN chunk is required by its buffer");
            return;
        }
        if (glbBufferIndex != 0 || buffers.isEmpty()) throw invalid("BIN chunk must match the first URI-less buffer");
        long bufferLength = requiredNonNegativeInteger(buffers.get(0), "byteLength");
        if (bufferLength > binLength || binLength - bufferLength > 3) {
            throw invalid("GLB BIN chunk length does not match its buffer");
        }
        int paddingStart = Math.addExact(binOffset, Math.toIntExact(bufferLength));
        int binEnd = Math.addExact(binOffset, binLength);
        for (int i = paddingStart; i < binEnd; i++) {
            if (sourceBytes[i] != 0) throw invalid("GLB BIN padding must be zero-filled");
        }
    }

    private void validateTargets(JsonNode primitive, int accessorCount) {
        JsonNode targets = optionalArray(primitive, "targets");
        if (targets == null) return;
        checkListSize(targets.size(), limits.maxArrayItems(), "primitive targets");
        for (JsonNode target : targets) {
            JsonNode attributes = requireObject(target, "morph target");
            if (attributes.size() > limits.maxArrayItems()) throw invalid("Morph target attribute limit exceeded");
            attributes.fields().forEachRemaining(entry -> requireIndexValue(entry.getValue(), accessorCount,
                    "morph target accessor"));
        }
    }

    private void addTextureInfo(JsonNode parent, String field, int textureCount, List<Integer> target) {
        JsonNode textureInfo = optionalObject(parent, field);
        if (textureInfo == null) return;
        target.add(requiredIndex(textureInfo, "index", textureCount));
        optionalNonNegativeInteger(textureInfo, "texCoord", 0);
    }

    private void validateNodeGraph(List<GlbStructure.Node> nodes) {
        int[] parents = new int[nodes.size()];
        for (GlbStructure.Node node : nodes) {
            for (int child : node.childIndices()) {
                if (++parents[child] > 1) throw invalid("A node cannot have multiple parents");
            }
        }
        byte[] colors = new byte[nodes.size()];
        int[] nextChild = new int[nodes.size()];
        for (int root = 0; root < nodes.size(); root++) {
            if (colors[root] != 0) continue;
            List<Integer> stack = new ArrayList<>();
            stack.add(root);
            colors[root] = 1;
            while (!stack.isEmpty()) {
                if (stack.size() > limits.maxNodeHierarchyDepth()) {
                    throw invalid("GLB node hierarchy depth limit exceeded");
                }
                int current = stack.get(stack.size() - 1);
                List<Integer> children = nodes.get(current).childIndices();
                if (nextChild[current] >= children.size()) {
                    colors[current] = 2;
                    stack.remove(stack.size() - 1);
                    continue;
                }
                int child = children.get(nextChild[current]++);
                if (colors[child] == 1) throw invalid("Node hierarchy contains a cycle");
                if (colors[child] == 0) {
                    colors[child] = 1;
                    stack.add(child);
                }
            }
        }
    }

    private void validateDefaultScene(JsonNode root, int sceneCount) {
        if (!root.has("scene")) return;
        requireIndexValue(root.get("scene"), sceneCount, "default scene");
    }

    private void checkArrayCount(JsonNode parent, String field, int limit) {
        JsonNode array = optionalArray(parent, field);
        if (array != null) checkListSize(array.size(), Math.min(limit, limits.maxArrayItems()), field);
    }

    private void checkListSize(int size, int limit, String description) {
        if (size > limit) throw invalid("GLB " + description + " limit exceeded");
    }

    private JsonNode arrayOrEmpty(JsonNode parent, String field) {
        JsonNode result = optionalArray(parent, field);
        return result == null ? STRICT_JSON.createArrayNode() : result;
    }

    private JsonNode optionalArray(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isArray()) throw invalid(field + " must be an array");
        return value;
    }

    private JsonNode requiredArray(JsonNode parent, String field) {
        JsonNode value = optionalArray(parent, field);
        if (value == null) throw invalid(field + " is required");
        return value;
    }

    private JsonNode optionalObject(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) return null;
        return requireObject(value, field);
    }

    private JsonNode requiredObject(JsonNode parent, String field) {
        JsonNode value = optionalObject(parent, field);
        if (value == null) throw invalid(field + " is required");
        return value;
    }

    private JsonNode requireObject(JsonNode value, String description) {
        if (value == null || !value.isObject()) throw invalid(description + " must be an object");
        return value;
    }

    private String requiredText(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw invalid(field + " must be a non-empty string");
        }
        return value.textValue();
    }

    private String optionalText(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw invalid(field + " must be a string");
        return value.textValue();
    }

    private Integer optionalIndex(JsonNode object, String field, int upperBound) {
        JsonNode value = object.get(field);
        if (value == null || value.isNull()) return null;
        return requireIndexValue(value, upperBound, field);
    }

    private int requiredIndex(JsonNode object, String field, int upperBound) {
        JsonNode value = object.get(field);
        if (value == null) throw invalid(field + " is required");
        return requireIndexValue(value, upperBound, field);
    }

    private int requireIndexValue(JsonNode value, int upperBound, String description) {
        if (!value.isIntegralNumber() || !value.canConvertToInt()) throw invalid(description + " must be an integer index");
        int index = value.intValue();
        if (index < 0 || index >= upperBound) throw invalid(description + " index is out of range");
        return index;
    }

    private List<Integer> indexArray(JsonNode object, String field, int upperBound, boolean optional) {
        JsonNode array = optionalArray(object, field);
        if (array == null) {
            if (optional) return List.of();
            throw invalid(field + " is required");
        }
        checkListSize(array.size(), limits.maxArrayItems(), field);
        List<Integer> result = new ArrayList<>(array.size());
        for (JsonNode value : array) result.add(requireIndexValue(value, upperBound, field));
        return List.copyOf(result);
    }

    private long requiredNonNegativeInteger(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw invalid(field + " must be a non-negative integer");
        }
        return value.longValue();
    }

    private long optionalNonNegativeInteger(JsonNode object, String field, long defaultValue) {
        JsonNode value = object.get(field);
        if (value == null || value.isNull()) return defaultValue;
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw invalid(field + " must be a non-negative integer");
        }
        return value.longValue();
    }

    private Long optionalBoxedNonNegativeInteger(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw invalid(field + " must be a non-negative integer");
        }
        return value.longValue();
    }

    private Integer optionalInteger(JsonNode object, String field, int min, int max) {
        JsonNode value = object.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToInt()
                || value.intValue() < min || value.intValue() > max) {
            throw invalid(field + " is out of range");
        }
        return value.intValue();
    }

    private List<Integer> distinct(List<Integer> values) {
        return List.copyOf(new java.util.LinkedHashSet<>(values));
    }

    private void ensureUnique(List<Integer> values, String description) {
        if (new HashSet<>(values).size() != values.size()) throw invalid("Duplicate " + description + " index");
    }

    private boolean nonblank(String value) {
        return value != null && !value.isBlank();
    }

    private long uint32(int value) {
        return Integer.toUnsignedLong(value);
    }

    private GlbStructureException invalid(String message) {
        return new GlbStructureException(message);
    }

    private long checkedAdd(long current, long increment, long limit, String description) {
        final long total;
        try {
            total = Math.addExact(current, increment);
        } catch (ArithmeticException exception) {
            throw new GlbStructureException("GLB " + description + " count overflow", exception);
        }
        if (total > limit) throw invalid("GLB " + description + " limit exceeded");
        return total;
    }

    private record Container(int jsonOffset, int jsonLength, int binOffset, int binLength) {}

    private static final class BoundedByteArrayOutputStream extends ByteArrayOutputStream {
        private BoundedByteArrayOutputStream() {
            super(8192);
        }

        private byte[] buffer() {
            return buf;
        }
    }

    public record Limits(long maxFileBytes, long maxJsonBytes, int maxJsonDepth, int maxNodeHierarchyDepth,
                         int maxScenes, int maxNodes, int maxMeshes, int maxPrimitives,
                         int maxMaterials, int maxTextures, int maxImages, int maxArrayItems,
                         long maxPrimitiveOccurrences, long maxSceneNodeMemberships,
                         long maxTraversalOperations) {
        public Limits {
            if (maxFileBytes < HEADER_BYTES || maxFileBytes > Integer.MAX_VALUE
                    || maxJsonBytes < 1 || maxJsonBytes > maxFileBytes
                    || maxJsonDepth < 1 || maxNodeHierarchyDepth < 1
                    || maxScenes < 0 || maxNodes < 0 || maxMeshes < 0
                    || maxPrimitives < 0 || maxMaterials < 0 || maxTextures < 0 || maxImages < 0
                    || maxArrayItems < 1 || maxPrimitiveOccurrences < 0
                    || maxPrimitiveOccurrences > MAX_CONFIGURED_OCCURRENCES || maxSceneNodeMemberships < 0
                    || maxSceneNodeMemberships > MAX_CONFIGURED_SCENE_RELATIONS || maxTraversalOperations < 0
                    || maxTraversalOperations > MAX_CONFIGURED_TRAVERSAL_OPERATIONS) {
                throw new IllegalArgumentException("GLB parser limits must be finite and positive");
            }
        }

        public static Limits defaults(long maxFileBytes) {
            return new Limits(maxFileBytes, Math.min(4L * 1024 * 1024, maxFileBytes), 64, 256,
                    1_000, 100_000, 50_000, 100_000, 10_000, 10_000, 10_000, 100_000,
                    100_000, 250_000, 500_000);
        }

        public Limits withMaxFileBytes(long value) {
            return new Limits(value, Math.min(maxJsonBytes, value), maxJsonDepth, maxNodeHierarchyDepth, maxScenes, maxNodes,
                    maxMeshes, maxPrimitives, maxMaterials, maxTextures, maxImages, maxArrayItems,
                    maxPrimitiveOccurrences, maxSceneNodeMemberships, maxTraversalOperations);
        }

        public Limits withMaxJsonBytes(long value) {
            return new Limits(maxFileBytes, value, maxJsonDepth, maxNodeHierarchyDepth, maxScenes, maxNodes, maxMeshes,
                    maxPrimitives, maxMaterials, maxTextures, maxImages, maxArrayItems,
                    maxPrimitiveOccurrences, maxSceneNodeMemberships, maxTraversalOperations);
        }

        public Limits withMaxJsonDepth(int value) {
            return new Limits(maxFileBytes, maxJsonBytes, value, maxNodeHierarchyDepth, maxScenes, maxNodes, maxMeshes,
                    maxPrimitives, maxMaterials, maxTextures, maxImages, maxArrayItems,
                    maxPrimitiveOccurrences, maxSceneNodeMemberships, maxTraversalOperations);
        }

        public Limits withMaxNodeHierarchyDepth(int value) {
            return new Limits(maxFileBytes, maxJsonBytes, maxJsonDepth, value, maxScenes, maxNodes,
                    maxMeshes, maxPrimitives, maxMaterials, maxTextures, maxImages, maxArrayItems,
                    maxPrimitiveOccurrences, maxSceneNodeMemberships, maxTraversalOperations);
        }

        public Limits withMaxNodes(int value) {
            return new Limits(maxFileBytes, maxJsonBytes, maxJsonDepth, maxNodeHierarchyDepth, maxScenes, value, maxMeshes,
                    maxPrimitives, maxMaterials, maxTextures, maxImages, maxArrayItems,
                    maxPrimitiveOccurrences, maxSceneNodeMemberships, maxTraversalOperations);
        }

        public Limits withMaxPrimitives(int value) {
            return new Limits(maxFileBytes, maxJsonBytes, maxJsonDepth, maxNodeHierarchyDepth, maxScenes, maxNodes, maxMeshes,
                    value, maxMaterials, maxTextures, maxImages, maxArrayItems,
                    maxPrimitiveOccurrences, maxSceneNodeMemberships, maxTraversalOperations);
        }

        public Limits withMaxMaterials(int value) {
            return new Limits(maxFileBytes, maxJsonBytes, maxJsonDepth, maxNodeHierarchyDepth, maxScenes, maxNodes, maxMeshes,
                    maxPrimitives, value, maxTextures, maxImages, maxArrayItems,
                    maxPrimitiveOccurrences, maxSceneNodeMemberships, maxTraversalOperations);
        }

        public Limits withMaxTextures(int value) {
            return new Limits(maxFileBytes, maxJsonBytes, maxJsonDepth, maxNodeHierarchyDepth, maxScenes, maxNodes, maxMeshes,
                    maxPrimitives, maxMaterials, value, maxImages, maxArrayItems,
                    maxPrimitiveOccurrences, maxSceneNodeMemberships, maxTraversalOperations);
        }

        public Limits withMaxImages(int value) {
            return new Limits(maxFileBytes, maxJsonBytes, maxJsonDepth, maxNodeHierarchyDepth, maxScenes, maxNodes, maxMeshes,
                    maxPrimitives, maxMaterials, maxTextures, value, maxArrayItems,
                    maxPrimitiveOccurrences, maxSceneNodeMemberships, maxTraversalOperations);
        }

        public Limits withMaxScenes(int value) {
            return new Limits(maxFileBytes, maxJsonBytes, maxJsonDepth, maxNodeHierarchyDepth, value, maxNodes, maxMeshes,
                    maxPrimitives, maxMaterials, maxTextures, maxImages, maxArrayItems,
                    maxPrimitiveOccurrences, maxSceneNodeMemberships, maxTraversalOperations);
        }

        public Limits withMaxMeshes(int value) {
            return new Limits(maxFileBytes, maxJsonBytes, maxJsonDepth, maxNodeHierarchyDepth, maxScenes, maxNodes, value,
                    maxPrimitives, maxMaterials, maxTextures, maxImages, maxArrayItems,
                    maxPrimitiveOccurrences, maxSceneNodeMemberships, maxTraversalOperations);
        }

        public Limits withMaxArrayItems(int value) {
            return new Limits(maxFileBytes, maxJsonBytes, maxJsonDepth, maxNodeHierarchyDepth, maxScenes, maxNodes,
                    maxMeshes, maxPrimitives, maxMaterials, maxTextures, maxImages, value,
                    maxPrimitiveOccurrences, maxSceneNodeMemberships, maxTraversalOperations);
        }

        public Limits withMaxPrimitiveOccurrences(long value) {
            return new Limits(maxFileBytes, maxJsonBytes, maxJsonDepth, maxNodeHierarchyDepth, maxScenes, maxNodes,
                    maxMeshes, maxPrimitives, maxMaterials, maxTextures, maxImages, maxArrayItems,
                    value, maxSceneNodeMemberships, maxTraversalOperations);
        }

        public Limits withMaxSceneNodeMemberships(long value) {
            return new Limits(maxFileBytes, maxJsonBytes, maxJsonDepth, maxNodeHierarchyDepth, maxScenes, maxNodes,
                    maxMeshes, maxPrimitives, maxMaterials, maxTextures, maxImages, maxArrayItems,
                    maxPrimitiveOccurrences, value, maxTraversalOperations);
        }

        public Limits withMaxTraversalOperations(long value) {
            return new Limits(maxFileBytes, maxJsonBytes, maxJsonDepth, maxNodeHierarchyDepth, maxScenes, maxNodes,
                    maxMeshes, maxPrimitives, maxMaterials, maxTextures, maxImages, maxArrayItems,
                    maxPrimitiveOccurrences, maxSceneNodeMemberships, value);
        }
    }
}
