# cba-auth

The auth service for the CBA platform. It signs staff in to the back-office portal and issues tokens to the platform's services and to client applications (the 1UBank App and iRecharge).

It is built on [Keycloak](https://www.keycloak.org/), used unchanged from the official image (`quay.io/keycloak/keycloak`). This repository holds only our configuration on top of it.

## Realms

| Realm | Who | Trusted by |
|---|---|---|
| `cba` | Staff (portal login with MFA) and our services: `banking-api`, `payments`, `compliance` | CBA-CORE (Fineract OIDC federation) and the Banking API's staff endpoints |
| `clients` | Client applications: `app`, `irecharge` | The Banking API's client endpoints only |

- Staff have no roles here. Their permissions live in Fineract's roles screen; the auth service only confirms who they are.
- Tokens from `cba` carry `fineract_tenant=default`, which CBA-CORE uses to resolve the tenant. Fineract maps a service client to the user `service-account-<clientId>`.
- Scopes in `clients` (`customers`, `accounts`, `virtual-accounts`, `transfers`, `interbank`) decide which kinds of call a client may make. Which accounts it may touch is decided by the Banking API.
- A new client application is added by pull request to `realms/clients.yaml`; DevOps puts its secret in Secrets Manager.
- `user-provisioner` lets CBA-CORE create, update and disable staff users in `cba`. Admins keep managing staff in Fineract's user screen; CBA-CORE copies each change here after it is saved (package `org.apache.fineract.oneubank.authsync` in `cba-core`'s `fineract-provider`). Staff are never created here by hand.

## Layout

```
Dockerfile            Keycloak image with our build options (PostgreSQL, health, metrics)
docker-compose.yml    Local run: PostgreSQL, the auth service, the realm loader
realms/cba.yaml       Realm for staff and services
realms/clients.yaml   Realm for client applications
realms/master.yaml    Admin-console realm: only its login theme is set here
providers/            Auth-service plugins (fineract-user-migration), built into the image
themes/1ubank/        1UBank logo and colours for the sign-in, account and admin screens
.env.example          Variable names for a local run
```

Realms are applied by [keycloak-config-cli](https://github.com/adorsys/keycloak-config-cli). It is safe to run on every deploy: it creates or updates what the files describe, and removes only what it created earlier. Secrets are not in the files; they are read from environment variables (`$(env:NAME)`).

## Staff accounts

Admins manage staff only in Fineract's user screen. Two things keep the `cba` realm in step:

| | What it does | Where |
|---|---|---|
| Sync | Creates, updates and disables the staff user here whenever an admin changes them in Fineract | `cba-core`: package `org.apache.fineract.oneubank.authsync` in `fineract-provider`, client `user-provisioner` |
| First-login migration | A staff member this realm does not know yet signs in with their Fineract username and password. The plugin checks it with CBA-CORE, saves it here, and asks for an authenticator app. Later logins never involve CBA-CORE | `providers/fineract-user-migration`, client `user-migrator` |

The first-login migration stays on in every environment. It moves existing staff across at their first login, and also catches anyone the sync missed while the auth service was unreachable. It needs a Fineract user named `service-account-user-migrator`, with no roles; CBA-CORE's migration endpoints accept only this user.

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
cp .env.example .env        # then fill in every empty value (any random string works locally)
docker compose up -d --build
```

- Admin console: http://localhost:8180 (user and password from `.env`)
- First-login migration: set `FINERACT_MIGRATION_ENABLED=true` and `FINERACT_MIGRATION_API_URL` (your local CBA-CORE, for example `http://host.docker.internal:8080/fineract-provider/api/v1`; start CBA-CORE with `FINERACT_SERVER_SSL_ENABLED=false FINERACT_SERVER_PORT=8080`, because the plugin does not accept CBA-CORE's local self-signed certificate) in `.env`, then `docker compose up -d auth` and `docker compose up realm-loader`
- Health: http://localhost:9100/health/ready
- The `realm-loader` container applies `realms/*.yaml` once the auth service is up, then exits. Check it with `docker compose logs realm-loader`.

Get a client token, for example for the App:

```
curl -s -X POST http://localhost:8180/realms/clients/protocol/openid-connect/token \
  -d grant_type=client_credentials -d client_id=app -d client_secret=<APP_CLIENT_SECRET>
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
| `FINERACT_MIGRATION_API_URL` | CBA-CORE's API as the auth service reaches it: `https://<cba-core-internal-host>/fineract-provider/api/v1` | |
| `FINERACT_MIGRATION_TENANT` | `default` | |
| `FINERACT_MIGRATION_TOKEN_URL` | `http://localhost:8080/realms/cba/protocol/openid-connect/token` (the auth service calling itself) | |
| `FINERACT_MIGRATION_CLIENT_ID` | `user-migrator` | |
| `FINERACT_MIGRATION_CLIENT_SECRET` | Same value as `USER_MIGRATOR_SECRET` below | **Secret** |

**Realm loader** (`adorsys/keycloak-config-cli:6.5.1-26`, run after each deploy with `IMPORT_FILES_LOCATIONS` pointing at `realms/*.yaml` and `IMPORT_VARSUBSTITUTION_ENABLED=true`)

| Variable | Value | Secret |
|---|---|---|
| `KEYCLOAK_URL` | Internal URL of the auth service | |
| `KEYCLOAK_USER`, `KEYCLOAK_PASSWORD` | An admin of the `master` realm | **Secret** (password) |
| `PORTAL_URL` | Back-office portal URL, for example `https://portal.<environment-domain>` | |
| `FINERACT_MIGRATION_ENABLED` | `true` | |
| `BANKING_API_CLIENT_SECRET` | Secret of `banking-api`; the Banking API is given the same value | **Secret** |
| `PAYMENTS_CLIENT_SECRET` | Secret of `payments`; Payments is given the same value | **Secret** |
| `COMPLIANCE_CLIENT_SECRET` | Secret of `compliance`; Compliance is given the same value | **Secret** |
| `USER_PROVISIONER_SECRET` | Secret of `user-provisioner`; CBA-CORE is given the same value as `ONEUBANK_AUTHSYNC_CLIENTSECRET` | **Secret** |
| `USER_MIGRATOR_SECRET` | Secret of `user-migrator`; the auth service container is given the same value | **Secret** |
| `APP_CLIENT_SECRET` | Secret of `app`; the 1UBank App team is given the same value | **Secret** |
| `IRECHARGE_CLIENT_SECRET` | Secret of `irecharge`; the iRecharge team is given the same value | **Secret** |

**Also on each environment**

- Ports: 8080 for traffic, 9000 for health (`/health/ready`, `/health/live`) and metrics (`/metrics`).
- Production runs two instances. They find each other through the database (Keycloak's default `jdbc-ping` discovery) and share sessions over port 7800, so that port must be open between the instances.
- The admin console (`/admin`) is reachable only from the private network.
- The first start creates a temporary admin from `KC_BOOTSTRAP_ADMIN_*`. Create a permanent, named admin in the `master` realm and delete the temporary one.
- In CBA-CORE, create the Fineract users `service-account-banking-api` (with the Banking API's role) and `service-account-user-migrator` (no roles).

## Branches

`dev` → `uat` → `main`. Feature branches merge into `dev`; promotion is by pull request only.
