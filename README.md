# cba-auth

The auth service for the CBA platform. It signs staff in to the back-office portal and issues tokens to the platform's services and to client applications (the 1UBank App and iRecharge).

It is built on [Keycloak](https://www.keycloak.org/), used unchanged from the official image (`quay.io/keycloak/keycloak`). This repository holds only our configuration on top of it.

## Realms

| Realm | Who | Trusted by |
|---|---|---|
| `cba` | Staff (portal login with MFA) and our services: `banking-api`, `payments`, `compliance` | CBA-CORE (its OIDC federation) and the Banking API's staff endpoints |
| `clients` | Client applications: `app`, `irecharge` | The Banking API's client endpoints only |

- Staff have no roles here. Their permissions live in CBA-CORE's roles screen; the auth service only confirms who they are.
- Tokens from `cba` carry `cba_tenant=default`, which CBA-CORE uses to resolve the tenant (CBA-CORE setting `FINERACT_SECURITY_OIDC_TENANT_CLAIM=cba_tenant`). CBA-CORE maps a service client to the user `service-account-<clientId>`.
- Scopes in `clients` (`customers`, `accounts`, `virtual-accounts`, `transfers`, `interbank`) decide which kinds of call a client may make. Which accounts it may touch is decided by the Banking API.
- A new client application is added by pull request to `realms/clients.yaml`; DevOps puts its secret in Secrets Manager.
- `user-provisioner` lets CBA-CORE create, update and disable staff users in `cba`. Admins keep managing staff in CBA-CORE's user screen; CBA-CORE copies each change here after it is saved (package `org.apache.fineract.oneubank.authsync` in `cba-core`'s `fineract-provider`). Staff are never created here by hand.

## Layout

```
Dockerfile            Keycloak image with our build options (PostgreSQL, health, metrics)
docker-compose.yml    Local run: PostgreSQL, the auth service, the realm loader
realms/cba.yaml       Realm for staff and services
realms/clients.yaml   Realm for client applications
realms/master.yaml    Admin-console realm: only its login theme is set here
providers/            Auth-service plugins (cba-core-user-migration), built into the image
themes/1ubank/        1UBank logo and colours for the sign-in, account and admin screens
.env.example          Variable names for a local run
```

