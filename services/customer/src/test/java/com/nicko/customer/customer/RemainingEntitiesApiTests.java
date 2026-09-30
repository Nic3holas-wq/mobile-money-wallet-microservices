package com.nicko.customer.customer;

import com.nicko.customer.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "app.kyc.document-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.kyc.document-hash-key=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="})
@AutoConfigureMockMvc
@Transactional
class RemainingEntitiesApiTests {
    private static final String ME = "/api/v1/customers/me";
    private static final String PROFILE = """
            {"requestedTier":"TIER_1","occupation":"Engineer","sourceOfFunds":"SALARY","expectedMonthlyVolume":10000}
            """;
    private static final String CONSENT = """
            {"consentType":"MARKETING","documentVersion":"v1"}
            """;
    private static final String LIMIT = """
            {"transactionType":"TRANSFER","currency":"KES","perTransactionLimit":1000,
             "dailyLimit":5000,"monthlyLimit":20000,"dailyCountLimit":5,
             "effectiveFrom":"2026-01-01T00:00:00Z","reason":"Approved limits"}
            """;
    private static final String APPROVAL = """
            {"decision":"APPROVED","approvedTier":"TIER_1","pepStatus":"NOT_PEP",
             "sanctionsStatus":"CLEAR","riskRating":"LOW","expiresAt":"2099-01-01T00:00:00Z"}
            """;
    private static final String DOC_APPROVAL = """
            {"decision":"VERIFIED","verificationProvider":"manual-review"}
            """;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired KycDocumentRepository documents;
    @Autowired CustomerRepository customers;
    @Autowired CustomerLimitRepository limits;
    @Autowired OutboxEventRepository outbox;
    @Autowired CustomerAuditRepository audit;
    @Autowired CustomerContactRepository contacts;
    @MockitoBean JwtDecoder decoder;
    private UUID user, other, admin, customerId;

