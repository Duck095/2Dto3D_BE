package com.exe.morphia3d.design;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Private owner/project-scoped artifact files with atomic create-only writes. */
public final class FilesystemDesignArtifactStore implements DesignArtifactStore {
    private static final Set<String> MIMES = Set.of("image/png", "image/jpeg", "image/webp", "model/gltf-binary");
    private final Path root;
    private final long maxBytes;
    private final LegacyDesignArtifactProjectAuthorizer legacyAuthorizer;
    private final Supplier<UUID> artifactIds;
    private final ReservationFileCreator reservationFileCreator;

    public FilesystemDesignArtifactStore(Path root, LegacyDesignArtifactProjectAuthorizer legacyAuthorizer, long maxBytes) throws IOException {
        this(root, legacyAuthorizer, maxBytes, UUID::randomUUID, FilesystemDesignArtifactStore::createReservationFile);
    }

    FilesystemDesignArtifactStore(Path root, LegacyDesignArtifactProjectAuthorizer legacyAuthorizer, long maxBytes,
            Supplier<UUID> artifactIds) throws IOException {
        this(root, legacyAuthorizer, maxBytes, artifactIds, FilesystemDesignArtifactStore::createReservationFile);
    }

    FilesystemDesignArtifactStore(Path root, LegacyDesignArtifactProjectAuthorizer legacyAuthorizer, long maxBytes,
            Supplier<UUID> artifactIds, ReservationFileCreator reservationFileCreator) throws IOException {
        if (root == null || !root.isAbsolute() || maxBytes <= 0 || maxBytes > 100L * 1024 * 1024) {
            throw new IllegalArgumentException("DESIGN_ARTIFACT_STORAGE_CONFIG_INVALID");
        }
        this.root = root.normalize(); this.maxBytes = maxBytes;
        this.legacyAuthorizer = java.util.Objects.requireNonNull(legacyAuthorizer);
        this.artifactIds = java.util.Objects.requireNonNull(artifactIds);
        this.reservationFileCreator = java.util.Objects.requireNonNull(reservationFileCreator);
        rejectSymlinkComponents(this.root);
        Files.createDirectories(this.root);
        rejectSymlinkComponents(this.root);
    }

