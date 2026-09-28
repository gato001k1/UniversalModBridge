package dev.umb.guimap;

/**
 * Reads the width/height straight out of a PNG's IHDR chunk (8-byte signature, 4-byte length,
 * 4-byte "IHDR" tag, then 4 bytes width + 4 bytes height, all big-endian). No image library
 * needed. Used to report each GUI's ACTUAL texture-sheet size rather than assuming the common
 * 256x256 convention, per GENERALIZATION-PLAN.md GAP 2's "texture SIZE assumption" ask.
 */
public final class PngUtil {
    private PngUtil() {}

    public static final class Dim { public final int w, h; Dim(int w, int h) { this.w = w; this.h = h; } }

    public static Dim dimensions(byte[] png) {
        if (png == null || png.length < 24) return null;
        // signature: 137 80 78 71 13 10 26 10
        if ((png[0] & 0xFF) != 0x89 || png[1] != 'P' || png[2] != 'N' || png[3] != 'G') return null;
        if (png[12] != 'I' || png[13] != 'H' || png[14] != 'D' || png[15] != 'R') return null;
        int w = be32(png, 16), h = be32(png, 20);
        if (w <= 0 || h <= 0) return null;
        return new Dim(w, h);
    }

    private static int be32(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16) | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }
}
