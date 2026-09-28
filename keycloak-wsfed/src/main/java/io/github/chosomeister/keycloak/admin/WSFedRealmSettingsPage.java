package io.github.chosomeister.keycloak.admin;

import io.github.chosomeister.keycloak.protocol.wsfed.WSTrustActiveService;
import io.github.chosomeister.keycloak.protocol.wsfed.installation.WSFedIDPDescriptorClientInstallation;
import org.keycloak.Config;
import org.keycloak.component.ComponentModel;
import org.keycloak.component.ComponentValidationException;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.models.utils.PostMigrationEvent;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.services.scheduled.ScheduledTaskRunner;
import org.keycloak.services.ui.extend.UiPageProvider;
import org.keycloak.timer.TimerProvider;
import org.keycloak.services.ui.extend.UiPageProviderFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static io.github.chosomeister.keycloak.admin.ConsoleSetting.attribute;

/**
 * A "WS-Federation realm" page for the realm attributes the extension reads, so they no longer have
 * to be set with kcadm. It holds a single item per realm. The attributes remain what the extension
 * reads; the page writes them and is refreshed from them.
 *
 * <p>A page rather than a tab under Realm settings: the console shows extension tabs only with the
 * experimental declarative-ui feature, while a page is reachable by its address without it.
 */
public class WSFedRealmSettingsPage implements UiPageProvider, UiPageProviderFactory<ComponentModel> {

    public static final String ID = "WS-Federation realm";

    /** Shown so the single item has something to be listed by; not editable. */
    static final String REALM_NAME = "realm";

    static final List<ConsoleSetting<RealmModel>> SETTINGS = List.of(
            realmFlag("wsTrustEnabled", "Active WS-Trust endpoint",
                    "Serves /usernamemixed and /mex for clients that sign in without a browser, such as"
                            + " the Dynamics 365 SDK. It accepts a password directly, so leave it off unless"
                            + " something needs it. A client must also allow password sign-in.",
                    WSTrustActiveService.ENABLED_ATTRIBUTE),
            realmFlag("wsTrustAllowPasswordOnly", "Accept OTP accounts with a password only",
                    "Lets an account that has OTP sign in to the active endpoint with its password alone,"
                            + " bypassing the second factor there. Prefer a service account without OTP.",
                    WSTrustActiveService.ALLOW_PASSWORD_ONLY_ATTRIBUTE),
            realmFlag("announceWsTrust", "Announce WS-Trust in metadata",
                    "Lists the WS-Trust namespaces in the metadata without serving the active endpoint."
                            + " Not needed when the active endpoint is on.",
                    WSFedIDPDescriptorClientInstallation.ANNOUNCE_WS_TRUST_ATTRIBUTE),
            attribute("metadataClaimTypes", "Claim types in metadata",
                    "Claim type URIs the metadata advertises, separated by spaces or commas. Leave empty"
                            + " for UPN, primary SID and Name.",
                    ProviderConfigProperty.TEXT_TYPE, null, null,
                    realm -> realm.getAttribute(WSFedIDPDescriptorClientInstallation.CLAIM_TYPES_ATTRIBUTE),
                    (realm, value) -> realm.setAttribute(WSFedIDPDescriptorClientInstallation.CLAIM_TYPES_ATTRIBUTE, value)));

