#!/bin/sh
# Starts keycloak-config-cli only when the settings the realm files need are present. Without this, a missing
# PORTAL_URL is substituted as an empty string: the import "succeeds" and the portal can no longer sign in.
set -eu

case "${PORTAL_URL:-}" in
  http://?*|https://?*) ;;
  *)
    echo "realm-loader: PORTAL_URL must be the portal's address, for example https://portal.example.com (got '${PORTAL_URL:-}')" >&2
    exit 1
    ;;
esac
case "$PORTAL_URL" in
  */)
    echo "realm-loader: PORTAL_URL must not end with '/' (got '$PORTAL_URL')" >&2
    exit 1
    ;;
esac

exec java ${JAVA_OPTS:-} -jar /app/keycloak-config-cli.jar "$@"
