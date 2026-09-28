package dev.umb.legacy.legacyside.input;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import cpw.mods.fml.common.network.simpleimpl.IMessage;

import cpw.mods.fml.common.gameevent.InputEvent.KeyInputEvent;
import cpw.mods.fml.common.gameevent.InputEvent.MouseInputEvent;

import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;

import dev.umb.legacy.legacyside.network.LegacyNetworkLoopback;

/**
 * Explicit host entry point.
 * 's should call {@link #accept(LegacyInputRecord)} once per host tick after preparing the corresponding UmbPlayer.
 */
public final class LegacyInputDispatcher {
    public interface PacketPlan {
        /**
         * Builds the packet for one key edge. {@code pressed} is the level of the key
         * AFTER the edge (true = just pressed, false = just released), mirroring what a
         * real client sends on state change — not the mouse state, not a guess.
         */
        IMessage create(LegacyInputRecord input, boolean pressed);
    }

    private static final List<LegacyInputRecord> RECENT = new ArrayList<LegacyInputRecord>();
    private static final Map<String, PacketPlan> PLANS = new ConcurrentHashMap<String, PacketPlan>();
    /** Last pressed stable-id set per player name: plans fire on edges, never per-tick. */
    private static final Map<String, java.util.Set<String>> LAST =
            new ConcurrentHashMap<String, java.util.Set<String>>();

    private LegacyInputDispatcher() { }

    public static boolean accept(LegacyInputRecord input) {
        if (input == null) return false;
        boolean consumed = false;
        LegacyInputPlanLoader.installDefaultPlans();
        synchronized (RECENT) {
            RECENT.add(input);
            if (RECENT.size() > 64) {
                RECENT.remove(0);
            }
        }
        String name = playerName(input);
        if (name == null) {
            // No player identity (plan-only tests, or a facade without a seeded profile):
            // fire every pressed key as pressed. Production always has a player
            // (LegacyBridgeImpl rejects null).
            if (LegacyInputDiag.oncePer("accept-noname", 60_000_000_000L)) {
                LegacyInputDiag.log("accept: player without identity, plan-only path (no mirror)");
            }
            for (String key : input.pressedKeys()) {
                consumed |= firePlan(key, input, true);
            }
            return consumed;
        }
        java.util.Set<String> prev = LAST.get(name);
        if (prev == null) {
            prev = java.util.Collections.<String>emptySet();
        }
        java.util.Set<String> pressed = input.pressedKeys();
        java.util.Set<String> added = new java.util.LinkedHashSet<String>(pressed);
        added.removeAll(prev);
        java.util.Set<String> released = new java.util.LinkedHashSet<String>(prev);
        released.removeAll(pressed);
        LAST.put(name, new java.util.LinkedHashSet<String>(pressed));
        for (String key : added) {
            consumed |= firePlan(key, input, true);
        }
        for (String key : released) {
            consumed |= firePlan(key, input, false);
        }
        mirrorToClientLayer(input);
        // The server acceptance path ends here. Client-tick dispatch is additive and is owned by
        // LegacyBridgeImpl.tickEvents, after this method has returned, so a client-only facade or
        // listener cannot delay or suppress plan delivery, NBT/input application, or its result.
        return consumed;
    }

    /** Fires one plan; returns true when a packet reached the server handler. */
    private static boolean firePlan(String key, LegacyInputRecord input, boolean pressed) {
        PacketPlan plan = PLANS.get(key);
        if (plan == null) {
            return false;
        }
        IMessage message = plan.create(input, pressed);
        if (message == null) {
            return false;
        }
        LegacyNetworkLoopback.deliverToServer(message, input.player());
        // 5s throttle, not once-ever: repeats must stay visible while debugging live
        // (edges are inherently rate-limited by press cycles, so this cannot flood).
        if (LegacyInputDiag.oncePer("plan:" + key + ":" + pressed, 5_000_000_000L)) {
            LegacyInputDiag.log("plan " + key + " -> "
                    + message.getClass().getName() + " pressed=" + pressed + heldSummary(input));
        }
        return true;
    }

