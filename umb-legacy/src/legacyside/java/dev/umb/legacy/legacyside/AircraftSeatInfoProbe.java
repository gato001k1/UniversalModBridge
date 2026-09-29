package dev.umb.legacy.legacyside;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import dev.umb.bridge.api.LegacyBridge;

/**
 * fresh AH-1Z's gunner seat is placed with the PILOT's local offset instead of its own +0.79 -
 * exactly the symptom {@code MCH_EntityAircraft.updateSeatsPosition}'s fallback-to-{@code
 * seatsInfo[0]} branch produces (see that method's own trace in seats-live.md SS B) when {@code
 * seatsInfo.length < 2}. {@code seatsInfo} is built exactly once, lazily, by {@code
 * newSeatsPos(); ...}) from {@code getAcInfo().seatList} - so a too-short array can only mean
 * either (a) the REAL {@code ah-1z.txt}'s {@code AddGunnerSeat} line never reached {@code
 * MCH_HeliInfo.seatList} in the first place (a config-loading/asset bug), or (b) something calls
 * {@code getSeatsInfo()} before {@code seatList} finishes loading and the wrong (short) result
 * gets cached forever.
 *
 * <p>This probe isolates (a) from (b): it boots the SAME real, unmodified MCHeli mod (staged into
 * {@code gameDir/mods}, no different jar from the live game) through the SAME {@code
 * LegacyBridgeImpl.boot} mod-init pipeline the live game runs, then reads {@code
 * MCH_HeliInfoManager.get("ah-1z")}'s {@code seatList} DIRECTLY - before anything ever calls
 * {@code getSeatsInfo()}/{@code newSeatsPos()} on any entity. If {@code seatList} already has both
 * entries here, the config/asset loading is proven innocent and the bug is a live one-time-cache
 * timing issue (b); if it has only one, the config loading itself is the bug (a).</p>
 */
public final class AircraftSeatInfoProbe {
    private AircraftSeatInfoProbe() {
    }

    public static String run() throws Exception {
        LegacyBridge bridge = new LegacyBridgeImpl();
        VehicleProbe.FakeHostLevel hostWorld = new VehicleProbe.FakeHostLevel();
        bridge.boot(hostWorld);

        Class<?> modClass = Class.forName("mcheli.MCH_MOD");
        Object config = field(modClass, "config").get(null);
        if (config == null) {
            throw new IllegalStateException("MCH_MOD.config is absent after common boot");
        }

        Class<?> managerClass = Class.forName("mcheli.helicopter.MCH_HeliInfoManager");
        Method get = managerClass.getMethod("get", String.class);
        Object info = get.invoke(null, "ah-1z");
        if (info == null) {
            // Reports the manager's own key set so a naming mismatch is visible, not guessed.
            Field mapField = field(managerClass, "map");
            Object map = mapField.get(null);
            throw new IllegalStateException("MCH_HeliInfoManager.get(\"ah-1z\") returned null; keys="
                    + ((java.util.Map<?, ?>) map).keySet());
        }

        Class<?> aircraftInfoClass = Class.forName("mcheli.aircraft.MCH_AircraftInfo");
        @SuppressWarnings("unchecked")
        List<Object> seatList = (List<Object>) field(aircraftInfoClass, "seatList").get(info);
        int numSeatAndRack = (Integer) aircraftInfoClass.getMethod("getNumSeatAndRack").invoke(info);

        StringBuilder seats = new StringBuilder();
        Class<?> seatInfoClass = Class.forName("mcheli.aircraft.MCH_SeatInfo");
        Field posField = field(seatInfoClass, "pos");
        Field gunnerField = field(seatInfoClass, "gunner");
        Class<?> vec3Class = Class.forName("net.minecraft.util.Vec3");
        Field xField = field(vec3Class, "field_72450_a");
        Field yField = field(vec3Class, "field_72448_b");
        Field zField = field(vec3Class, "field_72449_c");
        for (int i = 0; i < seatList.size(); i++) {
            Object seatInfo = seatList.get(i);
            Object pos = posField.get(seatInfo);
            seats.append(" seat").append(i).append("=pos(")
                    .append(xField.getDouble(pos)).append(',')
                    .append(yField.getDouble(pos)).append(',')
                    .append(zField.getDouble(pos))
                    .append(")gunner=").append(gunnerField.getBoolean(seatInfo));
        }

        String reportPrefix = seatList.size() >= 2 ? "SEATINFO-OK" : "SEATINFO-SHORT";
        return reportPrefix + " seatListSize=" + seatList.size()
                + " getNumSeatAndRack=" + numSeatAndRack
                + seats
                + "\nfilePath=" + field(aircraftInfoClass, "filePath").get(info);
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