Realms are applied by [keycloak-config-cli](https://github.com/adorsys/keycloak-config-cli). It is safe to run on every deploy: it creates or updates what the files describe, and removes only what it created earlier. Secrets are not in the files; they are read from environment variables (`$(env:NAME)`).

## Staff accounts

Admins manage staff only in CBA-CORE's user screen. Two things keep the `cba` realm in step:

| | What it does | Where |
|---|---|---|
| Sync | Creates, updates and disables the staff user here whenever an admin changes them in CBA-CORE | `cba-core`: package `org.apache.fineract.oneubank.authsync` in `fineract-provider`, client `user-provisioner` |
| First-login migration | A staff member this realm does not know yet signs in with their CBA-CORE username and password. The plugin checks it with CBA-CORE, saves it here, and asks for an authenticator app. Later logins never involve CBA-CORE | `providers/cba-core-user-migration`, client `user-migrator` |

The first-login migration stays on in every environment. It moves existing staff across at their first login, and also catches anyone the sync missed while the auth service was unreachable. It needs a CBA-CORE user named `service-account-user-migrator`, with no roles; CBA-CORE's migration endpoints accept only this user.

## Theme

`themes/1ubank` puts 1UBank's logo and colours on the auth service's own screens, without copying any Keycloak template, so upgrades need no theme changes:

| Screen | Realm | Theme files |
|---|---|---|
| Sign-in pages (staff login, authenticator setup) | `cba`, `master` | `login/`: one stylesheet on Keycloak's `keycloak.v2` login page |
| Account console (staff change their password and authenticator) | `cba`, `master` | `account/`: logo on Keycloak's `keycloak.v3` account console |
| Admin console | `master` | `admin/`: white logo on Keycloak's `keycloak.v2` admin console |

The sign-in page is deliberately plainer than the back-office portal's own login screen. Locally the theme folder is mounted into the container, so edits show on page refresh.

## Run locally

Requires Docker.

```
cp .env.example .env        # then set the two passwords (any random string works locally)
docker compose up -d --build
```

- Admin console: http://localhost:8180 (user and password from `.env`)
- Client secrets: generated on the first start. Read them in the admin console (_realm > Clients > the client > Credentials_).
- First-login migration: set `CBA_CORE_MIGRATION_ENABLED=true` and `CBA_CORE_MIGRATION_API_URL` (your local CBA-CORE, for example `http://host.docker.internal:8080/fineract-provider/api/v1`; start CBA-CORE with `FINERACT_SERVER_SSL_ENABLED=false FINERACT_SERVER_PORT=8080`, because the plugin does not accept CBA-CORE's local self-signed certificate) in `.env`, then `docker compose up -d auth` and `docker compose up realm-loader`
- Health: http://localhost:9100/health/ready
- The `realm-loader` container applies `realms/*.yaml` once the auth service is up, then exits. Check it with `docker compose logs realm-loader`.

Get a client token, for example for the App:

```
curl -s -X POST http://localhost:8180/realms/clients/protocol/openid-connect/token \
  -d grant_type=client_credentials -d client_id=app -d client_secret=<the app client's secret>
```

## Deploying

The image runs `start --optimized`. Set the variables below on the auth service container and on the realm loader. Values marked **Secret** come from AWS Secrets Manager and are never written in code, images, task definitions or tickets.

**Auth service container**

| Variable | Value | Secret |
|---|---|---|
| `KC_DB_URL` | `jdbc:postgresql://<database-host>:5432/<auth database>` | |
| `KC_DB_USERNAME` | Database user of the auth service | |
| `KC_DB_PASSWORD` | Its password | **Secret** |
| `KC_HOSTNAME` | Public URL of the auth service, for example `https://auth.<environment-domain>` | |
| `KC_PROXY_HEADERS` | `xforwarded` (TLS ends at the load balancer) | |
| `KC_HTTP_ENABLED` | `true` (HTTP inside the private network, behind the load balancer) | |
| `KC_BOOTSTRAP_ADMIN_USERNAME` | First admin user, used once on the first start | |
| `KC_BOOTSTRAP_ADMIN_PASSWORD` | Its password | **Secret** |
| `CBA_CORE_MIGRATION_API_URL` | CBA-CORE's API as the auth service reaches it: `https://<cba-core-internal-host>/fineract-provider/api/v1` | |

The first-login migration plugin also reads `CBA_CORE_MIGRATION_TENANT` (default `default`), `CBA_CORE_MIGRATION_CLIENT_ID` (default `user-migrator`) and `CBA_CORE_MIGRATION_TOKEN_URL` (default `http://localhost:8080/realms/cba/protocol/openid-connect/token`). Leave them unset.

**Realm loader**

The realm loader is a separate, short-lived container (`adorsys/keycloak-config-cli:6.5.1-26`) that runs after each deploy. It signs in to the auth service's admin API and creates or updates the realms, clients and scopes in `realms/*.yaml`, then exits.

Fixed settings, the same on every environment:

| Variable | Value |
|---|---|
| `IMPORT_FILES_LOCATIONS` | Where the realm files are inside the container, for example `/config/*.yaml` with `realms/` mounted at `/config` |
| `IMPORT_VARSUBSTITUTION_ENABLED` | `true`, so the `$(env:...)` placeholders in the realm files (`PORTAL_URL`, `CBA_CORE_MIGRATION_ENABLED`) are filled from the variables below |
| `KEYCLOAK_AVAILABILITYCHECK_ENABLED` | `true`, so it waits for the auth service to be up |

Per environment:

| Variable | Value | Secret |
|---|---|---|
| `KEYCLOAK_URL` | The auth service's internal URL, where the loader reaches its admin API (the admin API is not public) | |
| `KEYCLOAK_USER` | The `realm-loader` admin user in the `master` realm | |
| `KEYCLOAK_PASSWORD` | Its password | **Secret** |
| `PORTAL_URL` | Back-office portal URL, for example `https://portal.<environment-domain>` | |
| `CBA_CORE_MIGRATION_ENABLED` | `true` | |

### Client secrets

The auth service generates each client's secret when the realm loader first creates the client; the realm files never contain secrets, and re-running the loader leaves them as they are. DevOps reads a secret, or regenerates it, in the admin console (_realm > Clients > the client > Credentials_) and puts it in Secrets Manager for the service that uses the client:

| Client | Realm | Its secret goes to |
|---|---|---|
| `banking-api` | `cba` | The Banking API |
| `payments` | `cba` | Payments |
| `compliance` | `cba` | Compliance |
| `user-provisioner` | `cba` | CBA-CORE, as `ONEUBANK_AUTHSYNC_CLIENTSECRET` |
| `user-migrator` | `cba` | Nobody: the first-login migration plugin runs inside the auth service and reads it there |
| `app` | `clients` | The 1UBank App team |
| `irecharge` | `clients` | The iRecharge team |

Regenerating a secret stops the old one at once: update the service's Secrets Manager entry and restart it straight after.

**Also on each environment**

- Ports: 8080 for traffic, 9000 for health (`/health/ready`, `/health/live`) and metrics (`/metrics`).
- Production runs two instances. They find each other through the database (Keycloak's default `jdbc-ping` discovery) and share sessions over port 7800, so that port must be open between the instances.
- The admin console (`/admin`) is reachable only from the private network.
- The first start creates a temporary admin from `KC_BOOTSTRAP_ADMIN_*`. Create a permanent, named admin in the `master` realm and delete the temporary one.
- In CBA-CORE, create the users `service-account-banking-api` (with the Banking API's role) and `service-account-user-migrator` (no roles).

## Branches

`dev` → `uat` → `main`. Feature branches merge into `dev`; promotion is by pull request only.
