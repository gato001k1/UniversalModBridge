package net.umb.fixtures.srg1710;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import java.util.Collection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.util.EnumChatFormatting;

/**
 * Minimal 1.7.10-era Forge fixture (spec §107 corpus, self-authored CC0-1.0).
 *
 * <p>Exercises what the analyzer and remapper must see on a REAL legacy mod:
 * a {@code cpw.mods.fml.common.@Mod} entrypoint (the 1.7.10 FML package — FML
 * moved to {@code net.minecraftforge.fml} only in 1.12), an
 * {@code @Mod.EventHandler} lifecycle method, {@code mcmod.info} metadata, and
 * static references to genuine 1.7.10 SRG names taken from
 * {@code research/mappings/joined-1.7.10.srg} (see README for line numbers):
 * {@code net/minecraft/client/Minecraft/func_71410_x},
 * {@code field_71439_g}, {@code net/minecraft/util/EnumChatFormatting}
 * ({@code func_96296_a}, {@code func_96300_b}) plus SRG reflection strings
 * (the ObfuscationReflectionHelper-era idiom real mods use).
 */
@Mod(modid = Srg1710Mod.MOD_ID, name = "UMB 1.7.10 SRG Fixture", version = "0.1.0")
public class Srg1710Mod {
    public static final String MOD_ID = "umb-fixture-1710-srg";

    static boolean lastProbe;

    @Mod.EventHandler
    public void onPreInit(FMLPreInitializationEvent event) {
        Minecraft mc = Minecraft.func_71410_x();
        EntityClientPlayerMP player = mc.field_71439_g;
        Collection<?> colors = EnumChatFormatting.func_96296_a(false, false);
        EnumChatFormatting black = EnumChatFormatting.func_96300_b("black");
        lastProbe = probeSrgNames() && player != null && colors != null && black != null;
    }

    /**
     * Era-idiom reflective probe: production 1.7.10 mods resolve SRG names
     * reflectively because dev (MCP) and production (SRG) namespaces differ.
     * The string constants below are the analyzer's SRG signal — five SRG
     * markers against two {@code field_} tallies clears the analyzer's
     * {@code srgRefs >= 2 * intermediaryRefs} bar (each {@code field_N_x}
     * string matches both patterns).
     */
    static boolean probeSrgNames() {
        return hasMember("net.minecraft.client.Minecraft", "func_71410_x")
                && hasMember("net.minecraft.util.EnumChatFormatting", "func_96296_a")
                && hasMember("net.minecraft.util.EnumChatFormatting", "func_96300_b")
                && hasMember("net.minecraft.client.Minecraft", "field_71439_g")
                && hasMember("net.minecraft.util.EnumChatFormatting", "field_96303_A");
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
