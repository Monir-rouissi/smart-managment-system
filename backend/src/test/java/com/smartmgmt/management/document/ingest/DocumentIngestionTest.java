package com.smartmgmt.management.document.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
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

/**
 * End-to-end ingestion: upload -> 202 -> background extract/chunk/embed -> READY.
 *
 * <p>Deliberately NOT {@code @Transactional} (so it does not extend
 * {@code IntegrationTest}): the pipeline is triggered after the upload
 * transaction commits, and a test that rolls back would never fire it. That also
 * means rows survive each test, hence the explicit cleanup.
 *
 * <p>Embeddings come from the offline {@code HashEmbeddingClient} -- no API key,
 * no network.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MockMvcSecurityConfiguration.class})
@WithUserDetails("manager@local")
class DocumentIngestionTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @TempDir
    static Path storageDir;

    @Autowired
    MockMvc mvc;

    @Autowired
    DocumentRepository documents;

    @Autowired
    DocumentChunkRepository chunks;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.storage.documents-dir", () -> storageDir.toString());
        // Small windows so a modest test file still produces several chunks.
        registry.add("app.ingest.chunk-tokens", () -> 120);
        registry.add("app.ingest.overlap-tokens", () -> 20);
    }

    @AfterEach
    void cleanUp() {
        documents.deleteAll();
    }

    @Test
    void textDocumentIsChunkedEmbeddedAndMarkedReady() throws Exception {
        UUID id = upload("handbook.txt", MediaType.TEXT_PLAIN_VALUE, longText().getBytes(StandardCharsets.UTF_8));

        Document ready = awaitStatus(id, DocumentStatus.READY);
        assertThat(ready.getChunkCount()).isGreaterThan(1);
        assertThat(ready.getEmbeddingModel()).isEqualTo("offline-hash-1536");
        assertThat(ready.getProcessedAt()).isNotNull();
        assertThat(ready.getErrorMessage()).isNull();

        List<DocumentChunk> stored = chunks.findByDocumentIdOrderByChunkIndexAsc(id);
        assertThat(stored).hasSize(ready.getChunkCount());
        assertThat(stored).allSatisfy(chunk -> {
            assertThat(chunk.getEmbedding()).isNotNull().hasSize(1536);
            assertThat(chunk.getContent()).isNotBlank();
            assertThat(chunk.getTokenCount()).isPositive();
        });
        assertThat(stored.stream().map(DocumentChunk::getChunkIndex).toList())
                .isEqualTo(IntStream.range(0, stored.size()).boxed().toList());
    }

    @Test
    void statusAndChunkCountAreVisibleOnTheApi() throws Exception {
        UUID id = upload("handbook.txt", MediaType.TEXT_PLAIN_VALUE, longText().getBytes(StandardCharsets.UTF_8));
        awaitStatus(id, DocumentStatus.READY);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/documents/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.embeddingModel").value("offline-hash-1536"))
                .andExpect(jsonPath("$.chunkCount").value(org.hamcrest.Matchers.greaterThan(1)))
                .andExpect(jsonPath("$.errorMessage").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void pdfChunksCarryThePageTheyCameFrom() throws Exception {
        UUID id = upload("report.pdf", MediaType.APPLICATION_PDF_VALUE, twoPagePdf());

        awaitStatus(id, DocumentStatus.READY);

        List<String> labels = chunks.findByDocumentIdOrderByChunkIndexAsc(id).stream()
                .map(DocumentChunk::getPageOrSection)
                .toList();
        assertThat(labels).isNotEmpty();
        assertThat(labels).contains("p. 1");
        assertThat(labels).anySatisfy(label -> assertThat(label).contains("2"));
    }

    @Test
    void unreadableFileEndsUpFailedWithAnExplanation() throws Exception {
        UUID id = upload("broken.pdf", MediaType.APPLICATION_PDF_VALUE, "%PDF-1.4 not really a pdf".getBytes());

        Document failed = awaitStatus(id, DocumentStatus.FAILED);
        assertThat(failed.getErrorMessage()).isNotBlank();
        assertThat(failed.getRetryCount()).isEqualTo(1);
        assertThat(failed.getChunkCount()).isZero();
        assertThat(chunks.countByDocumentId(id)).isZero();
    }

    @Test
    void reprocessReplacesChunksRatherThanAppending() throws Exception {
        UUID id = upload("handbook.txt", MediaType.TEXT_PLAIN_VALUE, longText().getBytes(StandardCharsets.UTF_8));
        int firstCount = awaitStatus(id, DocumentStatus.READY).getChunkCount();

        mvc.perform(post("/api/documents/{id}/reprocess", id))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("UPLOADED"));

        await().atMost(TIMEOUT).untilAsserted(() -> {
            Document reloaded = documents.findById(id).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(DocumentStatus.READY);
            assertThat(reloaded.getProcessedAt()).isNotNull();
        });

        assertThat(documents.findById(id).orElseThrow().getChunkCount()).isEqualTo(firstCount);
        assertThat(chunks.countByDocumentId(id)).isEqualTo(firstCount);
    }

    @Test
    void deletingADocumentRemovesItsChunks() throws Exception {
        UUID id = upload("handbook.txt", MediaType.TEXT_PLAIN_VALUE, longText().getBytes(StandardCharsets.UTF_8));
        awaitStatus(id, DocumentStatus.READY);
        assertThat(chunks.countByDocumentId(id)).isPositive();

        documents.deleteById(id);

        assertThat(chunks.countByDocumentId(id)).isZero();
    }

    private UUID upload(String filename, String contentType, byte[] content) throws Exception {
        String body = mvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", filename, contentType, content)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private Document awaitStatus(UUID id, DocumentStatus expected) {
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(documents.findById(id).orElseThrow().getStatus()).isEqualTo(expected));
        return documents.findById(id).orElseThrow();
    }

    private String longText() {
        return IntStream.range(0, 1200).mapToObj(i -> "clause" + i).collect(Collectors.joining(" "));
    }

    private byte[] twoPagePdf() throws Exception {
        try (PDDocument pdf = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int page = 1; page <= 2; page++) {
                PDPage pdPage = new PDPage();
                pdf.addPage(pdPage);
                try (PDPageContentStream content = new PDPageContentStream(pdf, pdPage)) {
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    for (int line = 0; line < 30; line++) {
                        content.beginText();
                        content.newLineAtOffset(50, 750 - line * 20);
                        content.showText("Page " + page + " line " + line
                                + " lorem ipsum dolor sit amet consectetur adipiscing elit");
                        content.endText();
                    }
                }
            }
            pdf.save(out);
            return out.toByteArray();
        }
    }
}
