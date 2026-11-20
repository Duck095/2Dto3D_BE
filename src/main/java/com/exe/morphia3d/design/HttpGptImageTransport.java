package com.exe.morphia3d.design;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** OpenAI Images API transport. It deliberately has no POST retry policy. */
public final class HttpGptImageTransport implements GptImageTransport {
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
    private static final Set<String> INPUT_MIMES = Set.of("image/png", "image/jpeg", "image/webp");

    private final RestClient client;
    private final DesignProviderProperties.GptImage settings;
    private final ObjectMapper json;

    public HttpGptImageTransport(RestClient client, DesignProviderProperties.GptImage settings) {
        this(client, settings, new ObjectMapper());
    }

    HttpGptImageTransport(RestClient client, DesignProviderProperties.GptImage settings, ObjectMapper json) {
        this.client = java.util.Objects.requireNonNull(client);
        this.settings = java.util.Objects.requireNonNull(settings);
        this.json = java.util.Objects.requireNonNull(json);
        if (!settings.isEnabled() || settings.getApiKey() == null || settings.getApiKey().isBlank()) {
            throw new IllegalStateException("GPT_IMAGE_TRANSPORT_NOT_CONFIGURED");
        }
    }

    @Override
    public List<ReferenceImageProvider.GeneratedReferenceImage> generate(
            ReferenceImageProvider.ReferenceImageRequest request) {
        validateRequest(request);
        List<ReferenceImageProvider.GeneratedReferenceImage> results = new ArrayList<>(request.slots().size());
        for (ReferenceViewSlot slot : request.slots()) {
            byte[] bytes = request.inputs().isEmpty()
                    ? postGeneration(slotPrompt(request.normalizedPrompt(), slot), slot)
                    : postEdit(request, slot);
            results.add(new ReferenceImageProvider.GeneratedReferenceImage(slot, "image/png", bytes, null));
        }
        return List.copyOf(results);
    }

    private byte[] postGeneration(String prompt, ReferenceViewSlot slot) {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("model", settings.getModel());
        body.put("prompt", prompt);
        body.put("n", 1);
        body.put("size", settings.getSize());
        body.put("quality", settings.getQuality());
        body.put("output_format", "png");
        return send("/v1/images/generations", MediaType.APPLICATION_JSON, body, slot);
    }

    private byte[] postEdit(ReferenceImageProvider.ReferenceImageRequest request, ReferenceViewSlot slot) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        int index = 0;
        for (ReferenceImageProvider.InputArtifact input : request.inputs()) {
            index++;
            HttpHeaders partHeaders = new HttpHeaders();
            partHeaders.setContentType(MediaType.parseMediaType(input.mimeType()));
            partHeaders.setContentDisposition(ContentDisposition.formData().name("image[]")
                    .filename("input-" + index + extensionFor(input.mimeType())).build());
            form.add("image[]", new org.springframework.http.HttpEntity<>(input.content(), partHeaders));
        }
        addPart(form, "model", settings.getModel());
        addPart(form, "prompt", slotPrompt(request.normalizedPrompt(), slot));
        addPart(form, "n", "1");
        addPart(form, "size", settings.getSize());
        addPart(form, "quality", settings.getQuality());
        addPart(form, "output_format", "png");
        return send("/v1/images/edits", MediaType.MULTIPART_FORM_DATA, form, slot);
    }

    private byte[] send(String endpoint, MediaType contentType, Object body, ReferenceViewSlot slot) {
        try {
            return client.post().uri(endpoint).contentType(contentType)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + settings.getApiKey())
                    .body(body)
                    .exchange((request, response) -> parseResponse(response, slot));
        } catch (DesignProviderCallException failure) {
            throw failure;
        } catch (Exception failure) {
            // RestClient does not expose a safe way to prove that a POST was never dispatched.
            throw new DesignProviderCallException("GPT_IMAGE_OUTCOME_UNKNOWN",
                    DesignProviderCallException.DispatchState.OUTCOME_UNKNOWN);
        }
    }

    private byte[] parseResponse(ClientHttpResponse response, ReferenceViewSlot slot) throws IOException {
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw unknownResponse();
        }
        long contentLength = response.getHeaders().getContentLength();
        if (contentLength > settings.getMaxResponseBytes()) throw unknownResponse();
        byte[] responseBytes = readBounded(response.getBody(), settings.getMaxResponseBytes());
        try {
            JsonNode data = json.readTree(responseBytes).path("data");
            if (!data.isArray() || data.size() != 1) throw unknownResponse();
            JsonNode encoded = data.get(0).path("b64_json");
            if (!encoded.isTextual() || encoded.textValue().isBlank()) throw unknownResponse();
            byte[] image = Base64.getDecoder().decode(encoded.textValue());
            if (image.length == 0 || image.length > settings.getMaxResponseBytes() || !hasPngSignature(image)) {
                throw unknownResponse();
            }
            return image;
        } catch (DesignProviderCallException safe) {
            throw safe;
        } catch (Exception invalid) {
            throw unknownResponse();
        }
    }

    private void validateRequest(ReferenceImageProvider.ReferenceImageRequest request) {
        if (request == null || request.jobId() == null || request.idempotencyKey() == null
                || request.idempotencyKey().isBlank() || request.normalizedPrompt() == null
                || request.normalizedPrompt().isBlank() || request.slots() == null || request.slots().isEmpty()
                || request.slots().size() > 4 || EnumSet.copyOf(request.slots()).size() != request.slots().size()) {
            throw notDispatched("GPT_IMAGE_REQUEST_INVALID");
        }
        if (request.inputs() == null) throw notDispatched("GPT_IMAGE_INPUT_INVALID");
        if (request.inputs().size() > 16) throw notDispatched("GPT_IMAGE_INPUT_INVALID");
        for (ReferenceImageProvider.InputArtifact input : request.inputs()) {
            if (input == null || input.content() == null || input.content().length == 0
                    || !INPUT_MIMES.contains(input.mimeType()) || input.content().length >= 50L * 1024 * 1024
                    || input.byteSize() != input.content().length) {
                throw notDispatched("GPT_IMAGE_INPUT_INVALID");
            }
        }
    }

    private String slotPrompt(String normalizedPrompt, ReferenceViewSlot slot) {
        return normalizedPrompt + " — " + slot.apiValue() + " view";
    }

    private static String extensionFor(String mimeType) {
        return switch (mimeType) {
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            case "image/webp" -> ".webp";
            default -> throw notDispatched("GPT_IMAGE_INPUT_INVALID");
        };
    }

    private static void addPart(MultiValueMap<String, Object> form, String name, String value) {
        form.add(name, new org.springframework.http.HttpEntity<>(value));
    }

    private static byte[] readBounded(InputStream input, long maximum) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(maximum, 8192));
        byte[] buffer = new byte[8192];
        long count = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            count += read;
            if (count > maximum) throw unknownResponse();
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static boolean hasPngSignature(byte[] bytes) {
        if (bytes.length < PNG_SIGNATURE.length) return false;
        for (int i = 0; i < PNG_SIGNATURE.length; i++) if (bytes[i] != PNG_SIGNATURE[i]) return false;
        return true;
    }

    private static DesignProviderCallException unknownResponse() {
        return new DesignProviderCallException("GPT_IMAGE_RESPONSE_INVALID",
                DesignProviderCallException.DispatchState.OUTCOME_UNKNOWN);
    }

    private static DesignProviderCallException notDispatched(String safeCode) {
        return new DesignProviderCallException(safeCode, DesignProviderCallException.DispatchState.NOT_DISPATCHED);
    }
}
