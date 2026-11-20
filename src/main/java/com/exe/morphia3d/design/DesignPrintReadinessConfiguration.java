package com.exe.morphia3d.design;

import com.exe.morphia3d.service.MediaUploadProperties;
import com.exe.morphia3d.service.glb.GlbStructureParser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DesignPrintReadinessConfiguration {
    @Bean
    @ConditionalOnMissingBean(VerifiedModelDimensionEvidenceSource.class)
    VerifiedModelDimensionEvidenceSource noTrustedModelDimensionEvidenceSource() {
        return new NoTrustedModelDimensionEvidenceSource();
    }

    @Bean
    @ConditionalOnMissingBean(GlbStructureParser.class)
    GlbStructureParser designPrintReadinessGlbStructureParser(MediaUploadProperties properties) {
        return new GlbStructureParser(properties);
    }
}
