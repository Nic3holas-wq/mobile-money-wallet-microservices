package com.nicko.customer.customer;

import com.nicko.customer.customer.enums.VerificationSource;
import com.nicko.customer.repository.CustomerContactRepository;
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
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CustomerResourcesApiTests {
    private static final String ROOT = "/api/v1/customers/me/";
    private static final String ADDRESS = """
            {"addressType":"HOME","countryCode":"KE","county":"Nairobi","cityOrTown":"Nairobi",
             "addressLine1":"12 Test Road","primary":true}
            """;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired CustomerContactRepository contacts;
    @MockitoBean JwtDecoder decoder;
    private UUID user;
    private UUID other;

    @BeforeEach
    void customers() throws Exception {
        user = UUID.randomUUID(); other = UUID.randomUUID();
        for (UUID id : new UUID[]{user, other}) {
            mvc.perform(as(post("/api/v1/customers"), id).contentType(MediaType.APPLICATION_JSON).content("""
                    {"firstName":"Test","lastName":"Customer","dateOfBirth":"1990-01-01",
                     "nationality":"KE","preferredLanguage":"en"}
                    """)).andExpect(status().isCreated());
        }
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, UUID id) {
        return request.with(jwt().jwt(token -> token.subject(id.toString())));
    }

    private String phone(boolean primary) {
        return "{\"contactType\":\"PHONE\",\"contactValue\":\"+2547"
                + String.format("%08d", Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 100000000L))
                + "\",\"primary\":" + primary + "}";
    }

    private String email(String value, boolean primary) {
        return "{\"contactType\":\"EMAIL\",\"contactValue\":\"" + value + "\",\"primary\":" + primary + "}";
    }

    private String create(String resource, String body) throws Exception {
        var result = mvc.perform(as(post(ROOT + resource), user).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        String id = json.readTree(result.getResponse().getContentAsString()).get("id").asText();
        assertThat(result.getResponse().getHeader("Location")).isEqualTo(ROOT + resource + "/" + id);
        return id;
    }

    @Test
    void addressCrudAndPrimaryReplacement() throws Exception {
        String first = create("addresses", ADDRESS);
        String second = create("addresses", ADDRESS.replace("12 Test Road", "34 Other Road"));
        mvc.perform(as(get(ROOT + "addresses/" + first), user)).andExpect(status().isOk())
                .andExpect(jsonPath("$.primary").value(false));
        mvc.perform(as(get(ROOT + "addresses").param("size", "1"), user)).andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1)).andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(as(put(ROOT + "addresses/" + first), user).contentType(MediaType.APPLICATION_JSON)
                        .content(ADDRESS.replace("HOME", "WORK")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.addressType").value("WORK"))
                .andExpect(jsonPath("$.primary").value(true));
        mvc.perform(as(get(ROOT + "addresses/" + second), user)).andExpect(jsonPath("$.primary").value(false));
        mvc.perform(as(delete(ROOT + "addresses/" + first), user)).andExpect(status().isNoContent());
        mvc.perform(as(get(ROOT + "addresses/" + first), user)).andExpect(status().isNotFound());
    }

    @Test
    void contactCrudAndVerificationReset() throws Exception {
        String body = phone(true);
        String id = create("contacts", body);
        CustomerContact entity = contacts.findById(UUID.fromString(id)).orElseThrow();
        assertThat(entity.isVerified()).isFalse();
        entity.setVerified(true); entity.setVerifiedAt(Instant.now()); entity.setVerificationSource(VerificationSource.OTP);
        contacts.saveAndFlush(entity);
        mvc.perform(as(put(ROOT + "contacts/" + id), user).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.verified").value(true));
        mvc.perform(as(put(ROOT + "contacts/" + id), user).contentType(MediaType.APPLICATION_JSON).content(phone(true)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.verified").value(false))
                .andExpect(jsonPath("$.verifiedAt").isEmpty()).andExpect(jsonPath("$.verificationSource").isEmpty());
        mvc.perform(as(get(ROOT + "contacts"), user)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(as(delete(ROOT + "contacts/" + id), user)).andExpect(status().isNoContent());
        mvc.perform(as(get(ROOT + "contacts/" + id), user)).andExpect(status().isNotFound());
    }

    @Test
    void primaryContactIsPerTypeAndNormalizedDuplicatesAreRejected() throws Exception {
        String oldPhone = create("contacts", phone(true));
        String newPhone = create("contacts", phone(true));
        String value = UUID.randomUUID() + "@example.com";
        String mail = create("contacts", email(value.toUpperCase(java.util.Locale.ROOT), true));
        mvc.perform(as(get(ROOT + "contacts/" + oldPhone), user)).andExpect(jsonPath("$.primary").value(false));
        mvc.perform(as(get(ROOT + "contacts/" + newPhone), user)).andExpect(jsonPath("$.primary").value(true));
        mvc.perform(as(get(ROOT + "contacts/" + mail), user)).andExpect(jsonPath("$.primary").value(true))
                .andExpect(jsonPath("$.contactValue").value(value));
        mvc.perform(as(post(ROOT + "contacts"), other).contentType(MediaType.APPLICATION_JSON).content(email(value, false)))
                .andExpect(status().isConflict()).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        mvc.perform(as(put(ROOT + "contacts/" + newPhone), user).contentType(MediaType.APPLICATION_JSON).content(email(value, false)))
                .andExpect(status().isConflict());
    }

    @Test
    void resourcesCannotBeReadUpdatedOrDeletedByAnotherCustomer() throws Exception {
        String[] resources = {"addresses", "contacts"};
        String[] bodies = {ADDRESS, phone(false)};
        for (int i = 0; i < resources.length; i++) {
            String resource = resources[i], body = bodies[i];
            String id = create(resource, body);
            mvc.perform(as(get(ROOT + resource + "/" + id), other)).andExpect(status().isNotFound());
            mvc.perform(as(put(ROOT + resource + "/" + id), other).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isNotFound());
            mvc.perform(as(delete(ROOT + resource + "/" + id), other)).andExpect(status().isNotFound());
            mvc.perform(as(get(ROOT + resource), other)).andExpect(jsonPath("$.totalElements").value(0));
            mvc.perform(get(ROOT + resource)).andExpect(status().isUnauthorized());
            mvc.perform(post(ROOT + resource).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void rejectsInvalidInputPaginationAndUnregisteredUsers() throws Exception {
        for (String body : new String[]{phone(false).replace("+254", "0254"),
                email("invalid-email", false), "{\"contactType\":\"PHONE\"}"}) {
            mvc.perform(as(post(ROOT + "contacts"), user).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(as(post(ROOT + "addresses"), user).contentType(MediaType.APPLICATION_JSON)
                .content(ADDRESS.replace("KE", "KEN").replace("Nairobi", " "))).andExpect(status().isBadRequest());
        for (String resource : new String[]{"addresses", "contacts"}) {
            mvc.perform(as(get(ROOT + resource).param("page", "-1"), user)).andExpect(status().isBadRequest());
            mvc.perform(as(get(ROOT + resource).param("size", "101"), user)).andExpect(status().isBadRequest());
            mvc.perform(as(get(ROOT + resource + "/not-a-uuid"), user)).andExpect(status().isBadRequest());
            mvc.perform(as(get(ROOT + resource), UUID.randomUUID())).andExpect(status().isNotFound());
        }
    }

    @Test
    void cannotSupplyVerificationOrOwnerFields() throws Exception {
        String body = phone(false).replace("}", ",\"verified\":true,\"verificationSource\":\"OTP\","
                + "\"customerId\":\"" + other + "\",\"verifiedAt\":\"2026-01-01T00:00:00Z\"}");
        String id = create("contacts", body);
        mvc.perform(as(get(ROOT + "contacts/" + id), user)).andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(false)).andExpect(jsonPath("$.verifiedAt").isEmpty());
        mvc.perform(as(get(ROOT + "contacts/" + id), other)).andExpect(status().isNotFound());
    }
}
