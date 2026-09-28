package io.github.chosomeister.keycloak.admin;

import org.junit.jupiter.api.Test;
import org.keycloak.component.ComponentModel;
import org.keycloak.provider.ProviderConfigProperty;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The console pages write only what the admin changed, and read an unset attribute the way the
 * extension treats it.
 */
class ConsoleMirrorTest {

    private static final List<ConsoleSetting<Map<String, String>>> SETTINGS = List.of(
            ConsoleSetting.attribute("flag", "Flag", "help", ProviderConfigProperty.BOOLEAN_TYPE, null, "false",
                    m -> m.get("a.flag"), (m, v) -> m.put("a.flag", v)),
            ConsoleSetting.attribute("text", "Text", "help", ProviderConfigProperty.STRING_TYPE, null, null,
                    m -> m.get("a.text"), (m, v) -> m.put("a.text", v)),
            ConsoleSetting.attribute("choice", "Choice", "help", ProviderConfigProperty.LIST_TYPE,
                    List.of("KEY_ID", "NONE"), "KEY_ID", m -> m.get("a.choice"), (m, v) -> m.put("a.choice", v)));

    private static ComponentModel form(String flag, String text, String choice) {
        ComponentModel model = new ComponentModel();
        model.put("flag", flag);
        model.put("text", text);
        model.put("choice", choice);
        return model;
    }

    @Test
    void unsetReadsAsTheExtensionTreatsIt() {
        Map<String, String> attributes = new HashMap<>();
        assertEquals("false", SETTINGS.get(0).read().apply(attributes));
        assertEquals("", SETTINGS.get(1).read().apply(attributes));
        assertEquals("KEY_ID", SETTINGS.get(2).read().apply(attributes));
    }

    @Test
    void onlyChangedFieldsAreWritten() {
        Map<String, String> attributes = new HashMap<>(Map.of("a.text", "set elsewhere"));
        ComponentModel shown = form("false", "", "KEY_ID");
        ComponentModel saved = form("true", "", "KEY_ID");

        ConsoleMirror.applyChanges(shown, saved, attributes, SETTINGS);

        assertEquals("true", attributes.get("a.flag"));
        // the text on screen was stale; the admin did not touch it, so the newer value stays
        assertEquals("set elsewhere", attributes.get("a.text"));
        assertFalse(attributes.containsKey("a.choice"));
    }

    @Test
    void aNewItemComparesWithTheDefaults() {
        Map<String, String> attributes = new HashMap<>();
        ConsoleMirror.applyChanges(null, form("false", "", "NONE"), attributes, SETTINGS);
        assertEquals(Map.of("a.choice", "NONE"), attributes);
    }

    @Test
    void theRevisionAdvancesOnEachRefresh() {
        ComponentModel previous = new ComponentModel();
        previous.setName(ConsoleMirror.revised("urn:rp", null));
        assertEquals("urn:rp r1", previous.getName());
        assertEquals("urn:rp r2", ConsoleMirror.revised("urn:rp", previous));
        assertTrue(ConsoleMirror.revised("urn:renamed", previous).startsWith("urn:renamed r"));
    }
}
