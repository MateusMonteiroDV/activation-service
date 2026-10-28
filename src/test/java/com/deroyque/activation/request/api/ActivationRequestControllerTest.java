package com.deroyque.activation.request.api;

import com.deroyque.activation.request.application.ActivationRequestNotFoundException;
import com.deroyque.activation.request.application.ActivationRequestService;
import com.deroyque.activation.request.application.CreateActivationCommand;
import com.deroyque.activation.request.application.CreateOutcome;
import com.deroyque.activation.request.application.DuplicateExternalIdException;
import com.deroyque.activation.request.domain.ActivationRequest;
import com.deroyque.activation.shared.error.GlobalExceptionHandler;
import com.deroyque.activation.shared.security.SecurityConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ActivationRequestController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class ActivationRequestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ActivationRequestService service;

    @MockBean
    private JwtDecoder jwtDecoder;

    private final Instant now = Instant.parse("2026-10-26T14:03:11Z");

    @Test
    void createReturns201WithLocationHeaderWhenCreated() throws Exception {
        var request = ActivationRequest.create("EXT-123", "CUST-1", "PROD-1", "client-app", "corr-1", now);
        when(service.create(any(), eq("client-app"))).thenReturn(new CreateOutcome.Created(request));

        var body = new CreateActivationRequest("EXT-123", "CUST-1", "PROD-1");

        mockMvc.perform(post("/api/v1/requests")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_activation:write"))
                                .jwt(j -> j.claim("client_id", "client-app")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/requests/" + request.getId())))
                .andExpect(jsonPath("$.id", is(request.getId().toString())))
                .andExpect(jsonPath("$.externalId", is("EXT-123")))
                .andExpect(jsonPath("$.status", is("PENDING")));
    }

    @Test
    void createReturns200WhenDuplicateRetryLegitimate() throws Exception {
        var existing = ActivationRequest.create("EXT-123", "CUST-1", "PROD-1", "client-app", "corr-1", now);
        when(service.create(any(), eq("client-app"))).thenReturn(new CreateOutcome.AlreadyExists(existing));

        var body = new CreateActivationRequest("EXT-123", "CUST-1", "PROD-1");

        mockMvc.perform(post("/api/v1/requests")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_activation:write"))
                                .jwt(j -> j.claim("client_id", "client-app")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(existing.getId().toString())))
                .andExpect(jsonPath("$.externalId", is("EXT-123")));
    }

    @Test
    void createReturns400ProblemDetailWhenInvalidInput() throws Exception {
        var body = new CreateActivationRequest("", "invalid spaces customer", "P".repeat(70));

        mockMvc.perform(post("/api/v1/requests")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_activation:write"))
                                .jwt(j -> j.claim("client_id", "client-app")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title", is("Dados inválidos")))
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.errors", notNullValue()));
    }

    @Test
    void createReturns409WhenDuplicateExternalIdWithDifferentContent() throws Exception {
        when(service.create(any(), eq("client-app"))).thenThrow(new DuplicateExternalIdException("EXT-123"));

        var body = new CreateActivationRequest("EXT-123", "CUST-1", "PROD-1");

        mockMvc.perform(post("/api/v1/requests")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_activation:write"))
                                .jwt(j -> j.claim("client_id", "client-app")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title", is("Solicitação duplicada")))
                .andExpect(jsonPath("$.status", is(409)));
    }

    @Test
    void createReturns401WhenNoTokenProvided() throws Exception {
        var body = new CreateActivationRequest("EXT-123", "CUST-1", "PROD-1");

        mockMvc.perform(post("/api/v1/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createReturns403WhenMissingWriteScope() throws Exception {
        var body = new CreateActivationRequest("EXT-123", "CUST-1", "PROD-1");

        mockMvc.perform(post("/api/v1/requests")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_activation:read"))
                                .jwt(j -> j.claim("client_id", "client-app")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isForbidden());
    }

    @Test
    void findByIdReturns200WhenFound() throws Exception {
        var id = UUID.randomUUID();
        var request = ActivationRequest.create("EXT-123", "CUST-1", "PROD-1", "client-app", "corr-1", now);
        when(service.findById(id, "client-app")).thenReturn(request);

        mockMvc.perform(get("/api/v1/requests/{id}", id)
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_activation:read"))
                                .jwt(j -> j.claim("client_id", "client-app"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalId", is("EXT-123")));
    }

    @Test
    void findByIdReturns404ProblemDetailWhenNotFound() throws Exception {
        var id = UUID.randomUUID();
        when(service.findById(id, "client-app")).thenThrow(new ActivationRequestNotFoundException(id));

        mockMvc.perform(get("/api/v1/requests/{id}", id)
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_activation:read"))
                                .jwt(j -> j.claim("client_id", "client-app"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title", is("Solicitação não encontrada")))
                .andExpect(jsonPath("$.status", is(404)));
    }

    @Test
    void findByIdReturns403WhenMissingReadScope() throws Exception {
        var id = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/requests/{id}", id)
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_other"))
                                .jwt(j -> j.claim("client_id", "client-app"))))
                .andExpect(status().isForbidden());
    }
}
