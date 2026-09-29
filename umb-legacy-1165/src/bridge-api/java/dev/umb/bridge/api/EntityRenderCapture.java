package dev.umb.bridge.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Data-only result of running a legacy entity Render against the capture backend. */
public final class EntityRenderCapture {
    private static final float[] EMPTY_FLOATS = new float[0];
    private static final float[] OWNED_IDENTITY = new float[] {
            1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f
    };
    public static final class Draw {
        public final String texture;
        /** x,y,z,u,v,nx,ny,nz repeated once per vertex. */
        public final float[] vertices;
        public final int vertexCount;
        public Draw(String texture, float[] vertices, int vertexCount) {
            this(texture, vertices, vertexCount, null);
        }
        /** Captured legacy model-space matrix, column-major 4x4. */
        public final float[] matrix;
        public Draw(String texture, float[] vertices, int vertexCount, float[] matrix) {
            this(texture, vertices, vertexCount, matrix, true);
        }
        /** Internal capture fast path: the arrays are sealed and must not be mutated afterwards. */
        public static Draw owned(String texture, float[] vertices, int vertexCount, float[] matrix) {
            return new Draw(texture, vertices, vertexCount, matrix, false, false, false, true);
        }
        /** As {@link #owned(String, float[], int, float[])} with the legacy GL_CULL_FACE state. */
        public static Draw owned(String texture, float[] vertices, int vertexCount, float[] matrix,
                                 boolean cull) {
            return new Draw(texture, vertices, vertexCount, matrix, false, cull, false, true);
        }
        /**
         * As {@link #owned(String, float[], int, float[], boolean)} with the legacy GL_BLEND and
         * GL_LIGHTING state of the draw.
         */
        public static Draw owned(String texture, float[] vertices, int vertexCount, float[] matrix,
                                 boolean cull, boolean blend, boolean lighting) {
            return new Draw(texture, vertices, vertexCount, matrix, false, cull, blend, lighting);
        }
        /** True when the legacy renderer drew this with GL_CULL_FACE enabled (back faces hidden). */
        public final boolean cull;
        /** True when the legacy renderer drew this with GL_BLEND enabled (alpha-blended, e.g. glass). */
        public final boolean blend;
        /**
         * True when the legacy renderer drew this with GL_LIGHTING enabled (normal-shaded). False
         * means fixed-function lighting was off: no directional shading, only the lightmap.
         */
        public final boolean lighting;
        private Draw(String texture, float[] vertices, int vertexCount, float[] matrix, boolean copy) {
            this(texture, vertices, vertexCount, matrix, copy, false, false, true);
        }
        private Draw(String texture, float[] vertices, int vertexCount, float[] matrix, boolean copy,
                     boolean cull, boolean blend, boolean lighting) {
            this.cull = cull;
            this.blend = blend;
            this.lighting = lighting;
            this.texture = texture;
            this.vertices = vertices == null ? EMPTY_FLOATS : (copy ? vertices.clone() : vertices);
            this.vertexCount = Math.max(0, vertexCount);
            // Owned captures are immutable by convention; sharing the canonical identity avoids
            // one 64-byte float[] for every transform-only draw. Public copying constructors still
            // clone caller-provided matrices, so this does not expose mutable caller state.
            this.matrix = matrix == null ? (copy ? identity() : OWNED_IDENTITY)
                    : (copy ? matrix.clone() : matrix);
        }
        private static float[] identity() {
            return new float[] {1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f};
        }
    }
    public final String entityClass;
    public final String stateKey;
    public final boolean animated;
    public final int matrixOps;
    public final int pushes;
    public final int pops;
    public final List<Draw> draws;
    public EntityRenderCapture(String entityClass, String stateKey, boolean animated,
                               int matrixOps, int pushes, int pops, List<Draw> draws) {
        this.entityClass = entityClass == null ? "" : entityClass;
        this.stateKey = stateKey == null ? "" : stateKey;
        this.animated = animated;
        this.matrixOps = Math.max(0, matrixOps);
        this.pushes = Math.max(0, pushes);
        this.pops = Math.max(0, pops);
        this.draws = draws == null ? Collections.<Draw>emptyList()
                : Collections.unmodifiableList(new ArrayList<Draw>(draws));
    }
    public static EntityRenderCapture empty(String entityClass, String stateKey) {
        return new EntityRenderCapture(entityClass, stateKey, false, 0, 0, 0,
                Collections.<Draw>emptyList());
    }
    public int vertexCount() {
        int n = 0;
        for (Draw d : draws) n += d.vertexCount;
        return n;
    }
}
