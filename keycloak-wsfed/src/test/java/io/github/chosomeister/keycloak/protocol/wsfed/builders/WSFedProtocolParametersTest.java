package io.github.chosomeister.keycloak.protocol.wsfed.builders;

import io.github.chosomeister.keycloak.common.wsfed.WSFedConstants;
import jakarta.ws.rs.core.MultivaluedHashMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WSFedProtocolParametersTest {

    @Test
    void readsTheFirstValueForEachProtocolParameter() {
        MultivaluedHashMap<String, String> values = new MultivaluedHashMap<>();
        values.add(WSFedConstants.WSFED_ACTION, WSFedConstants.WSFED_SIGNIN_ACTION);
        values.add(WSFedConstants.WSFED_ACTION, "ignored");
        values.add(WSFedConstants.WSFED_REALM, "urn:test:realm");

        WSFedProtocolParameters parameters = WSFedProtocolParameters.fromParameters(values);

        assertEquals(WSFedConstants.WSFED_SIGNIN_ACTION, parameters.getWsfedAction());
        assertEquals("urn:test:realm", parameters.getWsfedRealm());
    }
}
