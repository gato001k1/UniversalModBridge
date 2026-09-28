package dev.umb.legacy.legacyside;

import java.lang.reflect.Field;
import java.util.ArrayList;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;

import dev.umb.bridge.api.LegacyBridge;
import dev.umb.bridge.api.StackData;
import dev.umb.legacy.legacyside.input.LegacyLwjglState;
import dev.umb.legacy.legacyside.network.LegacyNetworkLoopback;

/**
 * Headless proof for the selected option (b). This probe is intentionally MC-Heli-scoped and is
 * not production policy: the generic facade/runner/network seam above has no mod names.
 */
public final class McheliClientUniverseProbe {
    private McheliClientUniverseProbe() {
    }

    public static String run() throws Exception {
        final String playerId = "client-probe";
        // Boot first: ModClassLoader adds mod jars during CONSTRUCTING. This is why the probe
        // uses reflection for mod classes instead of linking them before LegacyBridge.boot().
        LegacyBridge bridge = new LegacyBridgeImpl();
        VehicleProbe.FakeHostLevel hostWorld = new VehicleProbe.FakeHostLevel();
        bridge.boot(hostWorld);

        final Object[] received = new Object[1];
        Class<?> packetClass = Class.forName("mcheli.helicopter.MCH_HeliPacketPlayerControl");
        LegacyNetworkLoopback.registerSimpleMessage(new IMessageHandler<IMessage, IMessage>() {
            @Override
            public IMessage onMessage(IMessage message, MessageContext context) {
                received[0] = message;
                return null;
            }
        }, (Class) packetClass, 0, Side.SERVER);

        Class<?> modClass = Class.forName("mcheli.MCH_MOD");
        Object config = field(modClass, "config").get(null);
        if (config == null) {
            throw new IllegalStateException("MCH_MOD.config is absent after common boot");
        }

        Class<?> infoClass = Class.forName("mcheli.helicopter.MCH_HeliInfo");
        Object info = infoClass.getConstructor(String.class).newInstance("client-probe");
        seedInfo(info);
        Class<?> heliClass = Class.forName("mcheli.helicopter.MCH_EntityHeli");
        Object heli = UmbUnsafe.allocate(heliClass);
        // setAcInfo() also builds render/collision part arrays and therefore assumes a constructed
        // entity. The probe only needs the control path's read-only info lookup; seed that field
        // directly, preserving the real method name/evidence without executing its setup side effects.
        set(heli, heliClass.getSuperclass(), "acInfo", info);
        Class<?> cameraClass = Class.forName("mcheli.MCH_Camera");
        set(heli, heliClass.getSuperclass(), "camera", UmbUnsafe.allocate(cameraClass));

        // Use the real server-side legacy twin as the source for the client facade. This keeps
        // the proof on the same path as live option (b): mounted state is mirrored from the
        // legacy player, not manufactured only on EntityClientPlayerMP.
        UmbWorld legacyWorld = UmbWorld.create(hostWorld, 0);
        UmbPlayer legacyPlayer = UmbPlayer.create(legacyWorld,
                new VehicleProbe.FakeHostPlayer(StackData.EMPTY));
        set(legacyPlayer, net.minecraft.entity.Entity.class, "field_70154_o", heli);
        LegacyClientFacade.setWorld(heli, legacyWorld);
        // Real interaction mounts the player through an MCH seat/helper entity. Prove the generic
        // host rider mirror follows that nested passenger chain before restoring the direct
        // mounted form used by the client-control assertion below.
        Class<?> seatClass = Class.forName("mcheli.aircraft.MCH_EntitySeat");
        Object seat = UmbUnsafe.allocate(seatClass);
        set(seat, net.minecraft.entity.Entity.class, "field_70153_n", legacyPlayer);
        set(heli, net.minecraft.entity.Entity.class, "field_70153_n", seat);
        set(legacyPlayer, net.minecraft.entity.Entity.class, "field_70154_o", seat);
        String nestedRider = new EntityHandleImpl((net.minecraft.entity.Entity) heli).riderName();
        if (!legacyPlayer.func_70005_c_().equals(nestedRider)) {
            throw new IllegalStateException("nested seat rider was not discovered: " + nestedRider);
        }
        set(heli, net.minecraft.entity.Entity.class, "field_70153_n", null);
        set(legacyPlayer, net.minecraft.entity.Entity.class, "field_70154_o", null);
        legacyPlayer.func_70078_a((net.minecraft.entity.Entity) heli);
        boolean realVanillaMount = legacyPlayer.field_70154_o == heli
                && ((net.minecraft.entity.Entity) heli).field_70153_n == legacyPlayer;
        if (!realVanillaMount) {
            throw new IllegalStateException("vanilla mountEntity facade did not establish both links");
        }
        String interactionGates = interactionGateSnapshot(heli, legacyPlayer);
        LegacyClientFacade.Binding client = LegacyClientFacade.install(legacyPlayer, legacyWorld);
        if (client.player.field_70154_o != heli) {
            throw new IllegalStateException("client facade did not mirror mounted legacy player");
        }
        boolean containersSeeded = field(legacyPlayer.getClass().getSuperclass(),
                "field_71069_bz").get(legacyPlayer) != null
                && field(legacyPlayer.getClass().getSuperclass(), "field_71070_bA").get(legacyPlayer) != null;
        if (field(client.player.getClass().getSuperclass(), "field_71071_by").get(client.player) == null) {
            Class<?> inventoryClass = Class.forName("net.minecraft.entity.player.InventoryPlayer");
            Object inventory = inventoryClass.getConstructor(
                    Class.forName("net.minecraft.entity.player.EntityPlayer")).newInstance(client.player);
            field(client.player.getClass().getSuperclass(), "field_71071_by").set(client.player, inventory);
        }

        Class<?> handlerClass = Class.forName("mcheli.helicopter.MCH_ClientHeliTickHandler");
        Object handler = handlerClass.getConstructor(
                Class.forName("net.minecraft.client.Minecraft"),
                Class.forName("mcheli.MCH_Config")).newInstance(client.minecraft, config);
        final Object[] directPacketResult = new Object[1];
        final boolean[] directActionResult = new boolean[1];
        final boolean[] genericPacketResult = new boolean[1];
        Object registeredState = "unavailable";
        Object registeredRiding = "unavailable";
        Object genericPacket = null;
        LegacyLwjglState.begin(playerId);
        Object keyUp = field(handler.getClass(), "KeyUp").get(handler);
        int runtimeKeyCode = ((Integer) field(keyUp.getClass(), "key").get(keyUp)).intValue();
        try {
            // MCH_Config bytecode/defaults ground KeyUp to LWJGL2 code 17 (mcheli-key-defaults.json).
            LegacyLwjglState.setDown(playerId, 17, true);
            updateKeys(handler);
            // Exercise the generic FML client/render event path as well as the bounded direct
            // control assertion. The dispatcher rebinds a fresh facade and mirrors this probe's
            // ridden entity into its thePlayer field.
            LegacyLwjglState.setDown(legacyPlayer.func_70005_c_(), 17, true);
            LegacyClientTickDispatcher.tick(legacyPlayer, legacyWorld);
            if (field(heli.getClass().getSuperclass(), "field_70170_p").get(heli) != legacyWorld) {
                throw new IllegalStateException("client entity world was not restored");
            }
            genericPacketResult[0] = received[0] != null;
            genericPacket = received[0];
            registeredState = registeredHeliKeyState();
            registeredRiding = registeredRidingState();
            Class<?> aircraftTickClass = Class.forName("mcheli.aircraft.MCH_AircraftClientTickHandler");
            Object directPacket = packetClass.newInstance();
            directPacketResult[0] = directPacket;
            java.lang.reflect.Method commonPlayerControl = aircraftTickClass.getDeclaredMethod(
                    "commonPlayerControl",
                    Class.forName("net.minecraft.entity.player.EntityPlayer"),
                    Class.forName("mcheli.aircraft.MCH_EntityAircraft"),
                    boolean.class, Class.forName("mcheli.aircraft.MCH_PacketPlayerControlBase"));
            commonPlayerControl.setAccessible(true);
            LegacyLwjglState.begin(legacyPlayer.func_70005_c_());
            boolean directAction;
            try {
                directAction = ((Boolean) commonPlayerControl.invoke(
                        handler, client.player, heli, Boolean.TRUE, directPacket)).booleanValue();
            } finally {
                LegacyLwjglState.end();
            }
            directActionResult[0] = directAction;
            LegacyClientTickRunner.invoke(handler, "playerControl", new Class<?>[] {
                    Class.forName("net.minecraft.entity.player.EntityPlayer"), heliClass, boolean.class
            }, client.player, heli, Boolean.FALSE);
        } finally {
            LegacyLwjglState.end();
            LegacyLwjglState.clearPlayer(playerId);
        }

        if (received[0] == null) {
            throw new IllegalStateException("client tick emitted no MCH_HeliPacketPlayerControl");
        }
        return "CLIENT-OK\n"
                + "facade=option-b Minecraft/thePlayer/theWorld/gameSettings\n"
                + "mountedLegacyPlayer=" + (legacyPlayer.field_70154_o == heli)
                + " mountedClientFacade=" + (client.player.field_70154_o == heli)
                + " realVanillaMount=" + realVanillaMount
                + " nestedSeatRiderName=" + nestedRider
                + " containersSeeded=" + containersSeeded + "\n"
                + "interactionGates=" + interactionGates + "\n"
                + "ticks=1 handler=" + handler.getClass().getName() + " controlMethod=playerControl\n"
                + "genericDispatches=" + LegacyClientTickDispatcher.dispatchCount()
                + " registeredClientSubscribers=" + LegacyClientTickDispatcher.registeredCount()
                + " clientListenerInvocations=" + LegacyClientTickDispatcher.lastTickInvocations()
                + " clientListenerFailures=" + LegacyClientTickDispatcher.lastTickFailures()
                + " genericPacket=" + genericPacketResult[0]
                + " registeredKeyState=" + registeredState
                + " registeredRiding=" + registeredRiding
                + " clientEntityWorldRestored="
                + (field(heli.getClass().getSuperclass(), "field_70170_p").get(heli) == legacyWorld)
                + "\n"
                + "mirroredKey=KeyUp lwjgl=17 runtimeKey=" + runtimeKeyCode
                + " keyDown=" + keyUp.getClass().getMethod("isKeyDown").invoke(keyUp)
                + " keyPress=" + keyUp.getClass().getMethod("isKeyPress").invoke(keyUp) + "\n"
                + "directCommon=action=" + directActionResult[0]
                + " throttleUp=" + valueForReport(directPacketResult[0], "throttleUp") + "\n"
                + "packet=" + received[0].getClass().getName()
                + " throttleUp=" + value(genericPacket, "throttleUp")
                + " throttleDown=" + value(genericPacket, "throttleDown")
                + " moveRight=" + value(genericPacket, "moveRight")
                + " moveLeft=" + value(genericPacket, "moveLeft")
                + " entityThrottleUp=" + value(heli, "throttleUp")
                + " entityThrottleDown=" + value(heli, "throttleDown") + "\n"
                + "route=LegacyNetworkLoopback -> SERVER handler";
    }

