package com.exe.morphia3d.service.glb;

import java.util.List;

/** Immutable structural view of a GLB. It contains indices and source metadata, never rewritten geometry. */
public record GlbStructure(
        int defaultSceneIndex,
        List<Scene> scenes,
        List<Node> nodes,
        List<Mesh> meshes,
        List<Material> materials,
        List<Texture> textures,
        List<Image> images,
        List<PrimitiveOccurrence> primitiveOccurrences) {

    public GlbStructure {
        scenes = List.copyOf(scenes);
        nodes = List.copyOf(nodes);
        meshes = List.copyOf(meshes);
        materials = List.copyOf(materials);
        textures = List.copyOf(textures);
        images = List.copyOf(images);
        primitiveOccurrences = List.copyOf(primitiveOccurrences);
    }

    public record Scene(int index, String name, List<Integer> rootNodeIndices) {
        public Scene { rootNodeIndices = List.copyOf(rootNodeIndices); }
    }

    public record Node(int index, String name, Integer meshIndex,
                       List<Integer> childIndices, List<Integer> sceneIndices) {
        public Node {
            childIndices = List.copyOf(childIndices);
            sceneIndices = List.copyOf(sceneIndices);
        }
    }

    public record Mesh(int index, String name, List<Primitive> primitives) {
        public Mesh { primitives = List.copyOf(primitives); }
    }

    public record Primitive(int index, Integer materialIndex, List<String> attributeNames,
                            List<Integer> textureIndices, int mode, long positionCount, Long indexCount,
                            boolean verifiedSurface) {
        public Primitive {
            attributeNames = List.copyOf(attributeNames);
            textureIndices = List.copyOf(textureIndices);
        }

        /** Compatibility constructor for metadata-only callers that do not verify geometry buffers. */
        public Primitive(int index, Integer materialIndex, List<String> attributeNames,
                         List<Integer> textureIndices) {
            this(index, materialIndex, attributeNames, textureIndices, 4, 0, null, false);
        }
    }

    public record Material(int index, String name, List<Integer> textureIndices) {
        public Material { textureIndices = List.copyOf(textureIndices); }
    }

    public record Texture(int index, List<Integer> sourceImageIndices, Integer samplerIndex) {
        public Texture { sourceImageIndices = List.copyOf(sourceImageIndices); }
    }

    /** URI values are deliberately not returned or dereferenced. */
    public record Image(int index, String name, String mimeType, boolean uriSource, Integer bufferViewIndex) {}

    /** One configurable node/mesh/primitive occurrence. Names are descriptive only. */
    public record PrimitiveOccurrence(int nodeIndex, int meshIndex, int primitiveIndex,
                                      String originalName, Integer materialIndex) {}
}
