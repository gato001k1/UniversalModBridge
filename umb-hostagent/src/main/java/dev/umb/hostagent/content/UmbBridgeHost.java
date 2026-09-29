package dev.umb.hostagent.content;

import dev.umb.bridge.api.HostWorld;
import dev.umb.bridge.api.LegacyBridge;
import dev.umb.hostagent.AgentLog;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * R4: universe boot is LAZY and SYNCHRONOUS on the server thread, at first legacy need (first
 * machine placement or interaction) -- never at beforeFreeze (FML mutates JVM globals and racing
 * the registry freeze is not worth hiding the ~7s boot). Boot exactly once; a boot failure
 * disables the bridge instead of crashing the game.
 *
 * The actual {@link LegacyBridge} implementation is Lane A's (umb-legacy); this holder is the one
 * place Lane B code reaches it from, so every call site (UmbLegacyBlock.useWithoutItem,
 * UmbLegacyBlockEntity.setLevel) shares the same boot-once bookkeeping. Tests set a FAKE bridge.
 */
public final class UmbBridgeHost {

    private static volatile LegacyBridge bridge;
    private static final AtomicBoolean bootAttempted = new AtomicBoolean(false);
    /** What the universe keeps from boot; follows the integrated server across world reloads. */
    private static volatile LiveHostWorld live;

    private UmbBridgeHost() {
    }

    public static void set(LegacyBridge b) {
        bridge = b;
    }

    public static LegacyBridge get() {
        return bridge;
    }

    /**
     * Ensures the legacy universe is booted, blocking synchronously on the CALLING thread (which
     * must be the server thread -- callers are expected to have already gone through
     * {@link dev.umb.hostagent.UmbThread#assertServer()}). Returns true if the bridge is usable
     * afterward.
     */
    public static boolean ensureBooted(HostWorld world) {
        LegacyBridge b = bridge;
        if (b == null) return false;
        LiveHostWorld l = live;
        if (l != null) l.observe(world);
        try {
            if (b.isBooted()) return true;
        } catch (Throwable t) {
            AgentLog.error("UmbBridgeHost.isBooted", t, 3);
            return false;
        }
        if (!bootAttempted.compareAndSet(false, true)) {
            // boot() is synchronous and this is all on one server thread in practice, so a
            // concurrent re-entry only happens if boot() itself re-enters this method; treat it
            // as "not ready yet" rather than blocking.
            try {
                return b.isBooted();
            } catch (Throwable t) {
                return false;
            }
        }
        long t0 = System.nanoTime();
        try {
            HostWorld bootWorld = world;
            if (world instanceof HostWorldImpl impl) {
                live = new LiveHostWorld(impl);
                bootWorld = live;
            }
            b.boot(bootWorld);
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            AgentLog.loud("UMB-BRIDGE universe booted in " + ms + " ms");
            return true;
        } catch (Throwable t) {
            AgentLog.loud("UMB-BRIDGE FAILED to boot: " + t);
            AgentLog.error("UmbBridgeHost.ensureBooted", t, 8);
            return false;
        }
    }

    /** For tests: forget the bridge and boot state so a fresh test can install its own fake. */
    public static void resetForTests() {
        bridge = null;
        live = null;
        bootAttempted.set(false);
    }
}
