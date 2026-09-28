package dev.umb.hostagent.effects;

import dev.umb.bridge.api.EffectData;
import dev.umb.hostagent.AgentLog;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

import java.lang.reflect.Method;

/** Client packet/tick seam. Known typed effects replay through real 26.2 client APIs. */
public final class LegacyClientEffectsHook {
    private static final LegacyEffectsQueue QUEUE = new LegacyEffectsQueue();
    private static final LegacyClientLoopManager LOOPS = new LegacyClientLoopManager();
    private static Method soundId, particle, hostDelivered, hostDropped;
    private LegacyClientEffectsHook() {}
    public static void accept(Object payload) {
        if (payload instanceof LegacyEffectsPayload p) for (EffectData e : p.effects())
            if ("unknown".equals(e.kind)) QUEUE.offer(new LegacyEffect.Unknown(e.tick, e.playerId, e.name, e.payload()));
            else if ("sound".equals(e.kind)) QUEUE.offer(new LegacyEffect.Sound(e.tick,e.playerId,e.name,e.x,e.y,e.z,e.a,e.b));
            else if ("particle".equals(e.kind)) QUEUE.offer(new LegacyEffect.Particle(e.tick,e.playerId,e.name,e.x,e.y,e.z,e.vx,e.vy,e.vz));
            else if ("recoil".equals(e.kind)) QUEUE.offer(new LegacyEffect.Recoil(e.tick,e.playerId,e.a,e.b));
            else if ("animation".equals(e.kind)) QUEUE.offer(new LegacyEffect.Animation(e.tick,e.playerId,e.name,e.timer));
            else if ("held_nbt".equals(e.kind)) QUEUE.offer(new LegacyEffect.HeldNbt(e.tick,e.playerId,e.payload()));
            else if ("loop_start".equals(e.kind)) QUEUE.offer(new LegacyEffect.LoopStart(e.tick,e.playerId,e.name,e.x,e.y,e.z,e.a,e.b));
            else if ("loop_update".equals(e.kind)) QUEUE.offer(new LegacyEffect.LoopUpdate(e.tick,e.playerId,e.name,e.x,e.y,e.z,e.a,e.b));
            else if ("loop_stop".equals(e.kind)) QUEUE.offer(new LegacyEffect.LoopStop(e.tick,e.playerId,e.name,e.x,e.y,e.z));
            else QUEUE.offer(new LegacyEffect.Unknown(e.tick,e.playerId,e.kind,e.payload()));
    }
    public static void clientTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) { LOOPS.clear(); return; }
        new LegacyEffectsReplay(QUEUE).replay(new LegacyEffectSink() {
            public void sound(LegacyEffect.Sound e) { try {
                Identifier id = fxSound(e.name()); if (id == null) { fxDropped("playSound", "missing-mapping"); LegacyFxCount.unknown("sound",e.name()); return; }
                mc.level.playLocalSound(e.x(),e.y(),e.z(),SoundEvent.createVariableRangeEvent(id),SoundSource.PLAYERS,e.volume(),e.pitch(),false);
                fxDelivered("playSound", "client-native");
            } catch (Throwable t) { fxDropped("playSound", "client-exception"); LegacyFxCount.unknown("sound",e.name()); } }
            public void particle(LegacyEffect.Particle e) { try {
                ParticleOptions p = fxParticle(e.name()); if (p == null) { fxDropped("spawnParticle", "missing-mapping"); LegacyFxCount.unknown("particle",e.name()); return; }
                mc.level.addParticle(p,e.x(),e.y(),e.z(),e.vx(),e.vy(),e.vz());
                fxDelivered("spawnParticle", "client-native");
            } catch (Throwable t) { fxDropped("spawnParticle", "client-exception"); LegacyFxCount.unknown("particle",e.name()); } }
            public void recoil(LegacyEffect.Recoil e) { mc.player.turn(e.yaw(), e.pitch()); }
            public void animation(LegacyEffect.Animation e) { /* render lane consumes NBT/timer state */ }
            public void heldNbt(LegacyEffect.HeldNbt e) { /* render lane owns stack patch application */ }
            public void loopStart(LegacyEffect.LoopStart e) { LOOPS.start(e); }
            public void loopUpdate(LegacyEffect.LoopUpdate e) { LOOPS.update(e); }
            public void loopStop(LegacyEffect.LoopStop e) { LOOPS.stop(e); }
            public void unknown(LegacyEffect.Unknown e) { LegacyFxCount.unknown("packet",e.packetType()); }
        }, 256);
        LOOPS.tick(mc);
    }
    private static Identifier fxSound(String name) throws Exception { if (soundId == null) { soundId=Class.forName("dev.umb.hostagent.content.LegacyFx").getDeclaredMethod("soundId",String.class); soundId.setAccessible(true); } return (Identifier)soundId.invoke(null,name); }
    private static ParticleOptions fxParticle(String name) throws Exception { if (particle == null) { particle=Class.forName("dev.umb.hostagent.content.LegacyFx").getDeclaredMethod("particle",String.class); particle.setAccessible(true); } return (ParticleOptions)particle.invoke(null,name); }
    private static void fxDelivered(String kind, String reason) throws Exception { if (hostDelivered == null) { hostDelivered=fxMethod("hostDelivered"); } hostDelivered.invoke(null,kind,reason); }
    private static void fxDropped(String kind, String reason) { try { if (hostDropped == null) { hostDropped=fxMethod("hostDropped"); } hostDropped.invoke(null,kind,reason); } catch (Throwable ignored) {} }
    private static Method fxMethod(String name) throws Exception { Method m=Class.forName("dev.umb.hostagent.content.LegacyFx").getDeclaredMethod(name,String.class,String.class); m.setAccessible(true); return m; }
    static final class LegacyFxCount { static void unknown(String k,String n) { AgentLog.line("Legacy effect skipped and counted: "+k+":"+n); } }
}
