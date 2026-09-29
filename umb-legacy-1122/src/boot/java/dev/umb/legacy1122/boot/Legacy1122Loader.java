package dev.umb.legacy1122.boot;

import java.lang.reflect.Field;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.launchwrapper.LaunchClassLoader;

/**
 * The 1.12.2 legacy universe classloader - the ONLY piece of {@code umb-legacy-1122/boot} that is
 * not new design: it is the 1.7.10 module's {@code LegacyLoader} with the package renamed. Both
 * eras are LaunchWrapper + FML, both need the SAME two Java-9+ compatibility fixes (LaunchWrapper
 * was written for Java 8: its {@code super(sources, null)} makes the bootstrap loader its parent,
 * hiding every platform module; its hardcoded {@code classLoaderExceptions} assume the JDK's own
 * jars sit on the application classpath, which is false once everything is embedded), and both
 * need the SAME child-first-by-ownership delegation policy so the identical class survives the
 * move from a bare JVM into the 26.2 client's own classloader graph. See ERA-1122-PLAN.md
 * "loader generation" for the evidence that both eras share this exact loader shape.
 *
 * <p>Delegation policy (identical structure to 1.7.10's, new package prefixes):</p>
 * <pre>
 *   java. / jdk. / sun. / com.sun.        -&gt; parent, always
 *   net.minecraft.launchwrapper.          -&gt; parent, always (FML casts to the HOST's LaunchClassLoader)
 *   org.apache.logging.                   -&gt; parent, always (LogWrapper mentions log4j.Level; see below)
 *   dev.umb.legacy1122.api. / .boot.      -&gt; parent, always (this era's plain-data handshake)
 *   dev.umb.bridge.api.                   -&gt; parent, always (the SHARED cross-era boundary contract)
 *   anything whose .class is in OUR jars  -&gt; CHILD FIRST, through the transformer pipeline
 *   everything else                       -&gt; parent fallback
 * </pre>
 *
 * <p><b>log4j version note (new for this era, not a copy-paste of the 1.7.10 comment):</b> 1.7.10's
 * shared log4j is 2.0-beta9. The real Forge 1.12.2 universal jar's manifest Class-Path pins
 * log4j-api/log4j-core 2.15.0 (verified: {@code META-INF/MANIFEST.MF} inside the fetched
 * {@code forge-1.12.2-14.23.5.2860-universal.jar}), newer than the vanilla library set's bundled
 * 2.8.1. {@link Legacy1122Classpath}'s manifest keeps only the 2.15.0 pair for that reason - the
 * same "one shared log4j" rule as 1.7.10, just a different pinned version.</p>
 */
public final class Legacy1122Loader extends LaunchClassLoader {

    private static final List<String> ALWAYS_PARENT = Collections.unmodifiableList(Arrays.asList(
            "java.",
            "jdk.",
            "sun.",
            "com.sun.",
            "net.minecraft.launchwrapper.",
            "org.apache.logging.",
            "dev.umb.legacy1122.api.",
            "dev.umb.legacy1122.boot.",
            "dev.umb.bridge.api."));

    private final ClassLoader delegateParent;
    private final List<String> alwaysParent;
    private final ConcurrentHashMap<String, Boolean> ownsCache = new ConcurrentHashMap<String, Boolean>();

    public Legacy1122Loader(URL[] sources, ClassLoader delegateParent) {
        this(sources, delegateParent, Collections.<String>emptyList());
    }

    public Legacy1122Loader(URL[] sources, ClassLoader delegateParent, List<String> extraParentPrefixes) {
        super(sources);
        if (delegateParent == null) {
            throw new IllegalArgumentException("the legacy loader needs a real parent loader");
        }
        this.delegateParent = delegateParent;
        List<String> p = new ArrayList<String>(ALWAYS_PARENT);
        p.addAll(extraParentPrefixes);
        this.alwaysParent = Collections.unmodifiableList(p);
        resetLaunchWrapperExclusions();
    }

    @SuppressWarnings("unchecked")
    private void resetLaunchWrapperExclusions() {
        try {
            Field f = LaunchClassLoader.class.getDeclaredField("classLoaderExceptions");
            f.setAccessible(true);
            Set<String> ex = (Set<String>) f.get(this);
            ex.clear();
            ex.addAll(alwaysParent);
        } catch (Exception e) {
            throw new IllegalStateException("cannot reset LaunchClassLoader.classLoaderExceptions", e);
        }
    }

