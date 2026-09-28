package io.github.chosomeister.keycloak.storage.ldap.mappers;

import org.jboss.logging.Logger;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.storage.ldap.LDAPStorageProvider;
import org.keycloak.storage.ldap.idm.model.LDAPObject;
import org.keycloak.storage.ldap.idm.query.internal.LDAPQuery;
import org.keycloak.storage.ldap.mappers.AbstractLDAPStorageMapper;

import org.keycloak.models.utils.UserModelDelegate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.Collections;
import java.util.Set;

/**
 * Publishes the Active Directory {@code objectSid} of a user as a Keycloak user attribute, in the
 * SDDL string form, so that a WS-Federation protocol mapper can issue it as the
 * {@code primarysid} claim.
 *
 * <p>Keycloak converts {@code objectGUID} for Active Directory but has no equivalent for
 * {@code objectSid}, and the stock attribute mapper would store the raw binary base64 encoded.
 * Relying parties such as Dynamics 365 on-premises key their user records on the SID string, so the
 * base64 form matches nothing.
 *
 * <p>The attribute is derived from the directory on every read rather than written back to it. A
 * SID is assigned by Active Directory and is not ours to change.
 */
public class ActiveDirectorySidLDAPStorageMapper extends AbstractLDAPStorageMapper {

    private static final Logger logger = Logger.getLogger(ActiveDirectorySidLDAPStorageMapper.class);

    public static final String USER_MODEL_ATTRIBUTE = "user.model.attribute";
    public static final String LDAP_ATTRIBUTE = "ldap.attribute";

    public ActiveDirectorySidLDAPStorageMapper(ComponentModel mapperModel, LDAPStorageProvider ldapProvider) {
        super(mapperModel, ldapProvider);
    }

    private String userModelAttribute() {
        return mapperModel.getConfig().getFirst(USER_MODEL_ATTRIBUTE);
    }

    private String ldapAttribute() {
        return mapperModel.getConfig().getFirst(LDAP_ATTRIBUTE);
    }

    /**
     * Reads the SID from the directory entry and converts it. A directory that does not return the
     * attribute, or returns something that is not a SID, leaves the user attribute untouched: an
     * absent claim is a clearer failure than a wrong one.
     */
    private String readSid(LDAPObject ldapUser) {
        String raw = ldapUser.getAttributeAsString(ldapAttribute());
        if (raw == null) {
            return null;
        }

        try {
            return ActiveDirectorySid.fromLdapValue(raw);
        } catch (IllegalArgumentException e) {
            logger.warnf("Could not read %s of LDAP entry %s as a SID: %s",
                    ldapAttribute(), ldapUser.getDn(), e.getMessage());
            return null;
        }
    }

    private void copySid(LDAPObject ldapUser, UserModel user) {
        String sid = readSid(ldapUser);
        if (sid != null) {
            user.setSingleAttribute(userModelAttribute(), sid);
        }
    }

    @Override
    public void onImportUserFromLDAP(LDAPObject ldapUser, UserModel user, RealmModel realm, boolean isCreate) {
        copySid(ldapUser, user);
    }

    @Override
    public void onRegisterUserToLDAP(LDAPObject ldapUser, UserModel localUser, RealmModel realm) {
        // A SID is issued by Active Directory when the account is created there. Nothing to write.
    }

    /**
     * Serves the SID straight from the directory entry on every read, without writing it to the
     * user. Writing here failed every sign-in on a READ_ONLY provider, the usual configuration for
     * Active Directory, where the user is wrapped read-only. Reading it like this also covers users
     * imported before the mapper existed, the way the stock mapper's "always read value from LDAP"
     * does.
     */
    @Override
    public UserModel proxy(LDAPObject ldapUser, UserModel delegate, RealmModel realm) {
        final String attribute = userModelAttribute();
        final String sid = readSid(ldapUser);
        if (attribute == null || attribute.isBlank()) {
            return delegate;
        }
        return new UserModelDelegate(delegate) {
            @Override
            public String getFirstAttribute(String name) {
                return attribute.equalsIgnoreCase(name) ? sid : super.getFirstAttribute(name);
            }

            @Override
            public Stream<String> getAttributeStream(String name) {
                if (attribute.equalsIgnoreCase(name)) {
                    return sid == null ? Stream.empty() : Stream.of(sid);
                }
                return super.getAttributeStream(name);
            }

            @Override
            public Map<String, List<String>> getAttributes() {
                Map<String, List<String>> attributes = new HashMap<>(super.getAttributes());
                if (sid == null) {
                    attributes.remove(attribute);
                } else {
                    attributes.put(attribute, List.of(sid));
                }
                return attributes;
            }
        };
    }

    /**
     * The attribute is only returned when the query asks for it by name. Declaring it binary
     * happens on the factory, which decorates the LDAP connection configuration.
     */
    @Override
    public void beforeLDAPQuery(LDAPQuery query) {
        query.addReturningLdapAttribute(ldapAttribute());
        query.addReturningReadOnlyLdapAttribute(ldapAttribute());
    }

    @Override
    public Set<String> getUserAttributes() {
        return Collections.singleton(userModelAttribute());
    }
}
