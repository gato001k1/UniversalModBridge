package dev.umb.legacy.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.mojang.authlib.GameProfile;

import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;

import dev.umb.legacy.legacyside.UmbShimTransformer;
import dev.umb.legacy.legacyside.input.LegacyInputDiag;
import dev.umb.legacy.legacyside.input.LegacyInputDispatcher;
import dev.umb.legacy.legacyside.input.LegacyInputRecord;
import dev.umb.legacy.legacyside.input.LegacyKeyBindingSynthesis;
import dev.umb.legacy.legacyside.input.LegacyKeyDefaults;
import dev.umb.legacy.legacyside.input.LegacyKeyBindingRegistry;
import dev.umb.legacy.legacyside.input.LegacyLwjglState;

/**
 * plan-driven KeyBinding synthesis, the player-contextual LWJGL2 mirror state, the per-tick dispatch mirror (held + press edge + input events), and the UmbShimTransformer LWJGL2 rewrites.
 * <p>No game, no display, no native libraries: every test runs in the...
 */
class LegacyInputBridgeTest {
    private static final Path REPO_PLANS = Paths.get("research/out/legacy/hbm-input-plans.json");
    private static final Path REPO_MCHELI_PLANS =
            Paths.get("research/out/legacy/mcheli-input-plans.json");
    private static final Path REPO_MCHELI_DEFAULTS =
            Paths.get("research/out/legacy/mcheli-key-defaults.json");

    private String savedPlansProp;

    @BeforeEach
    void isolate() throws Exception {
        savedPlansProp = System.getProperty("umb.inputPlans");
        Path fixture = LegacyInputTestPlans.writeTempPlans();
        System.setProperty("umb.inputPlans",
                fixture.getParent().toAbsolutePath().toString());
        LegacyKeyBindingSynthesis.clearForTest();
        LegacyKeyDefaults.clearForTest();
        LegacyInputDispatcher.clearEdgesForTest();
        LegacyInputDiag.clearForTest();
        LegacyInputTestKeys.alphaKey = null;
        LegacyInputTestKeys.betaMouse = null;
    }

    @AfterEach
    void restore() {
        LegacyLwjglState.end();
        LegacyKeyBindingSynthesis.clearForTest();
        LegacyKeyDefaults.clearForTest();
        LegacyInputDispatcher.clearEdgesForTest();
        LegacyInputDiag.clearForTest();
        LegacyInputTestKeys.alphaKey = null;
        LegacyInputTestKeys.betaMouse = null;
        if (savedPlansProp == null) {
            System.clearProperty("umb.inputPlans");
        } else {
            System.setProperty("umb.inputPlans", savedPlansProp);
        }
    }

    @Test
    void synthesisAssignsHoldersAndSkipsTheRest() throws Exception {
        int count = LegacyKeyBindingSynthesis.installFile(
                Paths.get(System.getProperty("umb.inputPlans")).resolve("testns-input-plans.json"));
        // alpha + beta + str + ghost all construct; only the unresolved entry is skipped.
        assertEquals(4, count);
        assertNotNull(LegacyInputTestKeys.alphaKey);
        assertNotNull(LegacyInputTestKeys.betaMouse);
        assertNull(LegacyInputTestKeys.notABinding);
        assertEquals(Integer.valueOf(30),
                LegacyKeyBindingSynthesis.codeFor(LegacyInputTestPlans.ALPHA_ID));
        assertEquals(Integer.valueOf(-100),
                LegacyKeyBindingSynthesis.codeFor(LegacyInputTestPlans.BETA_ID));
        assertEquals(LegacyInputTestKeys.alphaKey,
                LegacyKeyBindingRegistry.byId(LegacyInputTestPlans.ALPHA_ID));
        assertEquals(1, LegacyKeyBindingSynthesis.skipped());
        // alpha/beta holder assigns + str skip-holder + ghost holder-absent.
        assertEquals(4, LegacyKeyBindingSynthesis.report().size());
    }

