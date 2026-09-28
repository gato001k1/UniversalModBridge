package dev.umb.hostagent.effects;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import dev.umb.hostagent.AgentLog;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/** Bounded client loop seam; explicit stop packets and client-world loss both clear loops. */
final class LegacyClientLoopManager {
    private final Map<String, LegacyEffect.LoopStart> active = new LinkedHashMap<>();
    private Method soundId;
    private int cadence;

    void start(LegacyEffect.LoopStart effect) {
        if (active.size() >= 128 && !active.containsKey(key(effect))) {
            AgentLog.line("UMB-FX loop dropped reason=capacity");
            return;
        }
        active.put(key(effect), effect);
    }

    void stop(LegacyEffect.LoopStop effect) {
        active.remove(key(effect));
    }

    void update(LegacyEffect.LoopUpdate effect) {
        String key = key(effect);
        LegacyEffect.LoopStart previous = active.get(key);
        if (previous != null) {
            active.put(key, new LegacyEffect.LoopStart(effect.tick(), effect.playerId(),
                    effect.name(), effect.x(), effect.y(), effect.z(), effect.volume(), effect.pitch()));
        }
    }

    void clear() {
        active.clear();
    }

    void tick(Minecraft mc) {
        if (active.isEmpty() || mc.level == null) return;
        if (++cadence < 20) return;
        cadence = 0;
        for (LegacyEffect.LoopStart effect : active.values()) {
            try {
                Identifier id = soundId(effect.name());
                if (id == null) continue;
                mc.level.playLocalSound(effect.x(), effect.y(), effect.z(),
                        SoundEvent.createVariableRangeEvent(id), effect.playerId().isEmpty()
                                ? SoundSource.BLOCKS : SoundSource.PLAYERS,
                        effect.volume(), effect.pitch(), false);
            } catch (Throwable t) {
                AgentLog.error("LegacyClientLoopManager.tick", t, 1);
            }
        }
    }

    private Identifier soundId(String name) throws Exception {
        if (soundId == null) {
            soundId = Class.forName("dev.umb.hostagent.content.LegacyFx")
                    .getDeclaredMethod("soundId", String.class);
            soundId.setAccessible(true);
        }
        return (Identifier) soundId.invoke(null, name);
    }

    private static String key(LegacyEffect.LoopStart e) {
        return e.playerId() + "|" + e.name();
    }

    private static String key(LegacyEffect.LoopStop e) {
        return e.playerId() + "|" + e.name();
    }

    private static String key(LegacyEffect.LoopUpdate e) {
        return e.playerId() + "|" + e.name();
    }
}
