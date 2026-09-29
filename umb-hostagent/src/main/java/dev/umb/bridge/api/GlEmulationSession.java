package dev.umb.bridge.api;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Native-free immediate-mode GL/Tessellator recorder shared by the legacy render boundary.
 *
 * <p>This is intentionally data-only: it can be loaded by both class-loader sides, makes no
 * reference to LWJGL, and is also usable by the headless gates. The legacy shim supplies calls to
 * this session; objbridge consumes the sealed draws.</p>
 */
public final class GlEmulationSession {
    public static final int GL_POINTS = 0;
    public static final int GL_LINES = 1;
    public static final int GL_LINE_LOOP = 2;
    public static final int GL_LINE_STRIP = 3;
    public static final int GL_TRIANGLES = 4;
    public static final int GL_TRIANGLE_STRIP = 5;
    public static final int GL_TRIANGLE_FAN = 6;
    public static final int GL_QUADS = 7;
    public static final int GL_QUAD_STRIP = 8;

    public static final class State {
        public final String texture;
        public final int mode;
        public final int enabled;
        public final int blendSource;
        public final int blendDestination;
        public final int alphaFunction;
        public final float alphaReference;
        public final int cullFace;
        public final int color;
        public final boolean lighting;
        public final boolean depthMask;
        public final int shadeModel;
        public final int activeTexture;
        public final List<Integer> enabledCaps;

        State(String texture, int mode, int enabled, int blendSource, int blendDestination,
              int alphaFunction, float alphaReference, int cullFace, int color, boolean lighting,
              boolean depthMask, int shadeModel, int activeTexture, Set<Integer> enabledCaps) {
            this.texture = texture;
            this.mode = mode;
            this.enabled = enabled;
            this.blendSource = blendSource;
            this.blendDestination = blendDestination;
            this.alphaFunction = alphaFunction;
            this.alphaReference = alphaReference;
            this.cullFace = cullFace;
            this.color = color;
            this.lighting = lighting;
            this.depthMask = depthMask;
            this.shadeModel = shadeModel;
            this.activeTexture = activeTexture;
            this.enabledCaps = Collections.unmodifiableList(new ArrayList<Integer>(enabledCaps));
        }
    }

    public static final class Vertex {
        public final float x, y, z, u, v, nx, ny, nz;
        public final int color;
        Vertex(float x, float y, float z, float u, float v, float nx, float ny, float nz, int color) {
            this.x = x; this.y = y; this.z = z; this.u = u; this.v = v;
            this.nx = nx; this.ny = ny; this.nz = nz; this.color = color;
        }
    }

    public static final class Draw {
        public final String texture;
        public final int mode;
        public final State state;
        public final List<Vertex> vertices;
        public final float[] matrix;
        Draw(String texture, int mode, State state, List<Vertex> vertices, float[] matrix) {
            this.texture = texture;
            this.mode = mode;
            this.state = state;
            this.vertices = Collections.unmodifiableList(new ArrayList<Vertex>(vertices));
            this.matrix = matrix.clone();
        }
    }

    public static final class Mesh {
        public final List<Draw> draws;
        public final int matrixOps;
        public final int pushes;
        public final int pops;
        /**
         * Forge {@code RenderGameOverlayEvent.Pre}'s {@code ElementType.name()} (e.g.
         * {@code "CROSSHAIRS"}) for every vanilla overlay element a legacy mod cancelled while
         * this mesh's frame was captured - empty (the shared, allocation-free
         * {@link Collections#emptySet()}) whenever nothing was cancelled, which is the
         * overwhelming common case. The host side ({@code Hooks.renderHud} /
         * {@code LegacyHudPatcher}) reads this once per frame to suppress its own matching
         * vanilla draw, mirroring real Forge semantics: a cancelled Pre means "I drew this
         * element myself, don't also draw the native one".
         */
        public final Set<String> canceledElements;

        Mesh(List<Draw> draws, int matrixOps, int pushes, int pops) {
            this(draws, matrixOps, pushes, pops, Collections.<String>emptySet());
        }

