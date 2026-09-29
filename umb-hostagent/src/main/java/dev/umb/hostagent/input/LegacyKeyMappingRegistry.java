package dev.umb.hostagent.input;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

import java.util.LinkedHashMap;
import java.util.Map;
import dev.umb.bridge.api.LegacyBridge;

/** Registers the exact key names/defaults discovered from legacy input plans. */
public final class LegacyKeyMappingRegistry {
    private final Map<String, KeyMapping> mappings = new LinkedHashMap<>();
    private final Map<String, KeyMapping.Category> categories = new LinkedHashMap<>();
    private final Map<String, Integer> lastLegacyCodes = new LinkedHashMap<>();
    private static java.lang.reflect.Field currentKeyField;

    /**
     * Registers one twin. The category is per legacy mod namespace
     * ({@code Identifier(namespace, "legacy")}); the translation key is the legacy
     * description, which for real mods already is a lang key.
     */
    public KeyMapping register(String namespace, String legacyName, String translationKey,
                               InputConstants.Type type, int defaultKey) {
        // Mods can register the same KeyBinding twice (and eras can re-announce a plan); the
        // twin is one mapping either way, so a repeat is idempotent instead of aborting the
        // whole input bootstrap.
        KeyMapping existing = mappings.get(legacyName);
        if (existing != null) return existing;
        String ns = namespace == null || namespace.isBlank() ? "umb" : namespace;
        KeyMapping.Category category = categories.get(ns);
        if (category == null) {
            category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(ns, "legacy"));
            categories.put(ns, category);
        }
        KeyMapping mapping = new KeyMapping(translationKey, type, defaultKey, category);
        mappings.put(legacyName, mapping);
        lastLegacyCodes.put(legacyName, legacyCode(type, defaultKey));
        return mapping;
    }

    public Map<String, KeyMapping> mappings() { return Map.copyOf(mappings); }

    /**
     * Raw physical keyboard levels as {@code raw:<lwjgl2 code>} entries. Mods that poll
     * {@code org.lwjgl.input.Keyboard.isKeyDown(code)} with codes from their own runtime config
     * (unresolvable in the static input plans) need the physical keyboard, as real LWJGL2 gave
     * them. Set by the client hook; null in headless contexts.
     */
    public static volatile java.util.function.Supplier<Map<String, Boolean>> rawKeySampler;

    public Map<String, Boolean> sampleHeld() {
        Map<String, Boolean> result = new LinkedHashMap<>();
        mappings.forEach((name, mapping) -> result.put(name, mapping.isDown()));
        java.util.function.Supplier<Map<String, Boolean>> raw = rawKeySampler;
        if (raw != null) {
            try {
                Map<String, Boolean> sampled = raw.get();
                if (sampled != null) result.putAll(sampled);
            } catch (Throwable ignored) {
                // Raw polling is additive; a failure must never drop the plan-based keys.
            }
        }
        return result;
    }

    /** Pushes Controls-screen changes back once per changed mapping. */
    public void syncRebinds(LegacyBridge bridge) {
        if (bridge == null) return;
        for (Map.Entry<String, KeyMapping> entry : mappings.entrySet()) {
            KeyMapping mapping = entry.getValue();
            int code;
            try {
                code = legacyCode(mapping);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            Integer previous = lastLegacyCodes.get(entry.getKey());
            if (previous != null && previous.intValue() == code) continue;
            if (bridge.setKeyBinding(entry.getKey(), code)) {
                lastLegacyCodes.put(entry.getKey(), Integer.valueOf(code));
            }
        }
    }

    private static int legacyCode(InputConstants.Type type, int value) {
        return type == InputConstants.Type.MOUSE ? -100 - value : Lwjgl2ToGlfw.glfwToKeyboard(value);
    }

    private static int legacyCode(KeyMapping mapping) {
        try {
            if (currentKeyField == null) {
                currentKeyField = KeyMapping.class.getDeclaredField("key");
                currentKeyField.setAccessible(true);
            }
            InputConstants.Key key = (InputConstants.Key) currentKeyField.get(mapping);
            return legacyCode(key.getType(), key.getValue());
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("cannot read current key mapping", e);
        }
    }
}
