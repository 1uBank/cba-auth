package com.oneubank.cba.auth.migration;

import java.time.Duration;
import org.keycloak.Config;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.storage.UserStorageProviderFactory;

/**
 * Registers the first-login migration with the auth service. It signs in to CBA-CORE as the realm's {@code user-migrator}
 * client, using the secret the realm already holds for it, so no secret is configured here. Settings:
 *
 * <ul>
 * <li>{@code CBA_CORE_MIGRATION_API_URL}: CBA-CORE's API base, for example {@code https://cba-core/fineract-provider/api/v1}</li>
 * <li>{@code CBA_CORE_MIGRATION_TENANT}: CBA-CORE tenant, default {@code default}</li>
 * <li>{@code CBA_CORE_MIGRATION_TOKEN_URL}: the cba realm's token endpoint, as the auth service reaches itself</li>
 * <li>{@code CBA_CORE_MIGRATION_CLIENT_ID}: default {@code user-migrator}</li>
 * </ul>
 */
public class CbaCoreUserMigrationProviderFactory implements UserStorageProviderFactory<CbaCoreUserMigrationProvider> {

    public static final String ID = "cba-core-user-migration";

    private CbaCoreMigrationClient client;

    @Override
    public void init(Config.Scope config) {
        String apiUrl = env("CBA_CORE_MIGRATION_API_URL", null);
        if (apiUrl == null) {
            // Not configured on this environment: the provider can be registered but finds nobody.
            return;
        }
        client = new CbaCoreMigrationClient(new CbaCoreMigrationClient.MigrationSettings(stripSlash(apiUrl),
                env("CBA_CORE_MIGRATION_TENANT", "default"),
                env("CBA_CORE_MIGRATION_TOKEN_URL", "http://localhost:8080/realms/cba/protocol/openid-connect/token"),
                env("CBA_CORE_MIGRATION_CLIENT_ID", "user-migrator"), Duration.ofSeconds(10)));
    }

    @Override
    public CbaCoreUserMigrationProvider create(KeycloakSession session, ComponentModel model) {
        if (client == null) {
            throw new IllegalStateException(
                    "First-login migration is enabled in the realm, but CBA_CORE_MIGRATION_API_URL is not set");
        }
        return new CbaCoreUserMigrationProvider(session, model, client);
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getHelpText() {
        return "Moves existing CBA-CORE staff into this realm at their first login, keeping their current password.";
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
