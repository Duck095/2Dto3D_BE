package com.exe.morphia3d.design;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Primary;

import java.io.IOException;
import java.nio.file.Path;

/** Test-classpath-only providers for the disposable AI Studio integration harness. */
@Configuration(proxyBeanMethods = false)
@Profile("ai-studio-ci")
public class AiStudioCiTestConfiguration {
    @Bean
    @Primary
    TEST_ONLY_CiMeshyModelProvider aiStudioCiMeshyProvider() { return new TEST_ONLY_CiMeshyModelProvider(); }

    @Bean
    @Primary
    TEST_ONLY_FakeReferenceImageProvider aiStudioCiReferenceProvider() { return new TEST_ONLY_FakeReferenceImageProvider(); }

    @Bean
    @Primary
    DesignArtifactStore aiStudioCiArtifactStore(
            @Value("${ai-studio.ci.artifact-root:${java.io.tmpdir}/2dto3d-ai-studio-ci}") Path root) throws IOException {
        if (!root.isAbsolute()) throw new IllegalStateException("TEST_ONLY_ARTIFACT_ROOT_MUST_BE_ABSOLUTE");
        return new TEST_ONLY_FilesystemDesignArtifactStore(root);
    }

}
