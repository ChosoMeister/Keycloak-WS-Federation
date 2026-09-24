/*
 * Copyright 2016 Analytical Graphics, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package io.github.chosomeister.keycloak.protocol.wsfed.builders;

import org.jboss.logging.Logger;
import org.keycloak.common.util.Time;
import org.keycloak.models.AuthenticatedClientSessionModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.models.utils.SessionExpirationUtils;
import org.keycloak.protocol.saml.SamlConfigAttributes;
import org.keycloak.saml.common.constants.JBossSAMLURIConstants;
import org.keycloak.services.managers.ClientSessionCode;
import org.keycloak.services.resources.RealmsResource;

import jakarta.ws.rs.core.UriInfo;

/**
 * @author <a href="mailto:brat000012001@gmail.com">Peter Nalyvayko</a>
 * @version $Revision: 1 $
 * @date 10/4/2016
 */

public abstract class WsFedSAMLAssertionTypeAbstractBuilder<T extends WsFedSAMLAssertionTypeAbstractBuilder> {
    private static final Logger logger = Logger.getLogger(WsFedSAMLAssertionTypeAbstractBuilder.class);

    public static final String WSFED_NAME_ID = "WSFED_NAME_ID";
    public static final String WSFED_NAME_ID_FORMAT = "WSFED_NAME_ID_FORMAT";
    public static final String SAML_NAME_ID_FORMAT_ATTRIBUTE = "saml_name_id_format";
    public static final String SAML_DEFAULT_NAMEID_FORMAT = JBossSAMLURIConstants.NAMEID_FORMAT_UNSPECIFIED.get();
    public static final String SAML_FORCE_NAME_ID_FORMAT_ATTRIBUTE = "saml_force_name_id_format";
    public static final String SAML_PERSISTENT_NAME_ID_FOR = "saml.persistent.name.id.for";

    protected UserSessionModel userSession;
    protected AuthenticatedClientSessionModel clientSession;
    protected ClientSessionCode<?> accessCode;
    protected RealmModel realm;
    protected KeycloakSession session;
    protected UriInfo uriInfo;

    protected abstract T getThis();

    public UserSessionModel getUserSession() {
        return userSession;
    }

    public T setUserSession(UserSessionModel userSession) {
        this.userSession = userSession;
        return getThis();
    }

    public AuthenticatedClientSessionModel getClientSession() {
        return clientSession;
    }

    public T setClientSession(AuthenticatedClientSessionModel clientSession) {
        this.clientSession = clientSession;
        return getThis();
    }

    public ClientSessionCode<?> getAccessCode() {
        return accessCode;
    }

    public T setAccessCode(ClientSessionCode<?> accessCode) {
        this.accessCode = accessCode;
        return getThis();
    }

    public RealmModel getRealm() {
        return realm;
    }

    public T setRealm(RealmModel realm) {
        this.realm = realm;
        return getThis();
    }

    public KeycloakSession getSession() {
        return session;
    }

    public T setSession(KeycloakSession session) {
        this.session = session;
        return getThis();
    }

    public UriInfo getUriInfo() {
        return uriInfo;
    }

    public T setUriInfo(UriInfo uriInfo) {
        this.uriInfo = uriInfo;
        return getThis();
    }

    /**
     * Reads the lifespan a client sets for the assertions issued to it, from the same attribute and
     * with the same meaning as Keycloak's SAML protocol: a positive number of seconds replaces the
     * realm defaults, anything else leaves them alone.
     *
     * <p>Unset, the assertion's conditions follow the realm's access code lifespan, which is 60
     * seconds by default. That is Keycloak's own SAML default too, and it is short enough to be a
     * problem: a relying party such as Dynamics 365 ties its session to the shortest validity in
     * the token, and so signs the user out a minute after they sign in.
     *
     * @param client the relying party the assertion is for
     * @return the configured lifespan in seconds, or -1 when the client does not set one
     */
    public static int configuredAssertionLifespan(ClientModel client) {
        String configured = client.getAttribute(SamlConfigAttributes.SAML_ASSERTION_LIFESPAN);
        if (configured == null || configured.isBlank()) {
            return -1;
        }

        try {
            int lifespan = Integer.parseInt(configured.trim());
            return lifespan > 0 ? lifespan : -1;
        } catch (NumberFormatException e) {
            logger.warnf("Client %s sets %s to '%s', which is not a number of seconds. Using the realm defaults.",
                    client.getClientId(), SamlConfigAttributes.SAML_ASSERTION_LIFESPAN, configured);
            return -1;
        }
    }

