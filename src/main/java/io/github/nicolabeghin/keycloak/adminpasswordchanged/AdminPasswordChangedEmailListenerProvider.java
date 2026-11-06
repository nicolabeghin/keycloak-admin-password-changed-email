package io.github.nicolabeghin.keycloak.adminpasswordchanged;

import java.util.regex.Pattern;

import org.jboss.logging.Logger;
import org.keycloak.email.EmailException;
import org.keycloak.email.EmailTemplateProvider;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerTransaction;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.admin.OperationType;
import org.keycloak.events.admin.ResourceType;
import org.keycloak.http.HttpRequest;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

import static org.keycloak.models.utils.KeycloakModelUtils.runJobInTransaction;

/**
 * Sends the standard "password changed" email (the same one Keycloak's built-in
 * {@code email} listener sends for the user event {@code UPDATE_PASSWORD}) when an
 * administrator sets a user's password, which Keycloak reports only as an admin event.
 */
public class AdminPasswordChangedEmailListenerProvider implements EventListenerProvider {

    private static final Logger log = Logger.getLogger(AdminPasswordChangedEmailListenerProvider.class);

    // Admin API: PUT /admin/realms/{realm}/users/{id}/reset-password
    private static final Pattern RESET_PASSWORD_PATH = Pattern.compile("^users/([^/]+)/reset-password$");

    private final KeycloakSessionFactory sessionFactory;
    private final HttpRequest request;
    private final EventListenerTransaction tx =
            new EventListenerTransaction(this::sendEmail, null);

    public AdminPasswordChangedEmailListenerProvider(KeycloakSession session) {
        this.sessionFactory = session.getKeycloakSessionFactory();
        this.request = session.getContext().getHttpRequest();
        // Mail is sent only after the admin request committed, so a rolled-back change sends nothing.
        session.getTransactionManager().enlistAfterCompletion(tx);
    }

    @Override
    public void onEvent(Event event) {
    }

    @Override
    public void onEvent(AdminEvent event, boolean includeRepresentation) {
        if (event.getResourceType() != ResourceType.USER
                || event.getOperationType() != OperationType.ACTION
                || event.getError() != null
                || event.getResourcePath() == null
                || event.getRealmId() == null) {
            return;
        }
        if (RESET_PASSWORD_PATH.matcher(event.getResourcePath()).matches()) {
            tx.addAdminEvent(event, includeRepresentation);
        }
    }

    private void sendEmail(AdminEvent adminEvent, boolean includeRepresentation) {
        var matcher = RESET_PASSWORD_PATH.matcher(adminEvent.getResourcePath());
        if (!matcher.matches()) {
            return;
        }
        String userId = matcher.group(1);

        try {
            runJobInTransaction(sessionFactory, session -> {
                KeycloakContext context = session.getContext();
                RealmModel realm = session.realms().getRealm(adminEvent.getRealmId());
                if (realm == null) {
                    return;
                }
                context.setRealm(realm);
                context.setHttpRequest(request);

                UserModel user = session.users().getUserById(realm, userId);
                if (user == null || user.getEmail() == null) {
                    return;
                }
                // Temporary password: the user must change it at next login, and that change
                // fires the regular UPDATE_PASSWORD user event. Notify only on a real change.
                if (user.getRequiredActionsStream()
                        .anyMatch(UserModel.RequiredAction.UPDATE_PASSWORD.name()::equals)) {
                    return;
                }

                Event event = new Event();
                event.setType(EventType.UPDATE_PASSWORD);
                event.setRealmId(realm.getId());
                event.setUserId(user.getId());
                event.setTime(adminEvent.getTime());
                if (adminEvent.getAuthDetails() != null) {
                    event.setIpAddress(adminEvent.getAuthDetails().getIpAddress());
                }

                try {
                    session.getProvider(EmailTemplateProvider.class)
                            .setRealm(realm)
                            .setUser(user)
                            .sendEvent(event);
                } catch (EmailException e) {
                    log.error("Failed to send password changed email", e);
                }
            });
        } catch (RuntimeException e) {
            // Never let a notification problem surface as an admin API failure.
            log.error("Failed to process admin password change notification", e);
        }
    }

    @Override
    public void close() {
    }
}
