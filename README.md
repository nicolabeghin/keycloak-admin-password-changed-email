# keycloak-admin-password-changed-email

A Keycloak event listener that emails a user when **an administrator sets their password**.

## Why

Keycloak's built-in `email` event listener sends "password changed" mail for the user event
`UPDATE_PASSWORD`. When an admin sets a password from the admin console (or `PUT
/admin/realms/{realm}/users/{id}/reset-password`), Keycloak records only an **admin event**
(`USER` / `ACTION`) and no user event, so the built-in listener never fires and the user is not told.

## What it does

On a successful admin password reset, after the transaction commits, it sends the same email as the
built-in listener does for `UPDATE_PASSWORD`. It renders the template `event-update_password.ftl`
with the message keys `eventUpdatePasswordSubject`, `eventUpdatePasswordBody` and
`eventUpdatePasswordBodyHtml` of the realm's email theme. The mail is localized and branded like a
self-service password change, and you can customise the wording in your theme.

- The `{ip address}` shown in the mail is the **administrator's** address.
- A **temporary** password (the user must change it at next login) sends nothing. The user's own
  change then fires the regular `UPDATE_PASSWORD` event. Only real changes are notified.
- Users without an email address are skipped. Email verification is not required.
- Rejected resets (for example a password policy violation) send nothing.
- Mail failures are logged and never fail the admin request.

It needs no admin event details (`adminEventsDetailsEnabled` can stay off), so the password is never
stored in admin events.

## Install

Tested with Keycloak 26.8.1 (Java 17).

```bash
mvn package
cp target/keycloak-admin-password-changed-email-*.jar /opt/keycloak/providers/
/opt/keycloak/bin/kc.sh build        # not needed with start-dev
```

Then, per realm, under **Realm settings > Events**:

1. **Event listeners:** add `admin-password-changed-email`.
2. **Admin events settings:** turn on **Save events** (`adminEventsEnabled`).
3. Configure the realm's SMTP server (**Realm settings > Email**).

The default Keycloak theme titles the mail "Update password" while the body says the password was
changed. To change the subject, override `eventUpdatePasswordSubject` in your email theme's
`messages_<locale>.properties`.

## Test

`test/e2e.sh` starts a throwaway Keycloak and Mailpit in Docker, mounts the built jar and checks
four cases: a permanent reset sends one mail, and a temporary reset, a user without email and a
rejected password send none.

```bash
mvn package
test/e2e.sh
```

Variables: `KC_IMAGE`, `MAIL_IMAGE`, `MAIL_PORT` (default 18125).

## License

Apache License 2.0.