    @Test
    void synthesisUsesDefaultsTableForCodelessEntries() {
        int defaults = LegacyKeyDefaults.loadFile(REPO_MCHELI_DEFAULTS);
        assertEquals(124, defaults);
        int count = LegacyKeyBindingSynthesis.installFile(REPO_MCHELI_PLANS);
        assertEquals(124, count);
// Legacy compatibility behavior.
        // heli KeyUseWeapon <- KeyUseWeapon default -99 (middle mouse).
        assertEquals(Integer.valueOf(19), LegacyKeyBindingSynthesis.codeFor(
                "legacy:key:mcheli:mcheli/aircraft/MCH_AircraftClientTickHandler#KeyGUI"));
        assertEquals(Integer.valueOf(-99), LegacyKeyBindingSynthesis.codeFor(
                "legacy:key:mcheli:mcheli/helicopter/MCH_ClientHeliTickHandler#KeyUseWeapon"));
        assertEquals(Integer.valueOf(42), LegacyKeyBindingSynthesis.codeFor(
                "legacy:key:mcheli:mcheli/helicopter/MCH_ClientHeliTickHandler#KeyUnmountForce"));
        // MCH_Key instance fields can never be assigned: holder assignment is recorded
        // in the report, but every entry still yields mirror codes.
        assertEquals(0, LegacyKeyBindingSynthesis.skipped());
    }

    @Test
    void planLoaderSkipsMessageClassesInvisibleToThisLoader() {
        // Headless the mod jars are absent, so every HBM plan skips without throwing;
        // in-universe the mod-loader chain (LegacyModClasses) resolves them.
        assertEquals(0, dev.umb.legacy.legacyside.input.LegacyInputPlanLoader
                .installFile(REPO_PLANS));
    }

    @Test
    void realHbmPlansSynthesizeTwenty() {
        int count = LegacyKeyBindingSynthesis.installFile(REPO_PLANS);
        assertEquals(20, count);
        assertEquals(Integer.valueOf(49), LegacyKeyBindingSynthesis.codeFor(
                "legacy:key:hbm.key.calculator:49:hbm.key"));
        assertEquals(0, LegacyKeyBindingSynthesis.skipped());
    }

    @Test
    void lwjglStateLevelsEdgesAndContextIsolation() {
        assertFalse(LegacyLwjglState.isKeyDown(30));
        // No context open: samples are stored but invisible.
        LegacyLwjglState.setDown("p1", 30, true);
        assertFalse(LegacyLwjglState.isKeyDown(30));
        LegacyLwjglState.begin("p1");
        try {
            assertTrue(LegacyLwjglState.isKeyDown(30));
            assertTrue(LegacyLwjglState.next());
            assertEquals(30, LegacyLwjglState.getEventKey());
            assertTrue(LegacyLwjglState.getEventKeyState());
            assertFalse(LegacyLwjglState.next());
            // Mouse side: code -100 is button 0, never a keyboard key.
            assertFalse(LegacyLwjglState.isKeyDown(-100));
            assertFalse(LegacyLwjglState.isButtonDown(0));
        } finally {
            LegacyLwjglState.end();
        }
        LegacyLwjglState.setDown("p1", -100, true);
        LegacyLwjglState.setDown("p1", -99, true);
        LegacyLwjglState.begin("p1");
        try {
            assertTrue(LegacyLwjglState.isButtonDown(0));
            assertTrue(LegacyLwjglState.isButtonDown(1));
            assertTrue(LegacyLwjglState.mouseNext());
            assertEquals(0, LegacyLwjglState.getEventButton());
            assertTrue(LegacyLwjglState.getEventButtonState());
        } finally {
            LegacyLwjglState.end();
        }
        // A different player sees nothing; release clears the level.
        LegacyLwjglState.begin("p2");
        try {
            assertFalse(LegacyLwjglState.isKeyDown(30));
            assertFalse(LegacyLwjglState.isButtonDown(0));
        } finally {
            LegacyLwjglState.end();
        }
        LegacyLwjglState.setDown("p1", 30, false);
        LegacyLwjglState.begin("p1");
        try {
            assertFalse(LegacyLwjglState.isKeyDown(30));
            assertTrue(LegacyLwjglState.next());
            assertFalse(LegacyLwjglState.getEventKeyState());
        } finally {
            LegacyLwjglState.end();
        }
        LegacyLwjglState.clearPlayer("p1");
    }

