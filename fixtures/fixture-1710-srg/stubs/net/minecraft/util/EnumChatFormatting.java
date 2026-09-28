package net.minecraft.util;

/**
 * UMB test-corpus stub (CC0-1.0): SRG-named shape of 1.7.10's
 * {@code net.minecraft.util.EnumChatFormatting} as declared by
 * {@code research/mappings/joined-1.7.10.srg} (see fixture README):
 * {@code func_96296_a} is {@code MD: a/a (ZZ)Ljava/util/Collection;}
 * (line 8746), {@code func_96300_b} is
 * {@code MD: a/b (Ljava/lang/String;)La;} (line 8748), {@code BLACK} /
 * {@code DARK_BLUE} are {@code FD: a/a} / {@code FD: a/b} (lines 1847-1848).
 * Hand-authored from the mapping file, not extracted from any binary.
 * NEVER ship this class in a distributed jar (BLOCKERS.md B2).
 */
public final class EnumChatFormatting {
    public static final EnumChatFormatting BLACK = new EnumChatFormatting();
    public static final EnumChatFormatting DARK_BLUE = new EnumChatFormatting();

    private EnumChatFormatting() {
    }

    @SuppressWarnings("rawtypes")
    public static java.util.Collection func_96296_a(boolean b1, boolean b2) {
        return java.util.Collections.emptyList();
    }

    public static EnumChatFormatting func_96300_b(String name) {
        return BLACK;
    }
}
