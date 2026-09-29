package dev.umb.legacy.legacyside.input;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

import net.minecraft.client.settings.KeyBinding;

/** Records every legacy ClientRegistry.registerKeyBinding call. */
public final class LegacyKeyBindingRegistry {
    private static final Map<KeyBinding, String> IDS =
            Collections.synchronizedMap(new IdentityHashMap<KeyBinding, String>());
    private static final Map<KeyBinding, String> NAMESPACES =
            Collections.synchronizedMap(new IdentityHashMap<KeyBinding, String>());
    private static final Map<String, KeyBinding> BY_ID =
            Collections.synchronizedMap(new java.util.LinkedHashMap<String, KeyBinding>());

    private LegacyKeyBindingRegistry() { }

    public static String register(KeyBinding binding) {
        return register(binding, "umb");
    }

    public static String register(KeyBinding binding, String namespace) {
        if (binding == null) {
            return "legacy:key:null";
        }
        String old = IDS.get(binding);
        if (old != null) {
            return old;
        }
        // Id shape matches the analyzer's plan stableIds: description:code:category in
        // ctor stores arg1 in field_74515_c (read by func_151464_g) and arg3 in
        // field_151471_f (read by func_151466_e), i.e. the previous formula had the two
        // getters swapped and could never reproduce a plan id.
        String id = "legacy:key:" + binding.func_151464_g()
                + ":" + binding.func_151463_i() + ":" + binding.func_151466_e();
        IDS.put(binding, id);
        NAMESPACES.put(binding, namespace == null || namespace.length() == 0 ? "umb" : namespace);
        BY_ID.put(id, binding);
        return id;
    }

    public static String id(KeyBinding binding) {
        return register(binding);
    }

    public static Map<KeyBinding, String> snapshot() {
        synchronized (IDS) {
            return new IdentityHashMap<KeyBinding, String>(IDS);
        }
    }

    /** Reverse lookup: synthesized instance for a stable id, or null. */
    public static KeyBinding byId(String stableId) {
        return stableId == null ? null : BY_ID.get(stableId);
    }

    public static String namespace(KeyBinding binding) {
        String value = NAMESPACES.get(binding);
        return value == null ? "umb" : value;
    }

    public static boolean setCode(String stableId, int code) {
        KeyBinding binding = byId(stableId);
        if (binding == null) return false;
        try {
            binding.func_151462_b(code);
            KeyBinding.func_74506_a();
            return LegacyKeyBindingSynthesis.updateCode(stableId, code);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