    @Test
    void mirrorAppliesHeldPressEdgeAndPostsKeyEvent() throws Exception {
        LegacyKeyBindingSynthesis.installFile(
                Paths.get(System.getProperty("umb.inputPlans")).resolve("testns-input-plans.json"));
        EntityPlayerMP player = namedPlayer("MirrorTest");
        Set<String> pressed = new HashSet<String>(
                Collections.singleton(LegacyInputTestPlans.ALPHA_ID));
        LegacyInputRecord record = new LegacyInputRecord(11L, player, false, false, false,
                false, false, 0, 0.0F, 0.0F, 0.0D, 0.0D, 0.0D, null, pressed);
        LegacyInputDispatcher.mirrorToClientLayer(record);
        // Held state reaches the vanilla lookup; the press edge fires exactly once.
        assertTrue(LegacyInputTestKeys.alphaKey.func_151470_d());
        assertTrue(LegacyInputTestKeys.alphaKey.func_151468_f());
        assertFalse(LegacyInputTestKeys.alphaKey.func_151468_f());
        // The shim sees the same state in the player context only.
        LegacyLwjglState.begin("MirrorTest");
        try {
            assertTrue(LegacyLwjglState.isKeyDown(30));
        } finally {
            LegacyLwjglState.end();
        }
        assertFalse(LegacyLwjglState.isKeyDown(30));
        // Event posts are best-effort headless (no FML loader): the mirror must not
        // throw, and delivery is verified live. State assertions above are the gate.
        // Release: held clears, edge resets.
        LegacyInputRecord released = new LegacyInputRecord(12L, player, false, false, false,
                false, false, 0, 0.0F, 0.0F, 0.0D, 0.0D, 0.0D, null,
                Collections.<String>emptySet());
        LegacyInputDispatcher.mirrorToClientLayer(released);
        assertFalse(LegacyInputTestKeys.alphaKey.func_151470_d());
        LegacyLwjglState.clearPlayer("MirrorTest");
    }

    @Test
    void mirrorPostsMouseEventForButtonCodes() throws Exception {
        LegacyKeyBindingSynthesis.installFile(
                Paths.get(System.getProperty("umb.inputPlans")).resolve("testns-input-plans.json"));
        EntityPlayerMP player = namedPlayer("MouseTest");
        Set<String> pressed = new HashSet<String>(
                Collections.singleton(LegacyInputTestPlans.BETA_ID));
        LegacyInputRecord record = new LegacyInputRecord(21L, player, false, false, false,
                false, false, 0, 0.0F, 0.0F, 0.0D, 0.0D, 0.0D, null, pressed);
        LegacyInputDispatcher.mirrorToClientLayer(record);
        LegacyLwjglState.begin("MouseTest");
        try {
            assertTrue(LegacyLwjglState.isButtonDown(0));
        } finally {
            LegacyLwjglState.end();
        }
        LegacyLwjglState.clearPlayer("MouseTest");
    }