    /** Facade current slot at plan-fire time (proves selection state on the fire path). */
    private static String slotInfo(LegacyInputRecord input) {
        try {
            net.minecraft.entity.player.InventoryPlayer inv =
                    input.player().field_71071_by;
            return " slot=" + (inv == null ? "noinv" : String.valueOf(inv.field_70461_c));
        } catch (Throwable t) {
            return " slot=?";
        }
    }

    private static String idHex(Object o) {
        return o == null ? "null" : Integer.toHexString(System.identityHashCode(o));
    }

    /**
     * Facade held stack + its NBT at plan-fire time (universal dump, no mod knowledge).
     * Answers "did the handler's NBT land somewhere persistent" across press/release
     * without any bridge addition: the stack comes from the facade, not the record.
     */
    private static String heldSummary(LegacyInputRecord input) {
        try {
            if (input == null || input.player() == null) {
                return "";
            }
            ItemStack held = input.player().func_70694_bm();
            if (held == null || held.func_77973_b() == null) {
                return " held=null";
            }
            String nbt = "";
            try {
                if (held.field_77990_d != null) {
                    nbt = held.field_77990_d.toString();
                    if (nbt.length() > 400) {
                        nbt = nbt.substring(0, 400) + "...";
                    }
                }
            } catch (Throwable ignored) {
            }
            return " held=" + held.func_77973_b().getClass().getName() + " stackId="
                    + idHex(held) + slotInfo(input) + " nbt={" + nbt + "}";
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Applies the record's pressed stable ids to the synthesized client input layer.
     * Unknown stable ids (no synthesized binding) are ignored: they stay visible only
     * as packet-plan keys. A null player runs the plan path only, so plan-only unit
     * tests never touch global key state. Public for headless tests; the host entry
     * point remains {@link #accept}.
     */
    /** "raw:<lwjgl2 code>" = a physical key level sampled by the host (see LegacyKeyMappingRegistry). */
    static Integer rawCode(String key) {
        try {
            int code = Integer.parseInt(key.substring(4));
            // 1..255 keyboard; -100..-98 mouse buttons (LWJGL2 KeyBinding convention).
            return (code > 0 && code < 256) || (code >= -100 && code <= -98)
                    ? Integer.valueOf(code) : null;
        } catch (RuntimeException bad) {
            return null;
        }
    }

    public static void mirrorToClientLayer(LegacyInputRecord input) {
        if (input == null || input.player() == null) {
            return;
        }
        LegacyKeyBindingSynthesis.ensureSynthesized();
        String name = playerName(input);
        if (name == null) {
            return;
        }
        java.util.Set<Integer> codes = new java.util.LinkedHashSet<Integer>();
        for (String key : input.pressedKeys()) {
            Integer code = key != null && key.startsWith("raw:") ? rawCode(key)
                    : LegacyKeyBindingSynthesis.codeFor(key);
            if (code != null) {
                codes.add(code);
            }
        }
        LegacyLwjglState.noteHostLook(name, input.yaw(), input.pitch(),
                input.pressedKeys().contains("raw:focus"));
        java.util.Set<Integer> prev = LegacyLwjglState.downCodes(name);
        java.util.Set<Integer> added = new java.util.LinkedHashSet<Integer>(codes);
        added.removeAll(prev);
        java.util.Set<Integer> released = new java.util.LinkedHashSet<Integer>(prev);
        released.removeAll(codes);
        LegacyLwjglState.begin(name);
        try {
            for (String key : input.pressedKeys()) {
                if (LegacyInputDiag.oncePer("mirror:" + key, 5_000_000_000L)
                        && LegacyKeyBindingSynthesis.codeFor(key) != null) {
                    int c = LegacyKeyBindingSynthesis.codeFor(key).intValue();
                    boolean vanilla =
                            !LegacyKeyBindingSynthesis.bindingsForCode(c).isEmpty();
                    LegacyInputDiag.log("mirror " + key + " -> code " + c
                            + (vanilla ? " (vanilla binding)" : " (shim only)"));
                }
            }
            for (Integer code : codes) {
                if (!LegacyKeyBindingSynthesis.bindingsForCode(code.intValue()).isEmpty()) {
                    KeyBinding.func_74510_a(code.intValue(), true);
                }
                LegacyLwjglState.setDown(name, code.intValue(), true);
            }
            for (Integer code : released) {
                if (!LegacyKeyBindingSynthesis.bindingsForCode(code.intValue()).isEmpty()) {
                    KeyBinding.func_74510_a(code.intValue(), false);
                }
                LegacyLwjglState.setDown(name, code.intValue(), false);
            }
            for (Integer code : added) {
                for (KeyBinding binding :
                        LegacyKeyBindingSynthesis.bindingsForCode(code.intValue())) {
                    binding.field_151474_i = 1;
                }
            }
            for (Integer code : released) {
                for (KeyBinding binding :
                        LegacyKeyBindingSynthesis.bindingsForCode(code.intValue())) {
                    binding.field_151474_i = 0;
                }
            }
            boolean keyboardChanged = changedOnSide(added, released, true);
            boolean mouseChanged = changedOnSide(added, released, false);
            if (keyboardChanged) {
                postInputEvent(new KeyInputEvent());
            }
            if (mouseChanged) {
                postInputEvent(new MouseInputEvent());
            }
        } finally {
            LegacyLwjglState.end();
        }
    }

    /**
     * Best-effort event delivery. The FML bus needs a booted Loader, which headless
     * probe/test contexts do not have; the state mirror above is authoritative and must
     * never fail because a subscriber bus is unavailable. Live delivery is verified
     * in-game, not here.
     */
    private static void postInputEvent(cpw.mods.fml.common.eventhandler.Event event) {
        try {
            MinecraftForge.EVENT_BUS.post(event);
        } catch (Throwable ignored) {
            // No FML loader outside a booted universe; state was already mirrored.
        }
    }

    private static boolean changedOnSide(java.util.Set<Integer> added,
            java.util.Set<Integer> released, boolean keyboard) {
        for (Integer code : added) {
            if ((code.intValue() >= 0) == keyboard) {
                return true;
            }
        }
        for (Integer code : released) {
            if ((code.intValue() >= 0) == keyboard) {
                return true;
            }
        }
        return false;
    }

    /** Registers a bytecode-derived keybind -> packet mapping. */
    public static void registerPlan(String stableKeyId, PacketPlan plan) {
        if (stableKeyId == null || plan == null) return;
        PLANS.put(stableKeyId, plan);
    }

    /** Derived mapping entry: bytecode analysis supplies the stable key id and factory. */
    public static void sendDerived(String keyId, LegacyInputRecord input, boolean pressed,
            PacketPlan plan) {
        if (input == null || plan == null || keyId == null || !input.pressedKeys().contains(keyId)) {
            return;
        }
        IMessage message = plan.create(input, pressed);
        if (message != null) {
            LegacyNetworkLoopback.deliverToServer(message, input.player());
        }
    }

    /** Test-only reset for the per-player edge memory (the JUnit JVM hosts many tests). */
    public static void clearEdgesForTest() {
        LAST.clear();
    }

    private static String playerName(LegacyInputRecord input) {
        if (input == null || input.player() == null) {
            return null;
        }
        try {
            return input.player().func_70005_c_();
        } catch (Throwable t) {
            return null;
        }
    }

    public static List<LegacyInputRecord> recent() {
        synchronized (RECENT) {
            return Collections.unmodifiableList(new ArrayList<LegacyInputRecord>(RECENT));
        }
    }
}
