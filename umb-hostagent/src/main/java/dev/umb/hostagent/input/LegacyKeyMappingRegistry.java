package dev.umb.hostagent.input;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

import java.util.LinkedHashMap;
import java.util.Map;

/** Registers the exact key names/defaults discovered from legacy input plans. */
public final class LegacyKeyMappingRegistry {
    private final Map<String, KeyMapping> mappings = new LinkedHashMap<>();
    private final Map<String, KeyMapping.Category> categories = new LinkedHashMap<>();

    /**
     * Registers one twin. The category is per legacy mod namespace
     * ({@code Identifier(namespace, "legacy")}); the translation key is the legacy
     * description, which for real mods already is a lang key.
     */
    public KeyMapping register(String namespace, String legacyName, String translationKey,
                               InputConstants.Type type, int defaultKey) {
        if (mappings.containsKey(legacyName)) throw new IllegalArgumentException("duplicate key: " + legacyName);
        String ns = namespace == null || namespace.isBlank() ? "umb" : namespace;
        KeyMapping.Category category = categories.get(ns);
        if (category == null) {
            // Constructor and KeyMapping.Category.register are the 26.2 registration surface (javap).
            category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(ns, "legacy"));
            categories.put(ns, category);
        }
        KeyMapping mapping = new KeyMapping(translationKey, type, defaultKey, category);
        mappings.put(legacyName, mapping);
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
}
