package com.oneubank.cba.auth.migration;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.jboss.logging.Logger;
import org.keycloak.util.JsonSerialization;

/**
 * Calls CBA-CORE's staff-migration endpoints ({@code /v1/oneubank/staff-migration}). Signs in to the cba realm as the
 * {@code user-migrator} client, with the secret the realm holds for it (passed in by the caller, so a secret regenerated
 * in the admin console takes effect without a restart); CBA-CORE accepts those calls only from that client's CBA-CORE
 * user.
 */
final class CbaCoreMigrationClient {

    private static final Logger LOG = Logger.getLogger(CbaCoreMigrationClient.class);

    private final MigrationSettings settings;
    private final HttpClient http;

    private String accessToken;
    private Instant accessTokenExpiry = Instant.EPOCH;

    CbaCoreMigrationClient(MigrationSettings settings) {
        this.settings = settings;
        this.http = HttpClient.newBuilder().connectTimeout(settings.timeout()).build();
    }

    /** The staff member as CBA-CORE holds them, if they exist and may be migrated. */
    Optional<CbaCoreStaff> find(String username, String clientSecret) {
        try {
            HttpResponse<String> response = send(request("/staff-migration/" + encode(username), clientSecret).GET());
            if (response.statusCode() == 404) {
                return Optional.empty();
            }
            if (response.statusCode() != 200) {
                LOG.warnf("CBA-CORE returned %d when looking up staff user %s for migration", response.statusCode(), username);
                return Optional.empty();
            }
            JsonNode body = JsonSerialization.readValue(response.body(), JsonNode.class);
            return Optional.of(new CbaCoreStaff(text(body, "username"), text(body, "email"), text(body, "firstName"),
                    text(body, "lastName")));
        } catch (IOException e) {
            LOG.warnf("Could not reach CBA-CORE to look up staff user %s for migration: %s", username, e.getMessage());
            return Optional.empty();
        }
    }

    /** Whether the password matches the one CBA-CORE holds for this staff member. */
    boolean verifyPassword(String username, String password, String clientSecret) {
        try {
            String body = JsonSerialization.writeValueAsString(Map.of("password", password));
            HttpResponse<String> response = send(request("/staff-migration/" + encode(username) + "/verify", clientSecret)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)));
            if (response.statusCode() != 200) {
                LOG.warnf("CBA-CORE returned %d when checking the password of staff user %s", response.statusCode(), username);
                return false;
            }
            JsonNode result = JsonSerialization.readValue(response.body(), JsonNode.class);
            return result.path("valid").asBoolean(false);
        } catch (IOException e) {
            LOG.warnf("Could not reach CBA-CORE to check the password of staff user %s: %s", username, e.getMessage());
            return false;
        }
    }

    /** The client this signs in as, whose secret callers pass in. */
    String clientId() {
        return settings.clientId();
    }

    private HttpRequest.Builder request(String path, String clientSecret) throws IOException {
        return HttpRequest.newBuilder(URI.create(settings.apiUrl() + path)).timeout(settings.timeout())
                .header("Authorization", "Bearer " + accessToken(clientSecret)).header("Fineract-Platform-TenantId", settings.tenant())
                .header("Accept", "application/json");
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) throws IOException {
        try {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    private synchronized String accessToken(String clientSecret) throws IOException {
        if (accessToken != null && Instant.now().isBefore(accessTokenExpiry)) {
            return accessToken;
        }
        String form = "grant_type=client_credentials&client_id=" + encode(settings.clientId()) + "&client_secret="
                + encode(clientSecret);
        HttpRequest tokenRequest = HttpRequest.newBuilder(URI.create(settings.tokenUrl())).timeout(settings.timeout())
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build();
        HttpResponse<String> response;
        try {
            response = http.send(tokenRequest, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
        if (response.statusCode() != 200) {
            throw new IOException("token request for " + settings.clientId() + " returned " + response.statusCode());
        }
        JsonNode token = JsonSerialization.readValue(response.body(), JsonNode.class);
        accessToken = token.get("access_token").asText();
        accessTokenExpiry = Instant.now().plusSeconds(Math.max(0, token.get("expires_in").asLong() - 30));
        return accessToken;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    record CbaCoreStaff(String username, String email, String firstName, String lastName) {
    }

    record MigrationSettings(String apiUrl, String tenant, String tokenUrl, String clientId, Duration timeout) {
    }
}
