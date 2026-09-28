package dev.umb.hostagent.content;

import dev.umb.bridge.api.HostPlayer;
import dev.umb.bridge.api.InputData;
import dev.umb.bridge.api.LegacyBridge;
import dev.umb.bridge.api.StackData;
import dev.umb.hostagent.AgentLog;
import dev.umb.hostagent.input.LegacyAutomationKeys;
import dev.umb.hostagent.input.LegacyInputDiag;
import dev.umb.hostagent.input.LegacyInputFrame;
import dev.umb.hostagent.input.LegacyLatestInputs;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Host-side dispatch for the MinecraftServer.tickServer(BooleanSupplier) transformer seam. */
public final class LegacyServerTickHook {
    private static final Map<UUID, ServerPlayer> LAST_PLAYERS = new HashMap<>();
    private static long lastEntityDispatchLogNanos;

    private LegacyServerTickHook() {
    }

    /** Runs before PlayerList sends the join/terrain packets, so a lazy legacy boot cannot leave
     * the client simulating an empty world while the server thread is blocked in Forge startup. */
    public static void beforePlayerJoin(ServerPlayer player) {
        try {
            if (player == null || player.level() == null) return;
            HostWorldImpl world = new HostWorldImpl(player.level());
            if (!UmbBridgeHost.ensureBooted(world)) return;
            LegacyBridge bridge = UmbBridgeHost.get();
            if (bridge instanceof BridgeRouter router) router.prewarmEras(world);
            AgentLog.line("UMB world-join legacy boot gate complete");
        } catch (Throwable t) {
            AgentLog.error("LegacyServerTickHook.beforePlayerJoin", t, 5);
        }
    }

    public static void serverTickStart(MinecraftServer server) {
        dispatch(server, false);
    }

    public static void serverTickEnd(MinecraftServer server) {
        dispatch(server, true);
    }

    private static void dispatch(MinecraftServer server, boolean endPhase) {
        try {
            if (server == null || server.overworld() == null) return;
            HostWorldImpl world = new HostWorldImpl(server.overworld());
            if (!UmbBridgeHost.ensureBooted(world)) return;
            LegacyBridge bridge = UmbBridgeHost.get();
            if (bridge == null || !bridge.isBooted()) return;
            if (bridge instanceof BridgeRouter router) {
                // Era boot threads only publish readiness. Retries touch block entities here,
                // on the server thread, and weak queue entries never force a chunk to load.
                router.drainReadyTileRetries();
            }

            ArrayList<HostPlayer> players = new ArrayList<>();
            Set<UUID> current = new HashSet<>();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player == null) continue;
                current.add(player.getUUID());
                ServerPlayer previous = LAST_PLAYERS.put(player.getUUID(), player);
                HostPlayerImpl hostPlayer = new HostPlayerImpl(player);
                players.add(hostPlayer);
                if (previous != null && previous != player && !endPhase) {
                    bridge.playerRespawn(hostPlayer);
                }
            }
            LAST_PLAYERS.keySet().removeIf(id -> !current.contains(id));
            if (!endPhase) {
                // The entity pass runs before tickEvents(START). Synchronize the live host
                // player wrappers first so entity World/player queries see a teleport from this
                // server tick rather than the pose cached during the previous tick.
                bridge.syncPlayers(players);
                try {
                    applyLegacyInput(server, bridge);
                } finally {
                    // Entity lifecycle must not be lost when a legacy input subscriber throws or
                    // spends too long in a client-only path. Input remains first so MCHeli sees
                    // the current control frame during this same legacy tick.
                    long now = System.nanoTime();
                    if (now - lastEntityDispatchLogNanos >= 5_000_000_000L) {
                        lastEntityDispatchLogNanos = now;
                        AgentLog.line("[UMB-ENTITY] server tick dispatch -> "
                                + bridge.getClass().getName() + ".tickEntities()");
                    }
                    bridge.tickEntities();
                }
            }
            bridge.tickEvents(world, players.toArray(new HostPlayer[0]), endPhase);
        } catch (Throwable t) {
            AgentLog.error("LegacyServerTickHook." + (endPhase ? "end" : "start"), t, 5);
        }
    }

    /**
     * Owns legacy input dispatch: per player, the latest client frame (which the client
     * only resends on change) is re-applied before the legacy tick, merged with live
     * automation holds. Re-applying identical levels is safe: the legacy mirror derives
     * press edges from level diffs, and derived packet plans are edge/idempotent.
     */
    private static void applyLegacyInput(MinecraftServer server, LegacyBridge bridge) {
        int fromFrames = 0;
        int synthetic = 0;
        int automation = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player == null) continue;
            try {
                LegacyInputFrame f = LegacyLatestInputs.get(player.getScoreboardName());
                if (f == null) {
                    // Automation-only: no client frame ever arrived (idle/AFK player), but a
                    // press_legacy_key hold is live. Build a minimal record from live player
                    // state so the hold still reaches the mirror on the same path.
                    if (!LegacyAutomationKeys.hasLive()) continue;
                    f = syntheticFrame(player);
                    synthetic++;
                } else {
                    fromFrames++;
                }
                java.util.Map<String, Boolean> keys =
                        LegacyAutomationKeys.merged(f.legacyKeys());
                if (!keys.equals(f.legacyKeys())) {
                    automation++;
                }
                InputData data = new InputData(f.tick(), f.useDown(), f.attackDown(),
                        f.sneakDown(), f.usePressed(), f.attackPressed(), f.selectedSlot(),
                        f.yaw(), f.pitch(), f.lookX(), f.lookY(), f.lookZ(),
                        f.heldItemId(), f.heldCount(), f.heldDamage(), f.heldNbt(), keys);
                boolean verbose = LegacyInputDiag.oncePer("accept-call", 5_000_000_000L);
                if (verbose) {
                    LegacyInputDiag.line("acceptInput -> "
                            + bridge.getClass().getName() + "@"
                            + Integer.toHexString(System.identityHashCode(bridge))
                            + " player=" + player.getScoreboardName()
                            + " keys=" + keys.size());
                }
                boolean ok = bridge.acceptInput(new HostPlayerImpl(player), data);
                if (verbose) {
                    LegacyInputDiag.line("acceptInput <- " + ok);
                }
            } catch (Throwable t) {
                AgentLog.error("LegacyServerTickHook.applyLegacyInput", t, 5);
            }
        }
        if ((fromFrames > 0 || synthetic > 0)
                && LegacyInputDiag.oncePer("tick-apply", 30_000_000_000L)) {
            LegacyInputDiag.line("tick apply: " + fromFrames + " frame(s), "
                    + synthetic + " synthetic, automation merged into " + automation);
        }
    }

    private static LegacyInputFrame syntheticFrame(ServerPlayer player) {
        StackData s;
        try {
            s = LegacyStackConv.toLegacy(player.getInventory().getSelectedItem());
        } catch (Throwable t) {
            s = StackData.EMPTY;
        }
        Vec3 look = player.getLookAngle();
        return new LegacyInputFrame(System.nanoTime(), player.getScoreboardName(),
                s.legacyId, s.damage, s.count, s.nbt, false, false, false, false, false,
                look.x, look.y, look.z, player.getYRot(), player.getXRot(),
                player.getInventory().getSelectedSlot(), java.util.Map.of());
    }
}
