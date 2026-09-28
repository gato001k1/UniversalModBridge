package dev.umb.hostagent.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.umb.bridge.api.FieldPath;
import dev.umb.bridge.api.TileFieldSnapshot;
import dev.umb.bridge.api.TileHandle;
import dev.umb.hostagent.AgentLog;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Moving-parts lane: the server-tick-to-client-frame side channel for tile-entity fields
 * that drive block-entity renderer dynamics (spinning radar dishes, turret yaw/pitch), mirroring
 * {@link TileSnapshotChannel}'s own "plain in-process Map, not a real network packet" shortcut -
 * the embedded server and its client share one JVM here. Which fields to move comes from the
 * {@code renderer-dynamic-ops.json} sidecar's per-block field sets (only fields referenced by
 * extracted symbolic ops - never a whole object graph), resolved like every other piece of
 * extract-time data: system property {@code umb.legacy.dynamicsSidecar} wins, else
 * {@code <umb.repo>/research/out/legacy/rendermap/renderer-dynamic-ops.json}. Absent or
 * unreadable means no dynamic fields anywhere (every sync is then a no-op), never an error.
 *
 * <p>Protocol per block entity, all on the SERVER thread: {@link UmbLegacyBlockEntity#serverTick}
 * snapshots its block's field paths through the live {@link TileHandle} (honest
 * present/absent per path, exactly like the GUI lane) and publishes here ONLY when at least
 * one value changed since the last publish (a static machine sends once, then goes quiet).
 * A null/invalid handle publishes nothing and removes any stale entry, so the client never
 * renders a frozen last-known pose. The CLIENT (block-entity renderer) reads the same entry
 * at most once per frame - a plain memory read, never a cross-loader call from the render
 * thread. Entries are dropped when their block entity is removed
 * ({@code UmbLegacyBlockEntity.setRemoved}).</p>
 */
final class DynFieldChannel {

    /** One synced field: dotted key plus single/multi hop path for {@link TileHandle}. */
    static final class FieldSpec {
        final String key;
        final FieldPath path;
        FieldSpec(String key, FieldPath path) {
            this.key = key;
            this.path = path;
        }
    }

    /**
     * Door-live lane: one server-evaluated animation channel index. The extract-time
     * {@code channels[]} entry names a static {@code (String, TE-field-rooted runtime)}
     * evaluator plus the constant array index the renderer reads; the server calls
     * {@link TileHandle#evalStatic} once per distinct evaluator per tick and publishes
     * each declared index under its FULL channel key
     * ({@code owner.method.stringArg[index]}) - the exact key the client's symbolic
     * {@code {k:channel}} op looks up, so a present value animates and an absent one
     * (evaluator missing, index out of range, dead handle) honestly skips the op.
     */
    static final class ChannelSpec {
        final String key;
        final String owner;
        final String method;
        final String stringArg;
        final int index;
        final FieldPath objectPath;
        final AnimSpec anim;
        ChannelSpec(String key, String owner, String method, String stringArg, int index,
                FieldPath objectPath, AnimSpec anim) {
            this.key = key;
            this.owner = owner;
            this.method = method;
            this.stringArg = stringArg;
            this.index = index;
            this.objectPath = objectPath;
            this.anim = anim;
        }
    }

    /**
     * Door-live follow-up: how the server rebuilds a packet-installed animation object
     * (sidecar {@code anim} recipe, proven at extract time - see the rendermap lane).
     * Channels carrying a recipe evaluate through {@link TileHandle#evalAnim}; plain
     * channels keep the direct {@code evalStatic} path.
     */
    static final class AnimSpec {
        final String providerOwner;
        final String providerMethod;
        final FieldPath providerReceiver;
        final FieldPath[] providerArgs;
        final String clockOwner;
        final String clockMethod;
        final String clockField;
        AnimSpec(String providerOwner, String providerMethod, FieldPath providerReceiver,
                 FieldPath[] providerArgs, String clockOwner, String clockMethod,
                 String clockField) {
            this.providerOwner = providerOwner;
            this.providerMethod = providerMethod;
            this.providerReceiver = providerReceiver;
            this.providerArgs = providerArgs;
            this.clockOwner = clockOwner;
            this.clockMethod = clockMethod;
            this.clockField = clockField;
        }
    }

