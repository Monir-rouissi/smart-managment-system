package com.smartmgmt.management.customer;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.smartmgmt.IntegrationTest;

@WithUserDetails("manager@local")
class CustomerControllerTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void createsListsAndDeletesCustomer() throws Exception {
        String body = """
                {"name":"Acme Corp","email":"ops@acme.test","company":"Acme"}
                """;

        String created = mvc.perform(post("/api/customers")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("Acme Corp"))
                .andReturn().getResponse().getContentAsString();

        String id = JsonPath.read(created, "$.id");

        mvc.perform(get("/api/customers").param("q", "acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.totalElements").value(1));

        mvc.perform(get("/api/customers/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.company").value("Acme"));

        mvc.perform(delete("/api/customers/{id}", id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/customers/{id}", id)).andExpect(status().isNotFound());
    }

    @Test
    void rejectsBlankName() throws Exception {
        mvc.perform(post("/api/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void unknownIdReturns404() throws Exception {
        mvc.perform(get("/api/customers/{id}", "00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound());
    }
}
