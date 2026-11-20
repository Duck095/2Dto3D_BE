package com.exe.morphia3d.design;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.nio.file.Path;

@ConfigurationProperties(prefix = "ai-studio.artifacts")
public class DesignArtifactProperties {
    private String backend = "unconfigured";
    private Path root;
    private long maxBytes = 50L * 1024 * 1024;

    public String getBackend() { return backend; }
    public void setBackend(String backend) { this.backend = backend; }
    public Path getRoot() { return root; }
    public void setRoot(Path root) { this.root = root; }
    public long getMaxBytes() { return maxBytes; }
    public void setMaxBytes(long maxBytes) { this.maxBytes = maxBytes; }

    @PostConstruct
    public void validate() {
        if (!"unconfigured".equals(backend) && !"filesystem".equals(backend)) {
            throw new IllegalStateException("DESIGN_ARTIFACT_BACKEND_UNSUPPORTED");
        }
        if ("filesystem".equals(backend) && (root == null || !root.isAbsolute())) {
            throw new IllegalStateException("DESIGN_ARTIFACT_ABSOLUTE_ROOT_REQUIRED");
        }
        if (maxBytes <= 0 || maxBytes > 100L * 1024 * 1024) {
            throw new IllegalStateException("DESIGN_ARTIFACT_MAX_BYTES_INVALID");
        }
    }
}
