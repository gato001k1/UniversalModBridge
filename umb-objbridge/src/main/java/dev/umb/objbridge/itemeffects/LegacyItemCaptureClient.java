package dev.umb.objbridge.itemeffects;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.lang.reflect.Method;
import java.util.Optional;

/** Reflection-only bridge to the legacy-side IItemRenderer capture seam. */
final class LegacyItemCaptureClient {
    private static volatile Method capture;
    private static volatile String reason = "unresolved";
    private LegacyItemCaptureClient() { }

    static Object capture(ItemStack stack, ItemDisplayContext context, float partial, boolean transformOnly) {
        if (stack == null || stack.isEmpty()) return null;
        try {
            Method m = capture;
            if (m == null) {
                Class<?> type = Class.forName("dev.umb.legacy.legacyside.render.LegacyRenderCapture");
                m = type.getMethod("captureItem", String.class, int.class, int.class, byte[].class,
                        String.class, float.class, boolean.class);
                capture = m;
            }
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            byte[] nbt = legacyNbt(stack);
            return m.invoke(null, id, stack.getCount(), stack.getDamageValue(), nbt,
                    renderType(context), partial, transformOnly);
        } catch (Throwable t) {
            reason = t.getClass().getName();
            return null;
        }
    }

    static String lastReason() { return reason; }

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
