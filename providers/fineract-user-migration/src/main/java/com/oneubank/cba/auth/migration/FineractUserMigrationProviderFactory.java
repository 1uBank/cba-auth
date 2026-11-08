package com.oneubank.cba.auth.migration;

import java.time.Duration;
import org.keycloak.Config;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.storage.UserStorageProviderFactory;

/**
 * Registers the first-login migration with the auth service. Settings come from environment variables, so no secret is
 * stored in the realm:
 *
 * <ul>
 * <li>{@code FINERACT_MIGRATION_API_URL}: CBA-CORE's API base, for example {@code https://cba-core/fineract-provider/api/v1}</li>
 * <li>{@code FINERACT_MIGRATION_TENANT}: Fineract tenant, default {@code default}</li>
 * <li>{@code FINERACT_MIGRATION_TOKEN_URL}: the cba realm's token endpoint, as the auth service reaches itself</li>
 * <li>{@code FINERACT_MIGRATION_CLIENT_ID}: default {@code user-migrator}</li>
 * <li>{@code FINERACT_MIGRATION_CLIENT_SECRET}</li>
 * </ul>
 */
public class FineractUserMigrationProviderFactory implements UserStorageProviderFactory<FineractUserMigrationProvider> {

    public static final String ID = "fineract-user-migration";

    private FineractMigrationClient client;

    @Override
    public void init(Config.Scope config) {
        String apiUrl = env("FINERACT_MIGRATION_API_URL", null);
        String secret = env("FINERACT_MIGRATION_CLIENT_SECRET", null);
        if (apiUrl == null || secret == null) {
            // Not configured on this environment: the provider can be registered but finds nobody.
            return;
        }
        client = new FineractMigrationClient(new FineractMigrationClient.MigrationSettings(stripSlash(apiUrl),
                env("FINERACT_MIGRATION_TENANT", "default"),
                env("FINERACT_MIGRATION_TOKEN_URL", "http://localhost:8080/realms/cba/protocol/openid-connect/token"),
                env("FINERACT_MIGRATION_CLIENT_ID", "user-migrator"), secret, Duration.ofSeconds(10)));
    }

    @Override
    public FineractUserMigrationProvider create(KeycloakSession session, ComponentModel model) {
        if (client == null) {
            throw new IllegalStateException("First-login migration is enabled in the realm, but FINERACT_MIGRATION_API_URL or "
                    + "FINERACT_MIGRATION_CLIENT_SECRET is not set");
        }
        return new FineractUserMigrationProvider(session, model, client);
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getHelpText() {
        return "Moves existing Fineract staff into this realm at their first login, keeping their current password.";
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