    @BeforeEach
    void register() throws Exception {
        user = UUID.randomUUID(); other = UUID.randomUUID(); admin = UUID.randomUUID();
        for (UUID actor : new UUID[]{user, other}) {
            var result = mvc.perform(as(post("/api/v1/customers"), actor).contentType(MediaType.APPLICATION_JSON).content("""
                    {"firstName":"Test","lastName":"Customer","dateOfBirth":"1990-01-01","nationality":"KE","preferredLanguage":"en"}
                    """)).andExpect(status().isCreated()).andReturn();
            if (actor.equals(user)) { customerId = UUID.fromString(json.readTree(result.getResponse().getContentAsString()).get("id").asText()); }
        }
    }
    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, UUID actor) {
        return request.with(jwt().jwt(token -> token.subject(actor.toString())));
    }
    private MockHttpServletRequestBuilder staff(MockHttpServletRequestBuilder request) {
        return request.with(jwt().jwt(token -> token.subject(admin.toString()))
                .authorities(new SimpleGrantedAuthority("CUSTOMER_ADMIN")));
    }
    private String adminRoot() { return "/api/v1/admin/customers/" + customerId; }
    private String document(String number) {
        return "{\"documentType\":\"NATIONAL_ID\",\"documentNumber\":\"" + number +
                "\",\"issuingCountry\":\"KE\",\"issuedAt\":\"2020-01-01\",\"expiresAt\":\"2099-01-01\",\"frontFileReference\":\"test-file-reference\"}";
    }
    private void createProfile(UUID actor) throws Exception {
        mvc.perform(as(post(ME + "/kyc-profile"), actor).contentType(MediaType.APPLICATION_JSON).content(PROFILE))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("NOT_STARTED"));
    }
    private String addDocument(String number) throws Exception {
        var result = mvc.perform(as(post(ME + "/kyc-profile/documents"), user).contentType(MediaType.APPLICATION_JSON)
                        .content(document(number))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.documentNumber").doesNotExist())
                .andExpect(jsonPath("$.documentNumberHash").doesNotExist())
                .andExpect(jsonPath("$.documentNumberEncrypted").doesNotExist())
                .andExpect(jsonPath("$.verificationStatus").value("PENDING")).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    @Test
    void consentHistoryWithdrawalAndOwnership() throws Exception {
        var result = mvc.perform(as(post(ME + "/consents"), user).contentType(MediaType.APPLICATION_JSON).content(CONSENT))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.accepted").value(true)).andReturn();
        String id = json.readTree(result.getResponse().getContentAsString()).get("id").asText();
        mvc.perform(as(post(ME + "/consents"), user).contentType(MediaType.APPLICATION_JSON).content(CONSENT))
                .andExpect(status().isConflict());
        mvc.perform(as(get(ME + "/consents"), user)).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(as(get(ME + "/consents/" + id), other)).andExpect(status().isNotFound());
        mvc.perform(as(post(ME + "/consents/" + id + "/withdrawal"), other)).andExpect(status().isNotFound());
        for (int i = 0; i < 2; i++) {
            mvc.perform(as(post(ME + "/consents/" + id + "/withdrawal"), user)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.accepted").value(true)).andExpect(jsonPath("$.withdrawnAt").isNotEmpty());
        }
        mvc.perform(as(delete(ME + "/consents/" + id), user)).andExpect(status().isMethodNotAllowed());
        mvc.perform(as(post(ME + "/consents"), user).contentType(MediaType.APPLICATION_JSON).content(CONSENT))
                .andExpect(status().isCreated());
    }

    @Test
    void onlyStaffCanManageLimitsAndCustomersOnlyReadTheirOwn() throws Exception {
        mvc.perform(as(post(adminRoot() + "/limits"), user).contentType(MediaType.APPLICATION_JSON).content(LIMIT))
                .andExpect(status().isForbidden());
        mvc.perform(as(post(ME + "/limits"), user).contentType(MediaType.APPLICATION_JSON).content(LIMIT))
                .andExpect(status().isMethodNotAllowed());
        var result = mvc.perform(staff(post(adminRoot() + "/limits")).contentType(MediaType.APPLICATION_JSON).content(LIMIT))
                .andExpect(status().isCreated()).andReturn();
        String id = json.readTree(result.getResponse().getContentAsString()).get("id").asText();
        assertThat(limits.findById(UUID.fromString(id)).orElseThrow().getCreatedByKeycloakId()).isEqualTo(admin);
        mvc.perform(as(get(ME + "/limits/" + id), user)).andExpect(status().isOk());
        mvc.perform(as(get(ME + "/limits/" + id), other)).andExpect(status().isNotFound());
        mvc.perform(as(get(ME + "/limits"), other)).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(staff(put(adminRoot() + "/limits/" + id)).contentType(MediaType.APPLICATION_JSON).content(LIMIT.replace("1000", "2000")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.perTransactionLimit").value(2000));
        mvc.perform(staff(get(adminRoot() + "/limits"))).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(staff(delete(adminRoot() + "/limits/" + id))).andExpect(status().isBadRequest());
        mvc.perform(staff(delete(adminRoot() + "/limits/" + id)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Replaced by new policy\"}")).andExpect(status().isNoContent());
        var history = audit.findByCustomerId(customerId, org.springframework.data.domain.Pageable.unpaged()).getContent().stream().filter(e -> e.getAction().startsWith("LIMIT_")).toList();
        assertThat(history).hasSize(3).allSatisfy(entry -> assertThat(entry.getActorId()).isEqualTo(admin));
        assertThat(history).anySatisfy(entry -> {
            assertThat(entry.getAction()).isEqualTo("LIMIT_UPDATED");
            assertThat(entry.getBeforeState()).containsEntry("perTransactionLimit", "1000");
            assertThat(entry.getAfterState()).containsEntry("perTransactionLimit", "2000");
        });
        assertThat(history).anySatisfy(entry -> {
            assertThat(entry.getAction()).isEqualTo("LIMIT_DELETED");
            assertThat(entry.getAfterState()).containsEntry("reason", "Replaced by new policy");
        });
        assertThat(outbox.findByCustomerId(customerId, org.springframework.data.domain.Pageable.unpaged()).getContent()).hasSize(4);
        mvc.perform(as(get(ME + "/limits/" + id), user)).andExpect(status().isNotFound());
    }

    @Test
    void documentCrudDuplicateProtectionAndEncryption() throws Exception {
        createProfile(user); createProfile(other);
        String number = "DOC-" + UUID.randomUUID();
        String id = addDocument(number);
        var stored = documents.findById(UUID.fromString(id)).orElseThrow();
        assertThat(stored.getDocumentNumberEncrypted()).startsWith("v1:").doesNotContain(number);
        assertThat(stored.getDocumentNumberHash()).doesNotContain(number);
        mvc.perform(as(post(ME + "/kyc-profile/documents"), other).contentType(MediaType.APPLICATION_JSON)
                .content(document(number.toLowerCase(java.util.Locale.ROOT)))).andExpect(status().isConflict());
        mvc.perform(as(get(ME + "/kyc-profile/documents/" + id), other)).andExpect(status().isNotFound());
        mvc.perform(as(put(ME + "/kyc-profile/documents/" + id), other).contentType(MediaType.APPLICATION_JSON)
                .content(document("OTHER"))).andExpect(status().isNotFound());
        mvc.perform(as(delete(ME + "/kyc-profile/documents/" + id), other)).andExpect(status().isNotFound());
        mvc.perform(as(put(ME + "/kyc-profile/documents/" + id), user).contentType(MediaType.APPLICATION_JSON)
                .content(document(number + "-UPDATED"))).andExpect(status().isOk());
        mvc.perform(as(get(ME + "/kyc-profile/documents"), user)).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(as(delete(ME + "/kyc-profile/documents/" + id), user)).andExpect(status().isNoContent());
        mvc.perform(as(get(ME + "/kyc-profile/documents/" + id), user)).andExpect(status().isNotFound());
    }

    @Test
    void kycWorkflowCreatesOutboxEventsAndKeepsWalletIneligible() throws Exception {
        createProfile(user);
        mvc.perform(as(post(ME + "/kyc-profile"), user).contentType(MediaType.APPLICATION_JSON).content(PROFILE))
                .andExpect(status().isConflict());
        mvc.perform(as(post(ME + "/kyc-profile/submission"), user)).andExpect(status().isConflict());
        String id = addDocument(UUID.randomUUID().toString());
        mvc.perform(as(post(ME + "/kyc-profile/submission"), user)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(as(put(ME + "/kyc-profile"), user).contentType(MediaType.APPLICATION_JSON).content(PROFILE))
                .andExpect(status().isConflict());
        mvc.perform(as(delete(ME + "/kyc-profile/documents/" + id), user)).andExpect(status().isConflict());
        mvc.perform(as(post(adminRoot() + "/kyc-profile/review"), user).contentType(MediaType.APPLICATION_JSON).content(APPROVAL))
                .andExpect(status().isForbidden());
        mvc.perform(staff(post(adminRoot() + "/kyc-profile/review")).contentType(MediaType.APPLICATION_JSON).content(APPROVAL))
                .andExpect(status().isConflict());
        mvc.perform(staff(post(adminRoot() + "/kyc-profile/documents/" + id + "/review"))
                .contentType(MediaType.APPLICATION_JSON).content(DOC_APPROVAL)).andExpect(status().isOk());
        var approved = mvc.perform(staff(post(adminRoot() + "/kyc-profile/review")).contentType(MediaType.APPLICATION_JSON).content(APPROVAL))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED")).andReturn();
        String correlation = approved.getResponse().getHeader("X-Request-ID");
        var entries = audit.findByCustomerId(customerId, org.springframework.data.domain.Pageable.unpaged()).getContent();
        assertThat(entries).hasSize(4);
        assertThat(entries).anySatisfy(entry -> {
            assertThat(entry.getAction()).isEqualTo("KYC_APPROVED");
            assertThat(entry.getCorrelationId().toString()).isEqualTo(correlation);
            assertThat(entry.getActorId()).isEqualTo(admin);
        });
        assertThat(outbox.findByCustomerId(customerId, org.springframework.data.domain.Pageable.unpaged()).getContent())
                .anySatisfy(event -> {
                    assertThat(event.getEventType()).isEqualTo("customer.kyc.verified.v1");
                    assertThat(event.getCorrelationId().toString()).isEqualTo(correlation);
                    assertThat(event.getPayload()).containsEntry("eventId", event.getId().toString())
                            .containsEntry("schemaVersion", 1).containsEntry("correlationId", correlation)
                            .containsKeys("eventType", "occurredAt", "customerId", "data");
                });
        mvc.perform(as(get(ME), user)).andExpect(jsonPath("$.kycStatus").value("APPROVED"))
                .andExpect(jsonPath("$.kycTier").value("TIER_1")).andExpect(jsonPath("$.walletEligible").value(false));
        mvc.perform(staff(get(adminRoot() + "/outbox-events"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].status").value("PENDING"))
                .andExpect(jsonPath("$.content[0].payload").doesNotExist());
        mvc.perform(as(get(adminRoot() + "/outbox-events"), user)).andExpect(status().isForbidden());
        mvc.perform(staff(post(adminRoot() + "/outbox-events")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void staffCannotApproveOwnKycAndRejectedProfileCanBeResubmitted() throws Exception {
        createProfile(user);
        String documentId = addDocument(UUID.randomUUID().toString());
        mvc.perform(as(post(ME + "/kyc-profile/submission"), user)).andExpect(status().isOk());
        UUID staffId = admin; admin = user;
        mvc.perform(staff(post(adminRoot() + "/kyc-profile/review")).contentType(MediaType.APPLICATION_JSON).content(APPROVAL))
                .andExpect(status().isForbidden());
        admin = staffId;
        mvc.perform(staff(post(adminRoot() + "/kyc-profile/documents/" + documentId + "/review"))
                .contentType(MediaType.APPLICATION_JSON).content("""
                {"decision":"REJECTED","verificationProvider":"manual-review","failureReason":"Unreadable evidence"}
                """)).andExpect(status().isOk());
        String rejection = """
                {"decision":"REJECTED","pepStatus":"NOT_SCREENED","sanctionsStatus":"NOT_SCREENED",
                 "riskRating":"HIGH","rejectionReason":"Provide clearer evidence"}
                """;
        mvc.perform(staff(post(adminRoot() + "/kyc-profile/review")).contentType(MediaType.APPLICATION_JSON).content(rejection))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
        mvc.perform(as(put(ME + "/kyc-profile"), user).contentType(MediaType.APPLICATION_JSON).content(PROFILE))
                .andExpect(status().isOk());
        mvc.perform(as(post(ME + "/kyc-profile/submission"), user)).andExpect(status().isOk())
                .andExpect(jsonPath("$.rejectionReason").isEmpty()).andExpect(jsonPath("$.reviewedAt").isEmpty());
        var history = audit.findByCustomerId(customerId, org.springframework.data.domain.Pageable.unpaged()).getContent();
        assertThat(history).hasSize(5);
        assertThat(history).anySatisfy(entry -> {
            assertThat(entry.getAction()).isEqualTo("KYC_REJECTED");
            assertThat(entry.getActorId()).isEqualTo(admin);
            assertThat(entry.getAfterState()).containsEntry("rejectionReason", "Provide clearer evidence");
        });
        assertThat(history).anySatisfy(entry -> {
            assertThat(entry.getAction()).isEqualTo("KYC_DOCUMENT_REJECTED");
            assertThat(entry.getActorId()).isEqualTo(admin);
            assertThat(entry.getOccurredAt()).isNotNull();
            assertThat(entry.getAfterState()).containsEntry("failureReason", "Unreadable evidence");
        });
        mvc.perform(staff(get(adminRoot() + "/audit-records").param("size", "1"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1)).andExpect(jsonPath("$.totalElements").value(5));
        mvc.perform(as(get(adminRoot() + "/audit-records"), user)).andExpect(status().isForbidden());
        mvc.perform(get(adminRoot() + "/audit-records")).andExpect(status().isUnauthorized());
        mvc.perform(staff(get(adminRoot() + "/audit-records").param("size", "101"))).andExpect(status().isBadRequest());
        mvc.perform(staff(delete(adminRoot() + "/audit-records"))).andExpect(status().isMethodNotAllowed());
        assertThat(json.writeValueAsString(history)).doesNotContain("documentNumber", "frontFileReference", "test-file-reference");
    }

    @Test
    void staffActivateOnlyCustomersWhoCompletedOnboarding() throws Exception {
        String activation = adminRoot() + "/activation";
        mvc.perform(as(post(activation), user)).andExpect(status().isForbidden());
        mvc.perform(staff(post("/api/v1/admin/customers/" + UUID.randomUUID() + "/activation"))).andExpect(status().isNotFound());
        mvc.perform(staff(post(activation))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("VERIFY_PRIMARY_PHONE"),
                        org.hamcrest.Matchers.containsString("ACCEPT_REQUIRED_POLICIES"),
                        org.hamcrest.Matchers.containsString("COMPLETE_KYC"))));

        createProfile(user);
        String documentId = addDocument(UUID.randomUUID().toString());
        mvc.perform(as(post(ME + "/kyc-profile/submission"), user)).andExpect(status().isOk());
        mvc.perform(staff(post(adminRoot() + "/kyc-profile/documents/" + documentId + "/review"))
                .contentType(MediaType.APPLICATION_JSON).content(DOC_APPROVAL)).andExpect(status().isOk());
        mvc.perform(staff(post(adminRoot() + "/kyc-profile/review")).contentType(MediaType.APPLICATION_JSON).content(APPROVAL))
                .andExpect(status().isOk());
        for (String type : new String[]{"TERMS_AND_CONDITIONS", "PRIVACY_POLICY"}) {
            mvc.perform(as(post(ME + "/consents"), user).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"consentType\":\"" + type + "\",\"documentVersion\":\"v1\"}")).andExpect(status().isCreated());
        }
        String digits = "071" + String.format("%07d", Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 10000000L));
        var phone = mvc.perform(as(post(ME + "/contacts"), user).contentType(MediaType.APPLICATION_JSON)
                .content("{\"contactType\":\"PHONE\",\"contactValue\":\"" + digits + "\",\"phoneRegion\":\"KE\",\"primary\":true}"))
                .andExpect(status().isCreated()).andReturn();
        mvc.perform(staff(post(activation))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Customer is not ready for activation: VERIFY_PRIMARY_PHONE"));
        var contact = contacts.findById(UUID.fromString(json.readTree(phone.getResponse().getContentAsString()).get("id").asText())).orElseThrow();
        contact.setVerified(true); contacts.saveAndFlush(contact);

        UUID staffId = admin; admin = user;
        mvc.perform(staff(post(activation))).andExpect(status().isForbidden());
        admin = staffId;
        mvc.perform(staff(post(activation))).andExpect(status().isOk())
                .andExpect(jsonPath("$.customerStatus").value("ACTIVE")).andExpect(jsonPath("$.walletEligible").value(true));
        mvc.perform(staff(post(activation))).andExpect(status().isConflict());
        mvc.perform(as(get(ME), user)).andExpect(jsonPath("$.customerStatus").value("ACTIVE"));
        assertThat(audit.findByCustomerId(customerId, org.springframework.data.domain.Pageable.unpaged()).getContent())
                .anySatisfy(entry -> {
                    assertThat(entry.getAction()).isEqualTo("CUSTOMER_ACTIVATED");
                    assertThat(entry.getActorId()).isEqualTo(admin);
                    assertThat(entry.getAfterState()).containsEntry("status", "ACTIVE");
                });
        assertThat(outbox.findByCustomerId(customerId, org.springframework.data.domain.Pageable.unpaged()).getContent())
                .anySatisfy(event -> assertThat(event.getEventType()).isEqualTo("customer.activated.v1"));
    }

    @Test
    void validatesRequestsAndPaginationAndRejectsAnonymousUsers() throws Exception {
        mvc.perform(as(post(ME + "/consents"), user).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(staff(post(adminRoot() + "/limits")).contentType(MediaType.APPLICATION_JSON)
                .content(LIMIT.replace("1000", "100000"))).andExpect(status().isBadRequest());
        mvc.perform(as(post(ME + "/kyc-profile"), user).contentType(MediaType.APPLICATION_JSON).content(PROFILE.replace("TIER_1", "TIER_0")))
                .andExpect(status().isBadRequest());
        for (String path : new String[]{"/consents", "/limits", "/kyc-profile", "/kyc-profile/documents"}) {
            mvc.perform(get(ME + path)).andExpect(status().isUnauthorized());
        }
        mvc.perform(as(get(ME + "/consents").param("size", "101"), user)).andExpect(status().isBadRequest());
        mvc.perform(as(get(ME + "/limits").param("page", "-1"), user)).andExpect(status().isBadRequest());
        mvc.perform(staff(get(adminRoot() + "/outbox-events").param("size", "0"))).andExpect(status().isBadRequest());
    }
}
