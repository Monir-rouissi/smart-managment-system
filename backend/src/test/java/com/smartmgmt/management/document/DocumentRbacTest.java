package com.smartmgmt.management.document;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.smartmgmt.IntegrationTest;

/**
 * Role/ownership checks, driven through real login + Bearer tokens (no
 * @WithUserDetails) so a genuinely anonymous request is exercised too.
 */
class DocumentRbacTest extends IntegrationTest {

    @TempDir
    static Path storageDir;

    @Autowired
    MockMvc mvc;

    @DynamicPropertySource
    static void storageProps(DynamicPropertyRegistry registry) {
        registry.add("app.storage.documents-dir", () -> storageDir.toString());
    }

    private String accessTokenFor(String email, String password) throws Exception {
        String login = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(login, "$.accessToken");
    }

    private String createProject(String managerToken, String ownerId) throws Exception {
        String body = ownerId == null
                ? "{\"name\":\"Doc rbac project\"}"
                : "{\"name\":\"Doc rbac project\",\"ownerId\":\"%s\"}".formatted(ownerId);
        String created = mvc.perform(post("/api/projects")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(created, "$.id");
    }

    @Test
    void userCanUploadOnlyToOwnedProjectAndNotWithNoProject() throws Exception {
        String managerToken = accessTokenFor("manager@local", "manager123");
        String userToken = accessTokenFor("user@local", "user123");

        String me = mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andReturn().getResponse().getContentAsString();
        String userId = JsonPath.read(me, "$.id");

        String ownedProjectId = createProject(managerToken, userId);
        String otherProjectId = createProject(managerToken, null);

        MockMultipartFile file = new MockMultipartFile("file", "notes.md", "text/markdown", "# hi".getBytes());

        mvc.perform(multipart("/api/documents")
                        .file(file)
                        .param("projectId", ownedProjectId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isAccepted());

        mvc.perform(multipart("/api/documents")
                        .file(file)
                        .param("projectId", otherProjectId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isForbidden());

        mvc.perform(multipart("/api/documents")
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void onlyManagersAndAdminsCanTriggerReprocessing() throws Exception {
        String managerToken = accessTokenFor("manager@local", "manager123");
        String userToken = accessTokenFor("user@local", "user123");

        String me = mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andReturn().getResponse().getContentAsString();
        String ownedProjectId = createProject(managerToken, JsonPath.read(me, "$.id"));

        MockMultipartFile file = new MockMultipartFile("file", "notes.md", "text/markdown", "# hi".getBytes());
        String uploaded = mvc.perform(multipart("/api/documents")
                        .file(file)
                        .param("projectId", ownedProjectId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String documentId = JsonPath.read(uploaded, "$.id");

        // Even on a document they uploaded themselves: re-ingesting costs embedding
        // calls, so it is not a USER-triggerable action.
        mvc.perform(post("/api/documents/{id}/reprocess", documentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/documents/{id}/reprocess", documentId))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/documents/{id}/reprocess", documentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + managerToken))
                .andExpect(status().isAccepted());
    }

    @Test
    void anonymousUploadIsRejected() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.txt", MediaType.TEXT_PLAIN_VALUE, "hello".getBytes());
        mvc.perform(multipart("/api/documents").file(file))
                .andExpect(status().isUnauthorized());
    }
}