        Mesh(List<Draw> draws, int matrixOps, int pushes, int pops, Set<String> canceledElements) {
            this.draws = Collections.unmodifiableList(new ArrayList<Draw>(draws));
            this.matrixOps = matrixOps;
            this.pushes = pushes;
            this.pops = pops;
            this.canceledElements = canceledElements == null || canceledElements.isEmpty()
                    ? Collections.<String>emptySet()
                    : Collections.unmodifiableSet(new LinkedHashSet<String>(canceledElements));
        }
        public int vertexCount() {
            int count = 0;
            for (Draw d : draws) count += d.vertices.size();
            return count;
        }
    }

    /** Combines independently captured overlay phases without exposing mutable session state.
     *  Unions {@code canceledElements} too (still allocation-free whenever both sides are the
     *  shared empty set, i.e. every frame with nothing cancelled). */
    public static Mesh concat(Mesh first, Mesh second) {
        if (first == null) return second;
        if (second == null) return first;
        List<Draw> joined = new ArrayList<Draw>(first.draws.size() + second.draws.size());
        joined.addAll(first.draws);
        joined.addAll(second.draws);
        Set<String> canceled;
        if (first.canceledElements.isEmpty()) {
            canceled = second.canceledElements;
        } else if (second.canceledElements.isEmpty()) {
            canceled = first.canceledElements;
        } else {
            canceled = new LinkedHashSet<String>(first.canceledElements);
            canceled.addAll(second.canceledElements);
        }
        return new Mesh(joined, first.matrixOps + second.matrixOps,
                first.pushes + second.pushes, first.pops + second.pops, canceled);
    }

    /** Attaches {@code canceledElements} to {@code mesh} without touching its draws - a no-op
     *  (returns {@code mesh} itself) when the set is null/empty, so a frame with nothing
     *  cancelled (the common case) never allocates a new Mesh here either. */
    public static Mesh withCanceledElements(Mesh mesh, Set<String> canceledElements) {
        if (mesh == null) return null;
        if (canceledElements == null || canceledElements.isEmpty()) return mesh;
        return new Mesh(mesh.draws, mesh.matrixOps, mesh.pushes, mesh.pops, canceledElements);
    }

    private static final class ListBuilder {
        final int id;
        final List<Draw> draws = new ArrayList<Draw>();
        ListBuilder(int id) { this.id = id; }
    }

    private final boolean collectVertices;
    private final ArrayDeque<double[]> matrices = new ArrayDeque<double[]>();
    private final List<Draw> draws = new ArrayList<Draw>();
    private static final int MAX_DISPLAY_LISTS = 2048;
    private static final long MAX_DISPLAY_LIST_BYTES = 16L * 1024L * 1024L;
    /** Display lists are created by model loading in one capture scope and called by a later
     * tile/entity scope. Keep the bounded registry across sessions, just like native GL does. */
    private static final Map<Integer, List<Draw>> DISPLAY_LISTS =
            new LinkedHashMap<Integer, List<Draw>>(64, 0.75f, true);
    private static final AtomicInteger NEXT_DISPLAY_LIST = new AtomicInteger(1);
    private static long displayListBytes;
    private double[] matrix = identity();
    private List<Vertex> current;
    private int mode;
    private String texture;
    private float u, v, nx, ny, nz;
    private int color = 0xFFFFFFFF;
    private int enabled;
    private final Set<Integer> enabledCaps = new LinkedHashSet<Integer>();
    private int blendSource, blendDestination, alphaFunction, cullFace;
    private float alphaReference;
    private boolean lighting;
    private boolean depthMask = true;
    private int shadeModel;
    private int activeTexture;
    private int matrixOps, pushes, pops;
    private ListBuilder recording;

    public GlEmulationSession(boolean collectVertices) {
        this.collectVertices = collectVertices;
    }

    public void begin(int primitiveMode) {
        end();
        mode = primitiveMode;
        // Keep an empty list when geometry is disabled: transform-only captures still need the
        // ordered draw/matrix boundaries for the cached mesh replay.
        current = new ArrayList<Vertex>();
    }

    public void end() {
        if (current == null) return;
        List<Vertex> out = current;
        current = null;
        Draw draw = new Draw(texture, mode, state(), out, matrix());
        draws.add(draw);
        if (recording != null) recording.draws.add(draw);
    }

    public void vertex(double x, double y, double z) { vertex(x, y, z, u, v); }

