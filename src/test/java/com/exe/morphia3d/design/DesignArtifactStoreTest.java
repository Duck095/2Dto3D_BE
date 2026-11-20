package com.exe.morphia3d.design;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DesignArtifactStoreTest {
    @TempDir Path tempDir;

    @Test
    void newArtifactKeyMustMatchOwnerAndProjectNamespace() throws Exception {
        UUID project = UUID.randomUUID();
        var store = new FilesystemDesignArtifactStore(tempDir, (owner, projectId, key) -> false, 1024);
        byte[] bytes = "private test image".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var descriptor = store.put(23, project, "image/png", bytes);

        assertThat(descriptor.artifactKey()).startsWith("dsa2:23:" + project + ":");
        assertThat(descriptor.sha256()).hasSize(64);
        assertThat(store.read(23, project, descriptor.artifactKey())).isEqualTo(bytes);
        assertThatThrownBy(() -> store.read(24, project, descriptor.artifactKey())).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> store.read(23, UUID.randomUUID(), descriptor.artifactKey())).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> store.read(23, project, "..\\24\\secret")).isInstanceOf(IOException.class);
        store.delete(23, project, descriptor.artifactKey());
        assertThatThrownBy(() -> store.read(23, project, descriptor.artifactKey())).isInstanceOf(IOException.class);
    }

    @Test
    void legacyUuidReadRequiresExactOwnerProjectLinkAndCannotFallBackToOwnerOnlyPath() throws Exception {
        UUID project = UUID.randomUUID();
        UUID otherProject = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        byte[] bytes = "legacy".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path legacy = tempDir.resolve("owners").resolve("23").resolve(key + ".artifact");
        Files.createDirectories(legacy.getParent()); Files.write(legacy, bytes);
        var store = new FilesystemDesignArtifactStore(tempDir, (owner, projectId, artifactKey) ->
                owner == 23 && project.equals(projectId) && key.equals(artifactKey), 1024);
        assertThat(store.read(23, project, key)).isEqualTo(bytes);
        assertThatThrownBy(() -> store.read(23, otherProject, key)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> store.read(24, project, key)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> store.delete(23, project, key)).isInstanceOf(IOException.class);

        String unlinked = UUID.randomUUID().toString();
        Files.write(tempDir.resolve("owners").resolve("23").resolve(unlinked + ".artifact"), bytes);
        assertThatThrownBy(() -> store.read(23, project, unlinked)).isInstanceOf(IOException.class);
    }

    @Test
    void atomicWriteDoesNotOverwriteExistingArtifactAndCleansTemporaryFiles() throws Exception {
        UUID project = UUID.randomUUID(); UUID artifactId = UUID.randomUUID();
        var store = new FilesystemDesignArtifactStore(tempDir, (owner, projectId, key) -> false, 1024, () -> artifactId);
        byte[] first = "first".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] second = "second".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var descriptor = store.put(23, project, "image/png", first);
        assertThatThrownBy(() -> store.put(23, project, "image/png", second))
                .isInstanceOf(IOException.class).hasMessage("DESIGN_ARTIFACT_ALREADY_EXISTS");
        assertThat(store.read(23, project, descriptor.artifactKey())).isEqualTo(first);
        try (var files = Files.list(tempDir.resolve("owners").resolve("23").resolve("projects").resolve(project.toString()))) {
            assertThat(files.map(path -> path.getFileName().toString()).toList()).containsExactly(artifactId + ".artifact");
        }
    }

    @Test
    void concurrentCollisionPublishesOnlyOneArtifactAndCleansReservationFiles() throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int iteration = 0; iteration < 25; iteration++) {
                UUID project = UUID.randomUUID(); UUID artifactId = UUID.randomUUID();
                Path root = tempDir.resolve("round-" + iteration);
                var store = new FilesystemDesignArtifactStore(root, (owner, projectId, key) -> false, 1024,
                        () -> artifactId, path -> {
                            try { Files.createFile(path); }
                            catch (java.nio.file.FileAlreadyExistsException collision) {
                                throw new java.nio.file.AccessDeniedException(path.toString());
                            }
                        });
                var start = new java.util.concurrent.CountDownLatch(1);
                java.util.concurrent.Callable<byte[]> writer = () -> {
                    start.await();
                    byte[] content = Thread.currentThread().getName().endsWith("1")
                            ? new byte[]{1, 1, 1} : new byte[]{2, 2, 2};
                    try {
                        store.put(23, project, "image/png", content);
                        return content;
                    } catch (IOException collision) {
                        assertThat(collision).hasMessage("DESIGN_ARTIFACT_ALREADY_EXISTS");
                        return null;
                    }
                };
                var first = pool.submit(writer); var second = pool.submit(writer); start.countDown();
                List<byte[]> results = java.util.Arrays.asList(first.get(), second.get());
                assertThat(results.stream().filter(java.util.Objects::nonNull)).hasSize(1);
                byte[] published = results.stream().filter(java.util.Objects::nonNull).findFirst().orElseThrow();
                assertThat(store.read(23, project, "dsa2:23:" + project + ":" + artifactId)).isEqualTo(published);
                try (var files = Files.list(root.resolve("owners").resolve("23").resolve("projects").resolve(project.toString()))) {
                    assertThat(files.map(path -> path.getFileName().toString()).toList())
                            .containsExactly(artifactId + ".artifact");
                }
            }
        } finally { pool.shutdownNow(); }
    }

    @Test
    void genuineReservationPermissionFailureIsNotReportedAsCollision() throws Exception {
        UUID project = UUID.randomUUID(); UUID artifactId = UUID.randomUUID();
        Path root = tempDir.resolve("permission-denied");
        var store = new FilesystemDesignArtifactStore(root, (owner, projectId, key) -> false, 1024,
                () -> artifactId, path -> { throw new java.nio.file.AccessDeniedException(path.toString()); });

        assertThatThrownBy(() -> store.put(23, project, "image/png", new byte[]{1, 2, 3}))
                .isInstanceOf(java.nio.file.AccessDeniedException.class)
                .hasMessageContaining(".artifact.creating");
        try (var files = Files.list(root.resolve("owners").resolve("23").resolve("projects").resolve(project.toString()))) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void rejectsUnsupportedMimeAndOversizedContent() throws Exception {
        var store = new FilesystemDesignArtifactStore(tempDir, (owner, projectId, key) -> false, 4);
        assertThatThrownBy(() -> store.put(23, UUID.randomUUID(), "text/plain", new byte[]{1}))
                .isInstanceOf(IOException.class).hasMessage("DESIGN_ARTIFACT_INVALID");
        assertThatThrownBy(() -> store.put(23, UUID.randomUUID(), "image/png", new byte[]{1, 2, 3, 4, 5}))
                .isInstanceOf(IOException.class).hasMessage("DESIGN_ARTIFACT_INVALID");
    }
}
