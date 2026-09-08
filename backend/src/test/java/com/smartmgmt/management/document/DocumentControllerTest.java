package com.smartmgmt.management.document;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.smartmgmt.IntegrationTest;

/** Happy-path coverage, acting as a manager throughout (see DocumentRbacTest for role checks). */
@WithUserDetails("manager@local")
class DocumentControllerTest extends IntegrationTest {

    @TempDir
    static Path storageDir;

    @Autowired
    MockMvc mvc;

    @DynamicPropertySource
    static void storageProps(DynamicPropertyRegistry registry) {
        registry.add("app.storage.documents-dir", () -> storageDir.toString());
    }

    private String createProject() throws Exception {
        String created = mvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Doc test project\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(created, "$.id");
    }

    @Test
    void uploadPersistsAndIsDownloadable() throws Exception {
        String projectId = createProject();

        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.txt", MediaType.TEXT_PLAIN_VALUE, "hello world".getBytes());

        String uploaded = mvc.perform(multipart("/api/documents")
                        .file(file)
                        .param("projectId", projectId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andExpect(jsonPath("$.name").value("notes.txt"))
                .andExpect(jsonPath("$.mimeType").value("text/plain"))
                .andReturn().getResponse().getContentAsString();
        String documentId = JsonPath.read(uploaded, "$.id");

        mvc.perform(get("/api/documents/{id}", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(projectId));

        mvc.perform(get("/api/documents/{id}/download", documentId))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"notes.txt\""))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().bytes("hello world".getBytes()));

        mvc.perform(get("/api/projects/{id}/documents", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(documentId));
    }

    @Test
    void rejectsUnsupportedFileType() throws Exception {
        String projectId = createProject();
        MockMultipartFile file = new MockMultipartFile(
                "file", "virus.exe", "application/octet-stream", "bad".getBytes());

        mvc.perform(multipart("/api/documents")
                        .file(file)
                        .param("projectId", projectId))
                .andExpect(status().isBadRequest());
    }

    @Test
    void globalUploadWithNoProjectIsAllowedForManager() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "handbook.pdf", MediaType.APPLICATION_PDF_VALUE, "%PDF-1.4 fake".getBytes());

        mvc.perform(multipart("/api/documents").file(file))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.projectId").value(org.hamcrest.Matchers.nullValue()));
    }
}
