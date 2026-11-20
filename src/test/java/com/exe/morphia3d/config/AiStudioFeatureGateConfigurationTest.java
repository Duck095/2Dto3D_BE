package com.exe.morphia3d.config;

import com.exe.morphia3d.feature.FeatureFamily;
import com.exe.morphia3d.feature.FeatureGate;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class AiStudioFeatureGateConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AiStudioFeatureGateConfiguration.class);

    @Test
    void registersAiFeatureGateFromApplicationProperties() {
        contextRunner.withPropertyValues("app.features.ai.enabled=true").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(FeatureGate.class);
            assertThat(context.getBean(FeatureGate.class).isEnabled(FeatureFamily.AI)).isTrue();
        });
    }
}
