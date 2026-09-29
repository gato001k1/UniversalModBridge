package dev.umb.legacy1122.legacyside;

import java.io.File;
import java.util.List;

import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.LaunchClassLoader;

/**
 * A harmless stand-in for real Forge's {@code net.minecraftforge.fml.common.launcher
 * .FMLServerTweaker}, instantiated by {@code Legacy1122Lifecycle.installForgeTransformers} to
 * populate {@code Launch.blackboard}'s {@code "Tweaks"} list (see that method's own javadoc for
 * the full trace of why this exists).
 *
 * <p>Real LaunchWrapper's {@code Launch.main()} populates {@code "Tweaks"} with the
 * already-CONSTRUCTED {@code ITweaker} objects as it processes each tweak class - a step this
 * project's lightweight lifecycle deliberately never runs (see {@code Legacy1122Lifecycle}'s own
 * javadoc: no ModLauncher/tweaker boot). Left unseeded, {@code "Tweaks"} stays {@code null}, and
 * any coremod that inspects "what tweaker actually ran" to infer the current side - real
 * MixinBooter's own {@code zone.rong.mixinbooter.util.Environment} static initializer does exactly
 * this, reading index 0 of that list and checking whether its class name ends in
 * {@code "FMLServerTweaker"} - NPEs before its own bootstrap logic ever runs (proven live,
 * unconditionally calls {@code tweaks.get(0)} on that null list).</p>
 *
 * <p>The REAL {@code net.minecraftforge.fml.common.launcher.FMLServerTweaker} is deliberately
 * never constructed anywhere in this project instead of using this stand-in: its superclass
 * {@code FMLTweaker}'s constructor calls {@code System.setSecurityManager(...)}, which throws
 * {@code UnsupportedOperationException} unconditionally on modern JDKs (JEP 411) - proven live,
 * this exact crash, when constructing the real class was this fix's first attempt. This class's
 * simple name is deliberately IDENTICAL to the real one (different package) purely so that
 * {@code Class.getName().endsWith("FMLServerTweaker")} checks - a real, observed convention, not
 * invented for this fix - see it as the SERVER tweaker, matching this project's own hardcoded
 * SERVER sided-handler ({@code Legacy1122Lifecycle}'s {@code IFMLSidedHandler} proxy always
 * answers {@code SERVER}). None of its methods are ever called - real {@code Launch.main()} would
 * call {@code acceptOptions}/{@code injectIntoClassLoader} before this lifecycle even starts, so
 * they are honest no-ops here, not stubs standing in for real behaviour this lifecycle still
 * needs.</p>
 */
public final class FMLServerTweaker implements ITweaker {

    @Override
    public void acceptOptions(List<String> args, File gameDir, File assetsDir, String profile) {
        // no-op: see class javadoc - real Launch.main() would have called this before any
        // lifecycle code runs; nothing downstream of this stand-in depends on it happening here.
    }

    @Override
    public void injectIntoClassLoader(LaunchClassLoader classLoader) {
        // no-op: see class javadoc.
    }

    @Override
    public String getLaunchTarget() {
        return "";
    }

    @Override
    public String[] getLaunchArguments() {
        return new String[0];
    }
}
