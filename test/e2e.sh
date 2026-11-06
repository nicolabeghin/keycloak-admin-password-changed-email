#!/usr/bin/env bash
# End-to-end check: throwaway Keycloak + Mailpit in Docker, the built jar mounted as a provider.
# Usage: mvn package && test/e2e.sh        (KC_IMAGE=quay.io/keycloak/keycloak:26.8.1 by default)
set -euo pipefail

KC_IMAGE="${KC_IMAGE:-quay.io/keycloak/keycloak:26.8.1}"
MAIL_IMAGE="${MAIL_IMAGE:-axllent/mailpit:latest}"
JAR="$(realpath "$(dirname "$0")"/../target/keycloak-admin-password-changed-email-*.jar | head -1)"
NET=apce-e2e
KC=apce-e2e-kc
MAIL=apce-e2e-mail
MAIL_PORT="${MAIL_PORT:-18125}"
MAIL_API=http://127.0.0.1:$MAIL_PORT/api/v1
REALM=e2e

cleanup() { docker rm -f "$KC" "$MAIL" >/dev/null 2>&1 || true; docker network rm "$NET" >/dev/null 2>&1 || true; }
trap cleanup EXIT
cleanup

docker network create "$NET" >/dev/null
docker run -d --name "$MAIL" --network "$NET" -p 127.0.0.1:$MAIL_PORT:8025 "$MAIL_IMAGE" >/dev/null
docker run -d --name "$KC" --network "$NET" \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
  -v "$JAR":/opt/keycloak/providers/listener.jar:ro \
  "$KC_IMAGE" start-dev >/dev/null

kcadm() { docker exec "$KC" /opt/keycloak/bin/kcadm.sh "$@"; }

echo "waiting for Keycloak..."
for _ in $(seq 1 90); do
  if kcadm config credentials --server http://localhost:8080 --realm master --user admin --password admin >/dev/null 2>&1; then break; fi
  sleep 2
done

kcadm create realms -s realm=$REALM -s enabled=true -s eventsEnabled=true \
  -s adminEventsEnabled=true \
  -s 'eventsListeners=["jboss-logging","admin-password-changed-email"]' \
  -s "smtpServer.host=$MAIL" -s smtpServer.port=1025 -s smtpServer.from=noreply@example.test >/dev/null

mk_user() { kcadm create users -r $REALM -s username="$1" -s enabled=true ${2:+-s email="$2"} -i; }
mails() { curl -s "$MAIL_API/messages" | python3 -c 'import json,sys; print(json.load(sys.stdin)["total"])'; }
fail() { echo "FAIL: $*"; docker logs "$KC" 2>&1 | grep -i -E 'password changed|Failed to' | tail -5; exit 1; }

U1=$(mk_user alice alice@example.test)
U2=$(mk_user bob bob@example.test)
U3=$(mk_user carol)

echo "case 1: permanent admin reset -> 1 mail"
kcadm set-password -r $REALM --userid "$U1" --new-password 'Str0ng-pass-1'
sleep 3
[ "$(mails)" = 1 ] || fail "expected 1 mail, got $(mails)"
BODY=$(curl -s "$MAIL_API/message/$(curl -s "$MAIL_API/messages" | python3 -c 'import json,sys; print(json.load(sys.stdin)["messages"][0]["ID"])')")
echo "$BODY" | python3 -c 'import json,sys; m=json.load(sys.stdin); print("  To:",m["To"][0]["Address"]); print("  Subject:",m["Subject"]); print("  Text:",m["Text"].strip().replace("\n"," | "))'
echo "$BODY" | grep -q '"Address":"alice@example.test"' || fail "recipient mismatch"

echo "case 2: temporary admin reset -> no new mail"
kcadm set-password -r $REALM --userid "$U2" --new-password 'Str0ng-pass-2' --temporary
sleep 3
[ "$(mails)" = 1 ] || fail "expected still 1 mail, got $(mails)"

echo "case 3: user without email -> no new mail, no error"
kcadm set-password -r $REALM --userid "$U3" --new-password 'Str0ng-pass-3'
sleep 3
[ "$(mails)" = 1 ] || fail "expected still 1 mail, got $(mails)"

echo "case 4: rejected password (policy) -> no new mail"
kcadm update realms/$REALM -s 'passwordPolicy=length(20)'
kcadm set-password -r $REALM --userid "$U1" --new-password 'short' 2>/dev/null && fail "policy violation was accepted"
sleep 3
[ "$(mails)" = 1 ] || fail "expected still 1 mail, got $(mails)"

echo "PASS"
