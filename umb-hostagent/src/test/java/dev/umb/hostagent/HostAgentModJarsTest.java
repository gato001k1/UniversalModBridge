package dev.umb.hostagent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * hostagent-purge headline fix: {@link HostAgent} carries a generic, possibly-multi-valued
 * {@code modjars=} argument alongside {@code snapshot=}/{@code log=}/{@code ns=}/{@code lang=},
 * and knows nothing about any particular mod's jar name (that default, when the caller passes
 * none, lives in {@code UmbUniverse}, not here). Uses synthetic jar names under a NON-mod
 * (test-fixture) naming convention on purpose, to prove nothing here is keyed to any specific
 * mod's identity.
 */
class HostAgentModJarsTest {

    @AfterEach
    void resetState() {
        // configure() with no jars restores the "nothing configured" empty-list default so this
        // test's state can't leak into any other test in the same JVM.
        HostAgent.configure(null, null, "hbm");
    }

    @Test
    void parseModJarsSplitsOnCommaAndTrimsWhitespace() {
        List<Path> jars = HostAgent.parseModJars(" C:\\mods\\coolmod-1.0.jar , C:\\mods\\othermod-2.0.jar ");
        assertEquals(Arrays.asList(Paths.get("C:\\mods\\coolmod-1.0.jar"), Paths.get("C:\\mods\\othermod-2.0.jar")), jars);
    }

    @Test
    void parseModJarsAcceptsExactlyOneJar() {
        List<Path> jars = HostAgent.parseModJars("C:\\mods\\solomod-3.1.jar");
        assertEquals(Collections.singletonList(Paths.get("C:\\mods\\solomod-3.1.jar")), jars);
    }

    @Test
    void parseModJarsOfNullOrEmptyIsEmptyNotAnError() {
        assertTrue(HostAgent.parseModJars(null).isEmpty());
        assertTrue(HostAgent.parseModJars("").isEmpty());
        assertTrue(HostAgent.parseModJars("   ").isEmpty());
    }

    @Test
    void parseModJarsSkipsBlankEntriesFromTrailingCommas() {
        List<Path> jars = HostAgent.parseModJars("C:\\mods\\a.jar,,C:\\mods\\b.jar,");
        assertEquals(Arrays.asList(Paths.get("C:\\mods\\a.jar"), Paths.get("C:\\mods\\b.jar")), jars);
    }

    @Test
    void premainArgsThreadModjarsThroughLikeEveryOtherKvOption() {
        var kv = HostAgent.parse("snapshot=C:\\a\\b.json;log=C:\\c\\d.log;ns=coolmod;"
                + "modjars=C:\\mods\\coolmod-1.0.jar,C:\\mods\\coolmod-addon-1.0.jar");
        assertEquals("C:\\mods\\coolmod-1.0.jar,C:\\mods\\coolmod-addon-1.0.jar", kv.get("modjars"));
        List<Path> jars = HostAgent.parseModJars(kv.get("modjars"));
        assertEquals(2, jars.size());
    }

    @Test
    void defaultModJarsIsEmptyUntilConfigured() {
        // Freshly configured with no jar list at all (the 3-arg overload) - not "the test mod's
        // jar", an honest empty list. Whoever boots the universe decides what an empty list means.
        HostAgent.configure(Paths.get("snap.json"), null, "coolmod");
        assertTrue(HostAgent.modJars().isEmpty());
    }

    @Test
    void configureFourArgOverloadSetsAndIsolatesTheJarList() {
        List<Path> given = Arrays.asList(Paths.get("mod-a.jar"), Paths.get("mod-b.jar"));
        HostAgent.configure(Paths.get("snap.json"), null, "coolmod", given);
        assertEquals(given, HostAgent.modJars());

        // mutating the caller's list afterward must not affect what HostAgent stored
        given = new java.util.ArrayList<>(given);
        given.add(Paths.get("mod-c.jar"));
        assertEquals(2, HostAgent.modJars().size());
    }

    @Test
    void configureThreeArgOverloadResetsJarListToEmpty() {
        HostAgent.configure(Paths.get("snap.json"), null, "coolmod",
                Collections.singletonList(Paths.get("mod-a.jar")));
        assertEquals(1, HostAgent.modJars().size());

        HostAgent.configure(Paths.get("snap.json"), null, "coolmod");
        assertTrue(HostAgent.modJars().isEmpty());
    }
}