    public void vertex(double x, double y, double z, double uu, double vv) {
        if (current == null || !collectVertices) return;
        double[] p = point(x, y, z);
        current.add(new Vertex((float) p[0], (float) p[1], (float) p[2], (float) uu,
                (float) vv, nx, ny, nz, color));
    }

    public void texCoord(double uu, double vv) { u = (float) uu; v = (float) vv; }
    public void normal(float x, float y, float z) { nx = x; ny = y; nz = z; }
    public void color(float r, float g, float b, float a) {
        color = ((clamp(a) & 255) << 24) | ((clamp(r) & 255) << 16)
                | ((clamp(g) & 255) << 8) | (clamp(b) & 255);
    }
    public void color(int rgba) { color = rgba; }
    public void bindTexture(String id) { texture = id; }

    public void pushMatrix() { matrices.push(matrix.clone()); pushes++; matrixOps++; }
    public void popMatrix() {
        if (!matrices.isEmpty()) matrix = matrices.pop();
        pops++; matrixOps++;
    }
    public void translate(double x, double y, double z) {
        double[] t = identity(); t[12] = x; t[13] = y; t[14] = z; multiply(t);
    }
    public void scale(double x, double y, double z) {
        double[] t = identity(); t[0] = x; t[5] = y; t[10] = z; multiply(t);
    }
    public void rotate(double angle, double x, double y, double z) {
        double len = Math.sqrt(x * x + y * y + z * z);
        if (len == 0.0) return;
        x /= len; y /= len; z /= len;
        double r = Math.toRadians(angle), c = Math.cos(r), s = Math.sin(r), q = 1.0 - c;
        double[] t = identity();
        t[0] = x*x*q+c; t[1] = y*x*q+z*s; t[2] = z*x*q-y*s;
        t[4] = x*y*q-z*s; t[5] = y*y*q+c; t[6] = z*y*q+x*s;
        t[8] = x*z*q+y*s; t[9] = y*z*q-x*s; t[10] = z*z*q+c;
        multiply(t);
    }

    /** State changes are recorded as host-independent bits; no native GL lookup occurs. */
    public void enable(int bit) { enabled |= 1 << (bit & 30); enabledCaps.add(bit); }
    public void disable(int bit) { enabled &= ~(1 << (bit & 30)); enabledCaps.remove(bit); }
    public void blendFunc(int source, int destination) { blendSource = source; blendDestination = destination; }
    public void alphaFunc(int function, float reference) { alphaFunction = function; alphaReference = reference; }
    public void cullFace(int face) { cullFace = face; }
    public void lighting(boolean on) { lighting = on; }
    public void depthMask(boolean on) { depthMask = on; }
    public void shadeModel(int model) { shadeModel = model; }
    public void activeTexture(int textureUnit) { activeTexture = textureUnit; }

    public int genLists(int count) {
        return NEXT_DISPLAY_LIST.getAndAdd(Math.max(0, count));
    }
    public void newList(int id) { end(); recording = new ListBuilder(id); }
    public void endList() {
        if (recording == null) return;
        List<Draw> built = Collections.unmodifiableList(new ArrayList<Draw>(recording.draws));
        long size = drawBytes(built);
        synchronized (DISPLAY_LISTS) {
            List<Draw> old = DISPLAY_LISTS.remove(recording.id);
            if (old != null) displayListBytes -= drawBytes(old);
            if (size <= MAX_DISPLAY_LIST_BYTES) {
                DISPLAY_LISTS.put(recording.id, built);
                displayListBytes += size;
                Iterator<Map.Entry<Integer, List<Draw>>> it = DISPLAY_LISTS.entrySet().iterator();
                while ((displayListBytes > MAX_DISPLAY_LIST_BYTES
                        || DISPLAY_LISTS.size() > MAX_DISPLAY_LISTS) && it.hasNext()) {
                    Map.Entry<Integer, List<Draw>> eldest = it.next();
                    displayListBytes -= drawBytes(eldest.getValue());
                    it.remove();
                }
                if (displayListBytes < 0L) displayListBytes = 0L;
            }
        }
        recording = null;
    }
    /** Replays the recorded geometry under the caller's current matrix/state. */
    public void callList(int id) {
        end();
        List<Draw> cached;
        synchronized (DISPLAY_LISTS) { cached = DISPLAY_LISTS.get(id); }
        if (cached == null) return;
        List<Draw> replayed = new ArrayList<Draw>(cached.size());
        for (Draw draw : cached) replayed.add(transformDraw(draw, matrix));
        draws.addAll(replayed);
        if (recording != null) recording.draws.addAll(replayed);
    }