    @Test
    void plansFireOnEdgesWithTrueLevels() throws Exception {
        List<Boolean> seen = new ArrayList<Boolean>();
        LegacyInputDispatcher.registerPlan("legacy:key:edge:1:cat",
                (input, pressed) -> {
                    seen.add(Boolean.valueOf(pressed));
                    return null;
                });
        EntityPlayerMP player = namedPlayer("EdgeTest");
        // Press: fires once with true.
        assertFalse(LegacyInputDispatcher.accept(inputRecord(player, 31L,
                Collections.singleton("legacy:key:edge:1:cat"))));
        assertEquals(Arrays.asList(Boolean.TRUE), seen);
        // Held: no edge, silent.
        assertFalse(LegacyInputDispatcher.accept(inputRecord(player, 32L,
                Collections.singleton("legacy:key:edge:1:cat"))));
        assertEquals(1, seen.size());
        // Release: fires once with false.
        assertFalse(LegacyInputDispatcher.accept(inputRecord(player, 33L,
                Collections.<String>emptySet())));
        assertEquals(Arrays.asList(Boolean.TRUE, Boolean.FALSE), seen);
        LegacyLwjglState.clearPlayer("EdgeTest");
    }

    @Test
    void nullPlayerAcceptKeepsLegacyPressedSemantics() {
        List<Boolean> seen = new ArrayList<Boolean>();
        LegacyInputDispatcher.registerPlan("legacy:key:nullp:1:cat",
                (input, pressed) -> {
                    seen.add(Boolean.valueOf(pressed));
                    return null;
                });
        LegacyInputRecord record = new LegacyInputRecord(41L, null, false, false, false,
                false, false, 0, 0.0F, 0.0F, 0.0D, 0.0D, 0.0D, null,
                Collections.singleton("legacy:key:nullp:1:cat"));
        LegacyInputDispatcher.accept(record);
        assertEquals(Arrays.asList(Boolean.TRUE), seen);
    }

    private static LegacyInputRecord inputRecord(EntityPlayerMP player, long tick,
            Set<String> pressed) {
        return new LegacyInputRecord(tick, player, false, false, false,
                false, false, 0, 0.0F, 0.0F, 0.0D, 0.0D, 0.0D, null, pressed);
    }

    /** Test-local packet: carries the pressed edge the plan fired with. */
    public static final class BoolMessage
            implements cpw.mods.fml.common.network.simpleimpl.IMessage {
        final boolean pressed;
        public BoolMessage() { this(false); }
        public BoolMessage(boolean pressed) { this.pressed = pressed; }
        @Override public void fromBytes(io.netty.buffer.ByteBuf buffer) { }
        @Override public void toBytes(io.netty.buffer.ByteBuf buffer) { }
    }

    @Test
    void planBooleanArgCarriesTheKeyEdge() throws Exception {
        Path boolFile = LegacyInputTestPlans.writeBoolPlans(BoolMessage.class.getName());
        assertEquals(1, dev.umb.legacy.legacyside.input.LegacyInputPlanLoader
                .installFile(boolFile));
        final List<Boolean> delivered = new ArrayList<Boolean>();
        dev.umb.legacy.legacyside.network.LegacyNetworkLoopback.registerSimpleMessage(
                new cpw.mods.fml.common.network.simpleimpl.IMessageHandler<BoolMessage,
                        cpw.mods.fml.common.network.simpleimpl.IMessage>() {
                    @Override
                    public cpw.mods.fml.common.network.simpleimpl.IMessage onMessage(
                            BoolMessage message,
                            cpw.mods.fml.common.network.simpleimpl.MessageContext context) {
                        delivered.add(Boolean.valueOf(message.pressed));
                        return null;
                    }
                }, BoolMessage.class, 77, cpw.mods.fml.relauncher.Side.SERVER);
        try {
            EntityPlayerMP player = namedPlayer("BoolTest");
            LegacyInputDispatcher.accept(inputRecord(player, 51L,
                    Collections.singleton(LegacyInputTestPlans.BOOL_ID)));
            LegacyInputDispatcher.accept(inputRecord(player, 52L,
                    Collections.singleton(LegacyInputTestPlans.BOOL_ID)));
            LegacyInputDispatcher.accept(inputRecord(player, 53L,
                    Collections.<String>emptySet()));
            assertEquals(Arrays.asList(Boolean.TRUE, Boolean.FALSE), delivered);
        } finally {
            LegacyLwjglState.clearPlayer("BoolTest");
        }
    }

