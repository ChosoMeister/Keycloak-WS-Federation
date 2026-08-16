package io.github.chosomeister.keycloak.protocol.wsfed;

import io.github.chosomeister.keycloak.protocol.wsfed.mappers.WSFedOIDCAccessTokenMapper;
import org.junit.jupiter.api.Test;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.protocol.ProtocolMapper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The admin console renders the built-in mappers of a client by joining each
 * {@link ProtocolMapperModel} to the mapper type metadata that Keycloak exposes for the client's
 * protocol. A built-in whose protocolMapper id is not registered for the wsfed protocol has no
 * metadata to join against, which crashes the client scope page. The same lookup decides which
 * mappers are applied when a token is issued, so an unregistered built-in is silently inert.
 */
class WSFedBuiltinMapperTest {

    private static final String SERVICES_FILE = "META-INF/services/org.keycloak.protocol.ProtocolMapper";

    private static List<ProtocolMapper> registeredMappers() throws Exception {
        InputStream stream = WSFedBuiltinMapperTest.class.getClassLoader().getResourceAsStream(SERVICES_FILE);
        assertNotNull(stream, "ProtocolMapper service registration file is missing from the build");

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            List<String> classNames = reader.lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .collect(Collectors.toList());

            assertFalse(classNames.isEmpty(), "No protocol mappers are registered");

            List<ProtocolMapper> mappers = new java.util.ArrayList<>();
            for (String className : classNames) {
                mappers.add((ProtocolMapper) Class.forName(className).getDeclaredConstructor().newInstance());
            }
            return mappers;
        }
    }

    @Test
    void everyBuiltinMapperResolvesToARegisteredWsfedMapperType() throws Exception {
        Set<String> registeredIds = registeredMappers().stream()
                .map(ProtocolMapper::getId)
                .collect(Collectors.toSet());

        Map<String, ProtocolMapperModel> builtins = new WSFedLoginProtocolFactory().getBuiltinMappers();
        assertFalse(builtins.isEmpty(), "The wsfed protocol declares no built-in mappers");

        for (Map.Entry<String, ProtocolMapperModel> entry : builtins.entrySet()) {
            ProtocolMapperModel model = entry.getValue();

            assertEquals(WSFedLoginProtocol.LOGIN_PROTOCOL, model.getProtocol(),
                    "Built-in mapper '" + entry.getKey() + "' is bound to the wrong protocol");
            assertTrue(registeredIds.contains(model.getProtocolMapper()),
                    "Built-in mapper '" + entry.getKey() + "' references unregistered mapper type '"
                            + model.getProtocolMapper() + "'");
        }
    }

    @Test
    void registeredMapperTypesExposeConsoleMetadata() throws Exception {
        for (ProtocolMapper mapper : registeredMappers()) {
            assertEquals(WSFedLoginProtocol.LOGIN_PROTOCOL, mapper.getProtocol(),
                    mapper.getId() + " is registered but does not claim the wsfed protocol");
            // The console dereferences helpText unconditionally when rendering a mapper type.
            assertNotNull(mapper.getHelpText(), mapper.getId() + " exposes no help text");
            assertNotNull(mapper.getDisplayType(), mapper.getId() + " exposes no display type");
            assertNotNull(mapper.getDisplayCategory(), mapper.getId() + " exposes no display category");
        }
    }

    @Test
    void jwtTokenPathHasUsableMappers() throws Exception {
        // Access tokens are transformed only by mappers implementing WSFedOIDCAccessTokenMapper.
        // Before the built-ins were corrected none of these were registered, so JWT clients
        // received a token with no mapped claims at all.
        List<ProtocolMapper> accessTokenMappers = registeredMappers().stream()
                .filter(WSFedOIDCAccessTokenMapper.class::isInstance)
                .collect(Collectors.toList());

        assertFalse(accessTokenMappers.isEmpty(),
                "No WSFedOIDCAccessTokenMapper is registered, so the JWT path applies no claim mapping");
    }
}