    @Override public DesignArtifactDescriptor put(Integer ownerUserId, UUID projectId, String mimeType, byte[] content) throws IOException {
        validateIdentity(ownerUserId, projectId);
        if (!MIMES.contains(mimeType) || content == null || content.length == 0 || content.length > maxBytes) {
            throw new IOException("DESIGN_ARTIFACT_INVALID");
        }
        UUID artifactId = artifactIds.get();
        if (artifactId == null) throw new IOException("DESIGN_ARTIFACT_ID_INVALID");
        String key = "dsa2:" + ownerUserId + ":" + projectId + ":" + artifactId;
        Path ownerPath = root.resolve("owners").resolve(ownerUserId.toString());
        Path parent = ownerPath.resolve("projects").resolve(projectId.toString());
        createSafeDirectories(parent);
        Path target = parent.resolve(artifactId + ".artifact");
        Path reservation = parent.resolve(artifactId + ".artifact.creating");
        Path temp = Files.createTempFile(parent, ".artifact-", ".tmp");
        boolean reserved = false;
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            createReservation(reservation, target);
            reserved = true;
            if (pathEntryExists(target)) throw artifactAlreadyExists();
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            return new DesignArtifactDescriptor(key, mimeType, content.length, sha256(content));
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException("DESIGN_ARTIFACT_ATOMIC_MOVE_UNSUPPORTED");
        } finally {
            try { Files.deleteIfExists(temp); }
            finally { if (reserved) Files.deleteIfExists(reservation); }
        }
    }

    @Override public byte[] read(Integer ownerUserId, UUID projectId, String artifactKey) throws IOException {
        Path file = resolve(ownerUserId, projectId, artifactKey, false);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) throw notFound();
        long size = Files.size(file);
        if (size <= 0 || size > maxBytes) throw notFound();
        try (SeekableByteChannel channel = Files.newByteChannel(file, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            ByteBuffer buffer = ByteBuffer.allocate((int) size);
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) { }
            if (buffer.hasRemaining()) throw notFound();
            return buffer.array();
        }
    }

    @Override public void delete(Integer ownerUserId, UUID projectId, String artifactKey) throws IOException {
        Files.deleteIfExists(resolve(ownerUserId, projectId, artifactKey, true));
    }

    private Path resolve(Integer ownerId, UUID projectId, String key, boolean deleting) throws IOException {
        validateIdentity(ownerId, projectId);
        if (key == null) throw notFound();
        if (key.startsWith("dsa2:")) {
            String[] parts = key.split(":", -1);
            if (parts.length != 4 || !Integer.toString(ownerId).equals(parts[1])) throw notFound();
            UUID encodedProject = parseUuid(parts[2]); UUID artifact = parseUuid(parts[3]);
            if (!projectId.equals(encodedProject)) throw notFound();
            Path file = root.resolve("owners").resolve(ownerId.toString()).resolve("projects")
                    .resolve(projectId.toString()).resolve(artifact + ".artifact").normalize();
            return validatePath(file);
        }
        UUID legacy = parseUuid(key);
        if (deleting || !legacyAuthorizer.isLinked(ownerId, projectId, key)) throw notFound();
        Path file = root.resolve("owners").resolve(ownerId.toString()).resolve(legacy + ".artifact").normalize();
        return validatePath(file);
    }

    private Path validatePath(Path file) throws IOException {
        if (!file.normalize().startsWith(root)) throw notFound();
        rejectSymlinkComponents(file.getParent());
        Path realRoot = root.toRealPath(LinkOption.NOFOLLOW_LINKS);
        Path realParent = file.getParent().toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!realParent.startsWith(realRoot) || Files.isSymbolicLink(file)) throw notFound();
        return file;
    }

    private void createSafeDirectories(Path target) throws IOException {
        Path cursor = root;
        for (Path part : root.relativize(target)) {
            cursor = cursor.resolve(part);
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(cursor) || !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) throw notFound();
            } else {
                try { Files.createDirectory(cursor); }
                catch (java.nio.file.FileAlreadyExistsException concurrentCreate) {
                    if (Files.isSymbolicLink(cursor) || !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) throw notFound();
                }
            }
        }
        rejectSymlinkComponents(target);
    }

    private void createReservation(Path reservation, Path target) throws IOException {
        try {
            reservationFileCreator.create(reservation);
        } catch (java.nio.file.FileAlreadyExistsException collision) {
            throw artifactAlreadyExists();
        } catch (AccessDeniedException denied) {
            if (pathEntryExists(reservation) || pathEntryExists(target)) throw artifactAlreadyExists();
            try {
                reservationFileCreator.create(reservation);
            } catch (java.nio.file.FileAlreadyExistsException collision) {
                throw artifactAlreadyExists();
            } catch (AccessDeniedException retryDenied) {
                if (pathEntryExists(reservation) || pathEntryExists(target)) throw artifactAlreadyExists();
                throw retryDenied;
            }
        }
    }

    private static boolean pathEntryExists(Path path) throws IOException {
        Path expectedName = path.getFileName();
        try (var entries = Files.newDirectoryStream(path.getParent())) {
            for (Path entry : entries) {
                if (entry.getFileName().equals(expectedName)) return true;
            }
            return false;
        } catch (NoSuchFileException absent) {
            return false;
        }
    }

    private static IOException artifactAlreadyExists() {
        return new IOException("DESIGN_ARTIFACT_ALREADY_EXISTS");
    }

    @FunctionalInterface
    interface ReservationFileCreator {
        void create(Path reservation) throws IOException;
    }

    private static void createReservationFile(Path reservation) throws IOException {
        Files.createFile(reservation);
    }

    private void rejectSymlinkComponents(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path cursor = absolute.getRoot();
        for (Path part : absolute) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) throw notFound();
        }
    }

    private static UUID parseUuid(String value) throws IOException {
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
            return parsed;
        } catch (Exception invalid) { throw notFound(); }
    }
    private static void validateIdentity(Integer owner, UUID project) {
        if (owner == null || owner <= 0 || project == null) throw new IllegalArgumentException("DESIGN_ARTIFACT_IDENTITY_INVALID");
    }
    private static IOException notFound() { return new DesignArtifactNotFoundException(); }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
