package com.oneubank.cba.auth.migration;

import org.jboss.logging.Logger;
import org.keycloak.component.ComponentModel;
import org.keycloak.credential.CredentialInput;
import org.keycloak.credential.CredentialInputValidator;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserCredentialModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserProvider;
import org.keycloak.models.credential.PasswordCredentialModel;
import org.keycloak.storage.UserStoragePrivateUtil;
import org.keycloak.storage.UserStorageProvider;
import org.keycloak.storage.user.UserLookupProvider;
import org.keycloak.storage.user.UserRegistrationProvider;

/**
 * Moves an existing staff member from CBA-CORE into the realm the first time they sign in.
 *
 * <ol>
 * <li>A username the realm does not know is looked up in CBA-CORE. If it is an active staff member, a user is created in
 * the realm, linked to this provider, and asked to set up an authenticator app.</li>
 * <li>The password they type is checked by CBA-CORE against its stored hash. If it matches, it is saved in the realm
 * (hashed the realm's own way) and the link is removed, so later logins never involve CBA-CORE.</li>
 * </ol>
 *
 * New staff never pass through here: CBA-CORE creates them in the realm directly when an admin adds them.
 */
public class FineractUserMigrationProvider
        implements UserStorageProvider, UserLookupProvider, UserRegistrationProvider, CredentialInputValidator {

    private static final Logger LOG = Logger.getLogger(FineractUserMigrationProvider.class);

    private final KeycloakSession session;
    private final ComponentModel model;
    private final FineractMigrationClient fineract;

    FineractUserMigrationProvider(KeycloakSession session, ComponentModel model, FineractMigrationClient fineract) {
        this.session = session;
        this.model = model;
        this.fineract = fineract;
    }

    @Override
    public UserModel getUserByUsername(RealmModel realm, String username) {
        UserProvider local = UserStoragePrivateUtil.userLocalStorage(session);
        if (local.getUserByUsername(realm, username) != null) {
            return null;
        }
        return fineract.find(username).map(staff -> {
            UserModel user = local.addUser(realm, staff.username());
            user.setEnabled(true);
            user.setEmail(staff.email());
            user.setFirstName(staff.firstName());
            user.setLastName(staff.lastName());
            user.setFederationLink(model.getId());
            user.addRequiredAction(UserModel.RequiredAction.CONFIGURE_TOTP);
            LOG.infof("Staff user %s found in CBA-CORE and added to realm %s for first-login migration", staff.username(),
                    realm.getName());
            return user;
        }).orElse(null);
    }

    @Override
    public UserModel getUserById(RealmModel realm, String id) {
        // Migrated users live in the realm's own storage, which answers lookups by ID.
        return null;
    }

    @Override
    public UserModel getUserByEmail(RealmModel realm, String email) {
        // Staff sign in with their Fineract username, so only lookups by username are migrated.
        return null;
    }

    @Override
    public boolean supportsCredentialType(String credentialType) {
        return PasswordCredentialModel.TYPE.equals(credentialType);
    }

    @Override
    public boolean isConfiguredFor(RealmModel realm, UserModel user, String credentialType) {
        return supportsCredentialType(credentialType) && model.getId().equals(user.getFederationLink());
    }

    @Override
    public boolean isValid(RealmModel realm, UserModel user, CredentialInput input) {
        if (!supportsCredentialType(input.getType()) || !model.getId().equals(user.getFederationLink())) {
            return false;
        }
        if (!fineract.verifyPassword(user.getUsername(), input.getChallengeResponse())) {
            return false;
        }
        user.setFederationLink(null);
        user.credentialManager().updateCredential(UserCredentialModel.password(input.getChallengeResponse(), false));
        LOG.infof("Staff user %s migrated from CBA-CORE with their existing password", user.getUsername());
        return true;
    }

    @Override
    public UserModel addUser(RealmModel realm, String username) {
        // New staff are created in the realm by CBA-CORE, not through this provider.
        return null;
    }

    @Override
    public boolean removeUser(RealmModel realm, UserModel user) {
        // Lets an admin delete a staff user who has not finished migrating. Nothing is removed from CBA-CORE.
        return true;
    }

    @Override
    public void close() {
        // Nothing to release.
    }
}
