package com.exe.morphia3d.design;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Meshy asynchronous Multi-Image transport. Submission POSTs are never retried. */
public final class HttpMeshyTransport implements MeshyTransport {
    private static final Pattern TASK_ID = Pattern.compile("[A-Za-z0-9_-]{1,100}");
    private static final Set<String> IMAGE_MIMES = Set.of("image/png", "image/jpeg", "image/webp");
    private static final Set<String> REQUIRED_SLOTS = Set.of("front", "right", "back", "left");
    private static final String ASSET_HOST = "assets.meshy.ai";
    private final RestClient client;
    private final DesignProviderProperties.Meshy settings;
    private final ObjectMapper json;

    public HttpMeshyTransport(RestClient client, DesignProviderProperties.Meshy settings) {
        this(client, settings, new ObjectMapper());
    }

    HttpMeshyTransport(RestClient client, DesignProviderProperties.Meshy settings, ObjectMapper json) {
        this.client = java.util.Objects.requireNonNull(client);
        this.settings = java.util.Objects.requireNonNull(settings);
        this.json = java.util.Objects.requireNonNull(json);
        if (!settings.isEnabled() || settings.getApiKey() == null || settings.getApiKey().isBlank()) {
            throw new IllegalStateException("MESHY_TRANSPORT_NOT_CONFIGURED");
        }
    }

