package io.github.chosomeister.keycloak.admin;

import org.jboss.logging.Logger;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.component.ComponentModel;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The console stores what a page or tab shows as a component, while the extension reads realm and
 * client attributes, which scripts and kcadm also write. The attributes stay the single source of
 * truth: saving in the console writes them, and changing them anywhere refreshes the component, so
 * the console never shows or saves back a stale value.
 */
final class ConsoleMirror {

    private static final Logger logger = Logger.getLogger(ConsoleMirror.class);

    /** Set while the extension itself refreshes a component, so that write is not echoed back. */
    private static final ThreadLocal<Boolean> MIRRORING = ThreadLocal.withInitial(() -> false);

    private ConsoleMirror() {
    }

    static boolean isMirroring() {
        return MIRRORING.get();
    }

    static void mirroring(Runnable work) {
        boolean previous = MIRRORING.get();
        MIRRORING.set(true);
        try {
            work.run();
        } finally {
            MIRRORING.set(previous);
        }
    }

    /**
     * Each refresh gives the component a new revision in its name, which the console sends back
     * unchanged when it saves. A save carrying an older revision comes from a form opened before
     * the values last changed, and is refused rather than allowed to overwrite them.
     */
    static String revised(String base, ComponentModel previous) {
        int revision = 0;
        if (previous != null && previous.getName() != null) {
            java.util.regex.Matcher m = REVISION.matcher(previous.getName());
            if (m.find()) {
                revision = Integer.parseInt(m.group(1));
            }
        }
        return base + " r" + (revision + 1);
    }

    private static final java.util.regex.Pattern REVISION = java.util.regex.Pattern.compile(" r(\\d+)$");

    static void refuseIfStale(org.keycloak.models.RealmModel realm, ComponentModel saved)
            throws org.keycloak.component.ComponentValidationException {
        if (saved.getId() == null) {
            return;
        }
        ComponentModel stored = realm.getComponent(saved.getId());
        if (stored != null && !Objects.equals(stored.getName(), saved.getName())) {
            throw new org.keycloak.component.ComponentValidationException(
                    "These settings changed after this page was opened. Reload the page and try again.");
        }
    }

    static <M> MultivaluedHashMap<String, String> configOf(M model, List<ConsoleSetting<M>> settings) {
        MultivaluedHashMap<String, String> config = new MultivaluedHashMap<>();
        for (ConsoleSetting<M> setting : settings) {
            config.putSingle(setting.key(), setting.read().apply(model));
        }
        return config;
    }

    static <M> boolean sameValues(ComponentModel component, M model, List<ConsoleSetting<M>> settings) {
        for (ConsoleSetting<M> setting : settings) {
            if (!Objects.equals(setting.read().apply(model), component.get(setting.key(), ""))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Writes onto the model only the fields the admin changed, compared with what the form showed.
     * A value that was stale on screen, because it was changed elsewhere after the page loaded, is
     * therefore never written back over the newer one.
     *
     * @param shown what the form displayed; null means a new item, which showed the defaults
     */
    static <M> void applyChanges(ComponentModel shown, ComponentModel saved, M model, List<ConsoleSetting<M>> settings) {
        for (ConsoleSetting<M> setting : settings) {
            if (!saved.getConfig().containsKey(setting.key())) {
                continue;
            }
            String before = shown == null ? defaultOf(setting) : shown.get(setting.key(), defaultOf(setting));
            String after = saved.get(setting.key(), "");
            if (!normalised(setting, before).equals(normalised(setting, after))) {
                setting.write().accept(model, after);
            }
        }
    }

    static String defaultOf(ConsoleSetting<?> setting) {
        Object value = setting.property().getDefaultValue();
        return value == null ? "" : String.valueOf(value);
    }

    private static String normalised(ConsoleSetting<?> setting, String value) {
        return ConsoleSetting.normalise(value,
                org.keycloak.provider.ProviderConfigProperty.BOOLEAN_TYPE.equals(setting.property().getType()),
                defaultOf(setting));
    }

    /**
     * A mirror failure must never fail the operation that triggered it, such as a client update or a
     * server start; the console then shows an older value until the next change.
     */
    static void quietly(String what, Supplier<?> work) {
        try {
            work.get();
        } catch (RuntimeException e) {
            logger.warnf(e, "Could not refresh the WS-Federation console settings for %s", what);
        }
    }
}