    private static void seedInfo(Object info) throws Exception {
        Class<?> c = info.getClass().getSuperclass();
        set(info, c, "seatList", new ArrayList());
        set(info, c, "weaponSetList", new ArrayList());
        set(info, c, "searchLights", new ArrayList());
        set(info, c, "cameraPosition", new ArrayList());
        set(info, c, "hudList", new ArrayList());
        set(info, c, "isEnableGunnerMode", Boolean.FALSE);
        set(info, c, "inventorySize", Integer.valueOf(0));
    }

    private static Object value(Object target, String name) throws Exception {
        return field(target.getClass(), name).get(target);
    }

    private static Object valueForReport(Object target, String name) throws Exception {
        return value(target, name);
    }

    private static void updateKeys(Object handler) throws Exception {
        Object[] keys = (Object[]) field(handler.getClass(), "Keys").get(handler);
        if (keys == null) {
            throw new IllegalStateException("MCH client handler did not build its Keys array");
        }
        for (Object key : keys) {
            if (key != null) {
                key.getClass().getMethod("update").invoke(key);
            }
        }
    }

    /** Probe-only visibility into the real registered handler; production dispatch remains generic. */
    private static Object registeredHeliKeyState() {
        try {
            Class<?> common = Class.forName("mcheli.MCH_ClientCommonTickHandler");
            Object root = field(common, "instance").get(null);
            Object[] ticks = (Object[]) field(common, "ticks").get(root);
            Object heliHandler = null;
            for (Object tick : ticks) {
                if (tick != null && tick.getClass().getName().contains("MCH_ClientHeliTickHandler")) {
                    heliHandler = tick;
                    break;
                }
            }
            if (heliHandler == null) return "missing";
            Object key = field(heliHandler.getClass(), "KeyUp").get(heliHandler);
            return "key=" + field(key.getClass(), "key").get(key)
                    + ",press=" + key.getClass().getMethod("isKeyPress").invoke(key)
                    + ",down=" + key.getClass().getMethod("isKeyDown").invoke(key)
                    + ",up=" + key.getClass().getMethod("isKeyUp").invoke(key);
        } catch (Throwable t) {
            return "error=" + t.getClass().getName();
        }
    }