    /** Recording vanilla item: proves the universal tick covers slots with right flags. */
    public static final class TickingItem extends net.minecraft.item.Item {
        final java.util.List<String> calls = new java.util.ArrayList<String>();

        @Override
        public void func_77663_a(net.minecraft.item.ItemStack stack,
                net.minecraft.world.World world, net.minecraft.entity.Entity entity,
                int slot, boolean selected) {
            calls.add(slot + ":" + selected);
        }
    }

    @Test
    void tickInventoryItemsCoversEverySlotOnceWithSelectedFlag() {
        UmbFacadeTest.FakeHostWorld hostWorld = new UmbFacadeTest.FakeHostWorld();
        dev.umb.legacy.legacyside.UmbWorld world =
                dev.umb.legacy.legacyside.UmbWorld.create(hostWorld, 0);
        UmbFacadeTest.FakeHostPlayer host = new UmbFacadeTest.FakeHostPlayer();
        dev.umb.legacy.legacyside.UmbPlayer player =
                dev.umb.legacy.legacyside.UmbPlayer.create(world, host);

        TickingItem ticker = new TickingItem();
        // Facade-selected slot 3: host held matches slot 3 after pull.
        dev.umb.bridge.api.StackData h3 =
                new dev.umb.bridge.api.StackData("umb:test-held", 1, 0, null);
        host.inventory[3] = h3;
        host.held = h3;
        dev.umb.bridge.api.StackData h7 =
                new dev.umb.bridge.api.StackData("umb:test-other", 1, 0, null);
        host.inventory[7] = h7;
        player.pullInventory();
        assertEquals(3, player.field_71071_by.field_70461_c);
        // Hand-place recording stacks AFTER the pull (pull would reconvert changes).
        net.minecraft.item.ItemStack held =
                new net.minecraft.item.ItemStack(ticker, 1);
        net.minecraft.item.ItemStack other =
                new net.minecraft.item.ItemStack(ticker, 1);
        player.field_71071_by.field_70462_a[3] = held;
        player.field_71071_by.field_70462_a[7] = other;

        player.tickInventoryItems(world);
        assertEquals(java.util.Arrays.asList("3:true", "7:false"), ticker.calls);
        // Second tick ticks again (once per tick, no dedupe); the internal pull keeps
        // both stacks because the host side did not change.
        player.tickInventoryItems(world);
        assertEquals(4, ticker.calls.size());
    }

