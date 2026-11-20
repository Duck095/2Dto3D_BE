package com.exe.morphia3d.design;

import com.exe.morphia3d.service.MediaUploadProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class DesignPrintReadinessConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(MediaUploadProperties.class, MediaUploadProperties::new)
            .withUserConfiguration(DesignPrintReadinessConfiguration.class);

    @Test void missingTrustedSourceUsesOneFailClosedDefaultAndNoDimensionLimitsAreActivated() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(VerifiedModelDimensionEvidenceSource.class)).hasSize(1);
            var source = context.getBean(VerifiedModelDimensionEvidenceSource.class);
            assertThat(source).isInstanceOf(NoTrustedModelDimensionEvidenceSource.class);
            assertThat(source.find(UUID.randomUUID(), "a".repeat(64))).isEmpty();
            assertThat(context.getEnvironment().containsProperty("ai-studio.print-readiness.min-dimension-mm")).isFalse();
            assertThat(context.getEnvironment().containsProperty("ai-studio.print-readiness.max-dimension-mm")).isFalse();
        });
    }

    @Test void userTrustedSourceOverridesDefaultWithoutAmbiguousBeans() {
        VerifiedModelDimensionEvidenceSource trusted = (id, sha) -> Optional.empty();
        runner.withBean(VerifiedModelDimensionEvidenceSource.class, () -> trusted).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(VerifiedModelDimensionEvidenceSource.class)).hasSize(1);
            assertThat(context.getBean(VerifiedModelDimensionEvidenceSource.class)).isSameAs(trusted);
            assertThat(context.getBeansOfType(NoTrustedModelDimensionEvidenceSource.class)).isEmpty();
        });
    }
}
