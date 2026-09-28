package dev.umb.legacy1165.legacyside;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * ModLoader lifecycle (same mechanism as {@link Legacy1165BridgeImpl#boot} / {@link M1165Probe})
 * against whichever mod jars {@code -Dumb.1165.modjars} names, then reports what the mods' own
 * client-setup registration produced - entity renderer factory count and TESR renderer count -
 * without re-firing any event itself (see {@link Legacy1165Lifecycle}'s class javadoc for why
 * Dist=CLIENT during construction is what makes that registration run for real).
 *
 * <p>Unlike {@link M1165Probe} (which drives one specific mod's container/GUI contract), this
 * probe only cares whether registration completed and the lifecycle reports {@code allOk()} -
 * {@code DistExecutor.runForDist}/{@code registerEntityRenderingHandler} calls executed.
 * Called reflectively from inside the booted {@code Legacy1165Loader}, exactly like
 * {@code M1165Probe}.</p>
 */
public final class EntityRenderClientProbe {

    private EntityRenderClientProbe() {
    }

    public static String run() throws Exception {
        String modJarsProp = System.getProperty("umb.1165.modjars");
        if (modJarsProp == null || modJarsProp.trim().isEmpty()) {
            throw new IllegalStateException(
                    "missing -Dumb.1165.modjars (semicolon-separated mod jar paths)");
        }
        List<File> modJars = new ArrayList<File>();
        for (String part : modJarsProp.split(";")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                modJars.add(new File(t).getAbsoluteFile());
            }
        }
        File gameDir = new File(System.getProperty("umb.1165.gamedir",
                System.getProperty("java.io.tmpdir") + "/umb-legacy1165-gamedir-entityrender"));
        ClassLoader me = EntityRenderClientProbe.class.getClassLoader();
        final StringBuilder log = new StringBuilder();
        Legacy1165Lifecycle.Result result = Legacy1165Lifecycle.run(me, modJars, gameDir,
                new Consumer<String>() {
                    @Override
                    public void accept(String msg) {
                        System.out.println("[EntityRenderClientProbe] " + msg);
                        log.append(msg).append('\n');
                    }
                });
        if (!result.allOk()) {
            StringBuilder err = new StringBuilder("lifecycle failed:\n");
            for (Legacy1165Lifecycle.Stage s : result.stages) {
                if (!s.ok) {
                    err.append(s.name).append(": ").append(s.error).append('\n');
                }
            }
            throw new IllegalStateException(err.toString());
        }
        return "PROBE-OK\n"
                + "modId=" + result.modId + " modVersion=" + result.modVersion + "\n"
                + "entityRendererFactories=" + result.entityRendererFactories + "\n"
                + "tesrRenderers=" + result.tesrRenderers + "\n"
                + "entityCaptureInstalled=" + result.entityCaptureInstalled + "\n";
    }
}