    /**
     * Door-live follow-up: a render dispatch through a helper object (sidecar
     * {@code dispatch}, proven at extract time). The server resolves the helper class
     * once per tile and syncs it in the update tag; the client draws exactly that
     * renderer's draws instead of every same-TE helper at once.
     */
    static final class DispatchSpec {
        final String owner;
        final String method;
        final FieldPath objectPath;
        DispatchSpec(String owner, String method, FieldPath objectPath) {
            this.owner = owner;
            this.method = method;
            this.objectPath = objectPath;
        }
    }

    private static final Map<BlockPosKey, TileFieldSnapshot> SNAPSHOTS = new ConcurrentHashMap<>();
    private static volatile Map<String, List<FieldSpec>> BLOCK_FIELDS;
    private static volatile Map<String, List<ChannelSpec>> BLOCK_CHANNELS;
    private static volatile Map<String, DispatchSpec> BLOCK_DISPATCHES;
    private static volatile Map<String, String> BLOCK_TECLASSES;

    private DynFieldChannel() {
    }

    /** Block-position key that does not retain a Level reference. */
    private record BlockPosKey(int x, int y, int z) {
        static BlockPosKey of(net.minecraft.core.BlockPos p) {
            return new BlockPosKey(p.getX(), p.getY(), p.getZ());
        }
    }

    /** Field specs for one block id, built once and cached (empty when the sidecar is absent). */
    static List<FieldSpec> fieldsFor(String blockId) {
        if (blockId == null) return List.of();
        Map<String, List<FieldSpec>> all = blockFields();
        List<FieldSpec> out = all.get(blockId);
        return out != null ? out : List.of();
    }

    /** Channel specs for one block id, built once and cached (empty when the sidecar is absent). */
    static List<ChannelSpec> channelsFor(String blockId) {
        if (blockId == null) return List.of();
        Map<String, List<ChannelSpec>> all = blockChannels();
        List<ChannelSpec> out = all.get(blockId);
        return out != null ? out : List.of();
    }

    /** Dispatch spec for one block id, or null (the union fallback then applies). */
    static DispatchSpec dispatchFor(String blockId) {
        if (blockId == null) return null;
        Map<String, DispatchSpec> all = blockDispatches();
        return all.get(blockId);
    }

    /**
     * Turret follow-up: the TESR-bound tile-entity class for one block id, or null when
     * the sidecar names none (no constraint - render like today).
     */
    static String teClassFor(String blockId) {
        if (blockId == null) return null;
        Map<String, String> all = blockTeClasses();
        return all.get(blockId);
    }

    /** Publishes a fresh snapshot (or clears on null) - SERVER thread only. */
    static void publish(net.minecraft.core.BlockPos pos, TileFieldSnapshot snapshot) {
        if (pos == null) return;
        BlockPosKey key = BlockPosKey.of(pos);
        if (snapshot == null) {
            SNAPSHOTS.remove(key);
        } else {
            SNAPSHOTS.put(key, snapshot);
        }
    }

    /** Reads the latest snapshot - CLIENT/render thread, plain map read, never null. */
    static TileFieldSnapshot get(net.minecraft.core.BlockPos pos) {
        if (pos == null) return TileFieldSnapshot.EMPTY;
        TileFieldSnapshot s = SNAPSHOTS.get(BlockPosKey.of(pos));
        return s != null ? s : TileFieldSnapshot.EMPTY;
    }

    /** Drops a block entity's entry when it is removed. Either side may call. */
    static void clear(net.minecraft.core.BlockPos pos) {
        if (pos != null) SNAPSHOTS.remove(BlockPosKey.of(pos));
    }

    /** For tests only. */
    static void resetForTests() {
        SNAPSHOTS.clear();
        BLOCK_FIELDS = null;
        BLOCK_CHANNELS = null;
        BLOCK_DISPATCHES = null;
        BLOCK_TECLASSES = null;
    }

    /** For tests only: point the field-set source at a scratch sidecar. */
    static void setSidecarForTests(Path sidecar) {
        BLOCK_FIELDS = readSidecar(sidecar).fields;
        BLOCK_CHANNELS = readSidecar(sidecar).channels;
        BLOCK_DISPATCHES = readSidecar(sidecar).dispatches;
        BLOCK_TECLASSES = readSidecar(sidecar).teClasses;
    }

    private static Map<String, List<FieldSpec>> blockFields() {
        Map<String, List<FieldSpec>> cached = BLOCK_FIELDS;
        if (cached != null) return cached;
        Path sidecar = sidecarPath();
        SidecarData loaded = readSidecar(sidecar);
        BLOCK_FIELDS = loaded.fields;
        BLOCK_CHANNELS = loaded.channels;
        BLOCK_DISPATCHES = loaded.dispatches;
        BLOCK_TECLASSES = loaded.teClasses;
        return loaded.fields;
    }

