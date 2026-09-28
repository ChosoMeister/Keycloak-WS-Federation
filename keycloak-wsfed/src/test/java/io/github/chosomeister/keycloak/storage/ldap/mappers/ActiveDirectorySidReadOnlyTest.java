package io.github.chosomeister.keycloak.storage.ldap.mappers;

import org.junit.jupiter.api.Test;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.UserModel;
import org.keycloak.storage.ReadOnlyException;
import org.keycloak.storage.ldap.idm.model.LDAPObject;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A READ_ONLY LDAP provider with import off hands the mappers a user that refuses every write. The
 * SID mapper must serve the SID from the directory entry without writing it, or no directory user
 * can sign in.
 */
class ActiveDirectorySidReadOnlyTest {

    /** S-1-5-21-1004336348-1177238915-682003330-1105 as Active Directory stores it. */
    private static final String SID_BYTES = "AQUAAAAAAAUVAAAA3PTcO4M9K0aCi6YoUQQAAA==";
    private static final String SID = "S-1-5-21-1004336348-1177238915-682003330-1105";

    private static UserModel readOnlyUser() {
        return (UserModel) Proxy.newProxyInstance(UserModel.class.getClassLoader(), new Class<?>[]{UserModel.class},
                (proxy, method, args) -> {
                    if (method.getName().startsWith("set") || method.getName().startsWith("remove")) {
                        throw new ReadOnlyException("The user is read-only.");
                    }
                    return switch (method.getName()) {
                        case "getAttributes" -> Map.of("upn", List.of("user@example.test"));
                        case "getFirstAttribute" -> null;
                        case "getAttributeStream" -> java.util.stream.Stream.empty();
                        default -> null;
                    };
                });
    }

    private static ActiveDirectorySidLDAPStorageMapper mapper() {
        ComponentModel model = new ComponentModel();
        model.put(ActiveDirectorySidLDAPStorageMapper.USER_MODEL_ATTRIBUTE, "ad_primary_sid");
        model.put(ActiveDirectorySidLDAPStorageMapper.LDAP_ATTRIBUTE, "objectSid");
        return new ActiveDirectorySidLDAPStorageMapper(model, null);
    }

    private static LDAPObject entry(String sidBase64) {
        LDAPObject ldapUser = new LDAPObject();
        if (sidBase64 != null) {
            ldapUser.setSingleAttribute("objectSid", sidBase64);
        }
        return ldapUser;
    }

    @Test
    void servesTheSidWithoutWritingToAReadOnlyUser() {
        UserModel user = mapper().proxy(entry(SID_BYTES), readOnlyUser(), null);

        assertEquals(SID, user.getFirstAttribute("ad_primary_sid"));
        assertEquals(List.of(SID), user.getAttributeStream("ad_primary_sid").toList());
        assertEquals(List.of(SID), user.getAttributes().get("ad_primary_sid"));
        // other attributes still come from the user
        assertEquals(List.of("user@example.test"), user.getAttributes().get("upn"));
    }

    @Test
    void anEntryWithoutASidLeavesTheAttributeAbsent() {
        UserModel user = mapper().proxy(entry(null), readOnlyUser(), null);

        assertNull(user.getFirstAttribute("ad_primary_sid"));
        assertEquals(List.of(), user.getAttributeStream("ad_primary_sid").toList());
    }

    @Test
    void theFixtureIsTheSidItClaimsToBe() {
        assertEquals(SID, ActiveDirectorySid.fromLdapValue(SID_BYTES));
    }
}
