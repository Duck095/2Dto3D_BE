package com.exe.morphia3d.design;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DesignArtifactStoreConfigurationTest {
    @TempDir Path tempDir;
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DesignArtifactStoreConfiguration.class);

    @Test
    void storageDefaultsToUnconfiguredAndDoesNotCreateFilesystemPaths() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(DesignArtifactStore.class)).isInstanceOf(UnconfiguredDesignArtifactStore.class);
        });
    }

    @Test
    void explicitlySelectedFilesystemRequiresAbsoluteRoot() {
        runner.withPropertyValues("ai-studio.artifacts.backend=filesystem")
                .run(context -> assertThat(context).hasFailed());

        runner.withPropertyValues(
                        "ai-studio.artifacts.backend=filesystem",
                        "ai-studio.artifacts.root=relative/path")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void explicitlySelectedFilesystemUsesPrivateScopedStore() {
        runner.withBean(LegacyDesignArtifactProjectAuthorizer.class, () -> (owner, project, key) -> false)
                .withPropertyValues("ai-studio.artifacts.backend=filesystem", "ai-studio.artifacts.root=" + tempDir)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(DesignArtifactStore.class)).isInstanceOf(FilesystemDesignArtifactStore.class);
                });
    }

}
