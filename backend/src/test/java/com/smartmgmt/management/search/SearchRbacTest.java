package com.smartmgmt.management.search;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.smartmgmt.MockMvcSecurityConfiguration;
import com.smartmgmt.TestcontainersConfiguration;
import com.smartmgmt.management.document.Document;
import com.smartmgmt.management.document.DocumentRepository;
import com.smartmgmt.management.document.DocumentStatus;
import com.smartmgmt.management.project.ProjectRepository;

/**
 * The important half of Phase 7: search is the first endpoint that returns rows
 * the caller never named, so the ownership rule has to hold inside the query.
 *
 * <p>Driven through real logins and Bearer tokens so an anonymous request is
 * genuinely anonymous.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MockMvcSecurityConfiguration.class})
class SearchRbacTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String SECRET_BODY = "Internal quarterly forecast for the acquisition of Northwind.";

    @TempDir
    static Path storageDir;

    @Autowired
    MockMvc mvc;

    @Autowired
    DocumentRepository documents;

    @Autowired
    ProjectRepository projects;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.storage.documents-dir", () -> storageDir.toString());
    }

    @AfterEach
    void cleanUp() {
        documents.deleteAll();
        projects.deleteAll();
    }

    private String tokenFor(String email, String password) throws Exception {
        String login = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(login, "$.accessToken");
    }

    private String createProject(String managerToken, String ownerId) throws Exception {
        String body = ownerId == null
                ? "{\"name\":\"Search rbac project\"}"
                : "{\"name\":\"Search rbac project\",\"ownerId\":\"%s\"}".formatted(ownerId);
        String created = mvc.perform(post("/api/projects")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(created, "$.id");
    }

    private void uploadAndAwaitReady(String token, String projectId, String name, String content) throws Exception {
        MockMultipartFile file =
                new MockMultipartFile("file", name, "text/plain", content.getBytes(StandardCharsets.UTF_8));
        var request = multipart("/api/documents").file(file).header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (projectId != null) {
            request = request.param("projectId", projectId);
        }
        String uploaded = mvc.perform(request)
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        UUID documentId = UUID.fromString(JsonPath.read(uploaded, "$.id"));
        org.awaitility.Awaitility.await().atMost(TIMEOUT).until(() -> documents.findById(documentId)
                .map(Document::getStatus)
                .orElse(null) == DocumentStatus.READY);
    }

    private String userId(String token) throws Exception {
        String me = mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(me, "$.id");
    }

    @Test
    void anonymousCannotSearch() throws Exception {
        mvc.perform(get("/api/search").param("q", "forecast"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aUserDoesNotSeeChunksFromSomeoneElsesProject() throws Exception {
        String managerToken = tokenFor("manager@local", "manager123");
        String userToken = tokenFor("user@local", "user123");

        String othersProject = createProject(managerToken, null);
        uploadAndAwaitReady(managerToken, othersProject, "forecast.txt", SECRET_BODY);

        // Absent, not 403: telling a USER that a matching document exists but is
        // off-limits is an existence oracle for other people's data.
        mvc.perform(get("/api/search").param("q", "forecast").param("mode", "KEYWORD")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content").isEmpty());

        mvc.perform(get("/api/search").param("q", "forecast").param("mode", "KEYWORD")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void aUserDoesSeeChunksFromAProjectTheyOwn() throws Exception {
        String managerToken = tokenFor("manager@local", "manager123");
        String userToken = tokenFor("user@local", "user123");

        String ownedProject = createProject(managerToken, userId(userToken));
        uploadAndAwaitReady(userToken, ownedProject, "mine.txt", "Notes about the migration schedule.");

        mvc.perform(get("/api/search").param("q", "migration").param("mode", "KEYWORD")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].documentName").value("mine.txt"));
    }

    @Test
    void aProjectLessGlobalDocumentIsInvisibleToAUser() throws Exception {
        String managerToken = tokenFor("manager@local", "manager123");
        String userToken = tokenFor("user@local", "user123");

        uploadAndAwaitReady(managerToken, null, "global.txt", "Company-wide travel reimbursement policy.");

        mvc.perform(get("/api/search").param("q", "reimbursement").param("mode", "KEYWORD")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        mvc.perform(get("/api/search").param("q", "reimbursement").param("mode", "KEYWORD")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void filteringByAProjectTheCallerCannotSeeReturnsNothing() throws Exception {
        String managerToken = tokenFor("manager@local", "manager123");
        String userToken = tokenFor("user@local", "user123");

        String othersProject = createProject(managerToken, null);
        uploadAndAwaitReady(managerToken, othersProject, "forecast.txt", SECRET_BODY);

        mvc.perform(get("/api/search").param("q", "forecast").param("mode", "KEYWORD")
                        .param("projectId", othersProject)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }
}
