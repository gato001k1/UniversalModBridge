package net.umb.fixtures.srg1122;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

/**
 * Minimal 1.12.2-era Forge fixture (spec §107 corpus, self-authored CC0-1.0).
 *
 * <p>Exercises what the analyzer and remapper must see on a REAL legacy mod:
 * a {@code net.minecraftforge.fml.common.@Mod} entrypoint (FML moved to the
 * {@code net.minecraftforge.fml} package in 1.12 — contrast the 1.7.10 twin's
 * {@code cpw.mods.fml.common.@Mod}), an {@code @Mod.EventHandler} lifecycle
 * method, {@code mcmod.info} metadata, and static references to genuine
 * 1.12.2 SRG names taken from
 * {@code research/mappings/joined-1.12.2.srg} (see README for line numbers):
 * {@code net/minecraft/client/Minecraft/func_71410_x},
 * {@code field_71439_g}, {@code net/minecraft/util/text/TextFormatting}
 * ({@code func_175744_a}, {@code func_96300_b}) plus SRG reflection strings
 * (the ObfuscationReflectionHelper-era idiom real mods use).
 */
@Mod(modid = Srg1122Mod.MOD_ID, name = "UMB 1.12.2 SRG Fixture", version = "0.1.0")
public class Srg1122Mod {
    public static final String MOD_ID = "umb-fixture-1122-srg";

    static boolean lastProbe;

    @Mod.EventHandler
    public void onPreInit(FMLPreInitializationEvent event) {
        Minecraft mc = Minecraft.func_71410_x();
        EntityPlayerSP player = mc.field_71439_g;
        TextFormatting code = TextFormatting.func_175744_a(0);
        TextFormatting black = TextFormatting.func_96300_b("black");
        lastProbe = probeSrgNames() && player != null && code != null && black != null;
    }

    /**
     * Era-idiom reflective probe: production 1.12.2 mods resolve SRG names
     * reflectively because dev (MCP) and production (SRG) namespaces differ.
     * The string constants below are the analyzer's SRG signal — five SRG
     * markers against two {@code field_} tallies clears the analyzer's
     * {@code srgRefs >= 2 * intermediaryRefs} bar (each {@code field_N_x}
     * string matches both patterns).
     */
    static boolean probeSrgNames() {
        return hasMember("net.minecraft.client.Minecraft", "func_71410_x")
                && hasMember("net.minecraft.util.text.TextFormatting", "func_175744_a")
                && hasMember("net.minecraft.util.text.TextFormatting", "func_96300_b")
                && hasMember("net.minecraft.client.Minecraft", "field_71439_g")
                && hasMember("net.minecraft.util.text.TextFormatting", "field_96303_A");
    }

    static boolean hasMember(String className, String member) {
        try {
            Class<?> c = Class.forName(className);
            try {
                c.getDeclaredField(member);
                return true;
            } catch (NoSuchFieldException e) {
                for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals(member)) {
                        return true;
                    }
                }
                return false;
            }
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