    /**
     * Client attribute that bounds the token's validity by the user's Keycloak session instead of
     * the realm's token defaults, so it is governed by the session settings an administrator
     * already manages in the console.
     */
    public static final String LIFESPAN_FROM_SESSION_ATTRIBUTE = "wsfed.token.lifespan.from-session";

    /**
     * How long the user's Keycloak session would last from now if the user did nothing further:
     * the earlier of its idle timeout and whatever remains of its maximum lifespan.
     *
     * <p>The token is bounded by that, never longer, so a relying party cannot keep a user signed
     * in after Keycloak has ended their session. While the user stays active the Keycloak session
     * keeps extending; when the token lapses the relying party sends the browser back, the live
     * session answers without asking for a password, and a fresh token is issued.
     *
     * <p>The computation is Keycloak's own, so realm settings, per-client session overrides and
     * remember-me are all accounted for exactly as Keycloak accounts for them when it ends a session.
     *
     * @return the remaining lifespan in seconds, or -1 when the session sets no bound
     */
    public static int sessionBoundLifespan(RealmModel realm, ClientModel client, UserSessionModel userSession) {
        if (userSession == null) {
            return -1;
        }

        long now = Time.currentTimeMillis();
        boolean rememberMe = userSession.isRememberMe();

        long idleEnd = SessionExpirationUtils.calculateClientSessionIdleTimestamp(
                false, rememberMe, now, realm, client);
        long maxEnd = SessionExpirationUtils.calculateClientSessionMaxLifespanTimestamp(
                false, rememberMe, now, userSession.getStarted() * 1000L, realm, client);

        long end = -1;
        for (long candidate : new long[]{idleEnd, maxEnd}) {
            if (candidate > 0 && (end < 0 || candidate < end)) {
                end = candidate;
            }
        }

        if (end <= now) {
            return -1;
        }
        return (int) Math.min(Integer.MAX_VALUE, (end - now) / 1000L);
    }

    /**
     * The lifespan to give the assertion, in order of precedence: an explicit
     * {@code saml.assertion.lifespan} on the client, then the Keycloak session when the client asks
     * for that, then nothing, which leaves the realm defaults in place.
     *
     * @return the lifespan in seconds, or -1 when the realm defaults apply
     */
    public static int effectiveAssertionLifespan(RealmModel realm, ClientModel client, UserSessionModel userSession) {
        int explicit = configuredAssertionLifespan(client);
        if (explicit > 0) {
            return explicit;
        }
        if (Boolean.parseBoolean(client.getAttribute(LIFESPAN_FROM_SESSION_ATTRIBUTE))) {
            return sessionBoundLifespan(realm, client, userSession);
        }
        return -1;
    }

    /**
     * The validity of the token as a whole, used for the WS-Trust Lifetime element of the response.
     * It follows the assertion whenever the assertion lifespan is set, so that a relying party
     * reading the shortest window in the token does not find a shorter one here.
     *
     * @return the token lifespan in seconds
     */
    public static int tokenLifespan(RealmModel realm, ClientModel client, UserSessionModel userSession) {
        int lifespan = effectiveAssertionLifespan(realm, client, userSession);
        return lifespan > 0 ? lifespan : realm.getAccessTokenLifespan();
    }

    protected String getResponseIssuer(RealmModel realm) {
        return RealmsResource.realmBaseUrl(uriInfo).build(realm.getName()).toString();
    }
}
