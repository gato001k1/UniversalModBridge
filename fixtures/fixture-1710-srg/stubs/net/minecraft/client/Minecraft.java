package net.minecraft.client;

import net.minecraft.client.entity.EntityClientPlayerMP;

/**
 * UMB test-corpus stub (CC0-1.0): SRG-named shape of 1.7.10's
 * {@code net.minecraft.client.Minecraft} as declared by
 * {@code research/mappings/joined-1.7.10.srg} (see fixture README):
 * {@code func_71410_x} is {@code MD: bao/B ()Lbao;} (line 13277),
 * {@code field_71439_g} is {@code FD: bao/h} (line 3973, javap-confirmed
 * {@code public bjk h} with {@code bjk} = EntityClientPlayerMP, CL line 904).
 * Hand-authored from the mapping file, not extracted from any binary.
 * NEVER ship this class in a distributed jar (BLOCKERS.md B2).
 */
public final class Minecraft {
    public EntityClientPlayerMP field_71439_g;

    private Minecraft() {
    }

    public static Minecraft func_71410_x() {
        return null;
    }
}
