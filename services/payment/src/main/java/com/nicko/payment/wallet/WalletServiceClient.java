package com.nicko.payment.wallet;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.util.UUID;

@Component
public class WalletServiceClient {

    private final RestClient restClient;
    private final String walletBaseUrl;
    private final String keycloakBaseUrl;
    private final String keycloakRealm;
    private final String clientId;
    private final String clientSecret;

    private volatile String cachedToken;
    private volatile Instant tokenExpiresAt = Instant.EPOCH;

    public WalletServiceClient(RestClient.Builder builder,
                               @Value("${clients.wallet.url}") String walletBaseUrl,
                               @Value("${app.keycloak.base-url}") String keycloakBaseUrl,
                               @Value("${app.keycloak.realm}") String keycloakRealm,
                               @Value("${app.keycloak.payment-client-id}") String clientId,
                               @Value("${app.keycloak.payment-client-secret:}") String clientSecret) {
        this.restClient = builder.build();
        this.walletBaseUrl = stripTrailingSlash(walletBaseUrl);
        this.keycloakBaseUrl = stripTrailingSlash(keycloakBaseUrl);
        this.keycloakRealm = keycloakRealm;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public WalletAccount findAccount(UUID customerId) {
        try {
            return restClient.get()
                    .uri(walletBaseUrl + "/internal/v1/payment-accounts/{customerId}", customerId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                    .retrieve()
                    .body(WalletAccount.class);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.CONFLICT, "No active wallet is available for this customer");
            }
            throw new WalletServiceException("Wallet service could not resolve the customer's wallet", exception);
        } catch (RestClientException exception) {
            throw new WalletServiceException("Wallet service is unavailable", exception);
        }
    }

    public WalletCreditResponse credit(UUID walletId, WalletCreditRequest request) {
        try {
            return restClient.post()
                    .uri(walletBaseUrl + "/internal/v1/wallets/{walletId}/credits", walletId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(WalletCreditResponse.class);
        } catch (RestClientResponseException exception) {
            throw new WalletServiceException("Wallet service rejected the deposit credit", exception);
        } catch (RestClientException exception) {
            throw new WalletServiceException("Wallet service is unavailable", exception);
        }
    }

    private String accessToken() {
        Instant now = Instant.now();
        if (cachedToken != null && now.isBefore(tokenExpiresAt.minusSeconds(30))) return cachedToken;
        synchronized (this) {
            now = Instant.now();
            if (cachedToken != null && now.isBefore(tokenExpiresAt.minusSeconds(30))) return cachedToken;
            if (clientSecret.isBlank()) {
                throw new WalletServiceException("Payment service Keycloak client credentials are not configured");
            }
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "client_credentials");
            form.add("client_id", clientId);
            form.add("client_secret", clientSecret);
            try {
                ServiceAccountTokenResponse response = restClient.post()
                        .uri(keycloakBaseUrl + "/realms/{realm}/protocol/openid-connect/token", keycloakRealm)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(form)
                        .retrieve()
                        .body(ServiceAccountTokenResponse.class);
                if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
                    throw new WalletServiceException("Keycloak returned no service access token");
                }
                cachedToken = response.accessToken();
                tokenExpiresAt = Instant.now().plusSeconds(Math.max(response.expiresIn(), 60));
                return cachedToken;
            } catch (RestClientException exception) {
                throw new WalletServiceException("Unable to obtain a wallet service access token", exception);
            }
        }
    }

    private static String stripTrailingSlash(String value) {
        return value.replaceAll("/+$", "");
    }
}
