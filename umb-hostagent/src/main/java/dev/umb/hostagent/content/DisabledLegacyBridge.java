package dev.umb.hostagent.content;

import dev.umb.bridge.api.ActivationResult;
import dev.umb.bridge.api.ItemUseResult;
import dev.umb.bridge.api.LegacyBridge;

import java.lang.reflect.Proxy;

/**
 * A zero-cost bridge used when an era has no installed content.  Keeping the router installed
 * means the host call sites remain universal, while the absent era does not construct a loader,
 * class path, Forge registry, or legacy universe at all.
 */
public final class DisabledLegacyBridge {
    private DisabledLegacyBridge() {
    }

    public static LegacyBridge create(String era) {
        return (LegacyBridge) Proxy.newProxyInstance(
                LegacyBridge.class.getClassLoader(), new Class<?>[] { LegacyBridge.class },
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        if ("toString".equals(method.getName())) return "DisabledLegacyBridge[" + era + "]";
                        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                        return proxy == args[0];
                    }
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == long.class) return 0L;
                    if (method.getReturnType() == float.class) return 0.0F;
                    if (method.getReturnType() == double.class) return 0.0D;
                    if (method.getReturnType() == ActivationResult.class) return ActivationResult.DECLINED;
                    if (method.getReturnType() == ItemUseResult.class) return ItemUseResult.DECLINED;
                    return null;
                });
    }
}
