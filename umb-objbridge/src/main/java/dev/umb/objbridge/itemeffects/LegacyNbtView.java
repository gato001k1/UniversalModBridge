package dev.umb.objbridge.itemeffects;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.io.ByteArrayInputStream;

/** Read-only view of the legacy tag copied into native {@code CUSTOM_DATA}. */
public final class LegacyNbtView {
    public static final String ROOT = "umb_legacy_nbt";
    private LegacyNbtView() { }

    public static CompoundTag read(ItemStack stack) {
        if (stack == null) return new CompoundTag();
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || data.isEmpty()) return new CompoundTag();
        java.util.Optional<byte[]> raw = data.copyTag().getByteArray(ROOT);
        if (raw.isEmpty() || raw.get().length == 0) return new CompoundTag();
        try {
            // LegacyStackConv stores the exact compressed StackData NBT bytes opaquely.
            return NbtIo.readCompressed(new ByteArrayInputStream(raw.get()), NbtAccounter.unlimitedHeap());
        } catch (Exception ignored) {
            return new CompoundTag();
        }
    }

    public static int integer(ItemStack stack, String field, int fallback) {
        return read(stack).getIntOr(field, fallback);
    }

    public static float number(ItemStack stack, String field, float fallback) {
        CompoundTag tag = read(stack);
        return tag.getFloatOr(field, fallback);
    }
}
