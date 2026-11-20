package com.exe.morphia3d.controller;

import com.exe.morphia3d.Morphia3dApplication;
import com.exe.morphia3d.config.TestOnlyAiGenerationConfiguration;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.Role;
import com.exe.morphia3d.enums.UserStatus;
import com.exe.morphia3d.repository.AiGenerationJobRepository;
import com.exe.morphia3d.repository.UserRepository;
import com.exe.morphia3d.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = Morphia3dApplication.class)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.features.ai.enabled=true",
        "app.ai-generation.enabled=true",
        "app.ai-generation.worker-enabled=true"
})
@Import(TestOnlyAiGenerationConfiguration.class)
@Transactional
class AiGenerationHttpContractIntegrationTest {
    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository users;
    @Autowired private AiGenerationJobRepository jobs;
    private MockMvc mvc;
    private User owner;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        owner = users.saveAndFlush(User.builder().name("TEST_ONLY AI owner")
                .email("ai-http-" + UUID.randomUUID() + "@example.invalid").password("TEST_ONLY")
                .role(Role.USER).status(UserStatus.ACTIVE).build());
    }

    @Test
    void testOnlyProviderAcceptsJobAndOwnerReadsThePersistedPendingState() throws Exception {
        String key = "TEST_ONLY-" + UUID.randomUUID();
        MockMultipartFile data = new MockMultipartFile("data", "", "application/json",
                "{\"prompt\":\"a small lamp\",\"style\":\"modern\",\"inputMode\":\"text\"}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        UserPrincipal principal = new UserPrincipal(owner);

        var response = mvc.perform(multipart("/api/ai-studio/generations").file(data)
                        .header("Idempotency-Key", key).with(authenticate(principal)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.errorCode").doesNotExist())
                .andReturn().getResponse();
        String id = com.jayway.jsonpath.JsonPath.read(response.getContentAsString(), "$.id");
        var created = jobs.findByUser_IdAndIdempotencyKey(owner.getId(), key).orElseThrow();
        assertThat(created.getQuality()).isNull();

        mvc.perform(get("/api/ai-studio/generations/{id}", id).with(authenticate(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor authenticate(UserPrincipal principal) {
        return authentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }
}
