package dev.umb.hostagent.content;

import dev.umb.bridge.api.StackData;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * StackData (dev.umb.bridge.api) &lt;-&gt; ItemStack (26.2).
 *
 * Two independent sources feed the legacyId/damage &lt;-&gt; native Item mapping:
 *  - Registrar's LEGACY_ITEMS/LEGACY_VARIANT_ITEMS maps (hbm:* and any other modded namespace
 *    content that Registrar newly REGISTERED as native 26.2 blocks/items), and
 *  - {@link VanillaItemBridge} (minecraft:* ids, which already exist natively on 26.2 and are
 *    never (re)registered -- see that class's javadoc for the identity/rename/unmapped split).
 * Registrar is checked first (a modded namespace never collides with "minecraft:"), then
 * VanillaItemBridge.
 *
 * Legacy NBT is carried losslessly as opaque compressed bytes inside native CUSTOM_DATA. No
 * mod-specific tag mapping is invented; the legacy side remains the owner of the tag schema.
 */
final class LegacyStackConv {

    private static final String LEGACY_NBT_KEY = "umb_legacy_nbt";

    private LegacyStackConv() {
    }

    static ItemStack toNative(StackData s) {
        if (s == null || s.isEmpty()) return ItemStack.EMPTY;
        Item item = Registrar.LEGACY_VARIANT_ITEMS.get(s.legacyId + "@" + s.damage);
        if (item == null) item = Registrar.LEGACY_ITEMS.get(s.legacyId);
        if (item == null) item = VanillaItemBridge.legacyToNative(s.legacyId, s.damage);
        if (item == null) {
            VanillaItemBridge.logUnmappedLegacyOnce(s.legacyId + "@" + s.damage);
            return ItemStack.EMPTY;
        }
        ItemStack out = new ItemStack(item, Math.max(0, s.count));
        if (s.nbt != null && s.nbt.length > 0) {
            CustomData.update(DataComponents.CUSTOM_DATA, out,
                    tag -> tag.putByteArray(LEGACY_NBT_KEY, s.nbt));
        }
        return out;
    }

    static StackData toLegacy(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return StackData.EMPTY;
        String key = Registrar.legacyVariantKeyForItem(stack.getItem());
        if (key == null) key = VanillaItemBridge.nativeToLegacyKey(stack.getItem());
        if (key == null) {
            VanillaItemBridge.logUnmappedNativeOnce(String.valueOf(stack.getItem()));
            return StackData.EMPTY;
        }
        int at = key.lastIndexOf('@');
        String legacyId = at >= 0 ? key.substring(0, at) : key;
        int damage = 0;
        if (at >= 0) {
            try {
                damage = Integer.parseInt(key.substring(at + 1));
            } catch (NumberFormatException ignored) {
                // leave damage=0
            }
        }
        byte[] nbt = null;
        CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
        if (custom != null && !custom.isEmpty()) {
            CompoundTag tag = custom.copyTag();
            java.util.Optional<byte[]> raw = tag.getByteArray(LEGACY_NBT_KEY);
            if (raw.isPresent() && raw.get().length > 0) {
                // Stacks produced by the legacy side carry the already-compressed opaque blob.
                nbt = raw.get().clone();
            } else {
                // A native /give or /item command writes ordinary 26.2 custom_data. Legacy
                // items have no native component schema, so the generic bridge must hand that
                // compound to the 1.7.10 ItemStack as its NBTTagCompound instead of silently
                // dropping it. This keeps NBT-backed legacy items (energy cells, magazines,
                // grenades, machines, etc.) commandable without mod-specific cases.
                tag.remove(LEGACY_NBT_KEY);
                if (!tag.isEmpty()) {
                    try {
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        NbtIo.writeCompressed(tag, bytes);
                        nbt = bytes.toByteArray();
                    } catch (IOException ignored) {
                        // An unencodable custom component is not allowed to break inventory sync.
                    }
                }
            }
        }
        return new StackData(legacyId, stack.getCount(), damage, nbt);
    }

    /** True if either Registrar (HBM/modded) or VanillaItemBridge (minecraft:*) knows this native item. */
    static boolean isKnownLegacyItem(Item item) {
        return Registrar.legacyVariantKeyForItem(item) != null || VanillaItemBridge.nativeToLegacyKey(item) != null;
    }
}