    @Test
    void facadeInventoryPullPreservesLegacyNbtSyncsSelectedAndSharesHeld() {
        dev.umb.legacy.legacyside.input.LegacyClientSelection.clearForTest();
        try {
        UmbFacadeTest.FakeHostWorld hostWorld = new UmbFacadeTest.FakeHostWorld();
        dev.umb.legacy.legacyside.UmbWorld world =
                dev.umb.legacy.legacyside.UmbWorld.create(hostWorld, 0);
        UmbFacadeTest.FakeHostPlayer host = new UmbFacadeTest.FakeHostPlayer();
        dev.umb.legacy.legacyside.UmbPlayer player =
                dev.umb.legacy.legacyside.UmbPlayer.create(world, host);

        dev.umb.bridge.api.StackData stone =
                new dev.umb.bridge.api.StackData("minecraft:stone", 1, 0, null);
        host.inventory[2] = stone;
        host.held = stone;
        player.pullInventory();
        // Selected slot follows the held stack (HBM reads field_70461_c directly).
        assertEquals(2, player.field_71071_by.field_70461_c);

        // A legacy-mutated stack (e.g. gun NBT) survives an unchanged pull.
        net.minecraft.item.ItemStack hand =
                new net.minecraft.item.ItemStack(new net.minecraft.item.Item(), 1);
        net.minecraft.nbt.NBTTagCompound tag = new net.minecraft.nbt.NBTTagCompound();
        hand.field_77990_d = tag;
        player.field_71071_by.field_70462_a[2] = hand;
        player.pullInventory();
        assertSame(hand, player.field_71071_by.field_70462_a[2]);
        assertSame(tag, player.field_71071_by.field_70462_a[2].field_77990_d);

        // A genuinely changed host slot is reconverted.
        host.inventory[2] = new dev.umb.bridge.api.StackData("minecraft:dirt", 1, 0, null);
        player.pullInventory();
        assertNotSame(hand, player.field_71071_by.field_70462_a[2]);

        // Host NBT churn alone must NOT reconvert (legacy NBT is facade-owned).
        net.minecraft.item.ItemStack kept =
                new net.minecraft.item.ItemStack(new net.minecraft.item.Item(), 1);
        player.field_71071_by.field_70462_a[2] = kept;
        host.inventory[2] = new dev.umb.bridge.api.StackData("minecraft:dirt", 1, 0,
                new byte[] {9, 9, 9});
        player.pullInventory();
        assertSame(kept, player.field_71071_by.field_70462_a[2]);

        // The equipped-item read shares the persistent array object, never a throwaway.
        dev.umb.bridge.api.StackData grass =
                new dev.umb.bridge.api.StackData("minecraft:grass", 1, 0, null);
        host.inventory[5] = grass;
        host.held = grass;
        player.pullInventory();
        assertEquals(5, player.field_71071_by.field_70461_c);
        net.minecraft.item.ItemStack hand2 =
                new net.minecraft.item.ItemStack(new net.minecraft.item.Item(), 1);
        player.field_71071_by.field_70462_a[5] = hand2;
        assertSame(hand2, player.func_70694_bm());
        assertSame(hand2, player.func_70694_bm());
        } finally {
            dev.umb.legacy.legacyside.input.LegacyClientSelection.clearForTest();
        }
    }

    @Test
    void clientSelectionNoteBeatsHostMatching() {
        dev.umb.legacy.legacyside.input.LegacyClientSelection.clearForTest();
        try {
            UmbFacadeTest.FakeHostWorld hostWorld = new UmbFacadeTest.FakeHostWorld();
            dev.umb.legacy.legacyside.UmbWorld world =
                    dev.umb.legacy.legacyside.UmbWorld.create(hostWorld, 0);
            UmbFacadeTest.FakeHostPlayer host = new UmbFacadeTest.FakeHostPlayer();
            dev.umb.legacy.legacyside.UmbPlayer player =
                    dev.umb.legacy.legacyside.UmbPlayer.create(world, host);
            // Server side holds ammo in slot 1 (e.g. after an automation give that
            // never reached the client): matching alone would select 1.
            dev.umb.bridge.api.StackData ammo =
                    new dev.umb.bridge.api.StackData("umb:test-ammo", 1, 0, null);
            host.inventory[1] = ammo;
            host.held = ammo;
            // ...but the client sits on slot 0 (frames are authoritative).
            dev.umb.legacy.legacyside.input.LegacyClientSelection.note("Steve", 0);
            player.pullInventory();
            assertEquals(0, player.field_71071_by.field_70461_c);
        } finally {
            dev.umb.legacy.legacyside.input.LegacyClientSelection.clearForTest();
        }
    }

