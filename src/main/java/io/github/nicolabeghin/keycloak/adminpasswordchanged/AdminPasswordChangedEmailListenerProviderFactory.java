package io.github.nicolabeghin.keycloak.adminpasswordchanged;

import org.keycloak.Config;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

public class AdminPasswordChangedEmailListenerProviderFactory implements EventListenerProviderFactory {

    public static final String ID = "admin-password-changed-email";

    @Override
    public EventListenerProvider create(KeycloakSession session) {
        return new AdminPasswordChangedEmailListenerProvider(session);
    }

    @Override
    public void init(Config.Scope config) {
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
    }

    @Override
    public void close() {
    }

    @Override
    public String getId() {
        return ID;
    }
}
