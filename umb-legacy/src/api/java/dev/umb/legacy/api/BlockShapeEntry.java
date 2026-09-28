package dev.umb.legacy.api;

import java.util.Collections;
import java.util.List;

/** Legacy compatibility behavior. */
public final class BlockShapeEntry {

    private final String id;
    private final int registryId;
    private final String className;
    private final String error;
    private final List<BlockMetaShape> metaShapes;

    public BlockShapeEntry(String id, int registryId, String className, String error,
                            List<BlockMetaShape> metaShapes) {
        this.id = id;
        this.registryId = registryId;
        this.className = className;
        this.error = error;
        this.metaShapes = metaShapes == null ? Collections.<BlockMetaShape>emptyList() : metaShapes;
    }

    public String id() { return id; }
    public int registryId() { return registryId; }
    public String className() { return className; }
    public String error() { return error; }
    public List<BlockMetaShape> metaShapes() { return metaShapes; }

    /** true when at least one distinct shape found is NOT a plain full 1x1x1 cube. */
    public boolean isNonCube() {
        for (int i = 0; i < metaShapes.size(); i++) {
            if (!metaShapes.get(i).isFullCube()) {
                return true;
            }
        }
        return false;
    }

    /** true when at least one distinct shape found declares more than one collision box. */
    public boolean isMultiBox() {
        for (int i = 0; i < metaShapes.size(); i++) {
            if (metaShapes.get(i).isMultiBox()) {
                return true;
            }
        }
        return false;
    }

    /** how many of the 16 probed metadata values were collapsed into this block's distinct shapes. */
    public int metasProbed() {
        int n = 0;
        for (int i = 0; i < metaShapes.size(); i++) {
            n += metaShapes.get(i).metas().length;
        }
        return n;
    }
}