    @Override
    public String submit(MeshyModelProvider.ModelRequest request) {
        Map<String, Object> body = prepareRequest(request);
        try {
            JsonNode response = client.post().uri("/openapi/v1/multi-image-to-3d")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + settings.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON).body(body)
                    .exchange((req, res) -> readJson(res, settings.getMaxDownloadBytes(), false));
            String taskId = response.path("result").asText(null);
            if (taskId == null || !TASK_ID.matcher(taskId).matches()) {
                throw unknown("MESHY_SUBMIT_RESPONSE_INVALID");
            }
            return taskId;
        } catch (DesignProviderCallException failure) {
            throw failure;
        } catch (Exception failure) {
            throw unknown("MESHY_SUBMIT_OUTCOME_UNKNOWN");
        }
    }

    @Override
    public MeshyModelProvider.TaskStatus status(String providerTaskReference) {
        JsonNode task = readTask(providerTaskReference);
        return switch (task.path("status").asText("").toUpperCase(java.util.Locale.ROOT)) {
            case "PENDING" -> MeshyModelProvider.TaskStatus.PENDING;
            case "IN_PROGRESS", "PROCESSING" -> MeshyModelProvider.TaskStatus.PROCESSING;
            case "SUCCEEDED" -> MeshyModelProvider.TaskStatus.SUCCEEDED;
            case "FAILED" -> MeshyModelProvider.TaskStatus.FAILED;
            default -> MeshyModelProvider.TaskStatus.UNKNOWN;
        };
    }

    @Override
    public MeshyModelProvider.GeneratedModel result(String providerTaskReference) {
        JsonNode task = readTask(providerTaskReference);
        if (!"SUCCEEDED".equalsIgnoreCase(task.path("status").asText())) {
            throw unknown("MESHY_RESULT_NOT_READY");
        }
        String resultUrl = task.path("model_urls").path("glb").asText(null);
        URI uri = parseAssetUri(resultUrl);
        try {
            byte[] bytes = client.get().uri(uri).exchange((req, res) -> {
                if (!res.getStatusCode().is2xxSuccessful()) throw readRetry("MESHY_DOWNLOAD_READ_RETRY");
                long length = res.getHeaders().getContentLength();
                if (length > settings.getMaxDownloadBytes()) throw unknown("MESHY_RESULT_TOO_LARGE");
                return readBounded(res.getBody(), settings.getMaxDownloadBytes(), "MESHY_DOWNLOAD_READ_RETRY");
            });
            if (bytes.length == 0) throw unknown("MESHY_RESULT_EMPTY");
            return new MeshyModelProvider.GeneratedModel("model/gltf-binary", bytes, null);
        } catch (DesignProviderReadRetryException | DesignProviderCallException safe) {
            throw safe;
        } catch (Exception failure) {
            throw readRetry("MESHY_DOWNLOAD_READ_RETRY");
        }
    }

    private JsonNode readTask(String taskId) {
        if (taskId == null || !TASK_ID.matcher(taskId).matches()) throw unknown("MESHY_TASK_REFERENCE_INVALID");
        try {
            return client.get().uri("/openapi/v1/multi-image-to-3d/{taskId}", taskId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + settings.getApiKey())
                    .exchange((req, res) -> readJson(res, settings.getMaxDownloadBytes(), true));
        } catch (DesignProviderReadRetryException | DesignProviderCallException safe) {
            throw safe;
        } catch (Exception failure) {
            throw readRetry("MESHY_STATUS_READ_RETRY");
        }
    }

    private JsonNode readJson(ClientHttpResponse response, long maxBytes, boolean retryable) throws IOException {
        if (!response.getStatusCode().is2xxSuccessful()) {
            if (retryable) throw readRetry("MESHY_STATUS_READ_RETRY");
            throw unknown("MESHY_SUBMIT_RESPONSE_INVALID");
        }
        if (response.getHeaders().getContentLength() > maxBytes) {
            throw retryable ? readRetry("MESHY_STATUS_READ_RETRY") : unknown("MESHY_SUBMIT_RESPONSE_INVALID");
        }
        try {
            JsonNode parsed = json.readTree(readBounded(response.getBody(), Math.min(maxBytes, 1024L * 1024),
                    retryable ? "MESHY_STATUS_READ_RETRY" : "MESHY_SUBMIT_OUTCOME_UNKNOWN"));
            if (parsed == null || !parsed.isObject()) throw unknown("MESHY_TASK_RESPONSE_INVALID");
            return parsed;
        } catch (DesignProviderReadRetryException | DesignProviderCallException safe) {
            throw safe;
        } catch (Exception malformed) {
            throw retryable ? readRetry("MESHY_STATUS_READ_RETRY") : unknown("MESHY_SUBMIT_OUTCOME_UNKNOWN");
        }
    }

    private Map<String, Object> prepareRequest(MeshyModelProvider.ModelRequest request) {
        if (request == null || request.jobId() == null || request.idempotencyKey() == null
                || request.idempotencyKey().isBlank() || request.approvedReferences() == null
                || request.approvedReferences().size() != 4) throw notDispatched("MESHY_REQUEST_INVALID");
        List<String> urls = new ArrayList<>(4);
        Set<String> slots = new java.util.HashSet<>();
        for (MeshyModelProvider.ApprovedReference reference : request.approvedReferences()) {
            if (reference == null || reference.slot() == null || !slots.add(reference.slot())
                    || reference.artifactKey() == null || reference.sha256() == null
                    || !reference.sha256().matches("[0-9a-fA-F]{64}")
                    || reference.content() == null || reference.content().length == 0
                    || reference.content().length > settings.getMaxInputBytes()
                    || !IMAGE_MIMES.contains(reference.mimeType())) throw notDispatched("MESHY_INPUT_INVALID");
            urls.add("data:" + reference.mimeType() + ";base64," + Base64.getEncoder().encodeToString(reference.content()));
        }
        if (!slots.equals(REQUIRED_SLOTS)) throw notDispatched("MESHY_INPUT_INVALID");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("image_urls", List.copyOf(urls));
        body.put("ai_model", settings.getModel());
        body.put("target_formats", List.of("glb"));
        if ("meshy-6".equals(settings.getModel()) || "meshy-6-lite".equals(settings.getModel())) {
            body.put("should_texture", false);
        }
        return body;
    }

    private URI parseAssetUri(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !ASSET_HOST.equalsIgnoreCase(uri.getHost())
                    || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getRawUserInfo() != null
                    || uri.getRawFragment() != null || uri.getRawPath() == null
                    || !uri.getRawPath().endsWith(".glb")) throw new IllegalArgumentException();
            return uri;
        } catch (Exception invalid) {
            throw unknown("MESHY_RESULT_URL_INVALID");
        }
    }

    private static byte[] readBounded(InputStream input, long maxBytes, String readError) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(maxBytes, 8192));
        byte[] buffer = new byte[8192]; long count = 0; int read;
        try {
            while ((read = input.read(buffer)) != -1) {
                count += read;
                if (count > maxBytes) throw unknown("MESHY_RESULT_TOO_LARGE");
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (DesignProviderCallException safe) { throw safe; }
        catch (IOException failure) { throw readRetry(readError); }
    }

    private static DesignProviderCallException notDispatched(String code) {
        return new DesignProviderCallException(code, DesignProviderCallException.DispatchState.NOT_DISPATCHED);
    }
    private static DesignProviderCallException unknown(String code) {
        return new DesignProviderCallException(code, DesignProviderCallException.DispatchState.OUTCOME_UNKNOWN);
    }
    private static DesignProviderReadRetryException readRetry(String code) { return new DesignProviderReadRetryException(code); }
}
