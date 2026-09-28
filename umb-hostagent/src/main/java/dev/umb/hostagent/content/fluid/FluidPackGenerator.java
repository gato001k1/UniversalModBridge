package dev.umb.hostagent.content.fluid;

import dev.umb.hostagent.content.LegacyIds;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** Generates only the new per-namespace fluid pack, using a staging directory then atomic swap. */
public final class FluidPackGenerator {
    public record Counts(int fluids, int stillWired, int flowingWired, int bucketModels,
                         int bucketTextureMissing, int bucketTexturesGenerated,
                         int missingStill, int missingFlowing,
                         int levelBlockstates, int levelBlockstatesSkipped) {}

    private FluidPackGenerator() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 4 && args.length != 5) throw new IllegalArgumentException(
                "usage: FluidPackGenerator <snapshot> <asset-root> <target> <namespace> [client.jar]");
        Counts c = generate(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]), args[3],
                args.length == 5 ? Path.of(args[4]) : null);
        System.out.println("UMB-FLUID-PACK fluids=" + c.fluids() + " stillWired=" + c.stillWired()
                + " flowingWired=" + c.flowingWired() + " bucketModels=" + c.bucketModels()
                + " bucketTexturesGenerated=" + c.bucketTexturesGenerated()
                + " bucketTextureMissing=" + c.bucketTextureMissing() + " missingStill=" + c.missingStill()
                + " missingFlowing=" + c.missingFlowing()
                + " levelBlockstates=" + c.levelBlockstates()
                + " levelBlockstatesSkipped=" + c.levelBlockstatesSkipped());
    }

    public static Counts generate(Path snapshot, Path sourceAssets, Path target, String rawNamespace)
            throws IOException {
        return generate(snapshot, sourceAssets, target, rawNamespace, null);
    }

    public static Counts generate(Path snapshot, Path sourceAssets, Path target, String rawNamespace,
                                  Path clientJar) throws IOException {
        String ns = LegacyIds.sanitizeNamespace(rawNamespace);
        List<FluidEntry> fluids = FluidSnapshotReader.load(snapshot, ns);
        Path parent = target.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path staging = parent.resolve(target.getFileName() + ".staging-" + System.nanoTime());
        Files.createDirectories(staging);
        try {
            Files.writeString(staging.resolve("pack.mcmeta"), "{\n  \"pack\": {\n"
                    + "    \"description\": \"UMB generated fluid assets - " + ns + "\",\n"
                    + "    \"min_format\": 88,\n    \"max_format\": 88\n  }\n}\n", StandardCharsets.UTF_8);
            StringBuilder manifest = new StringBuilder("{\n  \"fluids\": [\n");
            int still = 0, flow = 0, bucketModels = 0, bucketMissing = 0, bucketGenerated = 0;
            int missingStill = 0, missingFlow = 0, levelStates = 0, levelSkipped = 0;
            for (int i = 0; i < fluids.size(); i++) {
                FluidEntry f = fluids.get(i);
                String path = LegacyIds.sanitizePath(f.name);
                String base = "fluid_" + path;
                String stillTex = copyFluidTexture(sourceAssets, ns, f.name, f.iconName, "still", staging, base);
                String flowTex = copyFluidTexture(sourceAssets, ns, f.name, f.iconName, "flow", staging, base);
                if (stillTex == null) missingStill++; else still++;
                if (flowTex == null) missingFlow++; else flow++;
                // The registered LiquidBlock is ns:fluid_<path>_block (FluidRegistrar builds the
                // same base from the same name with the same sanitizer), and it carries
                // LiquidBlock.LEVEL (0-15). Without a blockstate, 26.2 logs "Missing model for
                // variant" once per level (16 warnings per fluid). Mirror vanilla's own
                // blockstates/water.json (verified in the 26.2 client jar): a single "" variant -
                // which matches every level state - pointing at one particle-only model, with the
                // fluid's still texture as the particle (the same id FluidStateModelPatcher wires
                // into its runtime FluidModel materials, so the sprite is guaranteed stitched).
                String particle = stillTex != null ? stillTex : flowTex;
                if (particle == null) {
                    levelSkipped++;
                } else {
                    writeLiquidBlockstate(staging, ns, base + "_block");
                    writeLiquidBlockModel(staging, ns, base + "_block", particle);
                    levelStates++;
                }
                BucketTextureResult bucketTex = copyBucketTexture(sourceAssets, ns, f.name, staging, base,
                        clientJar, stillTex == null ? null : staging.resolve("assets").resolve(ns)
                                .resolve("textures").resolve("block").resolve(base + "_still.png"));
                if (!bucketTex.present()) bucketMissing++;
                if (bucketTex.generated()) bucketGenerated++;
                writeBucketModel(staging, ns, base);
                bucketModels++;
                String stillJson = stillTex == null ? "null" : quote(stillTex);
                String flowJson = flowTex == null ? "null" : quote(flowTex);
                manifest.append("    {\"fluid\": ").append(quote(ns + ":" + base))
                        .append(", \"still\": ").append(stillJson)
                        .append(", \"flowing\": ").append(flowJson).append("}")
                        .append(i + 1 == fluids.size() ? "\n" : ",\n");
                writeLang(staging, ns, base + "_bucket", pretty(f.name) + " Bucket");
            }
            manifest.append("  ]\n}\n");
            Path data = staging.resolve("data").resolve(ns).resolve("umb");
            Files.createDirectories(data);
            Files.writeString(data.resolve("fluid_models.json"), manifest.toString(), StandardCharsets.UTF_8);
            Path backup = null;
            if (Files.exists(target)) {
                backup = parent.resolve(target.getFileName() + ".backup-" + System.nanoTime());
                Files.move(target, backup, StandardCopyOption.ATOMIC_MOVE);
            }
            try {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ex) {
                if (backup != null && !Files.exists(target)) Files.move(backup, target, StandardCopyOption.ATOMIC_MOVE);
                throw ex;
            }
            if (backup != null) deleteTree(backup);
            return new Counts(fluids.size(), still, flow, bucketModels, bucketMissing, bucketGenerated,
                    missingStill, missingFlow, levelStates, levelSkipped);
        } catch (IOException | RuntimeException ex) {
            deleteTree(staging);
            throw ex;
        }
    }

    private static String copyFluidTexture(Path root, String ns, String fluidName, String icon, String kind,
                                           Path staging, String base) throws IOException {
        String sourceName = icon == null ? fluidName.replace("_fluid", "")
                : icon.substring(icon.indexOf(':') + 1);
        String suffix = kind.equals("still") ? "_still" : "_flow";
        String sourceStem = sourceName.endsWith("_still") ? sourceName.substring(0, sourceName.length() - 6) : sourceName;
        String candidate = sourceStem + suffix;
        Path source = root.resolve("assets").resolve(ns).resolve("textures").resolve("atlas-dump")
                .resolve("blocks").resolve(candidate + ".png");
        if (!Files.isRegularFile(source) && kind.equals("flow")) {
            source = root.resolve("assets").resolve(ns).resolve("textures").resolve("atlas-dump")
                    .resolve("blocks").resolve(sourceStem + "_flowing.png");
        }
        if (!Files.isRegularFile(source)) return null;
        Path dest = staging.resolve("assets").resolve(ns).resolve("textures").resolve("block")
                .resolve(base + suffix + ".png");
        Files.createDirectories(dest.getParent());
        Files.copy(source, dest);
        return ns + ":block/" + base + suffix;
    }

    private record BucketTextureResult(boolean present, boolean generated) {}

    private static BucketTextureResult copyBucketTexture(Path root, String ns, String name, Path staging, String base,
                                                          Path clientJar, Path stillTexture)
            throws IOException {
        List<String> candidates = new ArrayList<>();
        candidates.add("bucket_" + name);
        candidates.add("bucket_" + name.replace("_fluid", ""));
        candidates.add("fluid." + name.replace("_fluid", "") + ".bucket");
        for (String c : candidates) {
            Path source = root.resolve("assets").resolve(ns).resolve("textures").resolve("atlas-dump")
                    .resolve("items").resolve(c + ".png");
            if (!Files.isRegularFile(source)) continue;
            Path dest = staging.resolve("assets").resolve(ns).resolve("textures").resolve("item")
                    .resolve(base + ".png");
            Files.createDirectories(dest.getParent());
            Files.copy(source, dest);
            return new BucketTextureResult(true, false);
        }
        if (clientJar != null && stillTexture != null && Files.isRegularFile(stillTexture)) {
            Path empty = staging.resolve(".vanilla-bucket.png");
            try (java.util.jar.JarFile jar = new java.util.jar.JarFile(clientJar.toFile())) {
                var e = jar.getJarEntry("assets/minecraft/textures/item/bucket.png");
                if (e != null) try (var in = jar.getInputStream(e)) {
                    Files.copy(in, empty, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            if (Files.isRegularFile(empty)) {
                Path dest = staging.resolve("assets").resolve(ns).resolve("textures").resolve("item")
                        .resolve(base + ".png");
                BucketTextureComposer.compose(empty, stillTexture, dest);
                Files.deleteIfExists(empty);
                return new BucketTextureResult(true, true);
            }
        }
        return new BucketTextureResult(false, false);
    }

    private static void writeBucketModel(Path staging, String ns, String base) throws IOException {
        Path model = staging.resolve("assets").resolve(ns).resolve("models").resolve("item")
                .resolve(base + ".json");
        Files.createDirectories(model.getParent());
        Files.writeString(model, "{\n  \"parent\": \"minecraft:item/generated\",\n"
                + "  \"textures\": {\"layer0\": \"" + ns + ":item/" + base + "\"}\n}\n",
                StandardCharsets.UTF_8);
    }

    /**
     * Blockstate for a registered legacy LiquidBlock, mirroring vanilla water.json byte-shape: one
     * {@code ""} variant (it matches all 16 LEVEL states) at a same-named block model.
     */
    private static void writeLiquidBlockstate(Path staging, String ns, String block) throws IOException {
        Path file = staging.resolve("assets").resolve(ns).resolve("blockstates").resolve(block + ".json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\n  \"variants\": {\n    \"\": {\n      \"model\": \"" + ns + ":block/"
                + block + "\"\n    }\n  }\n}\n", StandardCharsets.UTF_8);
    }

    /**
     * The particle-only model vanilla water.json uses: no parent, no elements, so the blockstate
     * bakes silently for every level while the in-world fluid draw stays entirely with the
     * runtime FluidModel. The particle is the fluid's own still (else flowing) texture id.
     */
    private static void writeLiquidBlockModel(Path staging, String ns, String block, String particle)
            throws IOException {
        Path file = staging.resolve("assets").resolve(ns).resolve("models").resolve("block")
                .resolve(block + ".json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\n  \"textures\": {\n    \"particle\": \"" + particle + "\"\n  }\n}\n",
                StandardCharsets.UTF_8);
    }

    private static void writeLang(Path staging, String ns, String base, String label) throws IOException {
        Path lang = staging.resolve("assets").resolve(ns).resolve("lang");
        Files.createDirectories(lang);
        Path file = lang.resolve("en_us.json");
        String old = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8).trim() : "{}";
        if (old.equals("{}")) old = "{\n"; else old = old.substring(0, old.length() - 1) + ",\n";
        Files.writeString(file, old + "  \"item." + ns + "." + base + "\": " + quote(label) + "\n}\n",
                StandardCharsets.UTF_8);
    }

    private static String pretty(String raw) {
        String s = raw.replace('_', ' ').replace('-', ' ').trim();
        return s.isEmpty() ? raw : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
    private static String quote(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (var walk = Files.walk(p)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(x -> {
                try { Files.deleteIfExists(x); } catch (IOException e) { throw new RuntimeException(e); }
            });
        }
    }
}
