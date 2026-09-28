package net.minecraft.client;

import net.minecraft.client.entity.EntityPlayerSP;

/**
 * UMB test-corpus stub (CC0-1.0): SRG-named shape of 1.12.2's
 * {@code net.minecraft.client.Minecraft} as declared by
 * {@code research/mappings/joined-1.12.2.srg} (see fixture README):
 * {@code func_71410_x} is {@code MD: bib/z ()Lbib;} (line 30951),
 * {@code field_71439_g} is {@code FD: bib/h} (line 11512, javap-confirmed
 * {@code public bud h} with {@code bud} = EntityPlayerSP, CL line 2803).
 * Hand-authored from the mapping file, not extracted from any binary.
 * NEVER ship this class in a distributed jar (BLOCKERS.md B2).
 */
public final class Minecraft {
    public EntityPlayerSP field_71439_g;

    private Minecraft() {
    }

    public static Minecraft func_71410_x() {
        return null;
    }
}
