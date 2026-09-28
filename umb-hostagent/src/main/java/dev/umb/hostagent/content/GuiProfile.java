package dev.umb.hostagent.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.umb.hostagent.AgentLog;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tolerant reader for research/out/legacy/gui-profile.json (module umb-guimap's static bytecode
 * extraction: 181/181 GuiContainer subclasses paired EXACTLY to their Container class).
 *
 * Pure data - no net.minecraft types, so this is unit-testable off the game classpath, exactly
 * like {@link LegacySnapshot} / {@link BlockShapeProfile}.
 *
 * <p>Keyed by the legacy CONTAINER class name (e.g.
 * {@code "com.hbm.inventory.container.ContainerMachinePUREX"}), because that is the one thing the
 * host can recover about an opened legacy GUI without a {@code dev.umb.bridge.api} contract change
 * - {@link ContainerHandle} exposes no such id, and {@code LegacyBridgeImpl.activate()} sets
 * {@code title()} to the raw legacyBlockId, not a class name (see {@code UmbLegacyBlock}'s own
 * javadoc on that exact confusion). {@link LegacyContainerClassResolver} recovers the real class
 * name reflectively; this class is the lookup table keyed by it.</p>
 */
public final class GuiProfile {

    /** The vanilla dispenser panel's own size - the pre-existing (pre-Task-B) fallback. */
    public static final int FALLBACK_X = 176;
    public static final int FALLBACK_Y = 166;

    public static final class TextureRef {
        public final String namespace;
        public final String path;       // e.g. "textures/gui/processing/gui_purex.png"
        public final String assetPath;  // e.g. "assets/hbm/textures/gui/processing/gui_purex.png" - PackGen's copy source
        public final int sheetWidth, sheetHeight;

        TextureRef(String namespace, String path, String assetPath, int sheetWidth, int sheetHeight) {
            this.namespace = namespace;
            this.path = path;
            this.assetPath = assetPath;
            this.sheetWidth = sheetWidth;
            this.sheetHeight = sheetHeight;
        }
    }

    /**
     * SYNC-BINDING lane: a resolved linear expression over one live {@code ContainerHandle.syncData()}
     * register (a numerator sync index, an optional SECOND sync index as the divisor, else a
     * constant divisor) — reconstructs umb-guimap's {@code stateBinding} (source/multiplier/divisor/
     * offset/sign) at render time, exactly the formula {@code DYNAMIC-RECTS.md} already extracted,
     * now with the field resolved to a register instead of left symbolic. No {@code panelBase}
     * support (a numerator combined with a guiLeft/guiTop anchor, e.g. "guiTop+61-amt") — every real
     * corpus case of that shape has an unbindable divisor anyway (see SYNC-BINDING.md); scoping this
     * out rather than half-implementing an untested path.
     */
    public static final class SyncExpr {
        public final int numeratorSyncIndex;
        public final double multiplier;
        public final boolean divisorIsSyncIndex;
        public final int divisorSyncIndex;   // meaningful only when divisorIsSyncIndex
        public final double divisorConst;    // meaningful only when !divisorIsSyncIndex
        public final int offset;
        public final int sign;

        SyncExpr(int numeratorSyncIndex, double multiplier, boolean divisorIsSyncIndex,
                 int divisorSyncIndex, double divisorConst, int offset, int sign) {
            this.numeratorSyncIndex = numeratorSyncIndex;
            this.multiplier = multiplier;
            this.divisorIsSyncIndex = divisorIsSyncIndex;
            this.divisorSyncIndex = divisorSyncIndex;
            this.divisorConst = divisorConst;
            this.offset = offset;
            this.sign = sign;
        }

        /** {@code syncData} is typically {@code handle.syncData()[i]}/{@code menu.getData(i)} — a
         *  bounds-checked reader the caller controls. Division by zero (a divisor register that
         *  happens to read 0 this frame - e.g. a not-yet-initialised max) yields 0 rather than
         *  throwing or drawing garbage. */
        public int evaluate(java.util.function.IntUnaryOperator syncData) {
            double numerator = syncData.applyAsInt(numeratorSyncIndex);
            double divisor = divisorIsSyncIndex ? syncData.applyAsInt(divisorSyncIndex) : divisorConst;
            double term = divisor != 0 ? (numerator * multiplier / divisor) : 0.0;
            return offset + sign * (int) term;
        }
    }

    /** SYNC-BINDING lane: the structured guard condition on a {@link Rect} that IS otherwise fully
     *  resolvable — draw only when the live register value fails this SKIP test (see
     *  umb-guimap's {@code conditional.fieldCondition}, which records the bytecode's own skip test,
     *  not the draw-when predicate). */
    public static final class SyncGuard {
        public final int syncIndex;
        public final String skipOp; // EQ/NE/LT/GE/GT/LE
        public final int skipValue;

        SyncGuard(int syncIndex, String skipOp, int skipValue) {
            this.syncIndex = syncIndex; this.skipOp = skipOp; this.skipValue = skipValue;
        }

        /** True when the ORIGINAL bytecode would have skipped this draw — i.e. draw iff !shouldSkip. */
        public boolean shouldSkip(java.util.function.IntUnaryOperator syncData) {
            int v = syncData.applyAsInt(syncIndex);
            return switch (skipOp) {
                case "EQ" -> v == skipValue;
                case "NE" -> v != skipValue;
                case "LT" -> v < skipValue;
                case "GE" -> v >= skipValue;
                case "GT" -> v > skipValue;
                case "LE" -> v <= skipValue;
                default -> true; // unrecognised op - never draw on an ambiguous guard
            };
        }
    }

    /**
     * TILE-FIELD-SNAPSHOT lane: a short, bounded hop chain reaching one scalar value off a live
     * legacy tile entity (or entity) — mirrors umb-guimap's {@code fieldRequirement.hops}
     * (TILE-FIELD-REQUIREMENTS.md's bounded-one-extra-hop rule: at most one intermediate object
     * dereference, then a plain field read or a zero-arg accessor call). {@link #key} is a
     * canonical, GUI-scoped id — the hop chain rendered as {@code "feed.fluid"} (field) or
     * {@code "priority.ordinal()"} (accessor) — used to correlate this reference with its value in
     * a live {@code dev.umb.bridge.api.TileFieldSnapshot}: every {@link Rect}/guard leaf that needs
     * the SAME field uses the SAME key, and {@link GuiEntry#tileFieldRefs} is exactly the
     * deduplicated union of every key one GUI's rects/guards need — the FieldPath[] request sent to
     * the bridge once per menu-open, per {@code research/out/legacy/guimap-notes/GAUGE-RENDER.md}.
     */
    public static final class TileFieldRef {
        public final String key;
        public final String[] hopNames;
        public final String[] hopKinds; // "field" | "accessor", parallel to hopNames

