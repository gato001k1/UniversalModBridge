package net.minecraft.util.text;

/**
 * UMB test-corpus stub (CC0-1.0): SRG-named shape of 1.12.2's
 * {@code net.minecraft.util.text.TextFormatting} as declared by
 * {@code research/mappings/joined-1.12.2.srg} (see fixture README):
 * {@code func_175744_a} is {@code MD: a/a (I)La;} (line 15267),
 * {@code func_96300_b} is {@code MD: a/b (Ljava/lang/String;)La;}
 * (line 15271), {@code BLACK} / {@code DARK_BLUE} are {@code FD: a/a} /
 * {@code FD: a/b} (lines 3356-3357). Note the owner moved from 1.7.10's
 * {@code net.minecraft.util.EnumChatFormatting} to 1.12.2's
 * {@code net.minecraft.util.text.TextFormatting} — the fixture models the
 * real cross-era move the 1.7.10→1.12.2 bridge must cross.
 * Hand-authored from the mapping file, not extracted from any binary.
 * NEVER ship this class in a distributed jar (BLOCKERS.md B2).
 */
public final class TextFormatting {
    public static final TextFormatting BLACK = new TextFormatting();
    public static final TextFormatting DARK_BLUE = new TextFormatting();

    private TextFormatting() {
    }

    public static TextFormatting func_175744_a(int code) {
        return BLACK;
    }

    public static TextFormatting func_96300_b(String name) {
        return BLACK;
    }
}
