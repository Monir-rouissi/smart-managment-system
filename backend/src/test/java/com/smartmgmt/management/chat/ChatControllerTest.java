package com.smartmgmt.management.chat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.smartmgmt.MockMvcSecurityConfiguration;
import com.smartmgmt.TestcontainersConfiguration;
import com.smartmgmt.management.project.ProjectRepository;

/**
 * What is actually verifiable here without a real LLM/embedding key: access
 * control on conversations, persistence shape, and the "no relevant context ->
 * refuse" path. No document in this suite ever has real embeddings (the default
 * {@code HashEmbeddingClient} produces vectors with no semantic content), so
 * every retrieval here legitimately finds nothing -- which is exactly the
 * "I don't know." path. It does NOT exercise a real grounded answer, or
 * {@code ChatCompletionClient} at all: that needs a live GEMINI_API_KEY, same
 * blocker steps/phase7.md left open for item 33's semantic-search claim. See
 * that file before assuming this suite proves the RAG loop actually grounds
 * an answer -- it only proves the loop around it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MockMvcSecurityConfiguration.class})
class ChatControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ChatMessageRepository messages;

    @Autowired
    ChatConversationRepository conversations;

    @Autowired
    ProjectRepository projects;

    @AfterEach
    void cleanUp() {
        conversations.deleteAll(); // cascades to chat_message
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

    private String userId(String token) throws Exception {
        String me = mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(me, "$.id");
    }

    private String createProject(String managerToken, String ownerId) throws Exception {
        String body = ownerId == null
                ? "{\"name\":\"Chat rbac project\"}"
                : "{\"name\":\"Chat rbac project\",\"ownerId\":\"%s\"}".formatted(ownerId);
        String created = mvc.perform(post("/api/projects")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(created, "$.id");
    }

    @Test
    void anonymousCannotChat() throws Exception {
        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"What is the policy?\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousCannotListConversations() throws Exception {
        mvc.perform(get("/api/chat/conversations")).andExpect(status().isUnauthorized());
    }

    @Test
    void withNoMatchingDocumentsTheAssistantRefusesRatherThanGuessing() throws Exception {
        String userToken = tokenFor("user@local", "user123");

        String response = mvc.perform(post("/api/chat")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"What is the customer cancellation policy?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").exists())
                .andExpect(jsonPath("$.message.role").value("ASSISTANT"))
                .andExpect(jsonPath("$.message.content").value("I don't know."))
                .andExpect(jsonPath("$.message.sources").isEmpty())
                .andReturn().getResponse().getContentAsString();

        String conversationId = JsonPath.read(response, "$.conversationId");
        // Both turns persisted, not just the answer returned to the caller.
        mvc.perform(get("/api/chat/conversations/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].role").value("USER"))
                .andExpect(jsonPath("$[0].content").value("What is the customer cancellation policy?"))
                .andExpect(jsonPath("$[1].role").value("ASSISTANT"));
    }

    @Test
    void aSecondMessageContinuesTheSameConversationWhenIdIsPassed() throws Exception {
        String userToken = tokenFor("user@local", "user123");

        String first = mvc.perform(post("/api/chat")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"First question\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String conversationId = JsonPath.read(first, "$.conversationId");

        mvc.perform(post("/api/chat")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"%s\",\"message\":\"Follow up question\"}"
                                .formatted(conversationId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value(conversationId));

        mvc.perform(get("/api/chat/conversations/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4));
    }

    @Test
    void aUserCannotOpenSomeoneElsesConversation() throws Exception {
        String userToken = tokenFor("user@local", "user123");
        String managerToken = tokenFor("manager@local", "manager123");

        String created = mvc.perform(post("/api/chat")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Private question\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String conversationId = JsonPath.read(created, "$.conversationId");

        // 404, not 403: matches search's "absent, not forbidden" rule -- this
        // endpoint must not confirm that a conversation id belongs to someone.
        mvc.perform(get("/api/chat/conversations/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + managerToken))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/chat")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"%s\",\"message\":\"Hijack attempt\"}"
                                .formatted(conversationId)))
                .andExpect(status().isNotFound());
    }

    @Test
    void aUserMayStartAConversationScopedToAProjectTheyOwn() throws Exception {
        String managerToken = tokenFor("manager@local", "manager123");
        String userToken = tokenFor("user@local", "user123");
        String ownedProject = createProject(managerToken, userId(userToken));

        mvc.perform(post("/api/chat")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Project question\",\"projectId\":\"%s\"}".formatted(ownedProject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").exists());
    }

    @Test
    void aUserMayNotStartAConversationScopedToAProjectTheyDoNotOwn() throws Exception {
        String managerToken = tokenFor("manager@local", "manager123");
        String userToken = tokenFor("user@local", "user123");
        String othersProject = createProject(managerToken, null);

        mvc.perform(post("/api/chat")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Snooping\",\"projectId\":\"%s\"}".formatted(othersProject)))
                .andExpect(status().isForbidden());
    }

    @Test
    void blankMessageIsRejected() throws Exception {
        String userToken = tokenFor("user@local", "user123");

        mvc.perform(post("/api/chat")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listConversationsReturnsOnlyTheCallersOwn() throws Exception {
        String userToken = tokenFor("user@local", "user123");
        String managerToken = tokenFor("manager@local", "manager123");

        mvc.perform(post("/api/chat")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"User's own question\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/chat")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Manager's own question\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/chat/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("User's own question"));
    }
}
