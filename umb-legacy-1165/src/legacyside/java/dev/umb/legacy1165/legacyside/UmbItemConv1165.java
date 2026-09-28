package dev.umb.legacy1165.legacyside;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import dev.umb.bridge.api.StackData;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * The 1.16.5 {@code ItemStack} &lt;-&gt; {@code dev.umb.bridge.api.StackData} conversion.
 * Same shape as 1.7.10's {@code UmbItemConv} ({@code legacyId + count + damage + nbt}, and
 * {@code StackData.EMPTY} for empty), with the 1.16 adaptations: count/damage/NBT go through
 * methods (the fields went private), damage is still a plain int
 * ({@code getDamageValue/setDamageValue}), and ids resolve through the live Forge registries.
 *
 * <p>Every SRG member below was grounded against the in-lane SRG jars (see ERA-1165-PLAN.md
 * round-4 notes), several by method-body analysis, not by number continuity alone:</p>
 * <ul>
 *   <li>{@code field_190927_a} = EMPTY, {@code func_190926_b} = isEmpty,
 *       {@code func_77973_b} = getItem, {@code func_77979_a} = split,
 *       {@code func_77946_l} = copy (by elimination), {@code func_77978_p} = getTag (returns
 *       the tag field), {@code func_77982_d} = setTag (writes the tag field),
 *       {@code func_77942_o} = hasTag (null-checks the tag field).</li>
 *   <li>{@code func_77952_i} calls {@code Item.getDamage} = getDamageValue;
 *       {@code func_196085_b} calls {@code Item.setDamage} = setDamageValue;
 *       {@code func_190916_E} reads the count field (or 0) = getCount;
 *       {@code func_190920_e} is the only other void(int) writer = setCount.</li>
 *   <li>NBT bytes via stream-based {@code CompressedStreamTools} (1.16 has no byte[]
 *       helpers): {@code func_74799_a(tag, stream)} writes,
 *       {@code func_74796_a(stream)} reads.</li>
 * </ul>
 */
public final class UmbItemConv1165 {

    private UmbItemConv1165() {
    }

    static StackData toStackData(ItemStack legacy) {
        if (legacy == null || legacy.func_190926_b()) {
            return StackData.EMPTY;
        }
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(legacy.func_77973_b());
        String id = key == null ? null : key.toString();
        if (id == null) {
            return StackData.EMPTY;
        }
        byte[] nbt = null;
        if (legacy.func_77942_o()) {
            try {
                CompoundNBT tag = legacy.func_77978_p();
                if (tag != null) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    CompressedStreamTools.func_74799_a(tag, bos);
                    nbt = bos.toByteArray();
                }
            } catch (Exception e) {
                System.err.println("[UMB-ITEM] toStackData NBT write failed for " + id + ": " + e);
            }
        }
        return new StackData(id, legacy.func_190916_E(), legacy.func_77952_i(), nbt);
    }

    static ItemStack toLegacy(StackData s) {
        if (s == null || s.isEmpty()) {
            return ItemStack.field_190927_a;
        }
        Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(s.legacyId));
        if (item == null) {
            return ItemStack.field_190927_a;
        }
        ItemStack out = new ItemStack(item, Math.max(1, s.count));
        out.func_196085_b(Math.max(0, s.damage));
        if (s.nbt != null) {
            try {
                CompoundNBT tag = CompressedStreamTools.func_74796_a(
                        new ByteArrayInputStream(s.nbt));
                out.func_77982_d(tag);
            } catch (Exception e) {
                System.err.println("[UMB-ITEM] toLegacy NBT read failed for " + s.legacyId + ": "
                        + e);
                out.func_77982_d(null);
            }
        } else {
            out.func_77982_d(null);
        }
        return out;
    }
}
