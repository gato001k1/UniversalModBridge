package dev.umb.legacy.legacyside;

import java.io.IOException;

import cpw.mods.fml.common.registry.GameData;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTSizeTracker;

import dev.umb.bridge.api.StackData;

/**
 * The one legacy {@code ItemStack} &lt;-&gt; {@code dev.umb.bridge.api.StackData} conversion .
 * No {@code net.minecraft} type ever crosses the boundary - {@code StackData} is id+count+damage+nbt only, matching THE BOUNDARY CONTRACT verbatim
 */
public final class UmbItemConv {

    private UmbItemConv() {
    }

    static StackData toStackData(ItemStack legacy) {
        if (legacy == null || legacy.field_77994_a <= 0) {
            return StackData.EMPTY;
        }
        String id = GameData.getItemRegistry().func_148750_c(legacy.func_77973_b());
        byte[] nbt = null;
        if (legacy.field_77990_d != null) {
            try {
                nbt = CompressedStreamTools.func_74798_a(legacy.field_77990_d);
            } catch (IOException e) {
                nbt = null;
            }
        }
        return new StackData(id, legacy.field_77994_a, legacy.func_77960_j(), nbt);
    }

    public static ItemStack toLegacy(StackData s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        Item item = GameData.getItemRegistry().get(s.legacyId);
        if (item == null) {
            return null;
        }
        ItemStack out = new ItemStack(item, s.count, s.damage);
        if (s.nbt != null) {
            try {
                out.field_77990_d = CompressedStreamTools.func_152457_a(s.nbt, NBTSizeTracker.field_152451_a);
            } catch (IOException e) {
                out.field_77990_d = null;
            }
        }
        return out;
    }
}
