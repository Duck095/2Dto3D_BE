package com.exe.morphia3d.controller;

import com.exe.morphia3d.Morphia3dApplication;
import com.exe.morphia3d.entity.AiGenerationJob;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.AiGenerationStatus;
import com.exe.morphia3d.enums.Role;
import com.exe.morphia3d.enums.UserStatus;
import com.exe.morphia3d.repository.AiGenerationJobRepository;
import com.exe.morphia3d.repository.UserRepository;
import com.exe.morphia3d.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = Morphia3dApplication.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "app.features.ai.enabled=true")
@Transactional
class AiGenerationControllerSecurityIntegrationTest {
    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository users;
    @Autowired private AiGenerationJobRepository jobs;
    private MockMvc mvc;
    private User owner;
    private User other;
    private User designer;
    private UUID pendingJob;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        owner = saveUser(Role.USER, "owner");
        other = saveUser(Role.USER, "other");
        designer = saveUser(Role.DESIGNER, "designer");
        pendingJob = jobs.saveAndFlush(AiGenerationJob.builder().id(UUID.randomUUID()).user(owner)
                .idempotencyKey("security-test-" + UUID.randomUUID()).requestHash("b".repeat(64))
                .prompt("TEST_ONLY prompt").style("TEST_ONLY style").quality("TEST_ONLY quality")
                .inputMode("text").status(AiGenerationStatus.PENDING).attemptCount(0).build()).getId();
    }

    @Test
    void anonymousIsUnauthorizedAndCustomerCanReadOnlyOwnHistory() throws Exception {
        mvc.perform(get("/api/ai-studio/generations/history"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/ai-studio/generations/history").with(authenticate(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(pendingJob.toString()));
    }

    @Test
    void anotherCustomerCannotReadJobOrResultAndPendingResultIsUnavailable() throws Exception {
        mvc.perform(get("/api/ai-studio/generations/{id}", pendingJob).with(authenticate(other)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AI_GENERATION_NOT_FOUND"));

        mvc.perform(get("/api/ai-studio/generations/{id}/result", pendingJob).with(authenticate(owner)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AI_RESULT_NOT_READY"));
    }

    @Test
    void designerRoleIsDeniedEvenWhenAuthenticated() throws Exception {
        mvc.perform(get("/api/ai-studio/generations/history").with(authenticate(designer)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AI_ACCESS_DENIED"));
    }

    @Test
    void missingProviderReturnsStructuredUnavailableAndDoesNotCreateAJob() throws Exception {
        long before = jobs.count();
        MockMultipartFile data = new MockMultipartFile("data", "", "application/json",
                "{\"prompt\":\"a small lamp\",\"style\":\"modern\",\"inputMode\":\"text\"}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        mvc.perform(multipart("/api/ai-studio/generations").file(data)
                        .header("Idempotency-Key", "provider-not-ready-" + UUID.randomUUID())
                        .with(authenticate(owner)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_PROVIDER_NOT_CONFIGURED"))
                .andExpect(jsonPath("$.accepted").value(false));
        org.assertj.core.api.Assertions.assertThat(jobs.count()).isEqualTo(before);
    }

    private User saveUser(Role role, String suffix) {
        return users.saveAndFlush(User.builder().name("AI security " + suffix)
                .email("ai-security-" + suffix + "-" + UUID.randomUUID() + "@example.invalid")
                .password("TEST_ONLY").role(role).status(UserStatus.ACTIVE).build());
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor authenticate(User user) {
        UserPrincipal principal = new UserPrincipal(user);
        return authentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }
}