        TileFieldRef(String[] hopNames, String[] hopKinds) {
            this.hopNames = hopNames;
            this.hopKinds = hopKinds;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < hopNames.length; i++) {
                if (i > 0) sb.append('.');
                sb.append(hopNames[i]);
                if ("accessor".equals(hopKinds[i])) sb.append("()");
            }
            this.key = sb.toString();
        }
    }

    /**
     * TILE-FIELD-SNAPSHOT lane: a resolved linear expression over one or two live tile-entity
     * fields (a per-open-GUI snapshot, never a container sync register — see {@link SyncExpr} for
     * that older, much narrower mechanism this is layered alongside, not instead of).
     * Reconstructs umb-guimap's {@code stateBinding} (source/multiplier/divisor/offset/sign, PLUS
     * {@code panelBase}/{@code clampFunction} — deliberately out of {@link SyncExpr}'s scope per
     * SYNC-BINDING.md §7 recommendation 5, but cheap and fully specified once the source is a live
     * snapshot rather than a sync register) exactly as extracted.
     */
    public static final class TileExpr {
        public final TileFieldRef numerator;
        public final double multiplier;
        public final boolean divisorIsField;
        public final TileFieldRef divisorField;   // meaningful only when divisorIsField
        public final double divisorConst;         // meaningful only when !divisorIsField
        public final int offset;
        public final int sign;
        /** "guiLeft" | "guiTop" | null — the panel-relative anchor umb-guimap's {@code panelBase}
         *  names (e.g. "guiTop+97-power*53/getMaxPower"); resolved against the SCREEN's own
         *  leftPos/topPos, never a legacy GUI field (no legacy GUI object is ever instantiated). */
        public final String panelBaseAxis;
        public final int panelBaseDelta;
        /** "java.lang.Math.min" | "java.lang.Math.max" | null. */
        public final String clampFn;
        public final double clampValue;
        /**
         * GUI-fidelity lane: floor on a FIELD divisor, from umb-guimap's {@code divisorFloor}
         * ({@code A*K/max(FIELD, C)} divide-by-guarded-maximum). Null (the common case) means a
         * plain field divisor. Applied as {@code max(divFloor, divisor)} BEFORE the divide-by-zero
         * rule below, exactly reconstructing the bytecode's own guard.
         */
        public final Double divisorFloor;
        /**
         * GUI-fidelity lane: whole-term {@code Math.ceil} (assembler
         * {@code (int)Math.ceil(70.0*progress)} arrow width), from umb-guimap's
         * {@code ceilResult}. Applied after the clamp, before the int cast.
         */
        public final boolean ceilResult;

        TileExpr(TileFieldRef numerator, double multiplier, boolean divisorIsField, TileFieldRef divisorField,
                 double divisorConst, int offset, int sign, String panelBaseAxis, int panelBaseDelta,
                 String clampFn, double clampValue, Double divisorFloor, boolean ceilResult) {
            this.numerator = numerator;
            this.multiplier = multiplier;
            this.divisorIsField = divisorIsField;
            this.divisorField = divisorField;
            this.divisorConst = divisorConst;
            this.offset = offset;
            this.sign = sign;
            this.panelBaseAxis = panelBaseAxis;
            this.panelBaseDelta = panelBaseDelta;
            this.clampFn = clampFn;
            this.clampValue = clampValue;
            this.divisorFloor = divisorFloor;
            this.ceilResult = ceilResult;
        }

        /**
         * Never fabricates: returns {@code null} — not a stale or zero value — the instant a
         * required field is missing from THIS frame's snapshot (the tile was removed, the hop
         * failed to resolve, or no snapshot has arrived yet). The caller must skip the whole rect,
         * never substitute a default. Division by zero (a divisor field that reads 0 before the
         * machine initialises — the exact risk the brief calls out) yields a zero ratio term rather
         * than throwing or drawing garbage, matching {@link SyncExpr#evaluate}'s own convention.
         */
        public Integer evaluate(java.util.function.Function<String, Double> tileFields, int guiLeft, int guiTop) {
            Double num = tileFields.apply(numerator.key);
            if (num == null) return null;
            double divisor;
            if (divisorIsField) {
                Double d = tileFields.apply(divisorField.key);
                if (d == null) return null;
                divisor = d;
                if (divisorFloor != null) divisor = Math.max(divisorFloor.doubleValue(), divisor);
            } else {
                divisor = divisorConst;
            }
            double term = divisor != 0.0 ? (num * multiplier / divisor) : 0.0;
            double base = 0;
            if (panelBaseAxis != null) {
                base = ("guiLeft".equals(panelBaseAxis) ? guiLeft : guiTop) + panelBaseDelta;
            }
            double value = base + offset + sign * term;
            if ("java.lang.Math.min".equals(clampFn)) value = Math.min(value, clampValue);
            else if ("java.lang.Math.max".equals(clampFn)) value = Math.max(value, clampValue);
            if (ceilResult) value = Math.ceil(value);
            return (int) value;
        }
    }

    /**
     * TILE-FIELD-SNAPSHOT lane: a general AND/OR-of-COMPAREs guard tree evaluated against a live
     * tile-field snapshot, the mouse position and the panel origin — mirrors umb-guimap's
     * {@code conditional.skipCondition}/{@code guardNeeds} (GUARD-EXPRESSIONS.md). Deliberately
     * narrower than the extractor's own vocabulary: a {@code guiField} leaf (client-owned legacy
     * GUI state, e.g. {@code GuiTextField.isFocused()}) has NO live source in this architecture —
     * this project never instantiates a legacy GUI object at all (see the class javadoc on
     * {@link UmbLegacyScreen} and GENERALIZATION-PLAN.md's Vulkan section) — so any tree containing
     * one, or any other {@code unknown} leaf, is parsed into a node that always reports "cannot be
     * decided", which {@link #shouldSkip} treats exactly like a missing tile field: skip, never
     * fake a pass. See {@code research/out/legacy/guimap-notes/GAUGE-RENDER.md} for the exact count
     * this caps out on.
     */
    public static final class TileGuard {
        final Node root;

        TileGuard(Node root) {
            this.root = root;
        }

        /** True when the ORIGINAL bytecode's own skip test holds (draw iff !shouldSkip) — same
         *  polarity convention as {@link SyncGuard#shouldSkip}. An undecidable tree (any leaf could
         *  not be resolved this frame) counts as "skip", never as "safe to draw". */
        public boolean shouldSkip(java.util.function.Function<String, Double> tileFields,
                                   int mouseX, int mouseY, int guiLeft, int guiTop) {
            Boolean r = root.eval(tileFields, mouseX, mouseY, guiLeft, guiTop);
            return r == null || r;
        }

        static final class Node {
            final String op; // COMPARE | AND | OR
            final String compareOp;   // meaningful only for COMPARE
            final Operand left, right; // meaningful only for COMPARE
            final List<Node> operands; // meaningful only for AND/OR

            Node(String op, String compareOp, Operand left, Operand right, List<Node> operands) {
                this.op = op; this.compareOp = compareOp; this.left = left; this.right = right;
                this.operands = operands;
            }

            Boolean eval(java.util.function.Function<String, Double> tf, int mx, int my, int gl, int gt) {
                if ("COMPARE".equals(op)) {
                    Double l = left.resolve(tf, mx, my, gl, gt);
                    Double r = right.resolve(tf, mx, my, gl, gt);
                    if (l == null || r == null) return null;
                    return compare(compareOp, l, r);
                }
                boolean isOr = "OR".equals(op);
                boolean allKnown = true;
                for (Node n : operands) {
                    Boolean v = n.eval(tf, mx, my, gl, gt);
                    if (v == null) { allKnown = false; continue; }
                    if (isOr && v) return true;      // short-circuit: one true frame is enough to skip
                    if (!isOr && !v) return false;    // short-circuit: one false frame is enough
                }
                // OR with no true operand seen -> false unless some operand was undecidable (then
                // unknown, since that operand COULD have been true); AND is the mirror image.
                if (!allKnown) return null;
                return !isOr;
            }
        }

        static final class Operand {
            final String kind; // const | mouse | panelOrigin | tileField | (anything else -> unresolved)
            final double constValue;
            final String axis;   // mouse: "x"/"y"; panelOrigin: "guiLeft"/"guiTop"
            final int delta;     // panelOrigin only
            final TileFieldRef tileField;

            Operand(String kind, double constValue, String axis, int delta, TileFieldRef tileField) {
                this.kind = kind; this.constValue = constValue; this.axis = axis; this.delta = delta;
                this.tileField = tileField;
            }

            Double resolve(java.util.function.Function<String, Double> tf, int mx, int my, int gl, int gt) {
                return switch (kind) {
                    case "const" -> constValue;
                    case "mouse" -> "x".equals(axis) ? (double) mx : (double) my;
                    case "panelOrigin" -> (double) (("guiLeft".equals(axis) ? gl : gt) + delta);
                    case "tileField" -> tileField == null ? null : tf.apply(tileField.key);
                    default -> null; // guiField / unknown / distrusted-const-const — never fabricated
                };
            }
        }

        private static boolean compare(String op, double l, double r) {
            return switch (op) {
                case "EQ" -> l == r;
                case "NE" -> l != r;
                case "LT" -> l < r;
                case "GE" -> l >= r;
                case "GT" -> l > r;
                case "LE" -> l <= r;
                default -> true; // unrecognised -> never draw on an ambiguous guard
            };
        }
    }

    /**
     * One statically-drawable {@code drawTexturedModalRect} call (SCREEN-RENDER lane,
     * GENERALIZATION-PLAN.md GAP 2, extended by the SYNC-BINDING lane) — every rect either has all
     * SIX args as {@code CONST}/{@code PANEL_RELATIVE}, or up to four of {@code u,v,w,h} are instead
     * a live {@link SyncExpr}/guarded by a live {@link SyncGuard}; see {@link #buildRects} for the
     * exact, evidence-based skip policy (background-blit dedup, guarded/conditional, STATE_LINEAR/
     * UNRESOLVED, unresolved texture bind) and {@code research/out/legacy/guimap-notes/
     * SCREEN-RENDER.md}/{@code SYNC-BINDING.md} for the counts and reasoning.
     */
    public static final class Rect {
        /** Screen-space offset: final = leftPos+dx, topPos+dy (both args were PANEL_RELATIVE). */
        public final int dx, dy;
        /** Static value for a slot with no {@link #uExpr}/{@link #vExpr}/{@link #wExpr}/{@link #hExpr}
         *  override; ignored (may be 0) when the corresponding expr is non-null. */
        public final int u, v, w, h;
        /** Non-null exactly when the corresponding arg is a bound {@code STATE_LINEAR} value — see
         *  {@link #buildRects}. At most a handful of real rects ever have any of these set. */
        public final SyncExpr uExpr, vExpr, wExpr, hExpr;
        /** Non-null when this otherwise-fully-resolved rect was originally guarded by a single
         *  bound-field condition (see umb-guimap's {@code conditional.fieldCondition}) — evaluated
         *  live at render time instead of being skipped outright. */
        public final SyncGuard guard;
        /** TILE-FIELD-SNAPSHOT lane: dynamic destination coordinates, when present. */
        public final TileExpr xTileExpr, yTileExpr;
        /** TILE-FIELD-SNAPSHOT lane: non-null exactly when the corresponding arg is instead bound
         *  to a live per-tile-entity snapshot field (see {@link #buildRects}) — tried only when the
         *  older {@link SyncExpr} mechanism above did NOT already bind that arg, so the two never
         *  both apply to the same slot. */
        public final TileExpr uTileExpr, vTileExpr, wTileExpr, hTileExpr;
        /** TILE-FIELD-SNAPSHOT lane: non-null when this rect's guard resolves against a live tile
         *  snapshot/mouse position/panel origin instead of (or because) {@link #guard} above did
         *  not apply — see {@link #buildRects}. */
        public final TileGuard tileGuard;
        /** Index into the owning {@link GuiEntry#textures} array this rect actually binds
         *  (umb-guimap's {@code textureBindIndex}, schemaVersion 3) — always a valid, resolved,
         *  in-jar texture by construction (see {@link #buildRects}); never -1 here. */
        public final int textureIndex;

        Rect(int dx, int dy, int u, int v, int w, int h, int textureIndex) {
            this(dx, dy, u, v, w, h, null, null, null, null, null,
                    null, null, null, null, null, null, null, textureIndex);
        }

        Rect(int dx, int dy, int u, int v, int w, int h,
             SyncExpr uExpr, SyncExpr vExpr, SyncExpr wExpr, SyncExpr hExpr, SyncGuard guard,
             TileExpr xTileExpr, TileExpr yTileExpr,
             TileExpr uTileExpr, TileExpr vTileExpr, TileExpr wTileExpr, TileExpr hTileExpr, TileGuard tileGuard,
             int textureIndex) {
            this.dx = dx; this.dy = dy; this.u = u; this.v = v; this.w = w; this.h = h;
            this.uExpr = uExpr; this.vExpr = vExpr; this.wExpr = wExpr; this.hExpr = hExpr;
            this.guard = guard;
            this.xTileExpr = xTileExpr; this.yTileExpr = yTileExpr;
            this.uTileExpr = uTileExpr; this.vTileExpr = vTileExpr; this.wTileExpr = wTileExpr; this.hTileExpr = hTileExpr;
            this.tileGuard = tileGuard;
            this.textureIndex = textureIndex;
        }

        public boolean hasDynamicArg() {
            return xTileExpr != null || yTileExpr != null
                    || uExpr != null || vExpr != null || wExpr != null || hExpr != null
                    || uTileExpr != null || vTileExpr != null || wTileExpr != null || hTileExpr != null;
        }
    }

    /** One statically-drawable foreground label (literal or translation-key text, CONST x/y —
     *  see {@link #buildLabels}). Foreground-layer coordinates are already panel-relative in
     *  vanilla 1.7.10 (GL is translated by guiLeft/guiTop before drawGuiContainerForegroundLayer
     *  runs), so x/y here are added to leftPos/topPos directly, with no guiLeft/guiTop of their own. */
    public static final class Label {
        public final boolean translated;
        /** Literal text, or the translation key when {@link #translated}. */
        public final String text;
        public final int x, y;

        Label(boolean translated, String text, int x, int y) {
            this.translated = translated; this.text = text; this.x = x; this.y = y;
        }
    }

    /** Additive schema-v7 control record extracted by umb-guimap. */
    public static final class Button {
        public final String kind, handler, label, sourceMethod;
        public final int id, x, y, width, height;
        public final boolean hasBounds;
        public final boolean packetResolved;
        public final String packetMessageClass;
        public final List<String> packetConstructorArgs;

        Button(String kind, String handler, String label, String sourceMethod, int id,
               int x, int y, int width, int height, boolean hasBounds,
               boolean packetResolved, String packetMessageClass, List<String> packetConstructorArgs) {
            this.kind = kind; this.handler = handler; this.label = label; this.sourceMethod = sourceMethod;
            this.id = id; this.x = x; this.y = y; this.width = width; this.height = height;
            this.hasBounds = hasBounds;
            this.packetResolved = packetResolved;
            this.packetMessageClass = packetMessageClass;
            this.packetConstructorArgs = packetConstructorArgs;
        }
    }

    public static final class GuiEntry {
        public final String guiClassName;
        public final String containerClassName;
        /**
         * ironchest-visuals lane: optional container REGISTRY id (e.g.
         * "ironchest:iron_chest") for eras where several ContainerTypes share one
         * Container class (1.16.5). Null for every 1.7.10 row (guimap never emits
         * it), so the class-keyed path is byte-for-byte unchanged for them.
         */
        public final String containerId;
        /** true only for size confidence "exact"; "inferred"/"unresolved"/missing all fall back. */
        public final boolean sizeExact;
        public final int xSize, ySize;
        /** null when nothing in the jar resolves - caller must fall back to the vanilla panel. */
        public final TextureRef texture;
        /** Every texture this GUI's background layer binds, in bind order (parallel to
         *  {@code backgroundTextures} in the JSON) - null at an index that did not resolve to an
         *  existing-in-jar asset. {@link #texture} is the first non-null entry. Only non-null
         *  entries are ever referenced by {@link Rect#textureIndex}. */
        public final TextureRef[] textures;
        public final List<Rect> rects;
        public final List<Label> labels;
        public final List<Button> buttons;
        /** TILE-FIELD-SNAPSHOT lane: the deduplicated union of every {@link TileFieldRef} this
         *  GUI's own rects/guards need (see {@link #collectTileFieldRefs}) — the FieldPath[]
         *  request a host-side menu builds ONCE at menu-open time and re-uses every server tick
         *  (TILE-FIELD-REQUIREMENTS.md's own "a per-open-GUI snapshot is small by construction"
         *  finding: median 2, max 7 real fields). Empty for the ~80/181 GUIs that need none. */
        public final List<TileFieldRef> tileFieldRefs;

        GuiEntry(String guiClassName, String containerClassName, String containerId,
                 boolean sizeExact,
                 int xSize, int ySize, TextureRef texture, TextureRef[] textures,
                 List<Rect> rects, List<Label> labels, List<TileFieldRef> tileFieldRefs,
                 List<Button> buttons) {
            this.guiClassName = guiClassName;
            this.containerClassName = containerClassName;
            this.containerId = containerId;
            this.sizeExact = sizeExact;
            this.xSize = xSize;
            this.ySize = ySize;
            this.texture = texture;
            this.textures = textures;
            this.rects = rects;
            this.labels = labels;
            this.tileFieldRefs = tileFieldRefs;
            this.buttons = buttons;
        }
    }

    /**
     * NOTEXTURE-GAP lane: a read-only diagnostic record of one rect that failed the
     * "no resolvable texture" check in {@link #buildRects}, for {@link #diagnoseNoTextureSkips} —
     * never consulted by normal parsing/rendering, exists purely so a caller can classify the exact
     * cause (an unresolved bind index vs. a resolved-but-not-{@code existsInJar} texture entry vs. a
     * missing {@code path}/{@code assetPath}) against the SAME raw JSON {@link #buildRects} itself
     * reads, rather than re-deriving it from a separate pass that could drift out of sync.
     */
    public static final class TextureGapRecord {
        public final String guiClassName, containerClassName;
        /** Index of this rect within its GUI's own {@code backgroundDrawRects} array. */
        public final int rectIndex;
        /** The rect's own {@code textureBindIndex} as read from the JSON (may be -1). */
        public final int textureBindIndex;
        /** {@code GuiEntry#textures}'s length for this GUI (== {@code backgroundTextures.length}). */
        public final int texturesLength;
        /** Raw {@code backgroundTextures[textureBindIndex]} JSON object, or {@code null} when
         *  {@code textureBindIndex} itself is out of {@code [0, texturesLength)}. */
        public final JsonObject rawTexture;
        /** The full raw rect JSON object (args + conditional + textureBindIndex), for context. */
        public final JsonObject rawRect;

        TextureGapRecord(String guiClassName, String containerClassName, int rectIndex, int textureBindIndex,
                          int texturesLength, JsonObject rawTexture, JsonObject rawRect) {
            this.guiClassName = guiClassName;
            this.containerClassName = containerClassName;
            this.rectIndex = rectIndex;
            this.textureBindIndex = textureBindIndex;
            this.texturesLength = texturesLength;
            this.rawTexture = rawTexture;
            this.rawRect = rawRect;
        }
    }

    /**
     * NOTEXTURE-GAP lane: re-parses {@code file} exactly like {@link #load(Path)} (same size/guard/
     * unbindable/background-dup rules — a rect must clear every one of those, unchanged, before it
     * can even reach the texture check), but returns one {@link TextureGapRecord} per rect that then
     * fails the texture-resolution check, instead of the usual {@link GuiEntry} list. Diagnostic
     * only: never called from {@link #load}/{@link #parseString}, has no effect on
     * {@link #rectsNoTextureSkipped} or any other counter on the returned profile (there is no
     * returned profile), and changes no existing behavior.
     */
    public static List<TextureGapRecord> diagnoseNoTextureSkips(Path file) {
        List<TextureGapRecord> sink = new ArrayList<>();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(r);
            parse(root, sink);
        } catch (IOException | RuntimeException e) {
            // diagnostic-only: an unreadable file just yields an empty list, same tolerant
            // contract as GuiProfile.load.
        }
        return sink;
    }

    private final Map<String, GuiEntry> byContainerClass = new LinkedHashMap<>();
    /** ironchest-visuals lane: rows carrying containerId, first wins (see parse). */
    private final Map<String, GuiEntry> byContainerId = new LinkedHashMap<>();
    public int loadedGuis, duplicateContainers;
    /** ironchest-visuals lane: containerId collisions (same id, two rows). */
    public int duplicateContainerIds;
    public int sizeFallbackCount, textureFallbackCount;
    // SCREEN-RENDER lane coverage counters -- parse-time, over every container-paired GUI ROW in
    // the file (same universe as loadedGuis+duplicateContainers), so the numbers in
    // research/out/legacy/guimap-notes/SCREEN-RENDER.md can be read straight off a loaded
    // GuiProfile instance instead of re-deriving them. NOTE: when several GUI classes share one
    // Container class (e.g. HBM's 11 turret GUIs -> ContainerTurretBase), only the FIRST row is
    // kept in byContainerClass/entries() (see duplicateContainers above) -- these counters still
    // include every row's rects/labels, so summing over entries() alone can come out slightly
    // lower than rectsDrawable/labelsDrawable for a corpus with real duplicates. Not a bug: those
    // discarded rows are for a container class this GuiProfile can only ever answer once anyway.
    public int rectsTotal, rectsBackgroundDupSkipped, rectsGuardedSkipped, rectsUnbindableSkipped,
            rectsNoTextureSkipped, rectsOutOfPanelSkipped, rectsDrawable;
    public int labelsTotal, labelsUnresolvedSkipped, labelsDrawable;
    // SYNC-BINDING lane: how many of rectsDrawable got there via a live sync-register binding
    // (STATE_LINEAR arg(s) resolved to container.syncBindings) rather than pure CONST/PANEL_RELATIVE,
    // and how many of those also had their guard evaluated live rather than skipped outright.
    public int rectsSyncBoundDrawable, rectsSyncGuardEvaluatedDrawable;
    // TILE-FIELD-SNAPSHOT lane: the same two counters, for the NEW per-tile-entity-snapshot
    // mechanism (see TileExpr/TileGuard) — tried only after the sync-binding mechanism above
    // already failed to bind a given arg/guard, so a rect is never double-counted in both pairs.
    public int rectsTileBoundDrawable, rectsTileGuardEvaluatedDrawable;

    /** "log once per GUI" - containerClassName already logged for this kind of fallback. */
    private final Set<String> loggedSizeFallback = ConcurrentHashMap.newKeySet();
    private final Set<String> loggedTextureFallback = ConcurrentHashMap.newKeySet();

    private GuiProfile() {
    }

    public static GuiProfile empty() {
        return new GuiProfile();
    }

    /** Never throws: a missing/unreadable file behaves exactly like {@link #empty()}. */
    public static GuiProfile load(Path file) {
        if (file == null || !Files.isRegularFile(file)) return empty();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(JsonParser.parseReader(r));
        } catch (IOException | RuntimeException e) {
            return empty();
        }
    }

    public static GuiProfile parseString(String json) {
        return parse(JsonParser.parseString(json));
    }

    private static GuiProfile parse(JsonElement root) {
        return parse(root, null);
    }

    /** @param noTextureSink diagnostic-only, see {@link #diagnoseNoTextureSkips} — {@code null} for
     *  every normal call site ({@link #load}/{@link #parseString}), unchanged behavior in that case. */
    private static GuiProfile parse(JsonElement root, List<TextureGapRecord> noTextureSink) {
        GuiProfile p = new GuiProfile();
        if (root == null || !root.isJsonObject()) return p;
        JsonObject o = root.getAsJsonObject();
        for (JsonElement e : arr(o, "guis")) {
            if (!e.isJsonObject()) continue;
            JsonObject go = e.getAsJsonObject();
            JsonObject container = obj(go, "container");
            String containerClass = container == null ? null : str(container, "className");
            // GuiScreen-only classes (no Container at all, e.g. GUIRBMKConsole) have no container
            // key to be looked up by - out of scope here, recorded as a follow-up in
            // laneConsume-progress.md, not silently mis-paired to anything.
            if (containerClass == null) continue;
            String confidence = str(container, "confidence");
            if (!"exact".equals(confidence) && !"inferred".equals(confidence)) continue;

            String guiClass = str(go, "className");
            JsonObject size = obj(go, "size");
            boolean exact = size != null && "exact".equals(str(size, "confidence"));
            int xSize = exact ? i(size, "xSize", FALLBACK_X) : FALLBACK_X;
            int ySize = exact ? i(size, "ySize", FALLBACK_Y) : FALLBACK_Y;
            String containerId = containerIdOf(container);

            TextureRef[] textures = buildTextures(arr(go, "backgroundTextures"));
            TextureRef primary = null;
            for (TextureRef t : textures) { if (t != null) { primary = t; break; } }

            Map<String, Integer> syncFieldToIndex = buildSyncFieldMap(arr(container, "syncBindings"));
            JsonArray rawBackgroundTextures = arr(go, "backgroundTextures");
            List<Rect> rects = noTextureSink == null
                    ? buildRects(arr(go, "backgroundDrawRects"), textures, xSize, ySize, syncFieldToIndex, p)
                    : buildRects(arr(go, "backgroundDrawRects"), textures, xSize, ySize, syncFieldToIndex, p,
                        noTextureSink, rawBackgroundTextures, guiClass, containerClass);
            appendKnownRtgPowerFallback(rects, guiClass, containerClass, p);
            List<Label> labels = buildLabels(arr(go, "foregroundLabels"), p);
            List<Button> buttons = buildButtons(arr(go, "buttons"));
            List<TileFieldRef> tileFieldRefs = collectTileFieldRefs(rects);

            GuiEntry entry = new GuiEntry(guiClass, containerClass, containerId, exact, xSize, ySize, primary, textures,
                    rects, labels, tileFieldRefs, buttons);
            if (p.byContainerClass.putIfAbsent(containerClass, entry) != null) {
                p.duplicateContainers++;
            } else {
                p.loadedGuis++;
            }
            if (containerId != null) {
                if (p.byContainerId.putIfAbsent(containerId, entry) != null) {
                    p.duplicateContainerIds++;
                }
            }
        }
        return p;
    }

    private static List<Button> buildButtons(JsonArray raw) {
        List<Button> out = new ArrayList<>();
        for (JsonElement e : raw) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            JsonObject b = obj(o, "bounds");
            boolean has = b != null && b.has("x") && b.has("y") && b.has("width") && b.has("height");
            JsonObject recipe = obj(o, "packetRecipe");
            boolean packetResolved = recipe != null && bool(recipe, "resolved", false);
            String packetClass = recipe == null ? null : str(recipe, "messageClass");
            List<String> packetArgs = new ArrayList<>();
            if (recipe != null && recipe.has("constructorArgs") && recipe.get("constructorArgs").isJsonArray()) {
                for (JsonElement a : recipe.getAsJsonArray("constructorArgs")) {
                    packetArgs.add(a.isJsonPrimitive() ? a.getAsString() : a.toString());
                }
            }
            out.add(new Button(str(o, "kind"), str(o, "handler"), str(o, "label"), str(o, "sourceMethod"),
                    i(o, "id", -1), has ? i(b, "x", 0) : 0, has ? i(b, "y", 0) : 0,
                    has ? i(b, "width", 0) : 0, has ? i(b, "height", 0) : 0, has,
                    packetResolved, packetClass, packetArgs));
        }
        return out;
    }

    /**
     * Grounded HBM compatibility fallback for the one known extracted dynamic rect that the
     * profile cannot currently represent: GUIMachineRTG's right-hand power gauge.  The bytecode
     * evidence is the same profile row that resolves the heat gauge immediately to its left:
     * x=guiLeft+146, texture u=192, width=16, and power/maxPower drives a 51px fill from the
     * bottom (screen y=guiTop+61-ratio, texture v=61-ratio, height=ratio).  The extractor leaves
     * v/h as opaque locals, so buildRects correctly refuses to guess; this narrow, class-paired
     * fallback supplies only that independently grounded HBM shape and still binds every value to
     * the live tile snapshot.  It is deliberately not a general unresolved-rect heuristic.
     */
    private static void appendKnownRtgPowerFallback(List<Rect> rects, String guiClass,
                                                     String containerClass, GuiProfile counters) {
        if (!"com.hbm.inventory.gui.GUIMachineRTG".equals(guiClass)
                || !"com.hbm.inventory.container.ContainerMachineRTG".equals(containerClass)) return;
        for (Rect r : rects) {
            if (r.dx == 146 && r.u == 192 && r.w == 16
                    && r.vTileExpr != null && r.hTileExpr != null) return;
        }

        TileFieldRef power = new TileFieldRef(new String[]{"power"}, new String[]{"field"});
        TileFieldRef powerMax = new TileFieldRef(new String[]{"powerMax"}, new String[]{"field"});
        TileFieldRef hasPower = new TileFieldRef(new String[]{"hasPower"}, new String[]{"accessor"});
        TileExpr destinationY = new TileExpr(power, 51.0, true, powerMax, 0.0,
                0, -1, "guiTop", 61, null, 0.0, null, false);
        TileExpr textureV = new TileExpr(power, 51.0, true, powerMax, 0.0,
                61, -1, null, 0, null, 0.0, null, false);
        TileExpr height = new TileExpr(power, 51.0, true, powerMax, 0.0,
                0, 1, null, 0, null, 0.0, null, false);
        TileGuard guard = new TileGuard(new TileGuard.Node("COMPARE", "EQ",
                new TileGuard.Operand("tileField", 0.0, null, 0, hasPower),
                new TileGuard.Operand("const", 0.0, null, 0, null), null));
        rects.add(new Rect(146, 0, 192, 0, 16, 0,
                null, null, null, null, null,
                null, destinationY,
                null, textureV, null, height, guard, 0));
        counters.rectsDrawable++;
        counters.rectsTileBoundDrawable++;
        counters.rectsTileGuardEvaluatedDrawable++;
    }

    /** One entry per element of the JSON {@code backgroundTextures} array, preserving index —
     *  {@code null} where umb-guimap could not resolve a bindTexture argument to an existing-in-jar
     *  asset. Index-preserving is the whole point: {@code Rect#textureIndex} (umb-guimap's
     *  {@code textureBindIndex}) is only meaningful as an index into THIS array. */
    private static TextureRef[] buildTextures(JsonArray textures) {
        TextureRef[] out = new TextureRef[textures.size()];
        for (int i = 0; i < textures.size(); i++) {
            JsonElement te = textures.get(i);
            if (!te.isJsonObject()) continue;
            JsonObject to = te.getAsJsonObject();
            if (!bool(to, "existsInJar", false)) continue;
            String path = str(to, "path");
            String assetPath = str(to, "assetPath");
            if (path == null || assetPath == null) continue;
            int colon = path.indexOf(':');
            String ns = colon > 0 ? path.substring(0, colon) : "minecraft";
            String rel = colon >= 0 ? path.substring(colon + 1) : path;
            int sw = i(to, "sheetWidth", 256);
            int sh = i(to, "sheetHeight", 256);
            out[i] = new TextureRef(ns, rel, assetPath, sw, sh);
        }
        return out;
    }

    /**
     * SCREEN-RENDER lane: turns umb-guimap's per-arg-classified {@code backgroundDrawRects} into
     * the small subset that is SAFE to blit directly, skipping everything else with a counted,
     * named reason (see {@code research/out/legacy/guimap-notes/SCREEN-RENDER.md} for the
     * corpus-wide numbers and the evidence behind each rule):
     * <ol>
     *   <li><b>Background-blit duplicate</b> — a rect shaped exactly like the full-panel background
     *   convention (x=guiLeft+0, y=guiTop+0, u=0, v=0) is the SAME art {@link UmbLegacyScreen}
     *   already blits via {@link GuiEntry#texture}/xSize/ySize; drawing it again would double-blit.
     *   Matched by SHAPE, not by list position — verified against the real corpus that exactly one
     *   rect (never zero-or-ambiguous-many) matches this shape per GUI that has one at all, and
     *   that skipping the wrong one instead of matching by index-0 matters (7/181 GUIs draw a
     *   real, non-background rect FIRST).</li>
     *   <li><b>Guarded/conditional</b> — {@code conditional.guarded} means this draw only runs if a
     *   runtime legacy condition holds (e.g. "does this tank have fluid", "is this slot occupied").
     *   Evaluating that would mean running legacy bytecode, which this project never does (see
     *   GENERALIZATION-PLAN.md's Vulkan section) — skipped rather than guessed at.</li>
     *   <li><b>Unbindable</b> — any of the six args is neither {@code CONST} nor
     *   {@code PANEL_RELATIVE} (a {@code STATE_LINEAR} progress-bar/gauge value, or fully
     *   {@code UNRESOLVED}). {@link dev.umb.bridge.api.ContainerHandle#syncData()} is an
     *   undifferentiated 32-int register bank with no per-index field descriptor (see
     *   {@link UmbLegacyScreen}'s own class javadoc on exactly this), so there is currently no
     *   reliable way to bind a legacy field to a sync index — skipped rather than drawn frozen
     *   (which would look like working software that lies). A real follow-up needs a per-mod-free
     *   field-to-syncIndex contract on the bridge; see SCREEN-RENDER.md.</li>
     *   <li><b>No resolvable texture</b> — the rect's own {@code textureBindIndex} (umb-guimap
     *   schemaVersion 3) does not land on a {@link TextureRef} that resolved to an existing-in-jar
     *   asset (includes {@code textureBindIndex == -1}, meaning the method never bound one of its
     *   own).</li>
     *   <li><b>Out of panel</b> — the resolved destination rect does not fit inside
     *   {@code [0,xSize) x [0,ySize)} (mandatory "clamp or skip" constraint) — skipped rather than
     *   drawn off-panel, using the SAME resolved xSize/ySize that becomes
     *   {@code AbstractContainerScreen.imageWidth/imageHeight} at runtime.</li>
     * </ol>
     * x/y are required to be {@code PANEL_RELATIVE} (never bare {@code CONST}) — real-corpus check:
     * zero of the currently-drawable rects have a CONST x or y, which makes sense: the background
     * layer's coordinate space is screen-absolute (unlike the foreground layer), so a bare literal
     * x/y would be a fixed screen pixel regardless of window position — not a shape this project has
     * ever seen a real mod emit, and not one worth inventing a meaning for.
     *
     * <p><b>SYNC-BINDING lane update:</b> "unbindable" and "guarded" are no longer unconditional —
     * see {@link #tryBindStateLinear}/{@link #tryResolveGuard}. A {@code STATE_LINEAR} u/v/w/h arg
     * whose source field (and field-typed divisor, if any) is in {@code container.syncBindings}
     * becomes a {@link SyncExpr} instead of a hard skip; a guard reducible to one bound-field
     * condition becomes a {@link SyncGuard} instead of an unconditional skip. Both are the exception,
     * not the rule — see {@code research/out/legacy/guimap-notes/SYNC-BINDING.md} for exactly how
     * few of the real corpus's 464 guarded / 150 unbindable rects this actually reaches, and why.
     */
    private static List<Rect> buildRects(JsonArray backgroundDrawRects, TextureRef[] textures,
                                          int xSize, int ySize, Map<String, Integer> syncFieldToIndex,
                                          GuiProfile counters) {
        return buildRects(backgroundDrawRects, textures, xSize, ySize, syncFieldToIndex, counters,
                null, null, null, null);
    }

    /**
     * NOTEXTURE-GAP lane: same overload, plus an optional {@code noTextureSink} — when non-null,
     * every rect that fails the texture-resolution check gets one {@link TextureGapRecord} appended
     * (raw rect JSON + raw {@code backgroundTextures} array included, so a diagnostic caller can
     * classify the exact cause without re-deriving anything). {@code null} everywhere else in this
     * file (every existing call site), so normal parsing is byte-for-byte unchanged — this is a pure
     * read-only observation hook, never consulted by {@link #buildRects(JsonArray, TextureRef[],
     * int, int, Map, GuiProfile)} itself.
     */
    private static List<Rect> buildRects(JsonArray backgroundDrawRects, TextureRef[] textures,
                                          int xSize, int ySize, Map<String, Integer> syncFieldToIndex,
                                          GuiProfile counters, List<TextureGapRecord> noTextureSink,
                                          JsonArray rawBackgroundTextures, String guiClassName,
                                          String containerClassName) {
        List<Rect> out = new ArrayList<>();
        int rectIndex = -1;
        for (JsonElement re : backgroundDrawRects) {
            rectIndex++;
            if (!re.isJsonObject()) continue;
            JsonObject ro = re.getAsJsonObject();
            counters.rectsTotal++;
            JsonArray args = ro.getAsJsonArray("args");
            if (args == null || args.size() != 6) { counters.rectsUnbindableSkipped++; continue; }
            JsonObject ax = args.get(0).getAsJsonObject(), ay = args.get(1).getAsJsonObject();
            JsonObject au = args.get(2).getAsJsonObject(), av = args.get(3).getAsJsonObject();
            JsonObject aw = args.get(4).getAsJsonObject(), ah = args.get(5).getAsJsonObject();

            if (isPanelOrigin(ax, "guiLeft") && isPanelOrigin(ay, "guiTop")
                    && isConst(au, 0) && isConst(av, 0)) {
                counters.rectsBackgroundDupSkipped++;
                continue;
            }

            // SYNC-BINDING lane: a guard is no longer an unconditional skip. It is skipped ONLY
            // when it can't be reduced to a single bound-field condition (see umb-guimap's
            // conditional.fieldCondition / tryResolveGuard) - an ANDed multi-frame guard, a method
            // call, or a field this GUI's container never actually syncs all still land here,
            // exactly as before. A rect whose guard DOES resolve still has to clear every other
            // check below (texture/bounds/all-six-args) before it is actually drawn.
            JsonObject cond = ro.getAsJsonObject("conditional");
            boolean guarded = cond != null && bool(cond, "guarded", false);
            SyncGuard guardExpr = guarded ? tryResolveGuard(cond, syncFieldToIndex) : null;
            // TILE-FIELD-SNAPSHOT lane: tried only after the sync-binding mechanism above already
            // failed - see tryResolveTileGuard's own javadoc for the exact fieldCondition-first,
            // skipCondition-fallback policy and why a guiField/opaque/unresolvable leaf anywhere in
            // the tree makes the WHOLE guard resolve to null here (never a partial pass).
            TileGuard tileGuardExpr = (guarded && guardExpr == null) ? tryResolveTileGuard(cond) : null;
            if (guarded && guardExpr == null && tileGuardExpr == null) { counters.rectsGuardedSkipped++; continue; }

            Integer dx = panelRelativeDelta(ax, "guiLeft");
            Integer dy = panelRelativeDelta(ay, "guiTop");
            // A gauge can animate its destination coordinate as well as its texture source/size
            // (HBM's electric-furnace power bar is guiTop+52-power*34/100000).
            TileExpr xTile = dx == null ? tryBindTileLinear(ax) : null;
            TileExpr yTile = dy == null ? tryBindTileLinear(ay) : null;
            Integer uC = constValue(au), vC = constValue(av), wC = constValue(aw), hC = constValue(ah);
            SyncExpr uExpr = uC == null ? tryBindStateLinear(au, syncFieldToIndex) : null;
            SyncExpr vExpr = vC == null ? tryBindStateLinear(av, syncFieldToIndex) : null;
            SyncExpr wExpr = wC == null ? tryBindStateLinear(aw, syncFieldToIndex) : null;
            SyncExpr hExpr = hC == null ? tryBindStateLinear(ah, syncFieldToIndex) : null;
            // TILE-FIELD-SNAPSHOT lane: same "only after sync-binding already failed" ordering.
            TileExpr uTile = (uC == null && uExpr == null) ? tryBindTileLinear(au) : null;
            TileExpr vTile = (vC == null && vExpr == null) ? tryBindTileLinear(av) : null;
            TileExpr wTile = (wC == null && wExpr == null) ? tryBindTileLinear(aw) : null;
            TileExpr hTile = (hC == null && hExpr == null) ? tryBindTileLinear(ah) : null;
            boolean uOk = uC != null || uExpr != null || uTile != null;
            boolean vOk = vC != null || vExpr != null || vTile != null;
            boolean wOk = wC != null || wExpr != null || wTile != null;
            boolean hOk = hC != null || hExpr != null || hTile != null;
            if ((dx == null && xTile == null) || (dy == null && yTile == null)
                    || !uOk || !vOk || !wOk || !hOk) {
                counters.rectsUnbindableSkipped++;
                continue;
            }

            int tbi = ro.has("textureBindIndex") ? ro.get("textureBindIndex").getAsInt() : -1;
            if (tbi < 0 || tbi >= textures.length || textures[tbi] == null) {
                counters.rectsNoTextureSkipped++;
                if (noTextureSink != null) {
                    JsonObject rawTexture = (rawBackgroundTextures != null && tbi >= 0
                            && tbi < rawBackgroundTextures.size() && rawBackgroundTextures.get(tbi).isJsonObject())
                            ? rawBackgroundTextures.get(tbi).getAsJsonObject() : null;
                    noTextureSink.add(new TextureGapRecord(guiClassName, containerClassName, rectIndex,
                            tbi, textures.length, rawTexture, ro));
                }
                continue;
            }

            // Clamp/skip rather than draw off-panel garbage (mandatory constraint) - xSize/ySize
            // here are already the SAME resolved values (fallback-substituted where unresolved)
            // that will become UmbLegacyScreen's imageWidth/imageHeight at runtime, so this check
            // is authoritative, not a guess against a possibly-stale size. A dynamically-bound w/h
            // can't be range-checked ahead of time (the register value isn't known until render);
            // only the statically-known half of the check runs for those - the same limitation
            // (skip rather than fake) applies here as everywhere else in this file.
            int w = wC != null ? wC : 0, h = hC != null ? hC : 0;
            if ((dx != null && dx < 0) || (dy != null && dy < 0)) {
                counters.rectsOutOfPanelSkipped++; continue;
            }
            if (wC != null && dx != null && dx + w > xSize) { counters.rectsOutOfPanelSkipped++; continue; }
            if (hC != null && dy != null && dy + h > ySize) { counters.rectsOutOfPanelSkipped++; continue; }

            int u = uC != null ? uC : 0, v = vC != null ? vC : 0;
            out.add(new Rect(dx != null ? dx : 0, dy != null ? dy : 0, u, v, w, h,
                    uExpr, vExpr, wExpr, hExpr, guardExpr, xTile, yTile,
                    uTile, vTile, wTile, hTile, tileGuardExpr, tbi));
            counters.rectsDrawable++;
            if (uExpr != null || vExpr != null || wExpr != null || hExpr != null) counters.rectsSyncBoundDrawable++;
            if (guardExpr != null) counters.rectsSyncGuardEvaluatedDrawable++;
            if (uTile != null || vTile != null || wTile != null || hTile != null) counters.rectsTileBoundDrawable++;
            if (tileGuardExpr != null) counters.rectsTileGuardEvaluatedDrawable++;
        }
        return out;
    }

    /** TILE-FIELD-SNAPSHOT lane: the deduplicated union of every {@link TileFieldRef} one GUI's
     *  drawable rects/guards need, keyed by {@link TileFieldRef#key} — the FieldPath[] request a
     *  host-side menu builds once per menu-open (see {@link GuiEntry#tileFieldRefs}). */
    private static List<TileFieldRef> collectTileFieldRefs(List<Rect> rects) {
        Map<String, TileFieldRef> byKey = new LinkedHashMap<>();
        for (Rect r : rects) {
            addRef(byKey, r.xTileExpr); addRef(byKey, r.yTileExpr);
            addRef(byKey, r.uTileExpr); addRef(byKey, r.vTileExpr);
            addRef(byKey, r.wTileExpr); addRef(byKey, r.hTileExpr);
            if (r.tileGuard != null) collectGuardRefs(r.tileGuard.root, byKey);
        }
        return new ArrayList<>(byKey.values());
    }

    private static void addRef(Map<String, TileFieldRef> byKey, TileExpr e) {
        if (e == null) return;
        byKey.putIfAbsent(e.numerator.key, e.numerator);
        if (e.divisorIsField) byKey.putIfAbsent(e.divisorField.key, e.divisorField);
    }

    private static void collectGuardRefs(TileGuard.Node n, Map<String, TileFieldRef> byKey) {
        if (n == null) return;
        if ("COMPARE".equals(n.op)) {
            addOperandRef(byKey, n.left);
            addOperandRef(byKey, n.right);
        } else if (n.operands != null) {
            for (TileGuard.Node c : n.operands) collectGuardRefs(c, byKey);
        }
    }

    private static void addOperandRef(Map<String, TileFieldRef> byKey, TileGuard.Operand op) {
        if (op != null && op.tileField != null) byKey.putIfAbsent(op.tileField.key, op.tileField);
    }

    /**
     * Mandatory clamp (the brief's explicit correctness risk): a live sync/tile-bound width or
     * height can legitimately overshoot its own texture region — e.g. before a divisor field has
     * initialised, or between two ticks — so the destination rect must be clamped to fit inside its
     * own panel rather than drawn off-panel. {@code offset} is the rect's own panel-relative dx/dy,
     * {@code size} the live-evaluated w/h, {@code panelSize} the GUI's own resolved xSize/ySize.
     * Never negative: a rect that starts entirely outside the panel already clamps to 0 (the caller
     * then skips the draw, per {@link UmbLegacyScreen#drawExtraRects}).
     */
    public static int clampToPanel(int offset, int size, int panelSize) {
        return Math.max(0, Math.min(size, panelSize - offset));
    }

    /** {@code container.syncBindings} (umb-guimap schemaVersion 4) reduced to a lookup keyed by
     *  {@code ownerClass#fieldName}, ready for {@link #tryBindStateLinear}/{@link #tryResolveGuard}.
     *  Only the SERVER route ever actually populates {@code ContainerHandle.syncData()} in this
     *  bridge (it reads {@code container.func_75142_b()}'s effect, never runs
     *  {@code func_75137_b()} - the client route exists purely as an independent cross-check), so a
     *  binding is used here whenever {@code serverRoute} is true, REGARDLESS of whether the client
     *  route also saw it - agreement raises confidence but isn't required for correctness. An
     *  explicit two-route DISAGREEMENT is the one case excluded outright: SYNC-BINDING.md's own rule
     *  is "report it, never silently pick one" - excluding it here is that policy enforced in code,
     *  not just documented. */
    private static Map<String, Integer> buildSyncFieldMap(JsonArray syncBindings) {
        Map<String, Integer> map = new java.util.HashMap<>();
        if (syncBindings == null) return map;
        for (JsonElement e : syncBindings) {
            if (!e.isJsonObject()) continue;
            JsonObject bo = e.getAsJsonObject();
            if (!bool(bo, "serverRoute", false)) continue;
            if (bo.has("agree") && !bo.get("agree").isJsonNull() && !bo.get("agree").getAsBoolean()) continue;
            JsonObject field = bo.getAsJsonObject("field");
            if (field == null || !bo.has("syncIndex")) continue;
            String owner = str(field, "ownerClass"), name = str(field, "fieldName");
            if (owner == null || name == null) continue;
            map.put(owner + "#" + name, bo.get("syncIndex").getAsInt());
        }
        return map;
    }

    /** Resolves one {@code STATE_LINEAR} arg to a live {@link SyncExpr}, or null when it can't be
     *  (the field isn't sync-bound, the divisor is a field that isn't EITHER, or the expression uses
     *  a {@code panelBase}/{@code clampFunction} this lane deliberately doesn't evaluate - see the
     *  class javadoc on {@link SyncExpr}). Never guesses: every early return here is a real, honest
     *  "stays unbindable", not a shortcut. */
    private static SyncExpr tryBindStateLinear(JsonObject arg, Map<String, Integer> syncFieldToIndex) {
        if (!"STATE_LINEAR".equals(str(arg, "classification"))) return null;
        JsonObject binding = arg.getAsJsonObject("stateBinding");
        if (binding == null) return null;
        if (binding.has("panelBase") && !binding.get("panelBase").isJsonNull()) return null;
        if (binding.has("clampFunction")) return null;
        JsonObject source = binding.getAsJsonObject("source");
        Integer numIdx = fieldIndex(source, syncFieldToIndex);
        if (numIdx == null) return null;
        double multiplier = binding.has("multiplier") ? binding.get("multiplier").getAsDouble() : 1.0;
        int offset = binding.has("offset") ? binding.get("offset").getAsInt() : 0;
        int sign = binding.has("sign") ? binding.get("sign").getAsInt() : 1;
        JsonObject divisor = binding.getAsJsonObject("divisor");
        if (divisor == null) return null;
        String divKind = str(divisor, "kind");
        if ("const".equals(divKind)) {
            double dv = divisor.has("value") ? divisor.get("value").getAsDouble() : 1.0;
            return new SyncExpr(numIdx, multiplier, false, -1, dv, offset, sign);
        }
        if ("field".equals(divKind)) {
            Integer divIdx = fieldIndex(divisor, syncFieldToIndex);
            if (divIdx == null) return null;
            return new SyncExpr(numIdx, multiplier, true, divIdx, 0.0, offset, sign);
        }
        return null;
    }

    /** Resolves {@code conditional.fieldCondition} (umb-guimap schemaVersion 4) to a live
     *  {@link SyncGuard}, or null when there is none, or its field isn't sync-bound. */
    private static SyncGuard tryResolveGuard(JsonObject cond, Map<String, Integer> syncFieldToIndex) {
        if (!cond.has("fieldCondition")) return null;
        JsonObject fc = cond.getAsJsonObject("fieldCondition");
        Integer idx = fieldIndex(fc.getAsJsonObject("source"), syncFieldToIndex);
        if (idx == null) return null;
        String op = str(fc, "skipOp");
        if (op == null) return null;
        int val = fc.has("skipValue") ? fc.get("skipValue").getAsInt() : 0;
        return new SyncGuard(idx, op, val);
    }

    private static Integer fieldIndex(JsonObject fieldObj, Map<String, Integer> syncFieldToIndex) {
        if (fieldObj == null) return null;
        String owner = str(fieldObj, "ownerClass"), name = str(fieldObj, "fieldName");
        if (owner == null || name == null) return null;
        return syncFieldToIndex.get(owner + "#" + name);
    }

    // ==================== TILE-FIELD-SNAPSHOT lane ====================

    /** Walks umb-guimap's {@code fieldRequirement.hops} (present on a {@code source}/{@code divisor}
     *  object, or directly on a guard leaf) into a {@link TileFieldRef}, or null when there is none
     *  (a field that lives on the GUI itself, not any tile entity — not this mechanism's job) or the
     *  hop shape is anything other than the two kinds this project ever extracts. Never guesses. */
    private static TileFieldRef parseFieldRequirement(JsonObject holder) {
        if (holder == null) return null;
        JsonObject fr = obj(holder, "fieldRequirement");
        if (fr == null) return null;
        JsonArray hops = arr(fr, "hops");
        if (hops.size() == 0) return null;
        String[] names = new String[hops.size()];
        String[] kinds = new String[hops.size()];
        for (int i = 0; i < hops.size(); i++) {
            if (!hops.get(i).isJsonObject()) return null;
            JsonObject h = hops.get(i).getAsJsonObject();
            String n = str(h, "fieldName");
            String k = str(h, "kind");
            if (n == null || (!"field".equals(k) && !"accessor".equals(k))) return null;
            names[i] = n;
            kinds[i] = k;
        }
        return new TileFieldRef(names, kinds);
    }

    /** Resolves one {@code STATE_LINEAR} arg to a live {@link TileExpr} sourced from a per-tile
     *  snapshot, or null when its numerator has no tile-entity chain at all (a GUI-owned field —
     *  screen width/height and the like — genuinely out of this mechanism's scope) or its divisor is
     *  a field with no tile-entity chain either. Unlike {@link #tryBindStateLinear}, {@code panelBase}
     *  and {@code clampFunction} ARE evaluated here — SYNC-BINDING.md §7 recommendation 5, cheap and
     *  fully specified once the source is a live per-tick snapshot rather than a sync register. */
    private static TileExpr tryBindTileLinear(JsonObject arg) {
        if (!"STATE_LINEAR".equals(str(arg, "classification"))) return null;
        JsonObject binding = arg.getAsJsonObject("stateBinding");
        if (binding == null) return null;
        JsonObject source = binding.getAsJsonObject("source");
        TileFieldRef numerator = parseFieldRequirement(source);
        if (numerator == null) return null;
        double multiplier = binding.has("multiplier") ? binding.get("multiplier").getAsDouble() : 1.0;
        int offset = binding.has("offset") ? binding.get("offset").getAsInt() : 0;
        int sign = binding.has("sign") ? binding.get("sign").getAsInt() : 1;
        JsonObject divisor = binding.getAsJsonObject("divisor");
        if (divisor == null) return null;
        String divKind = str(divisor, "kind");
        boolean divisorIsField;
        TileFieldRef divField = null;
        double divConst = 1.0;
        if ("const".equals(divKind)) {
            divisorIsField = false;
            divConst = divisor.has("value") ? divisor.get("value").getAsDouble() : 1.0;
        } else if ("field".equals(divKind)) {
            divField = parseFieldRequirement(divisor);
            if (divField == null) return null; // a GUI-origin divisor field - not this mechanism's job
            divisorIsField = true;
        } else {
            return null;
        }
        String panelBaseAxis = null;
        int panelBaseDelta = 0;
        JsonObject panelBase = obj(binding, "panelBase");
        if (panelBase != null) {
            panelBaseAxis = str(panelBase, "axis");
            panelBaseDelta = panelBase.has("delta") ? panelBase.get("delta").getAsInt() : 0;
        }
        String clampFn = binding.has("clampFunction") ? str(binding, "clampFunction") : null;
        double clampValue = binding.has("clampValue") ? binding.get("clampValue").getAsDouble() : 0.0;
        // GUI-fidelity lane: divide-by-guarded-maximum floor + whole-term ceil (both optional,
        // both inert when absent - every pre-existing row parses exactly as before).
        Double divisorFloor = null;
        if (divisorIsField && divisor.has("divisorFloor") && divisor.get("divisorFloor").isJsonPrimitive()) {
            try {
                divisorFloor = Double.valueOf(divisor.get("divisorFloor").getAsDouble());
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        boolean ceilResult = binding.has("ceilResult") && binding.get("ceilResult").isJsonPrimitive()
                && bool(binding, "ceilResult", false);
        return new TileExpr(numerator, multiplier, divisorIsField, divField, divConst, offset, sign,
                panelBaseAxis, panelBaseDelta, clampFn, clampValue, divisorFloor, ceilResult);
    }

    /**
     * Resolves one guard's {@code conditional} to a live {@link TileGuard}, or null when it can't
     * be. {@code fieldCondition} (the single-frame field-vs-const shape, already reliable) is tried
     * FIRST and exclusively when present — {@code skipCondition}'s own tree for that exact shape has
     * a demonstrated extractor artifact for a bare single-operand {@code IFxx} test (a coincidentally
     * resolved compile-time "constant" standing in for what is actually a live, mutable tile field;
     * see {@code research/out/legacy/guimap-notes/GAUGE-RENDER.md} for the concrete example and the
     * corpus-wide count), so it is never trusted for a shape {@code fieldCondition} already covers
     * correctly. {@code skipCondition} is walked only as the fallback for the genuine multi-frame
     * (OR-of-comparisons) or two-operand shapes {@code fieldCondition}'s own extraction rule never
     * captures — see {@link #parseSkipConditionNode} for the same defensive distrust applied there.
     */
    private static TileGuard tryResolveTileGuard(JsonObject cond) {
        JsonObject fieldCondition = obj(cond, "fieldCondition");
        TileGuard.Node node = fieldCondition != null ? parseFieldConditionAsNode(fieldCondition) : null;
        if (node == null) {
            JsonObject skipCondition = obj(cond, "skipCondition");
            node = skipCondition != null ? parseSkipConditionNode(skipCondition) : null;
        }
        return node != null ? new TileGuard(node) : null;
    }

    private static TileGuard.Node parseFieldConditionAsNode(JsonObject fieldCondition) {
        TileFieldRef ref = parseFieldRequirement(obj(fieldCondition, "source"));
        if (ref == null) return null;
        String op = str(fieldCondition, "skipOp");
        if (op == null) return null;
        double val = fieldCondition.has("skipValue") ? fieldCondition.get("skipValue").getAsDouble() : 0.0;
        TileGuard.Operand left = new TileGuard.Operand("tileField", 0, null, 0, ref);
        TileGuard.Operand right = new TileGuard.Operand("const", val, null, 0, null);
        return new TileGuard.Node("COMPARE", op, left, right, null);
    }

    /** Walks umb-guimap's general {@code skipCondition} tree (GUARD-EXPRESSIONS.md schemaVersion 6)
     *  into a {@link TileGuard.Node}, or null the instant ANY leaf anywhere in the tree can't be
     *  resolved — a {@code guiField} (client-owned legacy GUI state with no live source in this
     *  architecture) or {@code unknown} (arithmetic, a poisoned local, ...) leaf fails the WHOLE
     *  guard, never just its own branch, so a rect is never drawn on a guard this project can only
     *  partially evaluate. */
    private static TileGuard.Node parseSkipConditionNode(JsonObject node) {
        String op = str(node, "op");
        if ("COMPARE".equals(op)) {
            JsonObject l = obj(node, "left"), r = obj(node, "right");
            String cmpOp = str(node, "compareOp");
            if (l == null || r == null || cmpOp == null) return null;
            // Defensive, but narrowly targeted: the demonstrated extractor artifact is specifically
            // a SINGLE-operand IFxx test whose real operand got resolved to a compile-time constant
            // by FieldConstResolver (a live, mutable tile field standing in for what should have
            // stayed unresolved) - frameCompareNode's own fallback path ALWAYS pairs that real
            // operand against a manufactured RIGHT-hand {@code Val.number(0)} (see
            // DrawLayerScanner#frameCompareNode's javadoc), never any other value. A genuine
            // TWO-operand IF_ICMPxx/IF_ACMPxx test whose real stack operands both happen to fold to
            // compile-time constants (verified live in this corpus: 61 of 67 both-const leaves have
            // a NON-zero right side, e.g. a real "0 >= 3" bound check) is a completely different,
            // unaffected code path and must not be punished for looking superficially similar - only
            // "both const AND right==0" is ever downgraded to unresolvable here. This still cannot
            // ever cause an incorrect DRAW (the guard just becomes undecidable, which
            // {@link TileGuard#shouldSkip} treats as skip), only a narrower, evidence-justified
            // "distrust" than a blanket rule would apply.
            if ("const".equals(str(l, "kind")) && "const".equals(str(r, "kind"))
                    && r.has("value") && !r.get("value").isJsonNull() && r.get("value").getAsDouble() == 0.0) {
                TileGuard.Operand distrusted = new TileGuard.Operand("distrusted", 0, null, 0, null);
                return new TileGuard.Node("COMPARE", cmpOp, distrusted, distrusted, null);
            }
            TileGuard.Operand lo = parseOperand(l), ro = parseOperand(r);
            if (lo == null || ro == null) return null;
            return new TileGuard.Node("COMPARE", cmpOp, lo, ro, null);
        }
        if ("AND".equals(op) || "OR".equals(op)) {
            JsonArray ops = node.getAsJsonArray("operands");
            if (ops == null) return null;
            List<TileGuard.Node> children = new ArrayList<>();
            for (JsonElement e : ops) {
                if (!e.isJsonObject()) return null;
                TileGuard.Node c = parseSkipConditionNode(e.getAsJsonObject());
                if (c == null) return null; // any unresolvable child fails the WHOLE guard
                children.add(c);
            }
            return new TileGuard.Node(op, null, null, null, children);
        }
        return null;
    }

    private static TileGuard.Operand parseOperand(JsonObject o) {
        String kind = str(o, "kind");
        if (kind == null) return null;
        switch (kind) {
            case "const": {
                if (!o.has("value") || o.get("value").isJsonNull()) return null; // e.g. an IFNULL const
                return new TileGuard.Operand("const", o.get("value").getAsDouble(), null, 0, null);
            }
            case "mouse": {
                String axis = str(o, "axis");
                return axis == null ? null : new TileGuard.Operand("mouse", 0, axis, 0, null);
            }
            case "panelOrigin": {
                String axis = str(o, "axis");
                if (axis == null) return null;
                int delta = o.has("delta") ? o.get("delta").getAsInt() : 0;
                return new TileGuard.Operand("panelOrigin", 0, axis, delta, null);
            }
            case "tileField": {
                TileFieldRef ref = parseFieldRequirement(o);
                return ref == null ? null : new TileGuard.Operand("tileField", 0, null, 0, ref);
            }
            default:
                // guiField / unknown - no live source in this architecture (no legacy GUI object is
                // ever instantiated) - never fabricated. See TileGuard's own class javadoc.
                return null;
        }
    }

    /**
     * Foreground labels: only fully-resolved text (literal or a translation key) with a CONST x
     * AND y survives — a centered-title x (computed from {@code FontRenderer.getStringWidth} at
     * runtime) or any other UNRESOLVED/STATE_LINEAR position is skipped rather than guessed at (see
     * SCREEN-RENDER.md for the exact count and which GUIs it affects). Real-corpus check: no
     * currently-drawable label has a PANEL_RELATIVE x/y either — 1.7.10's foreground layer already
     * runs inside a GL translate by guiLeft/guiTop, so a bare CONST offset here already means
     * "relative to the panel", the same convention {@code AbstractContainerScreen}'s own
     * {@code titleLabelX}/{@code inventoryLabelX} use.
     */
    private static List<Label> buildLabels(JsonArray foregroundLabels, GuiProfile counters) {
        List<Label> out = new ArrayList<>();
        for (JsonElement le : foregroundLabels) {
            if (!le.isJsonObject()) continue;
            JsonObject lo = le.getAsJsonObject();
            counters.labelsTotal++;
            if (bool(lo, "dynamic", true)) { counters.labelsUnresolvedSkipped++; continue; }
            String text = str(lo, "text");
            JsonObject lx = obj(lo, "x"), ly = obj(lo, "y");
            Integer x = lx == null ? null : constValue(lx);
            Integer y = ly == null ? null : constValue(ly);
            if (text == null || x == null || y == null) { counters.labelsUnresolvedSkipped++; continue; }
            out.add(new Label(bool(lo, "translated", false), text, x, y));
            counters.labelsDrawable++;
        }
        return out;
    }

    private static boolean isPanelOrigin(JsonObject arg, String base) {
        return "PANEL_RELATIVE".equals(str(arg, "classification"))
                && base.equals(str(arg, "base")) && i(arg, "delta", -1) == 0;
    }

    private static boolean isConst(JsonObject arg, int value) {
        return "CONST".equals(str(arg, "classification")) && i(arg, "value", value + 1) == value;
    }

    private static Integer panelRelativeDelta(JsonObject arg, String base) {
        if (!"PANEL_RELATIVE".equals(str(arg, "classification")) || !base.equals(str(arg, "base"))) return null;
        return arg.has("delta") ? arg.get("delta").getAsInt() : null;
    }

    private static Integer constValue(JsonObject arg) {
        if (!"CONST".equals(str(arg, "classification"))) return null;
        return arg.has("value") ? arg.get("value").getAsInt() : null;
    }

    /**
     * null when there is no pairing for this container class (never opened via a real HBM GUI, the
     * class is a raw GuiScreen with no container, or gui-profile.json was not supplied) - callers
     * must fall back to the vanilla dispenser panel entirely. Non-null but with
     * {@code sizeExact=false} / {@code texture=null} means a PARTIAL fallback (size and texture
     * fall back independently) - both kinds are logged exactly once per container class here so
     * the gap stays visible rather than silent.
     */
    public GuiEntry lookup(String containerClassName) {
        return lookup(containerClassName, null);
    }

    /**
     * ironchest-visuals lane: prefers the containerId-keyed row (1.16.5: one Container
     * class serves many ContainerTypes with different sizes/textures), then the
     * class-keyed row. A null/blank id behaves exactly like {@link #lookup(String)}.
     */
    public GuiEntry lookup(String containerClassName, String containerId) {
        if (containerId != null && !containerId.isBlank()) {
            GuiEntry e = byContainerId.get(containerId);
            if (e != null) {
                logFallbacks(containerId, e);
                return e;
            }
        }
        if (containerClassName == null) return null;
        GuiEntry e = byContainerClass.get(containerClassName);
        if (e == null) return null;
        logFallbacks(containerClassName, e);
        return e;
    }

    private void logFallbacks(String key, GuiEntry e) {
        if (!e.sizeExact && loggedSizeFallback.add(key)) {
            sizeFallbackCount++;
            AgentLog.loud("UMB-GUI size unresolved for " + e.guiClassName + " (container " + key
                    + ") - falling back to " + FALLBACK_X + "x" + FALLBACK_Y);
        }
        if (e.texture == null && loggedTextureFallback.add(key)) {
            textureFallbackCount++;
            AgentLog.loud("UMB-GUI no resolvable background texture for " + e.guiClassName + " (container "
                    + key + ") - falling back to the vanilla dispenser panel");
        }
    }

    public int size() {
        return byContainerClass.size();
    }

    /**
     * Multi-mod: adds {@code other}'s container entries to this profile (first owner wins on the
     * rare exact container-class collision, counted in duplicateContainers) and sums the coverage
     * counters. Registrar runs once per mod; it used to REPLACE the single static profile, so the
     * last mod (MC Heli, 5 GUIs) erased HBM's 171 and every HBM GUI fell back to a vanilla panel
     * with misplaced slots (live Tsar Bomba test 2026-09-23).
     */
    public GuiProfile mergeFrom(GuiProfile other) {
        if (other == null || other == this) return this;
        for (Map.Entry<String, GuiEntry> e : other.byContainerClass.entrySet()) {
            if (byContainerClass.putIfAbsent(e.getKey(), e.getValue()) != null) duplicateContainers++;
        }
        for (Map.Entry<String, GuiEntry> e : other.byContainerId.entrySet()) {
            if (byContainerId.putIfAbsent(e.getKey(), e.getValue()) != null) duplicateContainerIds++;
        }
        loadedGuis += other.loadedGuis; duplicateContainers += other.duplicateContainers;
        rectsTotal += other.rectsTotal; rectsBackgroundDupSkipped += other.rectsBackgroundDupSkipped;
        rectsGuardedSkipped += other.rectsGuardedSkipped; rectsUnbindableSkipped += other.rectsUnbindableSkipped;
        rectsNoTextureSkipped += other.rectsNoTextureSkipped; rectsOutOfPanelSkipped += other.rectsOutOfPanelSkipped;
        rectsDrawable += other.rectsDrawable; labelsTotal += other.labelsTotal;
        labelsUnresolvedSkipped += other.labelsUnresolvedSkipped; labelsDrawable += other.labelsDrawable;
        rectsSyncBoundDrawable += other.rectsSyncBoundDrawable;
        rectsSyncGuardEvaluatedDrawable += other.rectsSyncGuardEvaluatedDrawable;
        rectsTileBoundDrawable += other.rectsTileBoundDrawable;
        rectsTileGuardEvaluatedDrawable += other.rectsTileGuardEvaluatedDrawable;
        return this;
    }

    public Collection<GuiEntry> entries() {
        return byContainerClass.values();
    }

    // ---------------- json helpers (mirrors LegacySnapshot's tolerant style) ----------------

    /**
     * ironchest-visuals lane: the optional per-row container registry id
     * ({@code container.containerId}, e.g. "ironchest:iron_chest"). Blank/absent/malformed
     * means "class key only" (every 1.7.10 row) - never a fallback to a guessed value.
     */
    private static String containerIdOf(JsonObject container) {
        String id = str(container, "containerId");
        if (id == null) return null;
        int colon = id.indexOf(':');
        if (colon <= 0 || colon == id.length() - 1 || id.indexOf(':', colon + 1) >= 0) {
            return null;
        }
        return id;
    }

    private static JsonArray arr(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return (e != null && e.isJsonArray()) ? e.getAsJsonArray() : new JsonArray();
    }

    private static JsonObject obj(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return (e != null && e.isJsonObject()) ? e.getAsJsonObject() : null;
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return null;
        String v = e.getAsString();
        return (v == null || v.isEmpty()) ? null : v;
    }

    private static boolean bool(JsonObject o, String key, boolean def) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return def;
        try {
            return e.getAsBoolean();
        } catch (RuntimeException ex) {
            return def;
        }
    }

    private static int i(JsonObject o, String key, int def) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return def;
        try {
            return e.getAsInt();
        } catch (RuntimeException ex) {
            return def;
        }
    }
}
