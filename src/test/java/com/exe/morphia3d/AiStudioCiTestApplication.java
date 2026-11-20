package com.exe.morphia3d;

import com.exe.morphia3d.design.AiStudioCiTestConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** Test-classpath launcher so the real HTTP app can use test-only providers in CI. */
public final class AiStudioCiTestApplication {
    private AiStudioCiTestApplication() { }

    public static void main(String[] args) {
        new SpringApplicationBuilder(Morphia3dApplication.class, AiStudioCiTestConfiguration.class)
                .profiles("ai-studio-ci")
                .run(args);
    }
}
