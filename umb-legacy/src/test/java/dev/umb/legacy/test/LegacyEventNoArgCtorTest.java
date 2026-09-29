package dev.umb.legacy.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.umb.legacy.boot.LegacyLoader;

/**
 * subscribe must be constructible through the public no-arg constructor
 * {@code EventBus.register} uses ({@code Class.getConstructor().newInstance()},
 * bytecode-verified in the SRG jar).
 *
 * <p>Root cause they guard (proven with a recording transformer on a real HBM boot):
 * linking {@code LegacyBridgeImpl} - which happens at the very first
 * {@code new LegacyBridgeImpl()}, BEFORE {@code LegacyDriver.boot} registers any
 * {@code IClassTransformer} - makes the JVM verify ALL of its methods, and verification
 * loads every event type named in ANY method body ({@code WorldEvent$Load},
 * {@code EntityJoinWorldEvent} here). Those parents were therefore defined without
 * {@code EventSubscriptionTransformer} ever seeing them, so they never got the
 * Forge-standard {@code ()V}; every later registration on their subclasses dropped its
 * handlers (107 in one live run). The fix keeps every event reference out of
 * pre-registration-linked classes (see {@code LegacyEventPoster}) so Forge's own
 * transformer instruments the whole tree, and deletes the hand-picked
 * {@code UmbEventTransformer} whose {@code PlayerEvent} patch called a PRIVATE super
 * constructor. Forge's transformer is sufficient on its own: {@code buildEvents} loads
 * each superclass through the transformer chain, and the base {@code Event} already
 */
class LegacyEventNoArgCtorTest {

    private static final String BRIDGE = "dev.umb.legacy.legacyside.LegacyBridgeImpl";

    private static final String[] EVENTS = {
            "net.minecraftforge.event.world.WorldEvent$Unload",
            "net.minecraftforge.event.entity.living.LivingEvent$LivingUpdateEvent",
            "cpw.mods.fml.common.gameevent.PlayerEvent$PlayerLoggedInEvent",
    };

    private static final String[] PARENTS = {
            "net.minecraftforge.event.world.WorldEvent",
            "net.minecraftforge.event.entity.EntityEvent",
            "cpw.mods.fml.common.gameevent.PlayerEvent",
    };

    /** The live-failing children whose registration dropped handlers. */
    private static final String[] EARLY_CHILDREN = {
            "net.minecraftforge.event.world.WorldEvent$Load",
            "net.minecraftforge.event.entity.EntityJoinWorldEvent",
    };

    @Test
    void productionTransformerListHasForgeCoverAndNoHandEventPatch() throws Exception {
        List<String> transformers = productionTransformers();
        assertTrue(transformers.contains("cpw.mods.fml.common.asm.transformers.EventSubscriptionTransformer"),
                "Forge's own event transformer must stay registered: " + transformers);
        for (String t : transformers) {
            assertFalse(t.startsWith("dev.umb.legacy.") && t.contains("Event"),
                    "no hand-picked UMB event transformer may remain (see LegacyEventPoster): " + t);
        }
    }

    /**
     * The core regression test: defining + instantiating the bridge - exactly what every
     * entry point does BEFORE transformer registration - must not define any event type.
     * If anyone names an event type in a pre-registration-linked method body again, the
     * verifier loads it here and this fails, long before any game boot.
     */
    @Test
    void linkingTheBridgeDefinesNoEventTypes() throws Exception {
        LegacyLoader loader = universeLoader();
        Class<?> bridge = Class.forName(BRIDGE, true, loader);
        assertSame(loader, bridge.getClassLoader(), "bridge leaked to another loader");
        bridge.getDeclaredConstructor().newInstance();
        for (String name : PARENTS) {
            assertFalse(isDefined(loader, name), "event type defined before any transformer ran: " + name);
        }
        for (String name : EARLY_CHILDREN) {
            assertFalse(isDefined(loader, name), "event type defined before any transformer ran: " + name);
        }
    }