    private static Object registeredRidingState() {
        try {
            Class<?> common = Class.forName("mcheli.MCH_ClientCommonTickHandler");
            Object root = field(common, "instance").get(null);
            Object minecraft = field(root.getClass().getSuperclass(), "mc").get(root);
            Object player = field(minecraft.getClass(), "field_71439_g").get(minecraft);
            Object riding = field(player.getClass().getSuperclass(), "field_70154_o").get(player);
            Object gui = field(minecraft.getClass(), "field_71462_r").get(minecraft);
            return player.getClass().getName() + "->"
                    + (riding == null ? "null" : riding.getClass().getName())
                    + ",gui=" + (gui == null ? "null" : gui.getClass().getName());
        } catch (Throwable t) {
            return "error=" + t.getClass().getName();
        }
    }

/** Legacy compatibility behavior. */
    private static String interactionGateSnapshot(Object vehicle, Object player) {
        return "isDestroyed=" + invokeGate(vehicle, "isDestroyed")
                + ",acInfo=" + valueGate(vehicle, "getAcInfo")
                + ",checkTeam=" + invokeGate(vehicle, "checkTeam", player)
                + ",sneaking=" + invokeGate(player, "func_70093_af")
                + ",canRide=" + valueGate(vehicle, "getAcInfo.canRide")
                + ",directPassenger=" + (safeValue(vehicle, "field_70153_n") != null)
                + ",isUAV=" + invokeGate(vehicle, "isUAV")
                + ",playerInSeat=" + (safeValue(player, "field_70154_o") != null
                        && safeValue(player, "field_70154_o").getClass().getName().contains("EntitySeat"))
                + ",canRideSeatOrRack=" + invokeGate(vehicle, "canRideSeatOrRack",
                        Integer.valueOf(0), player);
    }

