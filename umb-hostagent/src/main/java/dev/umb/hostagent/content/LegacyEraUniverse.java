package dev.umb.hostagent.content;

import dev.umb.bridge.api.ActivationResult;
import dev.umb.bridge.api.EntityHandle;
import dev.umb.bridge.api.HostPlayer;
import dev.umb.bridge.api.HostWorld;
import dev.umb.bridge.api.ItemUseResult;
import dev.umb.bridge.api.LegacyBridge;
import dev.umb.bridge.api.StackData;
import dev.umb.bridge.api.TileHandle;
import dev.umb.hostagent.AgentLog;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared forwarding for every isolated legacy era universe (1.12.2, 1.16.5, ...). An era
 * subclass only implements {@link #boot(HostWorld)}: how to build its isolated loader and
 * start its real in-universe bridge, which it publishes to {@link #real}. Every bridge call
 * forwards null-safely to that bridge and logs failures under the era's label.
 */
public abstract class LegacyEraUniverse implements LegacyBridge {

    protected volatile LegacyBridge real;
    protected final AtomicBoolean bootAttempted = new AtomicBoolean(false);
    private final String label;

    protected LegacyEraUniverse(String label) {
        this.label = label;
    }

    // ---------------------------------------------------------------- forwarding (null-safe)


    protected final LegacyBridge peek() {
        return real;
    }

    @Override
    public boolean isBooted() {
        LegacyBridge r = real;
        if (r == null) return false;
        try {
            return r.isBooted();
        } catch (Throwable t) {
            AgentLog.error(label + ".isBooted", t, 3);
            return false;
        }
    }

    @Override
    public TileHandle createTile(String legacyBlockId, int x, int y, int z) {
        LegacyBridge r = peek();
        if (r == null) return null;
        try {
            return r.createTile(legacyBlockId, x, y, z);
        } catch (Throwable t) {
            AgentLog.error(label + ".createTile(" + legacyBlockId + ")", t, 3);
            return null;
        }
    }

    @Override
    public ActivationResult activate(String legacyBlockId, int x, int y, int z, HostPlayer player,
            int side, float hitX, float hitY, float hitZ) {
        LegacyBridge r = peek();
        if (r == null) return ActivationResult.DECLINED;
        try {
            return r.activate(legacyBlockId, x, y, z, player, side, hitX, hitY, hitZ);
        } catch (Throwable t) {
            AgentLog.error(label + ".activate(" + legacyBlockId + ")", t, 3);
            return ActivationResult.DECLINED;
        }
    }

    @Override
    public void clicked(String legacyBlockId, int x, int y, int z, HostPlayer player) {
        LegacyBridge r = peek();
        if (r != null) {
            try {
                r.clicked(legacyBlockId, x, y, z, player);
            } catch (Throwable t) {
                AgentLog.error(label + ".clicked(" + legacyBlockId + ")", t, 3);
            }
        }
    }

    @Override
    public void tickTile(TileHandle t) {
        LegacyBridge r = peek();
        if (r != null) {
            try {
                r.tickTile(t);
            } catch (Throwable e) {
            AgentLog.error(label + ".tickTile", e, 3);
            }
        }
    }

    @Override
    public void shutdown() {
        LegacyBridge r = real;
        if (r != null) {
            try {
                r.shutdown();
            } catch (Throwable t) {
            AgentLog.error(label + ".shutdown", t, 2);
            }
        }
    }

    @Override
    public void placedBy(String legacyBlockId, int x, int y, int z, HostPlayer player) {
        forwardVoid("placedBy", legacyBlockId,
                r -> r.placedBy(legacyBlockId, x, y, z, player));
    }

    @Override
    public void placedBy(String legacyBlockId, int x, int y, int z, HostPlayer player,
            StackData placedStack) {
        forwardVoid("placedBy", legacyBlockId,
                r -> r.placedBy(legacyBlockId, x, y, z, player, placedStack));
    }

    @Override
    public void added(String legacyBlockId, int x, int y, int z) {
        forwardVoid("added", legacyBlockId, r -> r.added(legacyBlockId, x, y, z));
    }

    @Override
    public void neighborChanged(String legacyBlockId, int x, int y, int z,
            String neighborLegacyBlockId) {
        forwardVoid("neighborChanged", legacyBlockId,
                r -> r.neighborChanged(legacyBlockId, x, y, z, neighborLegacyBlockId));
    }

    @Override
    public void broken(String legacyBlockId, int x, int y, int z, int meta, HostPlayer player) {
        forwardVoid("broken", legacyBlockId,
                r -> r.broken(legacyBlockId, x, y, z, meta, player));
    }

    @Override
    public boolean canPlaceAt(String legacyBlockId, int x, int y, int z) {
        LegacyBridge r = peek();
        if (r == null) return true;
        try {
            return r.canPlaceAt(legacyBlockId, x, y, z);
        } catch (Throwable t) {
            AgentLog.error(label + ".canPlaceAt(" + legacyBlockId + ")", t, 3);
            return true;
        }
    }

    @Override
    public StackData useItemRightClick(String legacyItemId, HostPlayer player) {
        LegacyBridge r = peek();
        if (r == null) return null;
        try {
            return r.useItemRightClick(legacyItemId, player);
        } catch (Throwable t) {
            AgentLog.error(label + ".useItemRightClick(" + legacyItemId + ")", t, 3);
            return null;
        }
    }

    @Override
    public ItemUseResult useItemOnBlock(String legacyItemId, HostPlayer player, int x, int y, int z,
            int side, float hitX, float hitY, float hitZ) {
        LegacyBridge r = peek();
        if (r == null) return ItemUseResult.DECLINED;
        try {
            return r.useItemOnBlock(legacyItemId, player, x, y, z, side, hitX, hitY, hitZ);
        } catch (Throwable t) {
            AgentLog.error(label + ".useItemOnBlock(" + legacyItemId + ")", t, 3);
            return ItemUseResult.DECLINED;
        }
    }

    @Override
    public EntityHandle restoreEntity(byte[] nbt) {
        LegacyBridge r = peek();
        if (r == null) return null;
        try {
            return r.restoreEntity(nbt);
        } catch (Throwable t) {
            AgentLog.error(label + ".restoreEntity", t, 3);
            return null;
        }
    }

    // ---- interaction surfaces: forward so era content gets more than defaults ----

    @Override
    public void tickBlock(String legacyBlockId, int x, int y, int z, boolean isRandom) {
        forwardVoid("tickBlock", legacyBlockId,
                r -> r.tickBlock(legacyBlockId, x, y, z, isRandom));
    }

    @Override
    public void entityInside(String legacyBlockId, int x, int y, int z, HostPlayer player) {
        forwardVoid("entityInside", legacyBlockId,
                r -> r.entityInside(legacyBlockId, x, y, z, player));
    }

    @Override
    public int placementMetadata(String legacyBlockId, int x, int y, int z, int side,
            float hitX, float hitY, float hitZ, int meta) {
        LegacyBridge r = peek();
        if (r == null) return meta;
        try {
            return r.placementMetadata(legacyBlockId, x, y, z, side, hitX, hitY, hitZ, meta);
        } catch (Throwable t) {
            AgentLog.error(label + ".placementMetadata(" + legacyBlockId + ")", t, 3);
            return meta;
        }
    }

    @Override
    public java.util.List<StackData> blockDrops(String legacyBlockId, int x, int y, int z, int meta,
            int fortune) {
        LegacyBridge r = peek();
        if (r == null) return null;
        try {
            return r.blockDrops(legacyBlockId, x, y, z, meta, fortune);
        } catch (Throwable t) {
            AgentLog.error(label + ".blockDrops(" + legacyBlockId + ")", t, 3);
            return java.util.Collections.emptyList();
        }
    }

    @Override
    public void stepOn(String legacyBlockId, int x, int y, int z, HostPlayer player) {
        forwardVoid("stepOn", legacyBlockId, r -> r.stepOn(legacyBlockId, x, y, z, player));
    }

    @Override
    public void fallOn(String legacyBlockId, int x, int y, int z, HostPlayer player, float distance) {
        forwardVoid("fallOn", legacyBlockId,
                r -> r.fallOn(legacyBlockId, x, y, z, player, distance));
    }

    @Override
    public void animateBlock(String legacyBlockId, int x, int y, int z) {
        forwardVoid("animateBlock", legacyBlockId, r -> r.animateBlock(legacyBlockId, x, y, z));
    }

    @Override
    public boolean hasComparatorInputOverride(String legacyBlockId) {
        LegacyBridge r = peek();
        if (r == null) return false;
        try {
            return r.hasComparatorInputOverride(legacyBlockId);
        } catch (Throwable t) {
            AgentLog.error(label + ".hasComparatorInputOverride(" + legacyBlockId + ")",
                    t, 3);
            return false;
        }
    }

    @Override
    public int comparatorInputOverride(String legacyBlockId, int x, int y, int z) {
        LegacyBridge r = peek();
        if (r == null) return 0;
        try {
            return r.comparatorInputOverride(legacyBlockId, x, y, z);
        } catch (Throwable t) {
            AgentLog.error(label + ".comparatorInputOverride(" + legacyBlockId + ")",
                    t, 3);
            return 0;
        }
    }

    @Override
    public java.util.List<String> itemTooltip(String legacyItemId, StackData stack, boolean advanced) {
        LegacyBridge r = peek();
        if (r == null) return java.util.Collections.emptyList();
        try {
            return r.itemTooltip(legacyItemId, stack, advanced);
        } catch (Throwable t) {
            AgentLog.error(label + ".itemTooltip(" + legacyItemId + ")", t, 3);
            return java.util.Collections.emptyList();
        }
    }

    @Override
    public StackData itemInventoryTick(String legacyItemId, StackData stack, HostPlayer player,
            int slot, boolean current) {
        LegacyBridge r = peek();
        if (r == null) return stack;
        try {
            return r.itemInventoryTick(legacyItemId, stack, player, slot, current);
        } catch (Throwable t) {
            AgentLog.error(label + ".itemInventoryTick(" + legacyItemId + ")", t, 3);
            return stack;
        }
    }

    @Override
    public int itemUseDuration(String legacyItemId, StackData stack) {
        LegacyBridge r = peek();
        if (r == null) return 0;
        try {
            return r.itemUseDuration(legacyItemId, stack);
        } catch (Throwable t) {
            AgentLog.error(label + ".itemUseDuration(" + legacyItemId + ")", t, 3);
            return 0;
        }
    }

    @Override
    public String itemUseAction(String legacyItemId, StackData stack) {
        LegacyBridge r = peek();
        if (r == null) return "none";
        try {
            return r.itemUseAction(legacyItemId, stack);
        } catch (Throwable t) {
            AgentLog.error(label + ".itemUseAction(" + legacyItemId + ")", t, 3);
            return "none";
        }
    }

    @Override
    public float itemDestroySpeed(String legacyItemId, StackData stack, String legacyBlockId) {
        LegacyBridge r = peek();
        if (r == null) return Float.NaN;
        try {
            return r.itemDestroySpeed(legacyItemId, stack, legacyBlockId);
        } catch (Throwable t) {
            AgentLog.error(label + ".itemDestroySpeed(" + legacyItemId + ")", t, 3);
            return Float.NaN;
        }
    }

    @Override
    public boolean itemCanHarvestBlock(String legacyItemId, StackData stack, String legacyBlockId) {
        LegacyBridge r = peek();
        if (r == null) return false;
        try {
            return r.itemCanHarvestBlock(legacyItemId, stack, legacyBlockId);
        } catch (Throwable t) {
            AgentLog.error(label + ".itemCanHarvestBlock(" + legacyItemId + ")", t, 3);
            return false;
        }
    }

    @Override
    public void itemUsingTick(String legacyItemId, StackData stack, HostPlayer player, int remaining) {
        forwardVoid("itemUsingTick", legacyItemId,
                r -> r.itemUsingTick(legacyItemId, stack, player, remaining));
    }

    @Override
    public void itemStoppedUsing(String legacyItemId, StackData stack, HostPlayer player,
            int remaining) {
        forwardVoid("itemStoppedUsing", legacyItemId,
                r -> r.itemStoppedUsing(legacyItemId, stack, player, remaining));
    }

    @Override
    public StackData itemEaten(String legacyItemId, StackData stack, HostPlayer player) {
        LegacyBridge r = peek();
        if (r == null) return stack;
        try {
            return r.itemEaten(legacyItemId, stack, player);
        } catch (Throwable t) {
            AgentLog.error(label + ".itemEaten(" + legacyItemId + ")", t, 3);
            return stack;
        }
    }

    @Override
    public void tickEvents(HostWorld world, HostPlayer[] players, boolean endPhase) {
        forwardVoid("tickEvents", "", r -> r.tickEvents(world, players, endPhase));
    }

    @Override
    public void tickEntities() {
        forwardVoid("tickEntities", "", LegacyBridge::tickEntities);
    }

    @Override
    public dev.umb.bridge.api.GlEmulationSession.Mesh renderHud(String playerName, float partialTicks,
                                                               int width, int height) {
        LegacyBridge r = peek();
        if (r == null) return null;
        try {
            return r.renderHud(playerName, partialTicks, width, height);
        } catch (Throwable t) {
            AgentLog.error(label + ".renderHud", t, 3);
            return null;
        }
    }

    @Override
    public dev.umb.bridge.api.GlEmulationSession.Mesh renderGui(String playerName, float partialTicks,
                                                                  int width, int height) {
        LegacyBridge r = peek();
        if (r == null) return null;
        try {
            return r.renderGui(playerName, partialTicks, width, height);
        } catch (Throwable t) {
            AgentLog.error(label + ".renderGui", t, 3);
            return null;
        }
    }

    @Override
    public LegacyBridge.CameraState cameraState(String playerName) {
        LegacyBridge r = peek();
        if (r == null) return null;
        try {
            return r.cameraState(playerName);
        } catch (Throwable t) {
            AgentLog.error(label + ".cameraState", t, 3);
            return null;
        }
    }

    @Override
    public float thirdPersonDistance(String playerName) {
        LegacyBridge r = peek();
        if (r == null) return Float.NaN;
        try {
            return r.thirdPersonDistance(playerName);
        } catch (Throwable t) {
            AgentLog.error(label + ".thirdPersonDistance", t, 3);
            return Float.NaN;
        }
    }

    @Override
    public void syncPlayers(java.util.List<HostPlayer> livePlayers) {
        forwardVoid("syncPlayers", "", r -> r.syncPlayers(livePlayers));
    }

    @Override
    public void playerRespawn(HostPlayer player) {
        forwardVoid("playerRespawn", "", r -> r.playerRespawn(player));
    }

    @Override
    public boolean acceptInput(HostPlayer player, dev.umb.bridge.api.InputData input) {
        LegacyBridge r = peek();
        if (r == null) return false;
        try {
            return r.acceptInput(player, input);
        } catch (Throwable t) {
            AgentLog.error(label + ".acceptInput", t, 3);
            return false;
        }
    }

    @Override
    public java.util.List<dev.umb.bridge.api.LegacyBridge.KeyBindingData> keyBindings() {
        LegacyBridge r = peek();
        return r == null ? java.util.Collections.emptyList() : r.keyBindings();
    }

    @Override
    public boolean setKeyBinding(String stableId, int legacyCode) {
        LegacyBridge r = peek();
        return r != null && r.setKeyBinding(stableId, legacyCode);
    }

    @Override
    public java.util.List<dev.umb.bridge.api.EffectData> drainClientEffects() {
        LegacyBridge r = peek();
        if (r == null) return java.util.Collections.emptyList();
        try {
            return r.drainClientEffects();
        } catch (Throwable t) {
            AgentLog.error(label + ".drainClientEffects", t, 3);
            return java.util.Collections.emptyList();
        }
    }

    @Override
    public double[] collisionBounds(String legacyBlockId, int x, int y, int z) {
        LegacyBridge r = peek();
        if (r == null) return null;
        try {
            return r.collisionBounds(legacyBlockId, x, y, z);
        } catch (Throwable t) {
            AgentLog.error(label + ".collisionBounds(" + legacyBlockId + ")", t, 3);
            return null;
        }
    }

    @Override
    public boolean hasItemRenderer(String legacyItemId, int damage, String renderType) {
        LegacyBridge r = peek();
        if (r == null) return false;
        try {
            return r.hasItemRenderer(legacyItemId, damage, renderType);
        } catch (Throwable t) {
            AgentLog.error(label + ".hasItemRenderer(" + legacyItemId + ")", t, 3);
            return false;
        }
    }

    @Override
    public dev.umb.bridge.api.EntityRenderCapture captureItem(String legacyItemId, int count,
            int damage, byte[] nbt, String renderType, float partialTick, boolean transformOnly) {
        LegacyBridge r = peek();
        if (r == null) return null;
        try {
            return r.captureItem(legacyItemId, count, damage, nbt, renderType, partialTick,
                    transformOnly);
        } catch (Throwable t) {
            AgentLog.error(label + ".captureItem(" + legacyItemId + ")", t, 3);
            return null;
        }
    }

    @Override
    public java.util.List<double[]> cachedShape(String legacyBlockId, int x, int y, int z,
            boolean selection) {
        LegacyBridge r = peek();
        if (r == null) return null;
        try {
            return r.cachedShape(legacyBlockId, x, y, z, selection);
        } catch (Throwable t) {
            AgentLog.error(label + ".cachedShape(" + legacyBlockId + ")", t, 3);
            return null;
        }
    }

    @Override
    public java.util.List<double[]> collisionBoxes(String legacyBlockId, int x, int y, int z) {
        LegacyBridge r = peek();
        if (r == null) return java.util.Collections.emptyList();
        try {
            return r.collisionBoxes(legacyBlockId, x, y, z);
        } catch (Throwable t) {
            AgentLog.error(label + ".collisionBoxes(" + legacyBlockId + ")", t, 3);
            return null;
        }
    }

    @Override
    public java.util.List<double[]> selectionBoxes(String legacyBlockId, int x, int y, int z) {
        LegacyBridge r = peek();
        if (r == null) return java.util.Collections.emptyList();
        try {
            return r.selectionBoxes(legacyBlockId, x, y, z);
        } catch (Throwable t) {
            AgentLog.error(label + ".selectionBoxes(" + legacyBlockId + ")", t, 3);
            return null;
        }
    }

    @Override
    public void invalidateShape(String legacyBlockId, int x, int y, int z) {
        LegacyBridge r = peek();
        if (r != null) r.invalidateShape(legacyBlockId, x, y, z);
    }

    @Override
    public void guiButtonPacket(String guiClass, int buttonId, int x, int y, int z, String[] args) {
        forwardVoid("guiButtonPacket", guiClass, r -> r.guiButtonPacket(guiClass, buttonId, x, y, z, args));
    }

    @Override
    public boolean guiMouseClick(String guiClass, int x, int y, int z,
                                 int guiX, int guiY, int button, int screenX, int screenY) {
        LegacyBridge r = peek();
        return r != null && r.guiMouseClick(guiClass, x, y, z, guiX, guiY, button, screenX, screenY);
    }

    @Override
    public boolean guiKeyTyped(String guiClass, char typedChar, int keyCode) {
        LegacyBridge r = peek();
        return r != null && r.guiKeyTyped(guiClass, typedChar, keyCode);
    }

    @Override
    public boolean guiTextFocused() {
        LegacyBridge r = peek();
        return r != null && r.guiTextFocused();
    }

    private void forwardVoid(String what, String id, java.util.function.Consumer<LegacyBridge> call) {
        LegacyBridge r = peek();
        if (r == null) return;
        try {
            call.accept(r);
        } catch (Throwable t) {
            AgentLog.error(label + "." + what + "(" + id + ")", t, 3);
        }
    }

    // ---------------------------------------------------------------- internals

    protected final void checkEraJvmFlags() {
        List<String> args;
        try {
            args = ManagementFactory.getRuntimeMXBean().getInputArguments();
        } catch (Throwable t) {
            AgentLog.loud("legacy era universe: cannot read JVM input arguments (" + t
                    + ") - proceeding without the flag pre-check");
            return;
        }
        List<String> needed = Arrays.asList(
                "java.base/sun.security.util=ALL-UNNAMED",
                "java.base/java.util.jar=ALL-UNNAMED");
        List<String> missing = new ArrayList<>();
        for (String need : needed) {
            boolean present = false;
            for (int i = 0; i < args.size(); i++) {
                if ("--add-opens".equals(args.get(i)) && i + 1 < args.size()
                        && need.equals(args.get(i + 1))) {
                    present = true;
                    break;
                }
                if (args.get(i).equals("--add-opens=" + need)) {
                    present = true;
                    break;
                }
            }
            if (!present) missing.add(need);
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("legacy era universe: the running JVM is missing "
                    + "era-native flags needed at STARTUP (ModLauncher SecureJarHandler reads "
                    + "JDK internals legal on Java 8): --add-opens " + missing
                    + ". The launcher must add them.");
        }
    }

    protected static File repoRoot() {
        String home = System.getProperty("umb.home");
        if (home != null && !home.isEmpty()) return new File(home).getAbsoluteFile();
        String prop = System.getProperty("umb.repo");
        if (prop != null && !prop.isEmpty()) {
            return new File(prop).getAbsoluteFile();
        }
        // Same derivation as UmbUniverse.repoRoot (duplicated, not refactored, to keep this
        try {
            java.security.CodeSource cs =
                    LegacyEraUniverse.class.getProtectionDomain().getCodeSource();
            if (cs != null) {
                File jar = new File(cs.getLocation().toURI()).getAbsoluteFile();
                File installed = jar.isFile() ? jar.getParentFile() : jar;
                if (installed != null && (new File(installed, "manifest.json").isFile()
                        || new File(installed, "jvm-arguments.txt").isFile()
                        || new File(installed, "inputs").isDirectory())) return installed;
                File derived = jar.getParentFile().getParentFile().getParentFile();
                if (derived != null && new File(derived, "umb-legacy-1122").isDirectory()) {
                    return derived;
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        throw new IllegalStateException("cannot determine repo root: pass -Dumb.repo=<repo>");
    }

    protected static File firstFile(File... candidates) {
        for (File f : candidates) if (f != null && f.isFile()) return f;
        return candidates[0];
    }

    protected static List<File> readManifest(File repo, File manifest) throws Exception {
        List<File> out = new ArrayList<>();
        for (String line : Files.readAllLines(manifest.toPath(), StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            File f = new File(repo, t).getAbsoluteFile();
            if (!f.isFile()) {
                throw new IllegalStateException("classpath-1122.txt references a missing file: "
                        + f + " - run umb-legacy-1122/build.ps1 first");
            }
            out.add(f);
        }
        if (out.isEmpty()) throw new IllegalStateException("classpath manifest is empty");
        return out;
    }

    protected static File requireFile(File f, String hint) {
        if (!f.isFile()) throw new IllegalStateException("missing required artifact: " + f
                + " - " + hint);
        return f;
    }

    /**
     * Same full-chain formatter as the in-universe bridge (duplicated, not shared: this class
     * must not gain dependencies beyond the JDK + bridge-api).
     */
    static String fullChain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<Throwable, Boolean>());
        appendChain(sb, t, seen, "");
        return sb.toString();
    }

    protected static void appendChain(StringBuilder sb, Throwable t,
            java.util.Set<Throwable> seen, String prefix) {
        if (t == null || !seen.add(t)) {
            return;
        }
        sb.append(prefix).append(t.getClass().getName()).append(": ").append(t.getMessage())
                .append('\n');
        StackTraceElement[] st = t.getStackTrace();
        for (int i = 0; i < Math.min(st.length, 30); i++) {
            String line = st[i].toString();
            // Stack-trim filter: keep our own, engine, and loader frames. Per-mod author
            // packages are deliberately NOT listed: the report must not name mods, and the
            // remaining frames are still fully informative without them.
            if (line.contains("<clinit>") || line.contains("dev.umb")
                    || line.contains("net.minecraftforge") || line.contains("net.minecraft")
                    || line.contains("cpw.mods")) {
                sb.append(prefix).append("  at ").append(line).append('\n');
            }
        }
        for (Throwable s : t.getSuppressed()) {
            sb.append(prefix).append("  suppressed:\n");
            appendChain(sb, s, seen, prefix + "    ");
        }
        appendChain(sb, t.getCause(), seen, prefix);
    }
}
