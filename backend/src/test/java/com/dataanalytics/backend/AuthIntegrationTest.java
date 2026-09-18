package com.dataanalytics.backend;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AuthIntegrationTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void register_thenLogin_returnsJwtToken() throws Exception {
        // --- Register ---
        String registerBody = "{\"email\":\"alice@example.com\",\"password\":\"secret123\",\"fullName\":\"Alice\"}";
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.fullName").value("Alice"))
                .andExpect(jsonPath("$.role").value("USER"));

        // --- Login with the same credentials ---
        String loginBody = "{\"email\":\"alice@example.com\",\"password\":\"secret123\"}";
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.email").value("alice@example.com"));
    }

    @Test
    void register_duplicateEmail_returnsConflict() throws Exception {
        String body = "{\"email\":\"bob@example.com\",\"password\":\"password123\",\"fullName\":\"Bob\"}";
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        // Second registration with same email
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void login_invalidCredentials_returnsUnauthorized() throws Exception {
        // Register a user first
        String body = "{\"email\":\"carol@example.com\",\"password\":\"correctpass\",\"fullName\":\"Carol\"}";
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        // Login with wrong password
        String loginBody = "{\"email\":\"carol@example.com\",\"password\":\"wrongpass\"}";
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void openApiDocs_areAvailableWithoutAuth() throws Exception {
        // springdoc 3.x + Boot 4.1 + Jackson 3: generating the full API doc
        // (including the paginated Page<> schemas) must boot cleanly.
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("InsightHub API"))
                .andExpect(jsonPath("$['paths']['/api/projects']['get']['parameters'][*]['name']",
                        org.hamcrest.Matchers.hasItem("pageable")));
    }

    @Test
    void swaggerUi_isAvailableWithoutAuth() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }

    @Test
    void createProject_thenList_returnsPaginatedEnvelope() throws Exception {
        String registerBody =
                "{\"email\":\"dave@example.com\",\"password\":\"secret123\",\"fullName\":\"Dave\"}";
        String content = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.parse(content).read("$.token", String.class);

        mockMvc.perform(post("/api/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Sales analysis\",\"description\":\"Q1 report\"}"))
                .andExpect(status().isCreated());

        // List is a Spring Data Page envelope: content + totalElements etc.
        mockMvc.perform(get("/api/projects")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Sales analysis"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));

        // Custom page size is honored.
        mockMvc.perform(get("/api/projects?size=1&page=0")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.pageable.pageSize").value(1));
    }
}
