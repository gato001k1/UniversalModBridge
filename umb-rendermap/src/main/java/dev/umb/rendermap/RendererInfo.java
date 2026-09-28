package dev.umb.rendermap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** What one renderer class draws, recovered from its bytecode. */
public class RendererInfo {
    public String rendererClass;                       // dotted
    public List<String> scannedClasses = new ArrayList<>();
    public Set<String> modelFields = new LinkedHashSet<>();   // "com.hbm.main.ResourceManager.turret_chekhov"
    public Set<String> textureFields = new LinkedHashSet<>();
    public Set<String> groups = new LinkedHashSet<>();
    public Set<String> renderTypes = new LinkedHashSet<>();
    public boolean usesRenderAll;
    public int glRotate, glScale, glTranslate;
    public boolean dynamic;
    public Set<String> dynamicReasons = new LinkedHashSet<>();
    public Set<String> boundTextureFields = new LinkedHashSet<>();
    public Set<String> unresolvedFieldRefs = new LinkedHashSet<>();
    /** dotted class names of any {@code net.minecraft.client.model.ModelBase} (Techne-style Java
     *  model) subclass this renderer references — CODE, not a loadable mesh. Reported, never baked. */
    public Set<String> javaModelClasses = new LinkedHashSet<>();

    /**
     * A texture resolved from the ENTITY-renderer-specific vanilla API,
     * {@code Render.func_110775_a(Entity)} = {@code getEntityTexture} (javap/SRG-verified against
     * {@code research/repos/MinecraftForge/fml/conf/methods.csv} — see
     * ENTITY-RENDER-EXTRACTION.md). Kept separate from {@link #textureFields}/
     * {@link #boundTextureFields} (which come from a direct field read or a {@code bindTexture}
     * call argument respectively) purely for provenance/reporting — all three are equally "the
     * renderer's own bytecode names this resource" evidentiary strength (never a live-runtime
     * guess), so all three count toward the {@code exact} confidence tier the same way.
     */
    public static class EntityTextureRef {
        /** "static-field" (a GETSTATIC of a resolvable holder field, possibly inherited — see
         *  {@link JarIndex#declaringClassOfField}), "instance-field" (a same-class single-
         *  assignment {@code this.field}, see {@link MethodSim}), or "inline" (a literal
         *  {@code new ResourceLocation(...)} constructed directly in the return expression). */
        public String kind;
        public String field;           // dotted holder field name, when kind names one
        public String path;            // "ns:path", when resolved
        public String assetPath;
        public String unresolvedReason;
    }
    public List<EntityTextureRef> entityTextureRefs = new ArrayList<>();
    /** True once a {@code func_110775_a} override was actually found somewhere in {@link
     *  #scope}/superclass chain — distinguishes "looked and found nothing resolvable" from
     *  "never even found the override" (e.g. it lives in a vanilla intermediate superclass this
     *  jar does not declare, which this analysis cannot see by construction). */
    public boolean getEntityTextureFound;

    /**
     * One Techne/vanilla-{@code ModelRenderer}-shaped box, recovered generically from a Java
     * model class's own {@code <init>} (see {@code RendererAnalyzer#resolveJavaModelGeometry}).
     * Every field here corresponds 1:1 to a vanilla {@code ModelRenderer} API argument
     * (javap/SRG-verified — see ENTITY-RENDER-EXTRACTION.md): {@code func_78787_b}/the
     * 3-arg constructor for the texture offset, {@code func_78789_a}/{@code func_78790_a}
     * (addBox) for the box, {@code func_78793_a} (setRotationPoint) for the pivot. Never
     * executed — extracted from constant bytecode operands only.
     */
    public static class TechnePart {
        public String field;                 // dotted "com.example.ModelFoo.head"
        public Integer texOffsetX, texOffsetY;
        public Float boxX, boxY, boxZ;
        public Integer boxW, boxH, boxD;
        public Float boxScale;               // set only by the 7-arg func_78790_a overload
        public Float rotPointX, rotPointY, rotPointZ;
        public String unresolvedReason;      // set (box/rotation left null) when an arg wasn't a compile-time constant
    }
    /** dotted java-model class -> its own parts, in declaration order. */
    public Map<String, List<TechnePart>> javaModelParts = new LinkedHashMap<>();
}
