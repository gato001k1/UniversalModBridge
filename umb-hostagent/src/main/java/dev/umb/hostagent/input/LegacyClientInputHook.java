package dev.umb.hostagent.input;

import dev.umb.bridge.api.StackData;
import dev.umb.hostagent.AgentLog;
import dev.umb.hostagent.effects.LegacyClientEffectsHook;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;

/** Client runTick seam. Captures once and sends the registered UMB payload over the active connection. */
public final class LegacyClientInputHook {
    private static final LegacyKeyMappingRegistry KEYS = new LegacyKeyMappingRegistry();
    private static final LegacyInputCapture CAPTURE = new LegacyInputCapture(KEYS);
    private static volatile LegacyInputFrame last;
    private static volatile LegacyInputFrame lastSent;
    private static volatile java.util.Map<String, Boolean> prevHeld =
            java.util.Map.of();
    private static Method toLegacy;
    private LegacyClientInputHook() {}

    /** LWJGL2 keyboard code -> GLFW key, for every code the translation table knows. */
    private static final int[][] RAW_KEYS = rawKeyTable();

    private static int[][] rawKeyTable() {
        java.util.List<int[]> pairs = new java.util.ArrayList<>();
        for (int code = 1; code < 256; code++) {
            try {
                pairs.add(new int[]{code, Lwjgl2ToGlfw.keyboardToGlfw(code)});
            } catch (Throwable unknown) {
                // Codes without a GLFW equivalent are simply never reported down.
            }
        }
        return pairs.toArray(new int[0][]);
    }

