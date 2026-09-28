package dev.umb.legacy.api;

import java.util.List;

/**
 * The only surface across the classloader boundary.
 *
 * <p>Implementations of this interface live INSIDE the legacy universe (they are compiled against
 * Forge 1.7.10 and loaded by the child-first legacy loader). The bootstrap side only ever sees this
 * interface and the plain-data carriers in this package, all of which mention java.* types only, so
 * the very same handshake works when the legacy universe is created from inside the Minecraft 26.2
 * client instead of from a standalone main().</p>
 */
public interface LegacyUniverse {

    /** Seed the FML statics, install the transformers, hand FML its sided handler. */
    void boot(UniverseConfig config) throws Exception;

    /** Drive CONSTRUCTING then PREINIT then INIT then POSTINIT. Never throws: every stage is reported. */
    List<StageResult> lifecycle();

    /** Read the LIVE legacy registries into plain data. */
    RegistrySnapshot snapshot() throws Exception;

    /** Release what can be released. The legacy universe is not restartable in-process. */
    void close();
}
