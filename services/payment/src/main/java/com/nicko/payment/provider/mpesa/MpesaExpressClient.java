package com.nicko.payment.provider.mpesa;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.net.URLEncoder;

@Component
public class MpesaExpressClient {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
            .withZone(ZoneId.of("Africa/Nairobi"));

    private final RestClient restClient;
    private final String baseUrl;
    private final String consumerKey;
    private final String consumerSecret;
    private final String businessShortCode;
    private final String passkey;
    private final String transactionType;
    private final String publicBaseUrl;
    private final String callbackToken;

    private volatile String cachedAccessToken;
    private volatile Instant tokenExpiresAt = Instant.EPOCH;

    public MpesaExpressClient(
            RestClient.Builder restClientBuilder,
            @Value("${app.mpesa.base-url}") String baseUrl,
            @Value("${app.mpesa.consumer-key:}") String consumerKey,
            @Value("${app.mpesa.consumer-secret:}") String consumerSecret,
            @Value("${app.mpesa.business-short-code:}") String businessShortCode,
            @Value("${app.mpesa.passkey:}") String passkey,
            @Value("${app.mpesa.transaction-type:CustomerPayBillOnline}") String transactionType,
            @Value("${app.mpesa.public-base-url:}") String publicBaseUrl,
            @Value("${app.mpesa.callback-token:}") String callbackToken) {
        this.restClient = restClientBuilder.build();
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.consumerKey = consumerKey;
        this.consumerSecret = consumerSecret;
        this.businessShortCode = businessShortCode;
        this.passkey = passkey;
        this.transactionType = transactionType;
        this.publicBaseUrl = stripTrailingSlash(publicBaseUrl);
        this.callbackToken = callbackToken;
    }

    public MpesaPushResponse initiate(String phoneNumber, long amount, String accountReference, String description) {
        requireConfigured();
        String timestamp = TIMESTAMP.format(Instant.now());
        String password = Base64.getEncoder().encodeToString(
                (businessShortCode + passkey + timestamp).getBytes(StandardCharsets.UTF_8));
        String callbackUrl = publicBaseUrl + "/api/v1/payments/callbacks/provider?token="
                + URLEncoder.encode(callbackToken, StandardCharsets.UTF_8);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("BusinessShortCode", businessShortCode);
        request.put("Password", password);
        request.put("Timestamp", timestamp);
        request.put("TransactionType", transactionType);
        request.put("Amount", amount);
        request.put("PartyA", phoneNumber);
        request.put("PartyB", businessShortCode);
        request.put("PhoneNumber", phoneNumber);
        request.put("CallBackURL", callbackUrl);
        request.put("AccountReference", accountReference);
        request.put("TransactionDesc", description == null || description.isBlank() ? "Wallet deposit" : description);

        try {
            return restClient.post()
                    .uri(baseUrl + "/mpesa/stkpush/v1/processrequest")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(MpesaPushResponse.class);
        } catch (RestClientResponseException exception) {
            boolean unknown = exception.getStatusCode().is5xxServerError();
            throw new MpesaProviderException(unknown
                    ? "M-Pesa did not return a conclusive response" : "M-Pesa rejected the deposit request",
                    exception, unknown);
        } catch (RestClientException exception) {
            throw new MpesaProviderException("M-Pesa did not return a conclusive response", exception, true);
        }
    }

    private String accessToken() {
        Instant now = Instant.now();
        if (cachedAccessToken != null && now.isBefore(tokenExpiresAt.minusSeconds(30))) {
            return cachedAccessToken;
        }
        synchronized (this) {
            now = Instant.now();
            if (cachedAccessToken != null && now.isBefore(tokenExpiresAt.minusSeconds(30))) {
                return cachedAccessToken;
            }
            try {
                MpesaTokenResponse response = restClient.get()
                        .uri(baseUrl + "/oauth/v1/generate?grant_type=client_credentials")
                        .headers(headers -> headers.setBasicAuth(consumerKey, consumerSecret))
                        .retrieve()
                        .body(MpesaTokenResponse.class);
                if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
                    throw new MpesaProviderException("M-Pesa authorization returned no access token");
                }
                cachedAccessToken = response.accessToken();
                tokenExpiresAt = Instant.now().plusSeconds(Math.max(response.expiresIn(), 60));
                return cachedAccessToken;
            } catch (RestClientException exception) {
                throw new MpesaProviderException("Unable to authorize with M-Pesa", exception);
            }
        }
    }

    private void requireConfigured() {
        if (consumerKey.isBlank() || consumerSecret.isBlank() || businessShortCode.isBlank()
                || passkey.isBlank() || publicBaseUrl.isBlank() || callbackToken.isBlank()) {
            throw new MpesaProviderException("M-Pesa Express settings are incomplete");
        }
    }

    private static String stripTrailingSlash(String value) {
        return value.replaceAll("/+$", "");
    }
}
