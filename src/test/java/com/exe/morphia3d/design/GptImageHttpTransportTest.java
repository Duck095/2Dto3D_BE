package com.exe.morphia3d.design;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestClient;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GptImageHttpTransportTest {
    private static final String SECRET = "test-only-openai-secret";
    private static final String PRIVATE_PROMPT = "private prompt never logged";

    @Test
    void generationRequestUsesJsonAndReturnsSinglePngPerSlot() throws Exception {
        var settings = settings();
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.openai.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        byte[] png = png();
        String body = "{\"data\":[{\"b64_json\":\"" + Base64.getEncoder().encodeToString(png) + "\"}]}";
        server.expect(requestTo("https://api.openai.com/v1/images/generations"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    assertThat(request.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
                    assertThat(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer " + SECRET);
                    assertThat(request.getURI().getScheme()).isEqualTo("https");
                })
                .andExpect(content().string(containsString("\"model\":\"gpt-image-2.6-flare\"")))
                .andExpect(content().string(containsString("\"prompt\":\"" + PRIVATE_PROMPT + " — front view\"")))
                .andExpect(content().string(containsString("\"n\":1")))
                .andExpect(content().string(containsString("\"size\":\"1024x1024\"")))
                .andExpect(content().string(containsString("\"quality\":\"high\"")))
                .andExpect(content().string(containsString("\"output_format\":\"png\"")))
                .andExpect(content().string(not(containsString("response_format"))))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        var outputs = new HttpGptImageTransport(builder.build(), settings).generate(request(List.of(ReferenceViewSlot.FRONT), List.of()));
        server.verify();
        assertThat(outputs).hasSize(1);
        assertThat(outputs.get(0).slot()).isEqualTo(ReferenceViewSlot.FRONT);
        assertThat(outputs.get(0).mimeType()).isEqualTo("image/png");
        assertThat(outputs.get(0).content()).isEqualTo(png);
    }

    @Test
    void editRequestUsesMultipartRawImagePartsNotDataUris() throws Exception {
        var settings = settings();
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.openai.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        byte[] png = png();
        String response = "{\"data\":[{\"b64_json\":\"" + Base64.getEncoder().encodeToString(png) + "\"}]}";
        server.expect(requestTo("https://api.openai.com/v1/images/edits"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(multipartContainsRawInput("source-one", PRIVATE_PROMPT))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        byte[] source = "source-one".getBytes(StandardCharsets.UTF_8);
        var input = new ReferenceImageProvider.InputArtifact("input-key", "image/png", source.length, "a".repeat(64), source);
        var outputs = new HttpGptImageTransport(builder.build(), settings).generate(request(List.of(ReferenceViewSlot.LEFT), List.of(input)));
        server.verify();
        assertThat(outputs).hasSize(1);
        assertThat(outputs.get(0).slot()).isEqualTo(ReferenceViewSlot.LEFT);
        assertThat(outputs.get(0).content()).isEqualTo(png);
    }

    @Test
    void requestsOnlyRequestedSlotsSequentiallyAndDoesNotRetryPost() throws Exception {
        var settings = settings();
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.openai.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        byte[] png = png();
        String response = "{\"data\":[{\"b64_json\":\"" + Base64.getEncoder().encodeToString(png) + "\"}]}";
        for (String slot : List.of("front", "left", "back")) {
            server.expect(requestTo("https://api.openai.com/v1/images/generations"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(content().string(containsString(slot + " view")))
                    .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        }
        var outputs = new HttpGptImageTransport(builder.build(), settings).generate(request(
                List.of(ReferenceViewSlot.FRONT, ReferenceViewSlot.LEFT, ReferenceViewSlot.BACK), List.of()));
        server.verify();
        assertThat(outputs).extracting(ReferenceImageProvider.GeneratedReferenceImage::slot)
                .containsExactly(ReferenceViewSlot.FRONT, ReferenceViewSlot.LEFT, ReferenceViewSlot.BACK);
    }

    @Test
    void malformedOrOversizedResponseHasSafeMessageAndNoPostRetry() throws Exception {
        var settings = settings();
        settings.setMaxResponseBytes(1);
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.openai.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.openai.com/v1/images/generations"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"data\":[{\"b64_json\":\"" + Base64.getEncoder().encodeToString(png()) + "\"}]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> new HttpGptImageTransport(builder.build(), settings)
                .generate(request(List.of(ReferenceViewSlot.FRONT), List.of())))
                .isInstanceOf(DesignProviderCallException.class)
                .hasMessage("GPT_IMAGE_RESPONSE_INVALID")
                .hasMessageNotContaining(SECRET).hasMessageNotContaining(PRIVATE_PROMPT).hasMessageNotContaining("b64_json");
        server.verify();
    }

    @Test
    void dispatchedTimeoutIsUnknownAndNeverRetried() {
        var settings = settings();
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.openai.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.openai.com/v1/images/generations"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(request -> { throw new java.io.IOException("secret response " + SECRET + " " + PRIVATE_PROMPT); });

        assertThatThrownBy(() -> new HttpGptImageTransport(builder.build(), settings)
                .generate(request(List.of(ReferenceViewSlot.FRONT), List.of())))
                .isInstanceOf(DesignProviderCallException.class)
                .hasMessage("GPT_IMAGE_OUTCOME_UNKNOWN")
                .hasMessageNotContaining(SECRET).hasMessageNotContaining(PRIVATE_PROMPT);
        server.verify();
    }

    private static RequestMatcher multipartContainsRawInput(String input, String prompt) {
        return request -> {
            MediaType contentType = request.getHeaders().getContentType();
            assertThat(contentType).isNotNull();
            assertThat(contentType.isCompatibleWith(MediaType.MULTIPART_FORM_DATA)).isTrue();
            content().string(containsString("name=\"image[]\"")).match(request);
            content().string(containsString("Content-Type: image/png")).match(request);
            content().string(containsString(input)).match(request);
            content().string(containsString(prompt + " — left view")).match(request);
            content().string(containsString("name=\"output_format\"")).match(request);
            content().string(containsString("name=\"n\"")).match(request);
            content().string(not(containsString("data:image/png;base64,"))).match(request);
            assertThat(request.getURI().getScheme()).isEqualTo("https");
            assertThat(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer " + SECRET);
        };
    }

    private static DesignProviderProperties.GptImage settings() {
        var value = new DesignProviderProperties.GptImage();
        value.setEnabled(true); value.setApiKey(SECRET); value.setBaseUrl("https://api.openai.com");
        value.setModel("gpt-image-2.6-flare"); value.setQuality("high"); value.setSize("1024x1024");
        return value;
    }

    private static ReferenceImageProvider.ReferenceImageRequest request(List<ReferenceViewSlot> slots,
            List<ReferenceImageProvider.InputArtifact> inputs) {
        return new ReferenceImageProvider.ReferenceImageRequest(UUID.randomUUID(), "stable-test-key", PRIVATE_PROMPT,
                "profile-v1", inputs, slots);
    }

    private static byte[] png() throws Exception {
        var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }
}