    private static Object valueGate(Object target, String name) {
        if ("getAcInfo.canRide".equals(name)) {
            Object info = invokeGate(target, "getAcInfo");
            if (info == null || info instanceof String) return "null";
            Object field = safeValue(info, "canRide");
            return field == null ? invokeGate(info, "canRide") : field;
        }
        Object value = invokeGate(target, name);
        return value == null || value instanceof String ? "null" : "present";
    }

    private static Object invokeGate(Object target, String name, Object... args) {
        if (target == null) return "target-null";
        try {
            java.lang.reflect.Method found = null;
            for (Class<?> c = target.getClass(); c != null && found == null; c = c.getSuperclass()) {
                for (java.lang.reflect.Method candidate : c.getDeclaredMethods()) {
                    if (candidate.getName().equals(name)
                            && candidate.getParameterTypes().length == args.length) {
                        found = candidate;
                        break;
                    }
                }
            }
            if (found == null) return "absent";
            found.setAccessible(true);
            Object result = found.invoke(target, args);
            return result == null ? "null" : result;
        } catch (Throwable t) {
            Throwable cause = t.getCause() == null ? t : t.getCause();
            return "error:" + cause.getClass().getSimpleName();
        }
    }

    private static Object safeValue(Object target, String name) {
        try {
            return value(target, name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void set(Object target, Class<?> owner, String name, Object value) throws Exception {
        field(owner, name).set(target, value);
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Class<?> c = owner;
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(owner.getName() + "." + name);
    }
}
