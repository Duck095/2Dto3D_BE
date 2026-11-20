package com.exe.morphia3d.design;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MeshyHttpTransportTest {
    private static final String SECRET = "test-only-meshy-secret";
    private static final String TASK_ID = "018a210d-8ba4-705c-b111-1f1776f7f578";

    @Test
    void submitUsesFourJsonDataUrisAndReturnsTaskId() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.meshy.ai");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.meshy.ai/openapi/v1/multi-image-to-3d"))
                .andExpect(method(HttpMethod.POST)).andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + SECRET))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string(containsString("\"ai_model\":\"meshy-7.1\"")))
                .andExpect(content().string(containsString("\"target_formats\":[\"glb\"]")))
                .andExpect(content().string(not(containsString("private prompt"))))
                .andExpect(content().string(containsString("\"image_urls\":[\"data:image/png;base64,AQID\",\"data:image/png;base64,AQID\",\"data:image/png;base64,AQID\",\"data:image/png;base64,AQID\"]")))
                .andRespond(withSuccess("{\"result\":\"" + TASK_ID + "\"}", MediaType.APPLICATION_JSON));
        assertThat(new HttpMeshyTransport(builder.build(), settings()).submit(request())).isEqualTo(TASK_ID);
        server.verify();
    }

    @Test
    void olderMeshiGeometryModelsDisableUnsupportedDefaultTexturing() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.meshy.ai");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.meshy.ai/openapi/v1/multi-image-to-3d"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("\"ai_model\":\"meshy-6\"")))
                .andExpect(content().string(containsString("\"should_texture\":false")))
                .andRespond(withSuccess("{\"result\":\"" + TASK_ID + "\"}", MediaType.APPLICATION_JSON));
        var modelSettings = settings(); modelSettings.setModel("meshy-6");
        assertThat(new HttpMeshyTransport(builder.build(), modelSettings).submit(request())).isEqualTo(TASK_ID);
        server.verify();
    }

    @Test
    void mapsTaskStatusesAndUnknownValuesToUnknown() {
        assertStatus("PENDING", MeshyModelProvider.TaskStatus.PENDING);
        assertStatus("IN_PROGRESS", MeshyModelProvider.TaskStatus.PROCESSING);
        assertStatus("SUCCEEDED", MeshyModelProvider.TaskStatus.SUCCEEDED);
        assertStatus("FAILED", MeshyModelProvider.TaskStatus.FAILED);
        assertStatus("CANCELED", MeshyModelProvider.TaskStatus.UNKNOWN);
        assertStatus("FUTURE_VALUE", MeshyModelProvider.TaskStatus.UNKNOWN);
    }

    @Test
    void resultDownloadsGlbWithoutForwardingAuthorization() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.meshy.ai");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        byte[] glb = {0x67, 0x6c, 0x54, 0x46, 2, 0, 0, 0, 12, 0, 0, 0};
        server.expect(requestTo("https://api.meshy.ai/openapi/v1/multi-image-to-3d/" + TASK_ID))
                .andExpect(method(HttpMethod.GET)).andRespond(withSuccess(taskBody("SUCCEEDED",
                        "https://assets.meshy.ai/tasks/test/output/model.glb?Expires=private"), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://assets.meshy.ai/tasks/test/output/model.glb?Expires=private"))
                .andExpect(method(HttpMethod.GET)).andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andRespond(withSuccess(glb, MediaType.parseMediaType("model/gltf-binary")));
        var model = new HttpMeshyTransport(builder.build(), settings()).result(TASK_ID);
        server.verify();
        assertThat(model.mimeType()).isEqualTo("model/gltf-binary");
        assertThat(model.content()).isEqualTo(glb);
    }

    @Test
    void submitTimeoutIsUnknownAndNeverRetried() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.meshy.ai");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.meshy.ai/openapi/v1/multi-image-to-3d"))
                .andExpect(method(HttpMethod.POST)).andRespond(request -> { throw new java.io.IOException(SECRET); });
        assertThatThrownBy(() -> new HttpMeshyTransport(builder.build(), settings()).submit(request()))
                .isInstanceOf(DesignProviderCallException.class).hasMessage("MESHY_SUBMIT_OUTCOME_UNKNOWN")
                .hasMessageNotContaining(SECRET);
        server.verify();
    }

    @Test
    void transientPollFailureIsSafeReadRetry() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.meshy.ai");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.meshy.ai/openapi/v1/multi-image-to-3d/" + TASK_ID))
                .andExpect(method(HttpMethod.GET)).andRespond(request -> { throw new java.io.IOException(SECRET); });
        assertThatThrownBy(() -> new HttpMeshyTransport(builder.build(), settings()).status(TASK_ID))
                .isInstanceOf(DesignProviderReadRetryException.class).hasMessage("MESHY_STATUS_READ_RETRY")
                .hasMessageNotContaining(SECRET);
        server.verify();
    }

    @Test
    void resultRejectsNonAllowlistedOrInsecureAssetUrl() {
        for (String url : List.of("http://assets.meshy.ai/model.glb", "https://evil.example/model.glb",
                "https://assets.meshy.ai.evil.example/model.glb")) {
            RestClient.Builder builder = RestClient.builder().baseUrl("https://api.meshy.ai");
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo("https://api.meshy.ai/openapi/v1/multi-image-to-3d/" + TASK_ID))
                    .andExpect(method(HttpMethod.GET)).andRespond(withSuccess(taskBody("SUCCEEDED", url), MediaType.APPLICATION_JSON));
            assertThatThrownBy(() -> new HttpMeshyTransport(builder.build(), settings()).result(TASK_ID))
                    .isInstanceOf(DesignProviderCallException.class).hasMessage("MESHY_RESULT_URL_INVALID")
                    .hasMessageNotContaining(url);
            server.verify();
        }
    }

    private static void assertStatus(String status, MeshyModelProvider.TaskStatus expected) {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.meshy.ai");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.meshy.ai/openapi/v1/multi-image-to-3d/" + TASK_ID))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(taskBody(status, null), MediaType.APPLICATION_JSON));
        assertThat(new HttpMeshyTransport(builder.build(), settings()).status(TASK_ID)).isEqualTo(expected);
        server.verify();
    }

    private static String taskBody(String status, String url) {
        return "{\"id\":\"" + TASK_ID + "\",\"status\":\"" + status + "\",\"model_urls\":{\"glb\":"
                + (url == null ? "null" : "\"" + url + "\"") + "}}";
    }

    private static MeshyModelProvider.ModelRequest request() {
        List<MeshyModelProvider.ApprovedReference> views = List.of("front", "right", "back", "left").stream()
                .map(slot -> new MeshyModelProvider.ApprovedReference(slot, "dsa2:1:" + UUID.randomUUID() + ":" + UUID.randomUUID(),
                        "a".repeat(64), new byte[]{1, 2, 3})).toList();
        return new MeshyModelProvider.ModelRequest(UUID.randomUUID(), "key", "private prompt", views, null);
    }
    private static DesignProviderProperties.Meshy settings() {
        var value = new DesignProviderProperties.Meshy(); value.setEnabled(true); value.setApiKey(SECRET);
        value.setBaseUrl("https://api.meshy.ai"); value.setModel("meshy-7.1"); return value;
    }
    private static org.springframework.test.web.client.RequestMatcher headerDoesNotExist(String name) {
        return request -> assertThat(request.getHeaders().get(name)).isNullOrEmpty();
    }
}
