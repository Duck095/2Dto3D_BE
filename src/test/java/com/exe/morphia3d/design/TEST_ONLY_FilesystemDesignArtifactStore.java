package com.exe.morphia3d.design;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Isolated test adapter; never registered by production configuration. */
public final class TEST_ONLY_FilesystemDesignArtifactStore implements DesignArtifactStore {
    private final Path root;

    public TEST_ONLY_FilesystemDesignArtifactStore(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        Files.createDirectories(this.root);
    }

    @Override
    public DesignArtifactDescriptor put(Integer ownerUserId, UUID projectId, String mimeType, byte[] content) throws IOException {
        if (ownerUserId == null || ownerUserId <= 0 || projectId == null || content == null || content.length == 0) {
            throw new IllegalArgumentException("Invalid artifact metadata");
        }
        String artifactId = UUID.randomUUID().toString();
        String key = "dsa2:" + ownerUserId + ":" + projectId + ":" + artifactId;
        Path target = path(ownerUserId, projectId, key);
        Files.createDirectories(target.getParent());
        Files.write(target, content, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
        return new DesignArtifactDescriptor(key, mimeType, content.length, sha256(content));
    }

    @Override
    public byte[] read(Integer ownerUserId, UUID projectId, String artifactKey) throws IOException {
        return Files.readAllBytes(path(ownerUserId, projectId, artifactKey));
    }

    @Override
    public void delete(Integer ownerUserId, UUID projectId, String artifactKey) throws IOException {
        Files.deleteIfExists(path(ownerUserId, projectId, artifactKey));
    }

    private Path path(Integer ownerUserId, UUID projectId, String key) throws IOException {
        if (ownerUserId == null || ownerUserId <= 0 || projectId == null || key == null) {
            throw new IOException("DESIGN_ARTIFACT_NOT_FOUND");
        }
        String prefix = "dsa2:" + ownerUserId + ":" + projectId + ":";
        if (!key.startsWith(prefix)) throw new IOException("DESIGN_ARTIFACT_NOT_FOUND");
        String artifact = key.substring(prefix.length());
        if (!artifact.matches("[0-9a-fA-F-]{36}")) throw new IOException("DESIGN_ARTIFACT_NOT_FOUND");
        Path ownerRoot = root.resolve("owners").resolve(ownerUserId.toString()).resolve("projects").resolve(projectId.toString()).normalize();
        Path target = ownerRoot.resolve(artifact.toLowerCase() + ".artifact").normalize();
        if (!target.startsWith(ownerRoot)) throw new IOException("DESIGN_ARTIFACT_NOT_FOUND");
        return target;
    }

    private String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
