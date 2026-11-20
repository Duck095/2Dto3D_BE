package com.exe.morphia3d.config;

import com.exe.morphia3d.feature.FeatureGate;
import com.exe.morphia3d.feature.FeatureGateProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration
@EnableConfigurationProperties(FeatureGateProperties.class)
public class AiStudioFeatureGateConfiguration {
    @Bean
    public FeatureGate featureGate(FeatureGateProperties properties, Environment environment) {
        if (environment.acceptsProfiles(Profiles.of("qa"))
                && (properties.anyEnabled() || properties.isAdminProductSnapshotUploadEnabled())) {
            throw new IllegalStateException("Restricted feature gates must remain disabled in the qa profile");
        }
        return new FeatureGate(properties);
    }
}