    private static Map<String, List<ChannelSpec>> blockChannels() {
        Map<String, List<ChannelSpec>> cached = BLOCK_CHANNELS;
        if (cached != null) return cached;
        blockFields();
        Map<String, List<ChannelSpec>> loaded = BLOCK_CHANNELS;
        return loaded != null ? loaded : Map.of();
    }

    private static Map<String, DispatchSpec> blockDispatches() {
        Map<String, DispatchSpec> cached = BLOCK_DISPATCHES;
        if (cached != null) return cached;
        blockFields();
        Map<String, DispatchSpec> loaded = BLOCK_DISPATCHES;
        return loaded != null ? loaded : Map.of();
    }

    private static Map<String, String> blockTeClasses() {
        Map<String, String> cached = BLOCK_TECLASSES;
        if (cached != null) return cached;
        blockFields();
        Map<String, String> loaded = BLOCK_TECLASSES;
        return loaded != null ? loaded : Map.of();
    }

    /** Package-visible for {@link DynLiveBounds}: one sidecar file serves both readers. */
    static Path sidecarPath() {
        try {
            String override = System.getProperty("umb.legacy.dynamicsSidecar");
            if (override != null && !override.isEmpty()) {
                Path p = Paths.get(override);
                if (Files.isRegularFile(p)) return p;
            }

            // Registry materialisation calls DynLiveBounds before the lazy legacy universe boot.
            // At that point UmbUniverse has not yet installed umb.repo, so a property-only lookup
            // permanently caches an empty set and every dynamic BlockState is born cacheable.
            // Search the configured root when available, then the game/cwd and this class's own
            // code-source ancestry.  This is a generic install-root lookup; no mod or block id is
            // involved, and an absent sidecar remains a valid no-op.
            java.util.LinkedHashSet<Path> roots = new java.util.LinkedHashSet<>();
            addRoot(roots, System.getProperty("umb.repo"));
            addRoot(roots, System.getProperty("umb.legacy.gameDir"));
            addRoot(roots, System.getProperty("user.dir"));
            try {
                java.net.URI location = DynFieldChannel.class.getProtectionDomain()
                        .getCodeSource().getLocation().toURI();
                Path source = Paths.get(location);
                addRoot(roots, Files.isDirectory(source) ? source.toString()
                        : source.getParent() == null ? source.toString() : source.getParent().toString());
            } catch (Throwable ignored) {
                // A restricted classloader may not expose a code source; the other roots suffice.
            }
            for (Path root : roots) {
                Path p = root.resolve("research").resolve("out").resolve("legacy")
                        .resolve("rendermap").resolve("renderer-dynamic-ops.json");
                if (Files.isRegularFile(p)) return p;
            }
        } catch (Throwable t) {
            AgentLog.errorOnce("DynFieldChannel.sidecarPath", t, 2);
        }
        return null;
    }

    /** Adds this path and a bounded set of parents as candidate install roots. */
    private static void addRoot(java.util.Set<Path> roots, String value) {
        if (value == null || value.isEmpty()) return;
        try {
            Path p = Paths.get(value).toAbsolutePath().normalize();
            for (int i = 0; p != null && i < 8; i++, p = p.getParent()) roots.add(p);
        } catch (Throwable ignored) {
            // One malformed optional path must not suppress the other discovery routes.
        }
    }

    /** Fields plus channels plus dispatches plus TE classes, parsed in a single read. */
    static final class SidecarData {
        final Map<String, List<FieldSpec>> fields;
        final Map<String, List<ChannelSpec>> channels;
        final Map<String, DispatchSpec> dispatches;
        final Map<String, String> teClasses;
        SidecarData(Map<String, List<FieldSpec>> fields,
                Map<String, List<ChannelSpec>> channels,
                Map<String, DispatchSpec> dispatches,
                Map<String, String> teClasses) {
            this.fields = fields;
            this.channels = channels;
            this.dispatches = dispatches;
            this.teClasses = teClasses;
        }
    }

