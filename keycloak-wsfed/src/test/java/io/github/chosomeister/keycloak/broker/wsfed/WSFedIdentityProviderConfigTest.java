package io.github.chosomeister.keycloak.broker.wsfed;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WSFedIdentityProviderConfigTest {

    @Test
    void signatureValidationIsEnabledByDefault() {
        assertTrue(new WSFedIdentityProviderConfig().isValidateSignature());
    }

    @Test
    void signatureValidationRequiresAnExplicitOptOut() {
        WSFedIdentityProviderConfig config = new WSFedIdentityProviderConfig();
        config.setValidateSignature(false);
        assertFalse(config.isValidateSignature());
    }
}
