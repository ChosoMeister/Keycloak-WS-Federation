package io.github.chosomeister.keycloak.admin;

import org.keycloak.provider.ProviderConfigProperty;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * One setting shown in the admin console, tied to the realm or client attribute the extension
 * already reads. The console form keys a field by {@link #key()}, which carries no dots because the
 * console's generic form treats a dot as nesting.
 *
 * @param key form field name, also the component config key
 * @param property the field as the console renders it
 * @param read current value on the model, normalised to what the form shows
 * @param write stores a form value on the model
 */
record ConsoleSetting<M>(String key, ProviderConfigProperty property,
                         Function<M, String> read, BiConsumer<M, String> write) {

    static <M> ConsoleSetting<M> attribute(String key, String label, String help, String type,
                                           List<String> options, String defaultValue,
                                           Function<M, String> getter, BiConsumer<M, String> setter) {
        ProviderConfigProperty property = new ProviderConfigProperty();
        property.setName(key);
        property.setLabel(label);
        property.setHelpText(help);
        property.setType(type);
        if (options != null) {
            property.setOptions(options);
        }
        if (defaultValue != null) {
            property.setDefaultValue(defaultValue);
        }
        boolean flag = ProviderConfigProperty.BOOLEAN_TYPE.equals(type);
        Function<M, String> read = model -> normalise(getter.apply(model), flag, defaultValue);
        BiConsumer<M, String> write = (model, value) -> setter.accept(model, normalise(value, flag, ""));
        return new ConsoleSetting<>(key, property, read, write);
    }

    /**
     * A boolean is always "true" or "false", so an unset attribute and an explicit false compare
     * equal. Anything else is trimmed, and unset reads as the field's default.
     */
    static String normalise(String value, boolean flag, String fallback) {
        if (flag) {
            return Boolean.toString(Boolean.parseBoolean(value == null ? null : value.trim()));
        }
        if (value == null || value.isBlank()) {
            return fallback == null ? "" : fallback;
        }
        return value.trim();
    }
}
