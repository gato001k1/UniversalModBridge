package dev.umb.legacy.legacyside;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import cpw.mods.fml.relauncher.FMLRelaunchLog;

/**
 * Pre-emptive fixes applied to the vanilla registries before any mod runs, for cases where a mod's
 * OWN workaround is what breaks on a modern JDK.
 *
 * <p>The motivating case, and the wall that stopped PREINIT once EnumHelper and the holder refs
 * were shimmed:</p>
 * <pre>
 *   ArrayIndexOutOfBoundsException: Index 62 out of bounds for length 32
 *     at net.minecraft.potion.Potion.&lt;init&gt;(Potion.java:68)
 *     at com.hbm.potion.HbmPotion.registerPotion(HbmPotion.java:86)
 *     at com.hbm.main.MainRegistry.PreLoad(MainRegistry.java:260)
 * </pre>
 * <p>1.7.10's {@code Potion.potionTypes} is {@code new Potion[32]} and HBM's potion ids start at
 * 62, so {@code HbmPotion.registerPotion} grows the array itself - through the classic
 * "clear the FINAL bit via {@code Field.class.getDeclaredField("modifiers")}" hack, inside a
 * {@code catch (Exception) {}}. On JDK 12+ that field is gone, the exception is SWALLOWED, the
 * bigger array is never installed, and the very next line writes {@code potionTypes[62]}.</p>
 *
 * <p>So there is nothing to shim in the mod: the mod's code is already conditional
 * ({@code if (id >= Potion.potionTypes.length)}). Handing it an array that is already big enough
 * makes it skip the reflection entirely, which is both simpler and closer to what the mod expects
 * to happen. Same trick a "potion id extender" coremod uses on real 1.7.10 servers.</p>
 */
final class LegacyCompat {

    private LegacyCompat() {
    }

    private static final List<String> APPLIED = new ArrayList<String>();

    static List<String> applied() {
        return Collections.unmodifiableList(APPLIED);
    }

    /**
     * Grow {@code net.minecraft.potion.Potion.potionTypes} (SRG {@code field_76425_a}) to
     * {@code size}, preserving every registered vanilla potion.
     */
    static void widenPotionRegistry(int size) throws Exception {
        Field f = net.minecraft.potion.Potion.class.getDeclaredField("field_76425_a");
        f.setAccessible(true);
        Object current = f.get(null);
        int oldLength = current == null ? 0 : Array.getLength(current);
        if (oldLength >= size) {
            APPLIED.add("Potion.potionTypes already " + oldLength + " >= " + size + ", untouched");
            return;
        }
        Object grown = Array.newInstance(net.minecraft.potion.Potion.class, size);
        if (current != null) {
            System.arraycopy(current, 0, grown, 0, oldLength);
        }
        EnumHelperShim.setFailsafeFieldValue(f, null, grown);
        int now = Array.getLength(f.get(null));
        if (now != size) {
            throw new IllegalStateException("Potion.potionTypes is still " + now + " after widening");
        }
        APPLIED.add("Potion.potionTypes " + oldLength + " -> " + size);
        FMLRelaunchLog.info("[umb-legacy] widened Potion.potionTypes %d -> %d", oldLength, size);
    }
}
