package com.github.guoyyit.httprouterjava.tests;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class RouterApiIntegrationTest {

    @Autowired
    private MockMvc mvc;

    /** Remove all routes visible at the start of a test (singleton context is shared). */
    @BeforeEach
    void cleanTable() throws Exception {
        var ids = new java.util.ArrayList<String>();
        mvc.perform(get("/router/routes")).andDo(result -> {
            var node = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(result.getResponse().getContentAsString());
            node.forEach(r -> ids.add(r.get("id").asText()));
        });
        if (!ids.isEmpty()) {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var node = mapper.createObjectNode();
            node.set("removals", mapper.valueToTree(ids));
            node.set("additions", mapper.createArrayNode());
            mvc.perform(post("/router/routes/batch")
                    .contentType("application/json").content(mapper.writeValueAsString(node)))
                    .andExpect(status().isNoContent());
        }
    }

    @Test
    void registerMatchAndDeleteThroughHttp() throws Exception {
        String body = """
                {"id":"users","method":"GET","pattern":"/users/{id}","handlerId":"UsersHandler","priority":0}
                """;
        mvc.perform(post("/router/routes").contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("users"));

        mvc.perform(post("/router/match").contentType("application/json")
                        .content("""
                                {"method":"GET","path":"/users/42","headers":{}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HIT"))
                .andExpect(jsonPath("$.handlerId").value("UsersHandler"))
                .andExpect(jsonPath("$.routeId").value("users"));

        mvc.perform(delete("/router/routes/users"))
                .andExpect(status().isNoContent());

        mvc.perform(post("/router/match").contentType("application/json")
                        .content("""
                                {"method":"GET","path":"/users/42","headers":{}}
                                """))
                .andExpect(jsonPath("$.status").value("NO_MATCH"));
    }

    @Test
    void noMatchMethodMismatchAndMissingPathAreDistinguishable() throws Exception {
        mvc.perform(post("/router/routes").contentType("application/json")
                .content("""
                        {"id":"r","method":"POST","pattern":"/x","handlerId":"H"}
                        """)).andExpect(status().isCreated());

        mvc.perform(post("/router/match").contentType("application/json")
                        .content("""
                                {"method":"GET","path":"/x","headers":{}}
                                """))
                .andExpect(jsonPath("$.status").value("NO_MATCH"))
                .andExpect(jsonPath("$.reason").value(org.hamcrest.Matchers.containsString("POST")));

        mvc.perform(post("/router/match").contentType("application/json")
                        .content("""
                                {"method":"POST","path":"/y","headers":{}}
                                """))
                .andExpect(jsonPath("$.status").value("NO_MATCH"));
    }

    @Test
    void duplicateRegistrationReturns409AndKeepsOldTable() throws Exception {
        mvc.perform(post("/router/routes").contentType("application/json")
                .content("""
                        {"id":"a","method":"GET","pattern":"/u/{id}","handlerId":"H1"}
                        """)).andExpect(status().isCreated());
        mvc.perform(post("/router/routes").contentType("application/json")
                        .content("""
                                {"id":"b","method":"GET","pattern":"/u/{name}","handlerId":"H2"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("conflicts")));

        mvc.perform(get("/router/routes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("a"));
    }

    @Test
    void invalidPatternReturns400() throws Exception {
        mvc.perform(post("/router/routes").contentType("application/json")
                        .content("""
                                {"id":"bad","method":"GET","pattern":"no-slash","handlerId":"H"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteUnknownRouteReturns404() throws Exception {
        mvc.perform(delete("/router/routes/ghost"))
                .andExpect(status().isNotFound());
    }

    @Test
    void batchIsAtomicOnConflict() throws Exception {
        String batch = """
                {"additions":[
                  {"id":"ok1","method":"GET","pattern":"/ok/1","handlerId":"H"},
                  {"id":"ok2","method":"GET","pattern":"/ok/2","handlerId":"H"},
                  {"id":"ok3","method":"GET","pattern":"/ok/1","handlerId":"H"}
                ],"removals":[]}
                """;
        mvc.perform(post("/router/routes/batch").contentType("application/json").content(batch))
                .andExpect(status().isConflict());
        mvc.perform(get("/router/routes"))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void headerConditionRouting() throws Exception {
        mvc.perform(post("/router/routes").contentType("application/json")
                .content("""
                        {"id":"v1","method":"GET","pattern":"/api","headers":{"X-Version":"1"},"handlerId":"V1"}
                        """)).andExpect(status().isCreated());
        mvc.perform(post("/router/routes").contentType("application/json")
                .content("""
                        {"id":"v2","method":"GET","pattern":"/api","headers":{"X-Version":"2"},"handlerId":"V2"}
                        """)).andExpect(status().isCreated());

        mvc.perform(post("/router/match").contentType("application/json")
                        .content("""
                                {"method":"GET","path":"/api","headers":{"X-Version":"2"}}
                                """))
                .andExpect(jsonPath("$.handlerId").value("V2"));
        mvc.perform(post("/router/match").contentType("application/json")
                        .content("""
                                {"method":"GET","path":"/api","headers":{"X-Version":"9"}}
                                """))
                .andExpect(jsonPath("$.status").value("NO_MATCH"))
                .andExpect(jsonPath("$.reason").value(org.hamcrest.Matchers.containsString("Header")));
    }
}
