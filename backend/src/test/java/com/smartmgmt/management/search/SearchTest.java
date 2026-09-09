package com.smartmgmt.management.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithUserDetails;
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
 * Search over really-ingested chunks.
 *
 * <p>Not {@code @Transactional} (so not {@code IntegrationTest}): ingestion runs
 * after the upload commits, so a rolled-back test would index nothing. Rows
 * therefore survive each test and are cleaned up explicitly.
 *
 * <p>Embeddings come from the offline {@code HashEmbeddingClient}. That is why
 * nothing here asserts that semantic mode returns *relevant* results — over hash
 * vectors it cannot. Semantic assertions are limited to shape and status; the
 * meaning of semantic search is only testable with a real key (see
 * steps/phase6.md).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MockMvcSecurityConfiguration.class})
@WithUserDetails("manager@local")
class SearchTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String BODY =
            "This agreement covers subscription termination and the refund window that follows it. "
            + "Either party may end the arrangement with thirty days of written notice.";

    @TempDir
    static Path storageDir;

    @Autowired
    MockMvc mvc;

    @Autowired
    DocumentRepository documents;

    @Autowired
    ProjectRepository projects;

    private UUID projectId;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.storage.documents-dir", () -> storageDir.toString());
    }

    @AfterEach
    void cleanUp() {
        documents.deleteAll();
        if (projectId != null) {
            projects.deleteById(projectId);
            projectId = null;
        }
    }

    private UUID uploadAndAwaitReady(String name, String content) throws Exception {
        String project = mvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Search project\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        projectId = UUID.fromString(JsonPath.read(project, "$.id"));

        MockMultipartFile file =
                new MockMultipartFile("file", name, "text/plain", content.getBytes(StandardCharsets.UTF_8));
        String uploaded = mvc.perform(multipart("/api/documents")
                        .file(file)
                        .param("projectId", projectId.toString()))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        UUID documentId = UUID.fromString(JsonPath.read(uploaded, "$.id"));
        await().atMost(TIMEOUT).until(() -> documents.findById(documentId)
                .map(Document::getStatus)
                .orElse(null) == DocumentStatus.READY);
        return documentId;
    }

    @Test
    void keywordSearchFindsTheChunkAndMarksTheMatchedTerm() throws Exception {
        uploadAndAwaitReady("contract.txt", BODY);

        String response = mvc.perform(get("/api/search").param("q", "termination").param("mode", "KEYWORD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].documentName").value("contract.txt"))
                .andExpect(jsonPath("$.content[0].match").value("KEYWORD"))
                .andReturn().getResponse().getContentAsString();

        // Highlights are inert markers, not HTML: the chunk text came from an
        // uploaded file and is never handed to the browser as markup.
        String snippet = JsonPath.read(response, "$.content[0].snippet");
        assertThat(snippet).contains("[[HL]]").contains("[[/HL]]");
        assertThat(snippet).doesNotContain("<b>").doesNotContain("<mark>");
    }

    @Test
    void stemmingMatchesARelatedFormOfTheWord() throws Exception {
        uploadAndAwaitReady("contract.txt", BODY);

        // 'english' config stems both sides: "terminate" reaches "termination".
        mvc.perform(get("/api/search").param("q", "terminate").param("mode", "KEYWORD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void aDocumentIsAlsoFoundByItsName() throws Exception {
        uploadAndAwaitReady("cancellation-policy.txt", "Unrelated body text about invoices.");

        mvc.perform(get("/api/search").param("q", "cancellation").param("mode", "KEYWORD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void aMalformedQueryIsAnEmptyResultNotAServerError() throws Exception {
        uploadAndAwaitReady("contract.txt", BODY);

        // to_tsquery would throw on this; websearch_to_tsquery must not.
        mvc.perform(get("/api/search").param("q", "foo & | \"bar").param("mode", "KEYWORD"))
                .andExpect(status().isOk());
    }

    @Test
    void aQueryShorterThanTwoCharactersIsRejected() throws Exception {
        mvc.perform(get("/api/search").param("q", "a")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/search").param("q", "   ")).andExpect(status().isBadRequest());
    }

    @Test
    void clientChosenSortIsRejectedBecauseResultsAreRanked() throws Exception {
        mvc.perform(get("/api/search").param("q", "termination").param("sort", "createdAt,desc"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deepPagingIsRefusedRatherThanServedMeaninglessly() throws Exception {
        mvc.perform(get("/api/search").param("q", "termination").param("page", "50").param("size", "20"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void semanticAndHybridModesRespond() throws Exception {
        uploadAndAwaitReady("contract.txt", BODY);

        // Shape only. With offline hash embeddings, asserting that these results are
        // *relevant* would be asserting a property of SHA-256.
        mvc.perform(get("/api/search").param("q", "ending a subscription").param("mode", "SEMANTIC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());

        mvc.perform(get("/api/search").param("q", "termination").param("mode", "HYBRID"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].documentName").value("contract.txt"));
    }

    @Test
    void chunksOfADocumentThatIsNoLongerReadyAreNotSearchable() throws Exception {
        UUID documentId = uploadAndAwaitReady("contract.txt", BODY);

        mvc.perform(get("/api/search").param("q", "termination").param("mode", "KEYWORD"))
                .andExpect(jsonPath("$.totalElements").value(1));

        // Chunks outlive a failed re-ingest; only READY is a coherent state to search.
        Document document = documents.findById(documentId).orElseThrow();
        document.setStatus(DocumentStatus.FAILED);
        documents.save(document);

        mvc.perform(get("/api/search").param("q", "termination").param("mode", "KEYWORD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }
}
