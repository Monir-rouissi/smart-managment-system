package com.smartmgmt.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.smartmgmt.IntegrationTest;

/** Exercises the "Done when" criteria from the JWT + RBAC milestone. */
class RbacTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    private String accessTokenFor(String email, String password) throws Exception {
        String login = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(login, "$.accessToken");
    }

    @Test
    void userCannotCreateProjectButManagerCan() throws Exception {
        String userToken = accessTokenFor("user@local", "user123");
        mvc.perform(post("/api/projects")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Should be blocked"}
                                """))
                .andExpect(status().isForbidden());

        String managerToken = accessTokenFor("manager@local", "manager123");
        mvc.perform(post("/api/projects")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Manager can create this"}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    void anonymousRequestIsRejected() throws Exception {
        mvc.perform(get("/api/projects")).andExpect(status().isUnauthorized());
    }

    @Test
    void sortByUnknownFieldIsRejected() throws Exception {
        String managerToken = accessTokenFor("manager@local", "manager123");
        mvc.perform(get("/api/projects")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + managerToken)
                        .param("sort", "owner.passwordHash,asc"))
                .andExpect(status().isBadRequest());
    }
}
