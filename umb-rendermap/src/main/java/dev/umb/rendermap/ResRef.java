package dev.umb.rendermap;

/** A static holder field resolved to a concrete resource path. */
public class ResRef {
    public enum Kind { MODEL, TEXTURE, OTHER }

    public String field;      // "com.hbm.main.ResourceManager.turret_chekhov"
    public Kind kind;
    public String path;       // "hbm:models/turrets/turret_chekhov.obj"
    public String assetPath;  // "assets/hbm/models/turrets/turret_chekhov.obj" (null when domain unknown)
    public String loader;     // "HFRWavefrontObject", "AdvancedModelLoader", "HmfModelLoader", null
    public String unresolvedReason;

    public ResRef(String field, Kind kind) { this.field = field; this.kind = kind; }

    public boolean resolved() { return path != null; }

    /** Convert "hbm:models/x.obj" to "assets/hbm/models/x.obj". */
    public static String toAssetPath(String namespaced) {
        if (namespaced == null) return null;
        int c = namespaced.indexOf(':');
        if (c < 0) return "assets/minecraft/" + namespaced;
        return "assets/" + namespaced.substring(0, c) + "/" + namespaced.substring(c + 1);
    }
}