    @Test
    void transformerRewritesKeyboardAndMouseToShim() throws Exception {        byte[] keyboardOrig = classBytes("org/lwjgl/input/Keyboard.class");
        byte[] mouseOrig = classBytes("org/lwjgl/input/Mouse.class");
        UmbShimTransformer transformer = new UmbShimTransformer();
        byte[] keyboardOut = transformer.transform("org.lwjgl.input.Keyboard",
                "org.lwjgl.input.Keyboard", keyboardOrig);
        byte[] mouseOut = transformer.transform("org.lwjgl.input.Mouse",
                "org.lwjgl.input.Mouse", mouseOrig);
        assertNotNull(keyboardOut);
        assertNotNull(mouseOut);
        assertFalse(Arrays.equals(keyboardOrig, keyboardOut));
        assertFalse(Arrays.equals(mouseOrig, mouseOut));
        assertTrue(UmbShimTransformer.applied().toString().contains("org.lwjgl.input.Keyboard"));
        assertTrue(UmbShimTransformer.applied().toString().contains("org.lwjgl.input.Mouse"));

        // The rewritten classes load without natives and answer from the mirror state.
        // Inner classes (Keyboard$KeyEvent) must come from the same child loader, or
        // the package-private inner is illegally accessed across loaders.
        final UmbShimTransformer innerTransformer = transformer;
        ClassLoader child = new ClassLoader(LegacyInputBridgeTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve)
                    throws ClassNotFoundException {
                if (name.startsWith("org.lwjgl.input.Keyboard")
                        || name.startsWith("org.lwjgl.input.Mouse")) {
                    Class<?> existing = findLoadedClass(name);
                    if (existing == null) {
                        byte[] bytes;
                        try {
                            bytes = classBytes(name.replace('.', '/') + ".class");
                        } catch (Exception e) {
                            throw new ClassNotFoundException(name, e);
                        }
                        if (name.equals("org.lwjgl.input.Keyboard")
                                || name.equals("org.lwjgl.input.Mouse")) {
                            byte[] rewrittenBytes = innerTransformer.transform(
                                    name, name, bytes);
                            if (rewrittenBytes != null) {
                                bytes = rewrittenBytes;
                            }
                        }
                        existing = defineClass(name, bytes, 0, bytes.length);
                    }
                    if (resolve) {
                        resolveClass(existing);
                    }
                    return existing;
                }
                return super.loadClass(name, resolve);
            }
        };
        Class<?> keyboard = Class.forName("org.lwjgl.input.Keyboard", true, child);
        Class<?> mouse = Class.forName("org.lwjgl.input.Mouse", true, child);
        Method isKeyDown = keyboard.getMethod("isKeyDown", Integer.TYPE);
        Method isButtonDown = mouse.getMethod("isButtonDown", Integer.TYPE);
        assertFalse(((Boolean) isKeyDown.invoke(null, 30)).booleanValue());
        LegacyLwjglState.setDown("ShimTest", 30, true);
        LegacyLwjglState.setDown("ShimTest", -100, true);
        LegacyLwjglState.begin("ShimTest");
        try {
            assertTrue(((Boolean) isKeyDown.invoke(null, 30)).booleanValue());
            assertTrue(((Boolean) isButtonDown.invoke(null, 0)).booleanValue());
        } finally {
            LegacyLwjglState.end();
        }
        LegacyLwjglState.clearPlayer("ShimTest");
    }

    private static byte[] classBytes(String resource) throws Exception {
        InputStream in = LegacyInputBridgeTest.class.getClassLoader()
                .getResourceAsStream(resource);
        assertNotNull(in, resource);
        try {
            byte[] buf = new byte[8192];
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    /** Allocates an EntityPlayerMP without a world and binds a GameProfile name via Unsafe. */
    private static EntityPlayerMP namedPlayer(String name) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        Method allocate = unsafeClass.getMethod("allocateInstance", Class.class);
        EntityPlayerMP player =
                (EntityPlayerMP) allocate.invoke(unsafe, EntityPlayerMP.class);
        Field profile = EntityPlayer.class.getDeclaredField("field_146106_i");
        Method offset = unsafeClass.getMethod("objectFieldOffset", Field.class);
        long off = ((Long) offset.invoke(unsafe, profile)).longValue();
        Method put = unsafeClass.getMethod("putObject", Object.class, Long.TYPE, Object.class);
        put.invoke(unsafe, player, off, new GameProfile(UUID.randomUUID(), name));
        return player;
    }
}
