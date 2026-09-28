package com.github.guoyyit.httprouterjava.tests;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "router.snapshot.dir=target/it-snapshots")
@AutoConfigureMockMvc
class RouterControllerIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @BeforeEach
    void reset() throws Exception {
        mvc.perform(put("/router/routes")
                .contentType("application/json")
                .content("{\"routes\":[]}"))
                .andExpect(status().isOk());
    }

    @Test
    void registersThenMatchesStaticRoute() throws Exception {
        mvc.perform(post("/router/routes")
                        .contentType("application/json")
                        .content("""
                                {"handlerId":"users","method":"GET","path":"/users","priority":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").isNumber());

        mvc.perform(get("/router/match").param("method", "GET").param("path", "/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handlerId").value("users"));
    }

    @Test
    void matchExtractsPathVariables() throws Exception {
        mvc.perform(post("/router/routes")
                .contentType("application/json")
                .content("""
                        {"handlerId":"by-id","method":"GET","path":"/users/{id:[0-9]+}","priority":0}
                        """)).andExpect(status().isOk());

        mvc.perform(get("/router/match")
                        .param("method", "GET").param("path", "/users/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handlerId").value("by-id"))
                .andExpect(jsonPath("$.pathVariables.id").value("42"));
    }

    @Test
    void headerConditionsAreEvaluated() throws Exception {
        mvc.perform(post("/router/routes")
                .contentType("application/json")
                .content("""
                        {"handlerId":"v1","method":"GET","path":"/x","priority":0,
                         "headers":[{"name":"X-Version","type":"EXACT","value":"v1"}]}
                        """)).andExpect(status().isOk());

        mvc.perform(get("/router/match")
                        .param("method", "GET").param("path", "/x")
                        .header("X-Version", "v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handlerId").value("v1"));

        mvc.perform(get("/router/match")
                        .param("method", "GET").param("path", "/x"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NO_MATCH"));
    }

    @Test
    void noMatchMethodNotAllowedAndConflictAreDistinct() throws Exception {
        mvc.perform(post("/router/routes")
                .contentType("application/json")
                .content("""
                        {"handlerId":"only-post","method":"POST","path":"/items","priority":0}
                        """)).andExpect(status().isOk());

        mvc.perform(get("/router/match").param("method", "GET").param("path", "/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NO_MATCH"));

        mvc.perform(get("/router/match").param("method", "GET").param("path", "/items"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.details[0]").value("POST"));
    }

    @Test
    void conflictingRegistrationIsRejectedAndOldTableKept() throws Exception {
        mvc.perform(post("/router/routes")
                .contentType("application/json")
                .content("""
                        {"handlerId":"a","method":"GET","path":"/users/{id}","priority":0}
                        """)).andExpect(status().isOk());

        mvc.perform(post("/router/routes")
                .contentType("application/json")
                .content("""
                        {"handlerId":"b","method":"GET","path":"/users/{name}","priority":0}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));

        // Old table remains authoritative.
        mvc.perform(get("/router/match")
                        .param("method", "GET").param("path", "/users/9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handlerId").value("a"));
    }

    @Test
    void invalidRouteReturnsBadRequest() throws Exception {
        mvc.perform(post("/router/routes")
                        .contentType("application/json")
                        .content("""
                                {"handlerId":"","method":"GET","path":"/x","priority":0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_ROUTE"));
    }

    @Test
    void differentPrioritiesResolveOverlap() throws Exception {
        mvc.perform(post("/router/routes")
                .contentType("application/json")
                .content("""
                        {"handlerId":"low","method":"GET","path":"/r","priority":0}
                        """)).andExpect(status().isOk());
        mvc.perform(post("/router/routes")
                .contentType("application/json")
                .content("""
                        {"handlerId":"high","method":"GET","path":"/r","priority":10}
                        """)).andExpect(status().isOk());

        mvc.perform(get("/router/match").param("method", "GET").param("path", "/r"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handlerId").value("high"));
    }

    @Test
    void removeAndRollbackLifecycle() throws Exception {
        // Reset creates version N; the add below creates N+1, removal N+2,
        // so capture the "add" version and roll back to it.
        String addResponse = mvc.perform(post("/router/routes")
                        .contentType("application/json")
                        .content("""
                                {"handlerId":"a","method":"GET","path":"/a","priority":0}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long addVersion = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(addResponse).get("version").asLong();

        mvc.perform(delete("/router/routes/a")).andExpect(status().isOk());
        mvc.perform(get("/router/match").param("method", "GET").param("path", "/a"))
                .andExpect(status().isNotFound());

        mvc.perform(post("/router/rollback/" + addVersion)).andExpect(status().isOk());
        mvc.perform(get("/router/match").param("method", "GET").param("path", "/a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handlerId").value("a"));
    }
}