    /** Native-free VBO adapter entry point: a decoded stream can be fed through normal vertices. */
    public void drawFloatStream(int primitiveMode, float[] data, int stride) {
        if (data == null || stride < 3) return;
        begin(primitiveMode);
        for (int i = 0; i + 2 < data.length; i += stride) {
            double uu = i + 4 < data.length ? data[i + 3] : u;
            double vv = i + 4 < data.length ? data[i + 4] : v;
            vertex(data[i], data[i + 1], data[i + 2], uu, vv);
        }
        end();
    }

    public Mesh seal() {
        end();
        return new Mesh(draws, matrixOps, pushes, pops);
    }

    private State state() {
        return new State(texture, mode, enabled, blendSource, blendDestination, alphaFunction,
                alphaReference, cullFace, color, lighting, depthMask, shadeModel, activeTexture,
                enabledCaps);
    }
    private void multiply(double[] b) {
        double[] a = matrix.clone();
        for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++) {
            matrix[c * 4 + r] = a[r] * b[c * 4] + a[4 + r] * b[c * 4 + 1]
                    + a[8 + r] * b[c * 4 + 2] + a[12 + r] * b[c * 4 + 3];
        }
        matrixOps++;
    }
    private double[] point(double x, double y, double z) {
        return new double[] {matrix[0]*x + matrix[4]*y + matrix[8]*z + matrix[12],
                matrix[1]*x + matrix[5]*y + matrix[9]*z + matrix[13],
                matrix[2]*x + matrix[6]*y + matrix[10]*z + matrix[14]};
    }
    private float[] matrix() {
        float[] out = new float[16];
        for (int i = 0; i < out.length; i++) out[i] = (float) matrix[i];
        return out;
    }
    private static long drawBytes(List<Draw> list) {
        long bytes = 0L;
        if (list == null) return bytes;
        for (Draw draw : list) {
            if (draw == null) continue;
            bytes += 64L + (draw.matrix == null ? 0L : 4L * draw.matrix.length);
            bytes += 36L * (draw.vertices == null ? 0L : draw.vertices.size());
        }
        return bytes;
    }
    private static Draw transformDraw(Draw source, double[] callerMatrix) {
        List<Vertex> vertices = new ArrayList<Vertex>(source.vertices.size());
        for (Vertex v : source.vertices) {
            double x = callerMatrix[0] * v.x + callerMatrix[4] * v.y
                    + callerMatrix[8] * v.z + callerMatrix[12];
            double y = callerMatrix[1] * v.x + callerMatrix[5] * v.y
                    + callerMatrix[9] * v.z + callerMatrix[13];
            double z = callerMatrix[2] * v.x + callerMatrix[6] * v.y
                    + callerMatrix[10] * v.z + callerMatrix[14];
            vertices.add(new Vertex((float) x, (float) y, (float) z, v.u, v.v,
                    v.nx, v.ny, v.nz, v.color));
        }
        float[] matrix = new float[16];
        for (int i = 0; i < matrix.length; i++) matrix[i] = (float) callerMatrix[i];
        return new Draw(source.texture, source.mode, source.state, vertices, matrix);
    }
    private static double[] identity() {
        double[] out = new double[16]; out[0] = out[5] = out[10] = out[15] = 1.0; return out;
    }
    private static int clamp(float v) { return (int) Math.max(0, Math.min(255, v * 255.0f + 0.5f)); }
}

// MIRROR of the canonical umb-bridge-api owned by Lane A (umb-legacy) -- do not hand-edit.
// Synced verbatim by tools/build-hostagent.ps1 from
// umb-legacy/src/bridge-api/java/dev/umb/bridge/api/ on every build. If you need to change the
// boundary contract, change it there (and change BOTH sides together per DESIGN.md).