    private static ConsoleSetting<RealmModel> realmFlag(String key, String label, String help, String attribute) {
        return attribute(key, label, help, ProviderConfigProperty.BOOLEAN_TYPE, null, "false",
                realm -> realm.getAttribute(attribute),
                (realm, value) -> realm.setAttribute(attribute, value));
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getHelpText() {
        return "WS-Federation settings for this realm";
    }

    @Override
    public Map<String, Object> getTypeMetadata() {
        return Map.of("displayFields", List.of(REALM_NAME));
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        List<ProviderConfigProperty> properties = new java.util.ArrayList<>();
        ProviderConfigProperty name = new ProviderConfigProperty();
        name.setName(REALM_NAME);
        name.setLabel("Realm");
        name.setHelpText("The realm these settings belong to.");
        name.setType(ProviderConfigProperty.STRING_TYPE);
        name.setReadOnly(true);
        properties.add(name);
        properties.addAll(SETTINGS.stream().map(ConsoleSetting::property).collect(Collectors.toList()));
        return properties;
    }

    @Override
    public void validateConfiguration(KeycloakSession session, RealmModel realm, ComponentModel model)
            throws ComponentValidationException {
        if (ConsoleMirror.isMirroring()) {
            return;
        }
        if (!realm.getId().equals(model.getParentId())) {
            throw new ComponentValidationException("These settings belong to their own realm.");
        }
        boolean another = itemsOf(realm).anyMatch(c -> !c.getId().equals(model.getId()));
        if (another) {
            throw new ComponentValidationException("This realm already has its WS-Federation settings; edit that entry.");
        }
        ConsoleMirror.refuseIfStale(realm, model);
    }

    @Override
    public void onCreate(KeycloakSession session, RealmModel realm, ComponentModel model) {
        if (!ConsoleMirror.isMirroring()) {
            ConsoleMirror.applyChanges(null, model, realm, SETTINGS);
            refresh(realm);
        }
    }

    @Override
    public void onUpdate(KeycloakSession session, RealmModel realm, ComponentModel oldModel, ComponentModel newModel) {
        if (!ConsoleMirror.isMirroring()) {
            ConsoleMirror.applyChanges(oldModel, newModel, realm, SETTINGS);
            refresh(realm);
        }
    }

    static java.util.stream.Stream<ComponentModel> itemsOf(RealmModel realm) {
        return realm.getComponentsStream(realm.getId(), UiPageProvider.class.getName())
                .filter(c -> ID.equals(c.getProviderId()));
    }

    private static org.keycloak.common.util.MultivaluedHashMap<String, String> configFor(RealmModel realm) {
        org.keycloak.common.util.MultivaluedHashMap<String, String> config = ConsoleMirror.configOf(realm, SETTINGS);
        config.putSingle(REALM_NAME, realm.getName());
        return config;
    }

    /** Brings the realm's single item in line with its attributes, creating it if it is missing. */
    static void refresh(RealmModel realm) {
        ComponentModel existing = itemsOf(realm).findFirst().orElse(null);
        if (existing != null) {
            if (!ConsoleMirror.sameValues(existing, realm, SETTINGS)
                    || !realm.getName().equals(existing.get(REALM_NAME))) {
                existing.setName(ConsoleMirror.revised(ID, existing));
                existing.setConfig(configFor(realm));
                ConsoleMirror.mirroring(() -> realm.updateComponent(existing));
            }
            return;
        }
        ComponentModel created = new ComponentModel();
        created.setName(ConsoleMirror.revised(ID, null));
        created.setProviderId(ID);
        created.setProviderType(UiPageProvider.class.getName());
        created.setParentId(realm.getId());
        created.setConfig(configFor(realm));
        ConsoleMirror.mirroring(() -> realm.addComponentModel(created));
    }

    /** How often the page is brought in line with attributes changed outside the console. */
    static final long REFRESH_INTERVAL_MILLIS = 60_000L;

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // Keycloak publishes no event when a realm's attributes are updated, so the page is refreshed
        // at start and then periodically. Saving writes only the fields the admin changed, so a value
        // that is briefly stale on screen is never written back.
        factory.register(event -> {
            if (event instanceof RealmModel.RealmPostCreateEvent created && !ConsoleMirror.isMirroring()) {
                ConsoleMirror.quietly("realm " + created.getCreatedRealm().getName(), () -> {
                    refresh(created.getCreatedRealm());
                    return null;
                });
            } else if (event instanceof PostMigrationEvent) {
                ConsoleMirror.quietly("all realms", () -> {
                    refreshAll(factory);
                    KeycloakModelUtils.runJobInTransaction(factory, session -> session.getProvider(TimerProvider.class)
                            .schedule(new ScheduledTaskRunner(factory, s -> ConsoleMirror.quietly("all realms", () -> {
                                s.realms().getRealmsStream().forEach(WSFedRealmSettingsPage::refresh);
                                return null;
                            })), REFRESH_INTERVAL_MILLIS, "wsfed-realm-settings-page"));
                    return null;
                });
            }
        });
    }

    static void refreshAll(KeycloakSessionFactory factory) {
        KeycloakModelUtils.runJobInTransaction(factory,
                session -> session.realms().getRealmsStream().forEach(WSFedRealmSettingsPage::refresh));
    }

    @Override
    public void init(Config.Scope config) {
    }

    @Override
    public void close() {
    }
}
