package com.exe.morphia3d.design;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.net.URI;
import java.time.Duration;
import java.util.Set;

@ConfigurationProperties(prefix = "ai-studio.providers")
public class DesignProviderProperties {
    private final GptImage gptImage = new GptImage();
    private final Meshy meshy = new Meshy();

    public GptImage getGptImage() { return gptImage; }
    public Meshy getMeshy() { return meshy; }

    @PostConstruct
    public void validate() {
        if (gptImage.enabled) gptImage.validate();
        if (meshy.enabled) meshy.validate();
    }

    public static class GptImage {
        private static final Set<String> MODELS = Set.of("gpt-image-1-mini", "gpt-image-1.5", "gpt-image-2",
                "gpt-image-2.5-sunburst", "gpt-image-2.5-flare", "gpt-image-2.6-sunburst", "gpt-image-2.6-flare");
        private boolean enabled;
        private String apiKey;
        private String baseUrl;
        private String model;
        private String size = "1024x1024";
        private String quality = "high";
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration responseTimeout = Duration.ofSeconds(90);
        private long maxResponseBytes = 20L * 1024 * 1024;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getSize() { return size; }
        public void setSize(String size) { this.size = size; }
        public String getQuality() { return quality; }
        public void setQuality(String quality) { this.quality = quality; }
        public Duration getConnectTimeout() { return connectTimeout; }
        public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
        public Duration getResponseTimeout() { return responseTimeout; }
        public void setResponseTimeout(Duration responseTimeout) { this.responseTimeout = responseTimeout; }
        public long getMaxResponseBytes() { return maxResponseBytes; }
        public void setMaxResponseBytes(long maxResponseBytes) { this.maxResponseBytes = maxResponseBytes; }

        private void validate() {
            requireCredential(apiKey, "GPT_IMAGE_CREDENTIAL_REQUIRED");
            requireHttpsBaseUrl(baseUrl, "api.openai.com", "GPT_IMAGE_HTTPS_BASE_URL_REQUIRED");
            if (model == null || !MODELS.contains(model) || "gpt-image-1".equals(model)) {
                throw new IllegalStateException("GPT_IMAGE_MODEL_UNSUPPORTED");
            }
            if (!Set.of("1024x1024", "1024x1536", "1536x1024").contains(size)) {
                throw new IllegalStateException("GPT_IMAGE_SIZE_UNSUPPORTED");
            }
            Set<String> qualityValues = model.startsWith("gpt-image-2.5-") || model.startsWith("gpt-image-2.6-")
                    ? Set.of("auto", "low", "medium", "high", "xhigh", "max")
                    : Set.of("auto", "low", "medium", "high");
            if (!qualityValues.contains(quality)) throw new IllegalStateException("GPT_IMAGE_QUALITY_UNSUPPORTED");
            validateTimeouts(connectTimeout, responseTimeout, maxResponseBytes, "GPT_IMAGE");
        }
    }

    public static class Meshy {
        private static final Set<String> MODELS = Set.of("latest", "meshy-7.1", "meshy-6", "meshy-6-lite");
        private boolean enabled;
        private String apiKey;
        private String baseUrl;
        private String model;
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration responseTimeout = Duration.ofSeconds(60);
        private Duration pollInterval = Duration.ofSeconds(5);
        private Duration readRetryBaseDelay = Duration.ofSeconds(5);
        private Duration readRetryMaxDelay = Duration.ofMinutes(5);
        private long maxInputBytes = 20L * 1024 * 1024;
        private long maxDownloadBytes = 50L * 1024 * 1024;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public Duration getConnectTimeout() { return connectTimeout; }
        public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
        public Duration getResponseTimeout() { return responseTimeout; }
        public void setResponseTimeout(Duration responseTimeout) { this.responseTimeout = responseTimeout; }
        public Duration getPollInterval() { return pollInterval; }
        public void setPollInterval(Duration pollInterval) { this.pollInterval = pollInterval; }
        public Duration getReadRetryBaseDelay() { return readRetryBaseDelay; }
        public void setReadRetryBaseDelay(Duration readRetryBaseDelay) { this.readRetryBaseDelay = readRetryBaseDelay; }
        public Duration getReadRetryMaxDelay() { return readRetryMaxDelay; }
        public void setReadRetryMaxDelay(Duration readRetryMaxDelay) { this.readRetryMaxDelay = readRetryMaxDelay; }
        public long getMaxInputBytes() { return maxInputBytes; }
        public void setMaxInputBytes(long maxInputBytes) { this.maxInputBytes = maxInputBytes; }
        public long getMaxDownloadBytes() { return maxDownloadBytes; }
        public void setMaxDownloadBytes(long maxDownloadBytes) { this.maxDownloadBytes = maxDownloadBytes; }

        private void validate() {
            requireCredential(apiKey, "MESHY_CREDENTIAL_REQUIRED");
            requireHttpsBaseUrl(baseUrl, "api.meshy.ai", "MESHY_HTTPS_BASE_URL_REQUIRED");
            if (model == null || !MODELS.contains(model)) throw new IllegalStateException("MESHY_MODEL_UNSUPPORTED");
            if (connectTimeout == null || responseTimeout == null || pollInterval == null
                    || readRetryBaseDelay == null || readRetryMaxDelay == null
                    || connectTimeout.isNegative() || connectTimeout.isZero()
                    || responseTimeout.isNegative() || responseTimeout.isZero()
                    || pollInterval.isNegative() || pollInterval.isZero()
                    || readRetryBaseDelay.isNegative() || readRetryBaseDelay.isZero()
                    || readRetryMaxDelay.compareTo(readRetryBaseDelay) < 0
                    || maxInputBytes <= 0 || maxInputBytes > 20L * 1024 * 1024
                    || maxDownloadBytes <= 0 || maxDownloadBytes > 100L * 1024 * 1024) {
                throw new IllegalStateException("MESHY_HTTP_LIMITS_INVALID");
            }
        }
    }

    private static void requireCredential(String value, String code) {
        if (value == null || value.isBlank()) throw new IllegalStateException(code);
    }

    private static void requireHttpsBaseUrl(String value, String expectedHost, String code) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !expectedHost.equalsIgnoreCase(uri.getHost())
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !"/".equals(uri.getRawPath()))) {
                throw new IllegalStateException(code);
            }
        } catch (IllegalArgumentException malformed) {
            throw new IllegalStateException(code);
        }
    }

    private static void validateTimeouts(Duration connect, Duration response, long maxBytes, String provider) {
        if (connect == null || response == null || connect.isNegative() || connect.isZero()
                || connect.compareTo(Duration.ofSeconds(30)) > 0 || response.isNegative() || response.isZero()
                || response.compareTo(Duration.ofMinutes(5)) > 0 || maxBytes <= 0 || maxBytes > 50L * 1024 * 1024) {
            throw new IllegalStateException(provider + "_HTTP_LIMITS_INVALID");
        }
    }
}