    /**
     * Forge's own transformer instruments the whole tree on its own: with only it
     * registered (every other production transformer is a passthrough for event classes -
     * fml_marker.cfg ships no mappings), loading the worst case first (the bare parents)
     * and constructing parents and subscribed children through the EventBus no-arg
     * constructor path proves every ancestor {@code ()V} exists and links.
     */
    @Test
    void subscribedEventsConstructThroughTheUniverseLoader() throws Exception {
        LegacyLoader loader = universeLoader();
        int before = loader.getTransformers().size();
        loader.registerTransformer("cpw.mods.fml.common.asm.transformers.EventSubscriptionTransformer");
        assertEquals(before + 1, loader.getTransformers().size(), "EventSubscriptionTransformer missing");
        for (String name : PARENTS) {
            Class<?> parent = Class.forName(name, true, loader);
            assertSame(loader, parent.getClassLoader(), "leaked to another loader: " + name);
            parent.getConstructor().newInstance();
        }
        for (String name : EVENTS) {
            Class<?> event = Class.forName(name, true, loader);
            assertSame(loader, event.getClassLoader(), "leaked to another loader: " + name);
            Object instance = event.getConstructor().newInstance();
            assertTrue(instance.getClass().getName().equals(name));
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> productionTransformers() throws Exception {
        Class<?> bridge = Class.forName(BRIDGE);
        Method m = bridge.getDeclaredMethod("transformerList", boolean.class);
        m.setAccessible(true);
        return (List<String>) m.invoke(null, false);
    }

    private static boolean isDefined(ClassLoader loader, String name) throws Exception {
        Method findLoaded = ClassLoader.class.getDeclaredMethod("findLoadedClass", String.class);
        findLoaded.setAccessible(true);
        return findLoaded.invoke(loader, name) != null;
    }

    private static LegacyLoader universeLoader() throws Exception {
        String repoProp = System.getProperty("umb.repo");
        File repo = repoProp != null ? new File(repoProp) : repoRoot();
        // Test-only override so this test can run against a scratch legacyside build while the
        // checked-in jars are locked by the live game; the real gate never sets it and always
        // exercises the freshly built jar.
        String testJarProp = System.getProperty("umb.legacy.testLegacysideJar");
        File legacyside = testJarProp != null ? new File(testJarProp)
                : new File(repo, "build/legacy/umb-legacy-legacyside.jar");
        File forgeSrg = new File(repo, "build/legacy/forge-1.7.10-10.13.4.1614-srg.jar");
        File runtime = new File(repo, "build/legacy/1.7.10-forge-srg-runtime-fields.jar");
        File guava = new File(repo,
                "research/visual/mc1710-native/libraries/com/google/guava/guava/17.0/guava-17.0.jar");
        // Forge's transformer is written against ASM 5.0.3 (Type.getType with a slash-form
        // internal name, which newer ASM rejects with "Invalid descriptor"). Production carries
        // asm-all-5.0.3 in the universe jar set (LegacyClasspath.forBoot) so the transformer
        // links it in-universe; the test must do the same instead of inheriting whatever ASM
        // the launching JVM happens to have.
        File asm = new File(repo,
                "research/visual/mc1710-native/libraries/org/ow2/asm/asm-all/5.0.3/asm-all-5.0.3.jar");
        for (File f : new File[]{legacyside, forgeSrg, runtime, guava, asm}) {
            assertTrue(f.isFile(), "missing: " + f + " - run tools/windows/build-legacy.ps1 first");
        }
        List<URL> urls = new ArrayList<URL>();
        for (File f : new File[]{legacyside, forgeSrg, runtime, guava, asm}) {
            urls.add(f.toURI().toURL());
        }
        return new LegacyLoader(urls.toArray(new URL[0]),
                LegacyEventNoArgCtorTest.class.getClassLoader());
    }

    private static File repoRoot() {
        File cur = new File("").getAbsoluteFile();
        while (cur != null) {
            if (new File(cur, "umb-legacy").isDirectory() && new File(cur, "research").isDirectory()) {
                return cur;
            }
            cur = cur.getParentFile();
        }
        throw new IllegalStateException("cannot locate repo root; pass -Dumb.repo=<path>");
    }
}
