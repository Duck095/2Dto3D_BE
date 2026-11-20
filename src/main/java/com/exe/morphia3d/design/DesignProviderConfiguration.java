package com.exe.morphia3d.design;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration
@EnableConfigurationProperties(DesignProviderProperties.class)
public class DesignProviderConfiguration {
    @Bean @ConditionalOnProperty(prefix = "ai-studio.providers.gpt-image", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(GptImageTransport.class)
    GptImageTransport configuredGptImageTransport(DesignProviderProperties properties) {
        DesignProviderProperties.GptImage settings = properties.getGptImage();
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(settings.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(settings.getResponseTimeout());
        RestClient client = RestClient.builder().baseUrl(settings.getBaseUrl()).requestFactory(factory).build();
        return new HttpGptImageTransport(client, settings);
    }

    @Bean @ConditionalOnProperty(prefix = "ai-studio.providers.meshy", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(MeshyTransport.class)
    MeshyTransport configuredMeshyTransport(DesignProviderProperties properties) {
        DesignProviderProperties.Meshy settings = properties.getMeshy();
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(settings.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
        org.springframework.http.client.JdkClientHttpRequestFactory factory =
                new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(settings.getResponseTimeout());
        RestClient client = RestClient.builder().baseUrl(settings.getBaseUrl()).requestFactory(factory).build();
        return new HttpMeshyTransport(client, settings);
    }

    @Bean @ConditionalOnMissingBean(GptImageTransport.class)
    GptImageTransport unavailableGptImageTransport() { return new UnavailableGptImageTransport(); }
    @Bean @ConditionalOnMissingBean(MeshyTransport.class)
    MeshyTransport unavailableMeshyTransport() { return new UnavailableMeshyTransport(); }
    @Bean @ConditionalOnMissingBean(ReferenceImageProvider.class)
    ReferenceImageProvider gptImageProvider(GptImageTransport transport) { return new GptImageReferenceProviderAdapter(transport); }
    @Bean @ConditionalOnMissingBean(MeshyModelProvider.class)
    MeshyModelProvider meshyModelProvider(MeshyTransport transport) { return new MeshyModelProviderAdapter(transport); }
}