    static {
        // Physical keys only while in-world with focus (no screen), like vanilla movement keys.
        LegacyKeyMappingRegistry.rawKeySampler = () -> {
            Minecraft mc = Minecraft.getInstance();
            java.util.Map<String, Boolean> raw = new java.util.LinkedHashMap<>();
            if (mc == null || mc.gui == null || mc.gui.screen() != null || !mc.isWindowActive()) return raw;
            com.mojang.blaze3d.platform.Window window = mc.getWindow();
            ensureStickyKeys(window);
            // Focus marker (not a key code): legacy inGameHasFocus / Display.isActive.
            raw.put("raw:focus", Boolean.TRUE);
            for (int[] pair : RAW_KEYS) {
                if (com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, pair[1])) {
                    raw.put("raw:" + pair[0], Boolean.TRUE);
                }
            }
            // Mouse buttons use the LWJGL2 KeyBinding convention (button - 100): mods that
            // poll Mouse.isButtonDown for bindings such as "fire weapon" see real levels.
            if (mc.mouseHandler != null) {
                if (mc.mouseHandler.isLeftPressed()) raw.put("raw:-100", Boolean.TRUE);
                if (mc.mouseHandler.isRightPressed()) raw.put("raw:-99", Boolean.TRUE);
                if (mc.mouseHandler.isMiddlePressed()) raw.put("raw:-98", Boolean.TRUE);
            }
            return raw;
        };
    }

    private static volatile long stickyWindow;

    /**
     * The raw sampler polls key LEVELS once per client tick (50 ms), so a tap shorter than a tick
     * (a quick R for a mod GUI, a 40 ms synthetic key press) was never seen by the legacy side.
     * GLFW sticky keys keep a released key reading PRESS until it is polled once. This sampler
     * polls every key each tick, so the latch lasts at most one tick and no tap is lost. Runs on
     * the client thread (GLFW input-mode calls must); re-applied if the window is recreated.
     */
    static void ensureStickyKeys(com.mojang.blaze3d.platform.Window window) {
        try {
            long handle = window == null ? 0L : window.handle();
            if (handle == 0L || handle == stickyWindow) return;
            org.lwjgl.glfw.GLFW.glfwSetInputMode(handle, org.lwjgl.glfw.GLFW.GLFW_STICKY_KEYS,
                    org.lwjgl.glfw.GLFW.GLFW_TRUE);
            stickyWindow = handle;
            LegacyInputDiag.line("raw key sampler: GLFW sticky keys on (short taps latch one tick)");
        } catch (Throwable t) {
            AgentLog.error("LegacyClientInputHook.ensureStickyKeys", t, 1);
            stickyWindow = window == null ? 0L : window.handle();
        }
    }

    public static KeyMapping registerLegacyKey(String namespace, String stableId,
            String translationKey, com.mojang.blaze3d.platform.InputConstants.Type type,
            int defaultKey) {
        return KEYS.register(namespace, stableId, translationKey, type, defaultKey);
    }

    public static void clientTick(Minecraft mc) {
        try {
            if (mc == null || mc.player == null || mc.getConnection() == null) return;
            LegacyInputBootstrap.ensure(mc, KEYS);
            KEYS.syncRebinds(dev.umb.hostagent.content.UmbBridgeHost.get());
            LocalPlayer p = mc.player; Inventory inv = p.getInventory(); ItemStack stack = inv.getSelectedItem();
            StackData s = stackData(stack);
            Vec3 look = p.getLookAngle();
            long tick = System.nanoTime(); // monotonic host-side sample id; never used as world time
            LegacyInputFrame f = CAPTURE.sample(tick, p.getScoreboardName(), s.legacyId, s.damage, s.count, s.nbt,
                    mc.options.keyUse.isDown(), mc.options.keyAttack.isDown(), mc.options.keyShift.isDown(),
                    look.x, look.y, look.z, p.getYRot(), p.getXRot(), inv.getSelectedSlot());
            last = f;
            for (java.util.Map.Entry<String, Boolean> e : f.legacyKeys().entrySet()) {
                if (Boolean.TRUE.equals(e.getValue())
                        && !Boolean.TRUE.equals(prevHeld.get(e.getKey()))
                        && LegacyInputDiag.oncePer("twin:" + e.getKey(), 0)) {
                    LegacyInputDiag.loud("twin live: " + e.getKey());
                }
            }
            prevHeld = f.legacyKeys();
            // Change-gated: the server re-applies the latest frame every one of its own
            // ticks, so resending an identical frame only burns bandwidth.
            if (LegacyFrameDiffer.changed(lastSent, f)) {
                p.connection.getConnection().send(new ServerboundCustomPayloadPacket(new LegacyInputPayload(f)));
                lastSent = f;
                if (LegacyInputDiag.oncePer("payload-sent", 30_000_000_000L)) {
                    long down = f.legacyKeys().values().stream().filter(Boolean.TRUE::equals).count();
                    LegacyInputDiag.line("payload sent: player=" + f.playerId()
                            + " keys-down=" + down + " use=" + f.useDown()
                            + " attack=" + f.attackDown() + " slot=" + f.selectedSlot());
                }
            }
            LegacyClientEffectsHook.clientTick();
        } catch (Throwable t) { AgentLog.error("LegacyClientInputHook.clientTick", t, 2); }
    }

    /** Predictive client gate: legacy-item attack input is withheld from vanilla block attack.
     * The server's packet plan remains the authority for whether a legacy action was consumed. */
    public static boolean suppressVanillaAttack() {
        LegacyInputFrame f = last;
        return f != null && !f.heldItemId().isEmpty() && (f.attackDown() || f.attackPressed());
    }

    /** Test-only: {@link #last} is otherwise only ever set from a live {@link #clientTick}, which
     *  needs a real {@code Minecraft} instance - same convention as {@code Hooks.setSuppressedElementsForTest}.
     *  Public (unlike that sibling) because {@link dev.umb.hostagent.LegacyAttackPatcherTest}, the
     *  only other caller, necessarily lives in a different package from this hook's own tests. */
    public static void setLastForTest(LegacyInputFrame frame) {
        last = frame;
    }

    private static StackData stackData(ItemStack stack) throws Exception {
        if (toLegacy == null) { Class<?> c=Class.forName("dev.umb.hostagent.content.LegacyStackConv"); toLegacy=c.getDeclaredMethod("toLegacy", ItemStack.class); toLegacy.setAccessible(true); }
        Object value = toLegacy.invoke(null, stack);
        return value instanceof StackData ? (StackData)value : StackData.EMPTY;
    }
}
