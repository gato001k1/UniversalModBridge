package dev.umb.hostagent.content.fluid;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Tints the vanilla empty-bucket silhouette with the average color of a fluid still sprite. */
final class BucketTextureComposer {
    private BucketTextureComposer() {}

    static void compose(Path emptyBucket, Path still, Path output) throws IOException {
        BufferedImage bucket = ImageIO.read(emptyBucket.toFile());
        BufferedImage fluid = ImageIO.read(still.toFile());
        if (bucket == null || fluid == null) throw new IOException("unreadable bucket/fluid texture");
        long r = 0, g = 0, b = 0, n = 0;
        for (int y = 0; y < fluid.getHeight(); y++) for (int x = 0; x < fluid.getWidth(); x++) {
            int argb = fluid.getRGB(x, y), a = (argb >>> 24) & 255;
            if (a < 32) continue;
            r += (argb >>> 16) & 255; g += (argb >>> 8) & 255; b += argb & 255; n++;
        }
        if (n == 0) throw new IOException("fluid still has no visible pixels");
        Color tint = new Color((int) (r / n), (int) (g / n), (int) (b / n));
        BufferedImage out = new BufferedImage(bucket.getWidth(), bucket.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < bucket.getHeight(); y++) for (int x = 0; x < bucket.getWidth(); x++) {
            int argb = bucket.getRGB(x, y), a = (argb >>> 24) & 255;
            if (a == 0) { out.setRGB(x, y, 0); continue; }
            int shade = Math.max(0, Math.min(255, (((argb >>> 16) & 255) + ((argb >>> 8) & 255) + (argb & 255)) / 3));
            int rr = tint.getRed() * shade / 255, gg = tint.getGreen() * shade / 255, bb = tint.getBlue() * shade / 255;
            out.setRGB(x, y, (a << 24) | (rr << 16) | (gg << 8) | bb);
        }
        Files.createDirectories(output.getParent());
        ImageIO.write(out, "png", output.toFile());
    }
}
