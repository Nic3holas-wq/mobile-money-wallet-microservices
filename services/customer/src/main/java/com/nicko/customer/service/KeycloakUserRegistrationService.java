package com.nicko.customer.service;

import com.nicko.customer.dto.UserRegistrationRequest;
import com.nicko.customer.dto.UserRegistrationResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

@Service
public class KeycloakUserRegistrationService {
    private final RestClient restClient;
    private final String baseUrl;
    private final String realm;
    private final String clientId;
    private final String clientSecret;

    public KeycloakUserRegistrationService(@Value("${app.keycloak.base-url}") String baseUrl,
            @Value("${app.keycloak.realm}") String realm,
            @Value("${app.keycloak.registration-client-id}") String clientId,
            @Value("${app.keycloak.registration-client-secret:}") String clientSecret) {
        this.restClient = RestClient.create();
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.realm = realm;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public UserRegistrationResponse register(UserRegistrationRequest request) {
        if (clientSecret.isBlank()) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE,
                    "User registration is not configured");
        }
        String token = serviceAccountToken();
        Map<String, Object> user = Map.of(
                "username", request.username(),
                "email", request.email(),
                "firstName", request.firstName(),
                "lastName", request.lastName(),
                "enabled", true,
                "emailVerified", false,
                "credentials", List.of(Map.of(
                        "type", "password",
                        "value", request.password(),
                        "temporary", false))
        );
        try {
            var result = restClient.post()
                    .uri(baseUrl + "/admin/realms/{realm}/users", realm)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(user)
                    .retrieve()
                    .toBodilessEntity();
            var location = result.getHeaders().getLocation();
            if (location == null || location.getPath().isBlank()) {
                throw new ResponseStatusException(BAD_GATEWAY, "Identity provider returned no user identifier");
            }
            String id = location.getPath().substring(location.getPath().lastIndexOf('/') + 1);
            return new UserRegistrationResponse(UUID.fromString(id), request.username(), request.email(), "CREATED");
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 409) {
                throw new ResponseStatusException(CONFLICT, "Username or email is already registered");
            }
            if (exception.getStatusCode().value() == 400) {
                throw new ResponseStatusException(BAD_REQUEST,
                        "Keycloak rejected the username, email, or password policy");
            }
            if (exception.getStatusCode().value() == 401 || exception.getStatusCode().value() == 403) {
                throw new ResponseStatusException(SERVICE_UNAVAILABLE,
                        "Registration client cannot create users; check its service-account credentials and manage-users role");
            }
            if (exception.getStatusCode().is4xxClientError()) {
                throw new ResponseStatusException(BAD_GATEWAY, "Identity provider rejected registration");
            }
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Identity provider is unavailable");
        } catch (RestClientException exception) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Identity provider is unavailable");
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(BAD_GATEWAY, "Identity provider returned an invalid user identifier");
        }
    }

    private String serviceAccountToken() {
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "client_credentials");
            form.add("client_id", clientId);
            form.add("client_secret", clientSecret);
            Map<?, ?> response = restClient.post()
                    .uri(baseUrl + "/realms/{realm}/protocol/openid-connect/token", realm)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
            Object token = response == null ? null : response.get("access_token");
            if (token instanceof String value && !value.isBlank()) return value;
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Identity provider token response is invalid");
        } catch (RestClientResponseException exception) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE,
                    "Identity provider registration credentials are invalid");
        } catch (RestClientException exception) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Identity provider is unavailable");
        }
    }
}
