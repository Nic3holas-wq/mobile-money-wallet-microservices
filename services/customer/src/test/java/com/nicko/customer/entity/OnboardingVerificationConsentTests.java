package com.nicko.customer.entity;

import com.nicko.customer.dto.*;
import com.nicko.customer.repository.*;
import com.nicko.customer.service.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "app.verification.hmac-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@AutoConfigureMockMvc
@Transactional
class OnboardingVerificationConsentTests {
    static final String ME = "/api/v1/customers/me";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ContactVerificationRepository challenges;
    @Autowired CustomerContactRepository contacts;
    @Autowired ContactVerificationService verification;
    @Autowired PlatformTransactionManager transactions;
    @Autowired CustomerRepository customers;
    @Autowired CustomerConsentRepository consents;
    @Autowired CustomerService customerService;
    @Autowired CustomerContactService contactService;
    @Autowired EntityManager em;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean VerificationDelivery delivery;
    @MockitoBean Clock clock;
    private Instant now;
    private UUID user;
    private final Map<UUID, String> codes = new ConcurrentHashMap<>();

    @BeforeEach
    void setup(org.junit.jupiter.api.TestInfo testInfo) throws Exception {
        now = Instant.parse("2026-09-25T12:00:00Z");
        when(clock.instant()).thenAnswer(i -> now);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        doAnswer(i -> { codes.put(i.getArgument(0), i.getArgument(3)); return null; })
                .when(delivery).send(any(), any(), anyString(), anyString());
        user = UUID.randomUUID();
        if (!testInfo.getTestMethod().orElseThrow().getName().equals("deliveryFailureRollsBackChallengeAndBusinessTransaction")) { register(user, "1990-01-01", 201); }
    }
    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, UUID actor) {
        return request.with(jwt().jwt(t -> t.subject(actor.toString())));
    }
    private void register(UUID actor, String birth, int expected) throws Exception {
        mvc.perform(as(post("/api/v1/customers"), actor).contentType(MediaType.APPLICATION_JSON)
                .content("{\"firstName\":\"Test\",\"lastName\":\"Customer\",\"dateOfBirth\":\"" + birth
                        + "\",\"nationality\":\"KE\",\"preferredLanguage\":\"en\"}"))
                .andExpect(status().is(expected));
    }
    private UUID contact(boolean primary) throws Exception {
        var result = mvc.perform(as(post(ME + "/contacts"), user).contentType(MediaType.APPLICATION_JSON)
                .content("{\"contactType\":\"EMAIL\",\"contactValue\":\"" + UUID.randomUUID() + "@example.com\",\"primary\":" + primary + "}"))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(json.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }
    private String challengePath(UUID contact) { return ME + "/contacts/" + contact + "/verification-challenges"; }
    private UUID challenge(UUID contact) throws Exception {
        var result = mvc.perform(as(post(challengePath(contact)), user)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").doesNotExist()).andReturn();
        return UUID.fromString(json.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }
    private void confirm(UUID contact, UUID challenge, String code, UUID actor, int status) throws Exception {
        mvc.perform(as(post(challengePath(contact) + "/" + challenge + "/confirmation"), actor)
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().is(status));
    }
    @Test
    void enforcesAgeBoundaryAndAllowlistedProfilePatch() throws Exception {
        register(UUID.randomUUID(), "2008-09-25", 201);
        register(UUID.randomUUID(), "2008-09-26", 400);
        mvc.perform(as(get(ME), user)).andExpect(jsonPath("$.customerNumber").value(org.hamcrest.Matchers.matchesPattern("CUS-2026-[0-9]{6,}")));
        mvc.perform(as(patch(ME), user).contentType(MediaType.APPLICATION_JSON)
                .content("{\"preferredName\":\" Nick \",\"preferredLanguage\":\"sw\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.preferredName").value("Nick"));
        mvc.perform(as(patch(ME), user).contentType(MediaType.APPLICATION_JSON).content("{\"firstName\":\"Changed\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(patch(ME), user).contentType(MediaType.APPLICATION_JSON).content("{\"preferredLanguage\":\" \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(get(ME), user)).andExpect(jsonPath("$.firstName").value("Test"));
    }
    @Test
    void normalizesPhonesWithExplicitRegionAndChecksCanonicalDuplicates() throws Exception {
        String digits = "071" + String.format("%07d", Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 10000000L));
        String body = "{\"contactType\":\"PHONE\",\"contactValue\":\"" + digits + "\",\"phoneRegion\":\"KE\",\"primary\":true}";
        mvc.perform(as(post(ME + "/contacts"), user).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.contactValue").value("+254" + digits.substring(1)));
        mvc.perform(as(post(ME + "/contacts"), user).contentType(MediaType.APPLICATION_JSON)
                .content(body.replace(digits, "+254 " + digits.substring(1))))
                .andExpect(status().isConflict());
    }
    @Test
    void verificationBindsOwnershipValueAndIsSingleUse() throws Exception {
        UUID contact = contact(false), challenge = challenge(contact);
        var stored = challenges.findById(challenge).orElseThrow();
        assertThat(stored.getCodeHash()).hasSize(64).isNotEqualTo(codes.get(challenge));
        UUID other = UUID.randomUUID(); register(other, "1990-01-01", 201);
        confirm(contact, challenge, codes.get(challenge), other, 404);
        mvc.perform(as(get(challengePath(contact) + "/" + challenge), user))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
        confirm(contact, challenge, codes.get(challenge), user, 200);
        confirm(contact, challenge, codes.get(challenge), user, 400);
        assertThat(contacts.findById(contact).orElseThrow().isVerified()).isTrue();
    }
    @Test
    void failedAttemptsPersistAndExhaustChallengeWithoutRollingBackTransaction() throws Exception {
        UUID contact = contact(false), challenge = challenge(contact);
        String wrong = codes.get(challenge).equals("000000") ? "000001" : "000000";
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            for (int i = 0; i < 5; i++) {
                assertThatThrownBy(() -> verification.confirm(user, contact, challenge, new ConfirmContactRequest(wrong)))
                        .isInstanceOf(VerificationRejectedException.class);
            }
            assertThat(tx.isRollbackOnly()).isFalse();
        });
        em.flush(); em.clear();
        assertThat(challenges.findById(challenge).orElseThrow().getAttempts()).isEqualTo(5);
        confirm(contact, challenge, codes.get(challenge), user, 400);
        assertThat(contacts.findById(contact).orElseThrow().isVerified()).isFalse();
    }
    @Test
    void expiryResendAndAccountRateLimits() throws Exception {
        UUID contact = contact(false), first = challenge(contact);
        mvc.perform(as(post(challengePath(contact)), user)).andExpect(status().isTooManyRequests());
        now = now.plusSeconds(60);
        UUID second = challenge(contact);
        confirm(contact, first, codes.get(first), user, 400);
        now = now.plusSeconds(300);
        confirm(contact, second, codes.get(second), user, 400);
        mvc.perform(as(delete(ME + "/contacts/" + contact), user)).andExpect(status().isNoContent());
        for (int i = 0; i < 3; i++) { challenge(contact(false)); }
        mvc.perform(as(post(challengePath(contact(false))), user)).andExpect(status().isTooManyRequests());
    }
    @Test
    void replacingVerifiedPrimaryRequiresVerificationAndPreservesOldContact() throws Exception {
        UUID old = contact(true), first = challenge(old);
        confirm(old, first, codes.get(first), user, 200);
        UUID replacement = contact(false);
        var replacementContact = contacts.findById(replacement).orElseThrow();
        String body = "{\"contactType\":\"EMAIL\",\"contactValue\":\"" + replacementContact.getContactValue() + "\",\"primary\":true}";
        mvc.perform(as(put(ME + "/contacts/" + replacement), user).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
        assertThat(contacts.findById(old).orElseThrow().isPrimary()).isTrue();
        UUID code = challenge(replacement);
        confirm(replacement, code, codes.get(code), user, 200);
        mvc.perform(as(put(ME + "/contacts/" + replacement), user).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.primary").value(true));
        assertThat(contacts.findById(old).orElseThrow().isVerified()).isTrue();
        assertThat(contacts.findById(old).orElseThrow().isPrimary()).isFalse();
        mvc.perform(as(delete(ME + "/contacts/" + old), user)).andExpect(status().isNoContent());
    }
    @Test
    void consentVersionsCurrentStateAndMandatoryWithdrawalUpdateCompletion() throws Exception {
        mvc.perform(as(get(ME + "/consents/policies"), user)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
        mvc.perform(as(post(ME + "/consents"), user).contentType(MediaType.APPLICATION_JSON)
                .content("{\"consentType\":\"TERMS_AND_CONDITIONS\",\"documentVersion\":\"invalid\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.documentVersion").exists());
        UUID terms = null;
        for (String type : List.of("TERMS_AND_CONDITIONS", "PRIVACY_POLICY")) {
            var result = mvc.perform(as(post(ME + "/consents"), user).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"consentType\":\"" + type + "\",\"documentVersion\":\"v1\"}"))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.channel").value("API")).andReturn();
            if (type.equals("TERMS_AND_CONDITIONS")) { terms = UUID.fromString(json.readTree(result.getResponse().getContentAsString()).get("id").asText()); }
        }
        mvc.perform(as(get(ME + "/completion"), user)).andExpect(status().isOk())
                .andExpect(jsonPath("$.outstandingSteps").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("ACCEPT_REQUIRED_POLICIES"))));
        var customer = customers.findByKeycloakUserId(user).orElseThrow();
        customer.setWalletEligible(true); customers.saveAndFlush(customer);
        mvc.perform(as(post(ME + "/consents/" + terms + "/withdrawal"), user)).andExpect(status().isOk());
        assertThat(customer.isWalletEligible()).isFalse();
        assertThat(consents.findById(terms).orElseThrow().isAccepted()).isTrue();
        mvc.perform(as(get(ME + "/completion"), user))
                .andExpect(jsonPath("$.outstandingSteps").value(org.hamcrest.Matchers.hasItem("ACCEPT_REQUIRED_POLICIES")));
    }
    @Test
    void changingUnverifiedValueInvalidatesPreviouslyIssuedCode() throws Exception {
        UUID contact = contact(false), code = challenge(contact);
        mvc.perform(as(put(ME + "/contacts/" + contact), user).contentType(MediaType.APPLICATION_JSON)
                .content("{\"contactType\":\"EMAIL\",\"contactValue\":\"" + UUID.randomUUID() + "@example.com\",\"primary\":false}"))
                .andExpect(status().isOk());
        confirm(contact, code, codes.get(code), user, 400);
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void deliveryFailureRollsBackChallengeAndBusinessTransaction() {
        UUID actor = UUID.randomUUID();
        java.util.concurrent.atomic.AtomicReference<UUID> challengeId = new java.util.concurrent.atomic.AtomicReference<>();
        doAnswer(i -> {
            challengeId.set(i.getArgument(0));
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
        }).when(delivery).send(any(), any(), anyString(), anyString());
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            customerService.register(actor, new RegisterCustomerRequest("Test", null, "Rollback", LocalDate.of(1990, 1, 1), null, "KE", "en"));
            var contact = contactService.create(actor, new CustomerContactRequest(com.nicko.customer.entity.enums.ContactType.EMAIL,
                    UUID.randomUUID() + "@example.com", false, null));
            verification.request(actor, contact.id());
        })).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(customers.existsByKeycloakUserId(actor)).isFalse();
        assertThat(challengeId.get()).isNotNull();
        assertThat(challenges.existsById(challengeId.get())).isFalse();
    }

}