    static SidecarData readSidecar(Path sidecar) {
        Map<String, List<FieldSpec>> fields = new LinkedHashMap<>();
        Map<String, List<ChannelSpec>> channels = new LinkedHashMap<>();
        Map<String, DispatchSpec> dispatches = new LinkedHashMap<>();
        Map<String, String> teClasses = new LinkedHashMap<>();
        if (sidecar == null || !Files.isRegularFile(sidecar))
            return new SidecarData(fields, channels, dispatches, teClasses);
        try {
            String text = Files.readString(sidecar, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            JsonObject blocks = root.has("blocks") && root.get("blocks").isJsonObject()
                    ? root.getAsJsonObject("blocks") : new JsonObject();
            for (Map.Entry<String, JsonElement> e : blocks.entrySet()) {
                if (e.getValue() == null || !e.getValue().isJsonObject()) continue;
                JsonObject b = e.getValue().getAsJsonObject();
                List<FieldSpec> specs = new ArrayList<>();
                if (b.has("fields") && b.get("fields").isJsonArray()) {
                    for (JsonElement f : b.getAsJsonArray("fields")) {
                        // New shape: {"key":..., "hops":[...]}; tolerate the old bare-string shape.
                        String key = null;
                        List<String> hops = new ArrayList<>();
                        if (f != null && f.isJsonObject()) {
                            JsonObject fo = f.getAsJsonObject();
                            key = str(fo, "key");
                            if (fo.has("hops") && fo.get("hops").isJsonArray()) {
                                for (JsonElement h : fo.getAsJsonArray("hops")) {
                                    if (h != null && h.isJsonPrimitive()) hops.add(h.getAsString());
                                }
                            }
                        } else if (f != null && f.isJsonPrimitive()) {
                            key = f.getAsString();
                            int dot = key.lastIndexOf('.');
                            hops.add(dot >= 0 ? key.substring(dot + 1) : key);
                        }
                        if (key == null || key.isEmpty() || hops.isEmpty()) continue;
                        String[] hopNames = hops.toArray(new String[0]);
                        String[] hopKinds = new String[hopNames.length];
                        for (int i = 0; i < hopKinds.length; i++) hopKinds[i] = "field";
                        specs.add(new FieldSpec(key, new FieldPath(key, hopNames, hopKinds)));
                    }
                }
                if (!specs.isEmpty()) fields.put(e.getKey(), List.copyOf(specs));
                List<ChannelSpec> chanSpecs = new ArrayList<>();
                if (b.has("channels") && b.get("channels").isJsonArray()) {
                    for (JsonElement c : b.getAsJsonArray("channels")) {
                        if (c == null || !c.isJsonObject()) continue;
                        JsonObject co = c.getAsJsonObject();
                        String key = str(co, "key");
                        String owner = str(co, "staticOwner");
                        String method = str(co, "staticMethod");
                        String stringArg = str(co, "stringArg");
                        if (key == null || owner == null || method == null || stringArg == null)
                            continue;
                        int index = channelIndex(key);
                        if (index < 0) continue;
                        List<String> hops = new ArrayList<>();
                        if (co.has("animHops") && co.get("animHops").isJsonArray()) {
                            for (JsonElement h : co.getAsJsonArray("animHops")) {
                                if (h != null && h.isJsonPrimitive()) hops.add(h.getAsString());
                            }
                        }
                        if (hops.isEmpty()) continue;
                        String pathKey = owner + "." + method + "." + stringArg;
                        String[] hopNames = hops.toArray(new String[0]);
                        String[] hopKinds = new String[hopNames.length];
                        for (int i = 0; i < hopKinds.length; i++) hopKinds[i] = "field";
                        chanSpecs.add(new ChannelSpec(key, owner, method, stringArg, index,
                                new FieldPath(pathKey, hopNames, hopKinds), parseAnim(co)));
                    }
                }
                if (!chanSpecs.isEmpty()) channels.put(e.getKey(), List.copyOf(chanSpecs));
                DispatchSpec dispatch = parseDispatch(b);
                if (dispatch != null) dispatches.put(e.getKey(), dispatch);
                String teClass = str(b, "teClass");
                if (teClass != null && !teClass.isEmpty()) teClasses.put(e.getKey(), teClass);
            }
        } catch (Throwable t) {
            AgentLog.error("DynFieldChannel.readSidecar " + sidecar, t, 2);
        }
        return new SidecarData(fields, channels, dispatches, teClasses);
    }

    /** Parses a channel's optional {@code anim} recipe; null when absent or malformed. */
    static AnimSpec parseAnim(JsonObject co) {
        if (co == null || !co.has("anim") || !co.get("anim").isJsonObject()) return null;
        try {
            JsonObject a = co.getAsJsonObject("anim");
            String providerOwner = str(a, "providerOwner");
            String providerMethod = str(a, "providerMethod");
            String clockOwner = str(a, "clockOwner");
            String clockMethod = str(a, "clockMethod");
            String clockField = str(a, "clockField");
            if (providerOwner == null || providerMethod == null || clockOwner == null
                    || clockMethod == null || clockField == null) return null;
            FieldPath receiver = hopsPath(a, "receiverHops", "receiverKinds",
                    providerOwner + "." + providerMethod);
            if (receiver == null) return null;
            if (!a.has("argHops") || !a.get("argHops").isJsonArray()
                    || !a.has("argKinds") || !a.get("argKinds").isJsonArray()) return null;
            JsonArray argHops = a.getAsJsonArray("argHops");
            JsonArray argKinds = a.getAsJsonArray("argKinds");
            if (argHops.size() != argKinds.size()) return null;
            FieldPath[] args = new FieldPath[argHops.size()];
            for (int i = 0; i < args.length; i++) {
                if (!argHops.get(i).isJsonArray() || !argKinds.get(i).isJsonArray()) return null;
                List<String> names = strings(argHops.get(i).getAsJsonArray());
                List<String> kinds = strings(argKinds.get(i).getAsJsonArray());
                if (names.size() != kinds.size()) return null;
                for (String k : kinds) {
                    if (!"field".equals(k) && !"accessor".equals(k)) return null;
                }
                args[i] = new FieldPath(providerOwner + "." + providerMethod + "#" + i,
                        names.toArray(new String[0]), kinds.toArray(new String[0]));
            }
            return new AnimSpec(providerOwner, providerMethod, receiver, args,
                    clockOwner, clockMethod, clockField);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Parses a block's optional {@code dispatch}; null when absent or malformed. */
    static DispatchSpec parseDispatch(JsonObject b) {
        if (b == null || !b.has("dispatch") || !b.get("dispatch").isJsonObject()) return null;
        try {
            JsonObject d = b.getAsJsonObject("dispatch");
            String owner = str(d, "owner");
            String method = str(d, "method");
            if (owner == null || method == null) return null;
            FieldPath objectPath = hopsPath(d, "objectHops", "objectKinds", owner + "." + method);
            if (objectPath == null) return null;
            return new DispatchSpec(owner, method, objectPath);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Builds a {@link FieldPath} from parallel hops/kinds arrays; null when malformed. */
    private static FieldPath hopsPath(JsonObject o, String hopsKey, String kindsKey, String pathKey) {
        if (!o.has(hopsKey) || !o.get(hopsKey).isJsonArray()
                || !o.has(kindsKey) || !o.get(kindsKey).isJsonArray()) return null;
        List<String> names = strings(o.getAsJsonArray(hopsKey));
        List<String> kinds = strings(o.getAsJsonArray(kindsKey));
        if (names.size() != kinds.size()) return null;
        for (String k : kinds) {
            if (!"field".equals(k) && !"accessor".equals(k)) return null;
        }
        return new FieldPath(pathKey, names.toArray(new String[0]), kinds.toArray(new String[0]));
    }

    private static List<String> strings(JsonArray a) {
        List<String> out = new ArrayList<>();
        for (JsonElement h : a) {
            if (h != null && h.isJsonPrimitive()) out.add(h.getAsString());
        }
        return out;
    }

    /** Trailing {@code [N]} index of a channel key, or -1 when the key is malformed. */
    static int channelIndex(String key) {
        if (key == null) return -1;
        int open = key.lastIndexOf('[');
        int close = key.lastIndexOf(']');
        if (open < 0 || close != key.length() - 1 || close <= open + 1) return -1;
        try {
            int index = Integer.parseInt(key.substring(open + 1, close));
            return index >= 0 ? index : -1;
        } catch (NumberFormatException n) {
            return -1;
        }
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return (e == null || e.isJsonNull() || !e.isJsonPrimitive()) ? null : e.getAsString();
    }

    /** Builds the one-shot {@code FieldPath[]} request from cached specs (never per tick). */
    static FieldPath[] buildRequest(List<FieldSpec> specs) {
        FieldPath[] out = new FieldPath[specs.size()];
        for (int i = 0; i < specs.size(); i++) out[i] = specs.get(i).path;
        return out;
    }

    /** True when every present value matches (same length, keys, presence and doubles). */
    static boolean sameSnapshot(TileFieldSnapshot a, TileFieldSnapshot b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a.keys.length != b.keys.length) return false;
        for (int i = 0; i < a.keys.length; i++) {
            if (!a.keys[i].equals(b.keys[i])) return false;
            if (a.present[i] != b.present[i]) return false;
            if (a.present[i] && Double.compare(a.values[i], b.values[i]) != 0) return false;
        }
        return true;
    }
}