    /**
     * Overridden to redirect into {@link #addTransformerExclusion}: this loader is the sole
     * authority over which names are parent-delegated ({@link #ALWAYS_PARENT}, fixed at
     * construction) - a caller-supplied prefix never ADDS to that set - but the caller's underlying
     * INTENT (wall 4: "this package must be its own island, exempt from the mod-instrumentation
     * pipeline") is honored anyway, through the one mechanism that is actually safe in this era's
     * single-isolated-loader design.
     *
     * <p>Real LaunchClassLoader normally lets ANY code holding a reference to it call this to add
     * MORE parent-delegated prefixes - and real coremods use exactly that. Real Sponge Mixin's own
     * bootstrap (MixinBooterPlugin.initialize() -&gt; installClassLoaderExclusionsAndTransformers,
     * proven live via a reflection dump of {@code classLoaderExceptions} moments after it ran)
     * calls this for its own packages (org.spongepowered.asm.mixin., .util., .launch., .service.,
     * .logging., .lib., org.objectweb.asm., even zone.rong.mixinbooter.service.) - forcing those
     * names to resolve via {@code parent} instead of this loader's own sources. That is the right
     * call in a REAL Forge launch, where Mixin's jar sits on the actual JVM launch classpath (or
     * gets pushed there itself via its own Premain-Class javaagent / injectSelfIntoAppClassLoader),
     * so parent genuinely has those classes too - and, just as importantly, the LaunchClassLoader
     * running the actual game NEVER defines them, so its own registered transformers (Mixin's own
     * {@code MixinTransformer} among them, once {@code MixinBootstrap.init()} registers it) never
     * run on them either. Neither premise holds here: this era's isolated universe has no javaagent
     * and no launch-classpath overlap, so mixinbooter's jar is ONLY ever reachable through this
     * loader's own addURL'd sources - the exact same shape as any other coremod jar this loader
     * loads.
     *
     * <p><b>First attempted a plain no-op (session 4, wall 4)</b> - proven live: MixinBooter's
     * coremod plugin failed to construct with {@code ClassNotFoundException:
     * org.spongepowered.asm.util.asm.ASM}, because honoring the exclusion call routed it to a
     * parent that has no such class, so {@code MixinBootstrap.init()} never ran and no mixin ever
     * applied. A plain no-op fixed that (these packages now self-define through this loader like
     * everything else) but broke something ELSE the exclusion call was ALSO protecting against
     * (session 5, wall 5): with {@code org.spongepowered.asm.*} now flowing through this loader's
     * OWN {@code findClass()}, Mixin's newly-registered {@code MixinTransformer} started running
     * its own {@code couldTransformClass()} check against Mixin's OWN internal implementation
     * classes - and that check itself calls {@code MixinEnvironment.getCompatibilityLevel()}, which
     * (the first time, before its enum's static initializer has finished) needs to load
     * {@code MixinEnvironment$CompatibilityLevel$1} - re-entering this SAME loader's
     * {@code findClass()} -&gt; {@code runTransformers()} -&gt; {@code MixinTransformer} chain
     * for the SAME class name that is already in the middle of being defined:
     * {@code ClassCircularityError: org/spongepowered/asm/mixin/MixinEnvironment$CompatibilityLevel$1}
     * (proven live, full recursive stack captured with
     * {@code -Dlegacy.debugClassLoading=true}), silently swallowed and turned into a
     * {@code ClassNotFoundException} by this loader's own {@code net.minecraftforge.}-prefix
     * fallback the NEXT time something unrelated (Forge's own binary-patch library,
     * {@code net.minecraftforge.fml.repackage.com.nothome.delta.ByteBufferSeekableSource}) got
     * caught in the same transformer's blast radius and fell back to a parent that does not have
     * IT either - {@code IncompatibleClassChangeError} against the copy of
     * {@code SeekableSource} that stayed on this loader (MixinBooter wall 5).
     *
     * <p>The fix that actually closes both windows at once: keep every caller-supplied prefix OFF
     * {@code classLoaderExceptions} (so this loader remains the sole definer, preserving identity -
     * wall 3's whole point) but feed the SAME prefix into {@code addTransformerExclusion} (so this
     * loader defines the class WITHOUT running any registered transformer - including Mixin's own -
     * against it, which is what actually prevented the self-referential recursion in a real launch).
     * Universal by construction: this override does not name MixinBooter, Mixin, or read anything
     * but the one argument every caller already supplies - whatever prefix a coremod asks to keep
     * off the transform pipeline gets kept off it, regardless of which coremod asked.
     */
    @Override
    public void addClassLoaderExclusion(String toExclude) {
        addTransformerExclusion(toExclude);
    }

    public boolean isParentDelegated(String name) {
        for (int i = 0; i < alwaysParent.size(); i++) {
            if (name.startsWith(alwaysParent.get(i))) {
                return true;
            }
        }
        return false;
    }

    /** true when this loader's own sources contain the class file for {@code name}. */
    public boolean owns(String name) {
        Boolean cached = ownsCache.get(name);
        if (cached != null) {
            return cached.booleanValue();
        }
        boolean owned = findResource(name.replace('.', '/').concat(".class")) != null;
        ownsCache.put(name, Boolean.valueOf(owned));
        return owned;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                if (isParentDelegated(name)) {
                    c = delegateParent.loadClass(name);
                } else if (name.startsWith("net.minecraft.") || name.startsWith("net.minecraftforge.")
                        || name.startsWith("cpw.mods.") || owns(name)) {
                    // Vanilla is obfuscated on disk, so owns(real SRG name) is false. The
                    // LaunchClassLoader rename transformer must see these requests before the
                    // child/parent decision; otherwise Forge signatures fall through to the
                    // host and fail on dependencies such as net.minecraft.crash.*.
                    try {
                        c = findClass(name);
                    } catch (ClassNotFoundException missingFromLegacyUniverse) {
                        if (name.startsWith("net.minecraft.") || name.startsWith("net.minecraftforge.")
                                || name.startsWith("cpw.mods.")) {
                            c = delegateParent.loadClass(name);
                        } else {
                            throw missingFromLegacyUniverse;
                        }
                    }
                } else {
                    c = delegateParent.loadClass(name);
                }
            }
            if (resolve) {
                resolveClass(c);
            }
            return c;
        }
    }

    @Override
    public URL getResource(String name) {
        URL own = findResource(name);
        if (own != null) {
            return own;
        }
        return delegateParent.getResource(name);
    }
}
