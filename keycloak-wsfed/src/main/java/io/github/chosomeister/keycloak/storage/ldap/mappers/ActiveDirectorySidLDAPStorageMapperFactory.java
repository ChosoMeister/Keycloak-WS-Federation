package io.github.chosomeister.keycloak.storage.ldap.mappers;

import org.keycloak.component.ComponentModel;
import org.keycloak.component.ComponentValidationException;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.storage.ldap.LDAPStorageProvider;
import org.keycloak.storage.ldap.mappers.AbstractLDAPStorageMapper;
import org.keycloak.storage.ldap.mappers.AbstractLDAPStorageMapperFactory;
import org.keycloak.storage.ldap.LDAPConfig;
import org.keycloak.storage.ldap.mappers.LDAPConfigDecorator;

import java.util.ArrayList;
import java.util.List;

public class ActiveDirectorySidLDAPStorageMapperFactory extends AbstractLDAPStorageMapperFactory
        implements LDAPConfigDecorator {

    public static final String PROVIDER_ID = "wsfed-ad-primary-sid-mapper";

    private static final List<ProviderConfigProperty> CONFIG_PROPERTIES = new ArrayList<>();

    static {
        ProviderConfigProperty userAttribute = new ProviderConfigProperty();
        userAttribute.setName(ActiveDirectorySidLDAPStorageMapper.USER_MODEL_ATTRIBUTE);
        userAttribute.setLabel("User Model Attribute");
        userAttribute.setType(ProviderConfigProperty.STRING_TYPE);
        userAttribute.setDefaultValue("ad_primary_sid");
        userAttribute.setHelpText("Name of the Keycloak user attribute that receives the SID in its "
                + "string form. A WS-Federation protocol mapper reads this attribute to issue the "
                + "primarysid claim.");
        CONFIG_PROPERTIES.add(userAttribute);

        ProviderConfigProperty ldapAttribute = new ProviderConfigProperty();
        ldapAttribute.setName(ActiveDirectorySidLDAPStorageMapper.LDAP_ATTRIBUTE);
        ldapAttribute.setLabel("LDAP Attribute");
        ldapAttribute.setType(ProviderConfigProperty.STRING_TYPE);
        ldapAttribute.setDefaultValue("objectSid");
        ldapAttribute.setHelpText("Name of the binary directory attribute holding the security "
                + "identifier. This is objectSid on Active Directory.");
        CONFIG_PROPERTIES.add(ldapAttribute);
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getHelpText() {
        return "Reads the binary Active Directory objectSid and stores it as a user attribute in "
                + "the S-1-5-21-... string form that AD FS issues as the primarysid claim.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return CONFIG_PROPERTIES;
    }

    @Override
    public void validateConfiguration(KeycloakSession session, RealmModel realm, ComponentModel config)
            throws ComponentValidationException {
        checkMandatoryConfigAttribute(ActiveDirectorySidLDAPStorageMapper.USER_MODEL_ATTRIBUTE,
                "User Model Attribute", config);
        checkMandatoryConfigAttribute(ActiveDirectorySidLDAPStorageMapper.LDAP_ATTRIBUTE,
                "LDAP Attribute", config);
    }

    /**
     * objectSid must reach us as raw bytes. Without this the JNDI provider hands the value over as
     * text and the byte sequence is mangled beyond recovery.
     */
    @Override
    public void updateLDAPConfig(LDAPConfig ldapConfig, ComponentModel mapperModel) {
        String attribute = mapperModel.getConfig().getFirst(ActiveDirectorySidLDAPStorageMapper.LDAP_ATTRIBUTE);
        if (attribute != null && !attribute.isBlank()) {
            ldapConfig.addBinaryAttribute(attribute);
        }
    }

    @Override
    protected AbstractLDAPStorageMapper createMapper(ComponentModel mapperModel, LDAPStorageProvider federationProvider) {
        return new ActiveDirectorySidLDAPStorageMapper(mapperModel, federationProvider);
    }
}
