package com.exe.morphia3d.design;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DesignProviderConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DesignProviderConfiguration.class);

    @Test
    void providersDefaultToUnavailableWithoutNetworkConfiguration() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(GptImageTransport.class)).isInstanceOf(UnavailableGptImageTransport.class);
            assertThat(context.getBean(MeshyTransport.class)).isInstanceOf(UnavailableMeshyTransport.class);
            assertThatThrownBy(() -> context.getBean(GptImageTransport.class).generate(null))
                    .isInstanceOf(ProviderUnavailableException.class)
                    .hasMessage("GPT_IMAGE_TRANSPORT_UNAVAILABLE");
            assertThatThrownBy(() -> context.getBean(MeshyTransport.class).submit(null))
                    .isInstanceOf(ProviderUnavailableException.class)
                    .hasMessage("MESHY_TRANSPORT_UNAVAILABLE");
        });
    }

    @Test
    void enabledOpenAiProviderFailsContextWithoutCredentialModelAndHttpsBaseUrl() {
        runner.withPropertyValues("ai-studio.providers.gpt-image.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void enabledMeshyProviderFailsContextWithoutCredentialModelAndHttpsBaseUrl() {
        runner.withPropertyValues("ai-studio.providers.meshy.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void enabledProvidersRejectHttpAndUnsupportedModels() {
        runner.withPropertyValues(
                        "ai-studio.providers.gpt-image.enabled=true",
                        "ai-studio.providers.gpt-image.api-key=test-only",
                        "ai-studio.providers.gpt-image.model=gpt-image-1",
                        "ai-studio.providers.gpt-image.base-url=http://api.openai.com")
                .run(context -> assertThat(context).hasFailed());

        runner.withPropertyValues(
                        "ai-studio.providers.meshy.enabled=true",
                        "ai-studio.providers.meshy.api-key=test-only",
                        "ai-studio.providers.meshy.model=unknown-model",
                        "ai-studio.providers.meshy.base-url=http://api.meshy.ai")
                .run(context -> assertThat(context).hasFailed());

        runner.withPropertyValues(
                        "ai-studio.providers.gpt-image.enabled=true",
                        "ai-studio.providers.gpt-image.api-key=test-only",
                        "ai-studio.providers.gpt-image.model=gpt-image-2.6-flare",
                        "ai-studio.providers.gpt-image.base-url=https://attacker.example")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void explicitlyConfiguredProvidersSelectHttpTransportsWithoutCallingNetwork() {
        runner.withPropertyValues(
                        "ai-studio.providers.gpt-image.enabled=true",
                        "ai-studio.providers.gpt-image.api-key=test-only-openai",
                        "ai-studio.providers.gpt-image.model=gpt-image-2.6-flare",
                        "ai-studio.providers.gpt-image.base-url=https://api.openai.com",
                        "ai-studio.providers.meshy.enabled=true",
                        "ai-studio.providers.meshy.api-key=test-only-meshy",
                        "ai-studio.providers.meshy.model=meshy-7.1",
                        "ai-studio.providers.meshy.base-url=https://api.meshy.ai")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(GptImageTransport.class)).isInstanceOf(HttpGptImageTransport.class);
                    assertThat(context.getBean(MeshyTransport.class)).isInstanceOf(HttpMeshyTransport.class);
                });
    }
}
