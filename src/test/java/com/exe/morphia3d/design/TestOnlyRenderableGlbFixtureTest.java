package com.exe.morphia3d.design;

import com.exe.morphia3d.service.MediaUploadProperties;
import com.exe.morphia3d.service.glb.GlbStructureParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestOnlyRenderableGlbFixtureTest {
    @Test
    void fixtureContainsAValidatedTriangleInTheDefaultScene() {
        var structure = new GlbStructureParser(new MediaUploadProperties())
                .parse(TestOnlyRenderableGlbFixture.validTriangle());

        assertThat(structure.primitiveOccurrences()).hasSize(1);
        assertThat(structure.meshes().get(0).primitives().get(0).positionCount()).isEqualTo(3);
        assertThat(structure.meshes().get(0).primitives().get(0).verifiedSurface()).isTrue();
    }
}
