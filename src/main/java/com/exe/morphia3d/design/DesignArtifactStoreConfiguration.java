package com.exe.morphia3d.design;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.io.IOException;

@Configuration
@EnableConfigurationProperties(DesignArtifactProperties.class)
public class DesignArtifactStoreConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "ai-studio.artifacts", name = "backend", havingValue = "filesystem")
    @ConditionalOnMissingBean(DesignArtifactStore.class)
    DesignArtifactStore filesystemDesignArtifactStore(DesignArtifactProperties properties,
            LegacyDesignArtifactProjectAuthorizer legacyAuthorizer) throws IOException {
        return new FilesystemDesignArtifactStore(properties.getRoot(), legacyAuthorizer, properties.getMaxBytes());
    }

    @Bean
    @ConditionalOnMissingBean(DesignArtifactStore.class)
    DesignArtifactStore designArtifactStore() { return new UnconfiguredDesignArtifactStore(); }
}
