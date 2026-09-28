package dev.umb.objbridge.itemeffects;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.lang.reflect.Method;
import java.util.Optional;

/** Reflection-only client for the legacy item-renderer capture, through the host bridge. */
final class LegacyItemCaptureClient {
    private static volatile Method capture;
    private static volatile Method hasRenderer;
    private static volatile String reason = "unresolved";
    private LegacyItemCaptureClient() { }

    /**
     * {@code IItemRenderer} for this display context at all? {@link HeldItemModel} calls this
     * INSTEAD OF the old {@code HeldItemRuntime.hasHeldData} static-sidecar check: that sidecar
     * only lists items a build-time bytecode census could statically resolve SOME data for
     * "has a renderer" - MCHeli's hand-coded Java model renderers, for one concrete example, were
     * never in that sidecar at all (zero recoverable OBJ geometry to describe) despite being
     * exactly the case this capture path exists for. This is a live registry lookup on the
     * legacy side, not a capture; no GL-EMU session runs.
     */
    static boolean hasCustomRenderer(ItemStack stack, ItemDisplayContext context) {
        if (stack == null || stack.isEmpty()) return false;
        try {
            Object bridge = bridge();
            if (bridge == null) return false;
            Method m = hasRenderer;
            if (m == null) {
                m = bridgeType().getMethod("hasItemRenderer", String.class, int.class, String.class);
                hasRenderer = m;
            }
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            Object result = m.invoke(bridge, id, stack.getDamageValue(), renderType(context));
            return Boolean.TRUE.equals(result);
        } catch (Throwable t) {
            reason = t.getClass().getName();
            return false;
        }
    }

    static Object capture(ItemStack stack, ItemDisplayContext context, float partial, boolean transformOnly) {
        if (stack == null || stack.isEmpty()) return null;
        try {
            Object bridge = bridge();
            if (bridge == null) return null;
            Method m = capture;
            if (m == null) {
                m = bridgeType().getMethod("captureItem", String.class, int.class, int.class,
                        byte[].class, String.class, float.class, boolean.class);
                capture = m;
            }
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            byte[] nbt = legacyNbt(stack);
            return m.invoke(bridge, id, stack.getCount(), stack.getDamageValue(), nbt,
                    renderType(context), partial, transformOnly);
        } catch (Throwable t) {
            reason = t.getClass().getName();
            return null;
        }
    }

    static String lastReason() { return reason; }

    // The legacy classes live in the era's own class loader, which this agent cannot see, so
    // item calls go through the host's bridge (both agents share the application loader).
    private static volatile Method bridgeGetter;

    private static Object bridge() throws ReflectiveOperationException {
        Method get = bridgeGetter;
        if (get == null) {
            get = Class.forName("dev.umb.hostagent.content.UmbBridgeHost").getMethod("get");
            bridgeGetter = get;
        }
        Object bridge = get.invoke(null);
        if (bridge == null) reason = "bridge-not-ready";
        return bridge;
    }

    private static Class<?> bridgeType() throws ClassNotFoundException {
        return Class.forName("dev.umb.bridge.api.LegacyBridge");
    }

    static String renderType(ItemDisplayContext context) {
        if (context == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                || context == ItemDisplayContext.FIRST_PERSON_LEFT_HAND) return "EQUIPPED_FIRST_PERSON";
        if (context == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND
                || context == ItemDisplayContext.THIRD_PERSON_LEFT_HAND) return "EQUIPPED";
        if (context == ItemDisplayContext.GUI) return "INVENTORY";
        return "ENTITY"; // dropped item / ground path
    }

    private static byte[] legacyNbt(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || data.isEmpty()) return null;
        Optional<byte[]> bytes = data.copyTag().getByteArray(LegacyNbtView.ROOT);
        return bytes.isPresent() ? bytes.get().clone() : null;
    }
}
