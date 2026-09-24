package com.nicko.customer.customer;

import com.nicko.customer.repository.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CustomerApiTests {
    @Autowired MockMvc mvc;
    @Autowired com.nicko.customer.controller.CustomerController controller;
    @Autowired com.nicko.customer.service.CustomerService service;
    @Autowired com.nicko.customer.config.SecurityErrorHandler securityErrors;

    @Test
    void loggingProxiesAreActive() {
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(controller)).isTrue();
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(service)).isTrue();
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(securityErrors)).isTrue();
    }

    @Autowired
    CustomerRepository repository;
    @MockitoBean JwtDecoder decoder;

    private static final String BODY = """
            {"firstName":"Jane","lastName":"Doe","dateOfBirth":"1995-05-12",
             "nationality":"KE","preferredLanguage":"en"}
            """;

    @Test
    void registersAndRetrievesOnlyTheAuthenticatedCustomer() throws Exception {
        UUID user = UUID.randomUUID();
        mvc.perform(post("/api/v1/customers").with(jwt().jwt(token -> token.subject(user.toString())))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/api/v1/customers/me"))
                .andExpect(jsonPath("$.customerStatus").value("PENDING"))
                .andExpect(jsonPath("$.kycStatus").value("NOT_STARTED"))
                .andExpect(jsonPath("$.kycTier").value("TIER_0"))
                .andExpect(jsonPath("$.walletEligible").value(false))
                .andExpect(jsonPath("$.keycloakUserId").doesNotExist());
        Customer stored = repository.findByKeycloakUserId(user).orElseThrow();
        assertThat(stored.getCreatedAt()).isNotNull();
        assertThat(stored.getVersion()).isZero();
        assertThat(repository.findByCustomerNumber(stored.getCustomerNumber())).isPresent();
        mvc.perform(get("/api/v1/customers/me").with(jwt().jwt(token -> token.subject(user.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(stored.getId().toString()));
        mvc.perform(get("/api/v1/customers/me").with(jwt().jwt(token -> token.subject(UUID.randomUUID().toString()))))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsDuplicateRegistration() throws Exception {
        String user = UUID.randomUUID().toString();
        mvc.perform(post("/api/v1/customers").with(jwt().jwt(token -> token.subject(user)))
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/customers").with(jwt().jwt(token -> token.subject(user)))
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isConflict());
    }

    @Test
    void databaseEnforcesUniqueUserEvenIfTheApplicationPrecheckIsBypassed() throws Exception {
        UUID user = UUID.randomUUID();
        mvc.perform(post("/api/v1/customers").with(jwt().jwt(token -> token.subject(user.toString())))
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isCreated());
        Customer original = repository.findByKeycloakUserId(user).orElseThrow();
        Customer duplicate = new Customer();
        duplicate.setKeycloakUserId(user);
        duplicate.setCustomerNumber("CUS-" + UUID.randomUUID());
        duplicate.setFirstName(original.getFirstName());
        duplicate.setLastName(original.getLastName());
        duplicate.setDateOfBirth(original.getDateOfBirth());
        duplicate.setNationality(original.getNationality());
        duplicate.setPreferredLanguage(original.getPreferredLanguage());
        duplicate.setCustomerStatus(original.getCustomerStatus());
        duplicate.setKycStatus(original.getKycStatus());
        duplicate.setKycTier(original.getKycTier());
        assertThatThrownBy(() -> repository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsInvalidInputWithoutPersisting() throws Exception {
        UUID user = UUID.randomUUID();
        mvc.perform(post("/api/v1/customers").with(jwt().jwt(token -> token.subject(user.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.replace("Jane", " ").replace("1995-05-12", "2995-05-12").replace("KE", "KEN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.firstName").exists())
                .andExpect(jsonPath("$.errors.dateOfBirth").exists())
                .andExpect(jsonPath("$.errors.nationality").exists());
        assertThat(repository.existsByKeycloakUserId(user)).isFalse();
    }

    @Test
    void ignoresClientSuppliedIdentityAndEligibility() throws Exception {
        UUID user = UUID.randomUUID();
        UUID forgedUser = UUID.randomUUID();
        String forged = BODY.replace("\"firstName\"", "\"keycloakUserId\":\"" + forgedUser
                + "\",\"walletEligible\":true,\"customerStatus\":\"ACTIVE\",\"firstName\"");
        mvc.perform(post("/api/v1/customers").with(jwt().jwt(token -> token.subject(user.toString())))
                        .contentType(MediaType.APPLICATION_JSON).content(forged))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.walletEligible").value(false))
                .andExpect(jsonPath("$.customerStatus").value("PENDING"));
        assertThat(repository.existsByKeycloakUserId(user)).isTrue();
        assertThat(repository.existsByKeycloakUserId(forgedUser)).isFalse();
    }

    @Test
    void rejectsMissingOrInvalidAuthentication() throws Exception {
        mvc.perform(get("/api/v1/customers/me")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/customers").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        when(decoder.decode("invalid-token")).thenThrow(new BadJwtException("Invalid token"));
        mvc.perform(get("/api/v1/customers/me").header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/customers/me").with(jwt().jwt(token -> token.subject("not-a-uuid"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsProblemDetailsForMalformedJsonAndUnsupportedMethods() throws Exception {
        mvc.perform(post("/api/v1/customers").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON).content("{invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.instance").value("/api/v1/customers"));
        mvc.perform(put("/api/v1/customers").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405));
    }

    @Test
    void returnsProblemDetailsForSecurityFailures() throws Exception {
        mvc.perform(get("/api/v1/customers/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(401));
        mvc.perform(get("/admin").with(jwt()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(403));
    }
}
