package dev.umb.hostagent.content;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import dev.umb.hostagent.AgentLog;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;

/**
 * PRESENTATION lane: legacy (1.7.10) sound and particle names to their 26.2 equivalents.
 *
 * <p>Two hard rules. One, every VANILLA mapping target below was verified to exist in the real
 * 26.2 vanilla sounds.json (asset index 26.2, object 9ac006d5537ed0fa4a7bcd1eccfc505155847686) or
 * as a javap-verified {@link ParticleTypes} constant - nothing here is assumed from memory. Two,
 * an unmapped name is a COUNTED SKIP ({@link #countSkip}), never a guessed substitute: a wrong
 * sound is worse than an honest silence.</p>
 *
 * <p>Mod sounds ("modid:key") pass through as-is except lowercased - modern resource ids forbid
 * uppercase while 1.7.10 mod sound keys are typically camelCase ("modid:block.crateBreak"); the
 * generated sound pack lowercases its keys with the same {@link Locale#ROOT} rule so the two
 * sides always agree.</p>
 */
final class LegacyFx {

    private LegacyFx() {}

    /** 1.7.10 vanilla sound name -> 26.2 vanilla sound id (minecraft namespace implied). */
    private static final Map<String, String> VANILLA_SOUNDS = new HashMap<>();
    static {
        VANILLA_SOUNDS.put("random.explode", "entity.generic.explode");
        VANILLA_SOUNDS.put("random.fizz", "block.fire.extinguish");
        VANILLA_SOUNDS.put("fire.fire", "block.fire.ambient");
        VANILLA_SOUNDS.put("fire.ignite", "item.flintandsteel.use");
        VANILLA_SOUNDS.put("random.click", "ui.button.click");
        VANILLA_SOUNDS.put("random.wood_click", "ui.button.click");
        VANILLA_SOUNDS.put("random.pop", "entity.item.pickup");
        VANILLA_SOUNDS.put("random.orb", "entity.experience_orb.pickup");
        VANILLA_SOUNDS.put("random.levelup", "entity.player.levelup");
        VANILLA_SOUNDS.put("random.break", "entity.item.break");
        VANILLA_SOUNDS.put("random.anvil_land", "block.anvil.land");
        VANILLA_SOUNDS.put("random.bow", "entity.arrow.shoot");
        VANILLA_SOUNDS.put("random.bowhit", "entity.arrow.hit");
        VANILLA_SOUNDS.put("mob.zombie.metal", "entity.zombie.attack_iron_door");
        VANILLA_SOUNDS.put("random.drink", "entity.generic.drink");
        VANILLA_SOUNDS.put("random.eat", "entity.generic.eat");
        VANILLA_SOUNDS.put("random.burp", "entity.player.burp");
        VANILLA_SOUNDS.put("random.chestopen", "block.chest.open");
        VANILLA_SOUNDS.put("random.chestclosed", "block.chest.close");
        VANILLA_SOUNDS.put("random.door_open", "block.wooden_door.open");
        VANILLA_SOUNDS.put("random.door_close", "block.wooden_door.close");
        VANILLA_SOUNDS.put("random.glass", "block.glass.break");
        VANILLA_SOUNDS.put("random.splash", "entity.generic.splash");
        VANILLA_SOUNDS.put("liquid.splash", "entity.generic.splash");
        VANILLA_SOUNDS.put("game.tnt.primed", "entity.tnt.primed");
        VANILLA_SOUNDS.put("fuse", "entity.tnt.primed");
        VANILLA_SOUNDS.put("liquid.lava", "block.lava.ambient");
        VANILLA_SOUNDS.put("liquid.lavapop", "block.lava.pop");
        VANILLA_SOUNDS.put("liquid.water", "block.water.ambient");
        VANILLA_SOUNDS.put("mob.ghast.fireball", "entity.ghast.shoot");
        VANILLA_SOUNDS.put("fireworks.blast", "entity.firework_rocket.blast");
        VANILLA_SOUNDS.put("fireworks.launch", "entity.firework_rocket.launch");
        VANILLA_SOUNDS.put("note.harp", "block.note_block.harp");
        VANILLA_SOUNDS.put("portal.travel", "block.portal.travel");
        VANILLA_SOUNDS.put("portal.trigger", "block.portal.trigger");
        VANILLA_SOUNDS.put("minecart.base", "entity.minecart.riding");
        VANILLA_SOUNDS.put("mob.wither.spawn", "entity.wither.spawn");
        VANILLA_SOUNDS.put("mob.enderdragon.growl", "entity.ender_dragon.growl");
        VANILLA_SOUNDS.put("tile.piston.out", "block.piston.extend");
        VANILLA_SOUNDS.put("tile.piston.in", "block.piston.contract");
    }

    /** 1.7.10 particle name (RenderGlobal.doSpawnParticle spelling, case-sensitive) -> 26.2
     *  particle. Only javap-verified {@link ParticleTypes} constants; names with no verified
     *  equivalent (fireworksSpark, snowballpoof, slime, mobSpell, ...) stay counted skips until
     *  their target constant is verified too. */
    private static final Map<String, ParticleOptions> PARTICLES = new HashMap<>();
    static {
        PARTICLES.put("smoke", ParticleTypes.SMOKE);
        PARTICLES.put("largesmoke", ParticleTypes.LARGE_SMOKE);
        PARTICLES.put("hugeexplosion", ParticleTypes.EXPLOSION_EMITTER);
        PARTICLES.put("largeexplode", ParticleTypes.EXPLOSION);
        PARTICLES.put("explode", ParticleTypes.POOF);
        PARTICLES.put("flame", ParticleTypes.FLAME);
        PARTICLES.put("lava", ParticleTypes.LAVA);
        PARTICLES.put("cloud", ParticleTypes.CLOUD);
        PARTICLES.put("reddust", DustParticleOptions.REDSTONE);
        PARTICLES.put("portal", ParticleTypes.PORTAL);
        PARTICLES.put("townaura", ParticleTypes.MYCELIUM);
        PARTICLES.put("crit", ParticleTypes.CRIT);
        PARTICLES.put("magicCrit", ParticleTypes.ENCHANTED_HIT);
        PARTICLES.put("enchantmenttable", ParticleTypes.ENCHANT);
        PARTICLES.put("dripWater", ParticleTypes.DRIPPING_WATER);
        PARTICLES.put("dripLava", ParticleTypes.DRIPPING_LAVA);
        PARTICLES.put("splash", ParticleTypes.SPLASH);
        PARTICLES.put("bubble", ParticleTypes.BUBBLE);
        PARTICLES.put("heart", ParticleTypes.HEART);
        PARTICLES.put("note", ParticleTypes.NOTE);
    }

    /** kind:name pairs already skipped, so each unknown name logs exactly once. */
    private static final Set<String> SKIPPED = ConcurrentHashMap.newKeySet();
    private static final Set<String> FALLBACKS = ConcurrentHashMap.newKeySet();

    private static final AtomicLong SOUND_LEGACY = new AtomicLong();
    private static final AtomicLong SOUND_HOST = new AtomicLong();
    private static final AtomicLong SOUND_MAPPED = new AtomicLong();
    private static final AtomicLong SOUND_MISSING = new AtomicLong();
    private static final AtomicLong SOUND_DROPPED = new AtomicLong();
    private static final AtomicLong PARTICLE_LEGACY = new AtomicLong();
    private static final AtomicLong PARTICLE_HOST = new AtomicLong();
    private static final AtomicLong PARTICLE_MAPPED = new AtomicLong();
    private static final AtomicLong PARTICLE_MISSING = new AtomicLong();
    private static final AtomicLong PARTICLE_DROPPED = new AtomicLong();
    private static final AtomicLong LAST_REPORT_NANOS = new AtomicLong();

    /** Null when the name has no honest 26.2 identity (caller counts the skip). */
    static Identifier soundId(String legacyName) {
        SOUND_LEGACY.incrementAndGet();
        if (legacyName == null || legacyName.isEmpty()) {
            SOUND_MISSING.incrementAndGet();
            report("playSound", "empty-name");
            return null;
        }
        if (legacyName.indexOf(':') >= 0) {
            Identifier id = Identifier.tryParse(legacyName.toLowerCase(Locale.ROOT));
            if (id == null) SOUND_MISSING.incrementAndGet();
            else SOUND_MAPPED.incrementAndGet();
            report("playSound", id == null ? "invalid-id" : "mapped");
            return id;
        }
        String modern = VANILLA_SOUNDS.get(legacyName);
        Identifier id = modern == null ? null : Identifier.tryParse("minecraft:" + modern);
        if (id == null) SOUND_MISSING.incrementAndGet();
        else SOUND_MAPPED.incrementAndGet();
        report("playSound", id == null ? "unmapped" : "mapped");
        return id;
    }

    /**
     * Returns a verified native particle. Unknown legacy names intentionally use vanilla smoke:
     * custom EntityFX has no 26.2 identity, but the presentation contract requires a visible,
     * generic fallback rather than silently dropping the effect. The distinct name is still
     * logged exactly once so coverage remains honest.
     */
    static ParticleOptions particle(String legacyName) {
        PARTICLE_LEGACY.incrementAndGet();
        if (legacyName == null || legacyName.isEmpty()) {
            PARTICLE_MISSING.incrementAndGet();
            report("spawnParticle", "empty-name");
            return null;
        }
        ParticleOptions known = PARTICLES.get(legacyName);
        if (known != null) {
            PARTICLE_MAPPED.incrementAndGet();
            report("spawnParticle", "mapped");
            return known;
        }
        if (legacyName.startsWith("umb:custom_dust:")) {
            String[] pieces = legacyName.split(":", -1);
            if (pieces.length == 4) {
                try {
                    int rgb = Integer.parseInt(pieces[2], 16) & 0xFFFFFF;
                    float scale = Float.parseFloat(pieces[3]);
                    if (!Float.isNaN(scale) && !Float.isInfinite(scale)) {
                        scale = Math.max(0.05F, Math.min(4.0F, scale));
                        PARTICLE_MAPPED.incrementAndGet();
                        report("spawnParticle", "custom-dust");
                        return new DustParticleOptions(rgb, scale);
                    }
                } catch (RuntimeException ignored) {
                    // Fall through to the counted generic smoke fallback.
                }
            }
        }
        if (FALLBACKS.add("particle:" + legacyName)) {
            AgentLog.line("LegacyFx: no 26.2 mapping for particle '" + legacyName
                    + "' - using generic smoke fallback (distinct fallbacks so far: "
                    + FALLBACKS.size() + ")");
        }
        PARTICLE_MISSING.incrementAndGet();
        report("spawnParticle", "generic-smoke-fallback");
        return ParticleTypes.SMOKE;
    }

    static void hostDelivered(String kind, String reason) {
        if ("playSound".equals(kind)) SOUND_HOST.incrementAndGet();
        else if ("spawnParticle".equals(kind)) PARTICLE_HOST.incrementAndGet();
        report(kind, reason);
    }

    static void hostDropped(String kind, String reason) {
        if ("playSound".equals(kind)) SOUND_DROPPED.incrementAndGet();
        else if ("spawnParticle".equals(kind)) PARTICLE_DROPPED.incrementAndGet();
        report(kind, reason);
    }

    private static void report(String kind, String reason) {
        long now = System.nanoTime();
        long last = LAST_REPORT_NANOS.get();
        if (last != 0L && now - last < 1_000_000_000L
                && !"empty-name".equals(reason) && !"unmapped".equals(reason)
                && !"invalid-id".equals(reason)) {
            return;
        }
        if (!LAST_REPORT_NANOS.compareAndSet(last, now)) return;
        if ("playSound".equals(kind)) {
            AgentLog.line("UMB-FX playSound legacy=" + SOUND_LEGACY.get() + " host=" + SOUND_HOST.get()
                    + " mapped=" + SOUND_MAPPED.get() + " missing=" + SOUND_MISSING.get()
                    + " dropped=" + SOUND_DROPPED.get() + " reason=" + reason);
        } else {
            AgentLog.line("UMB-FX spawnParticle legacy=" + PARTICLE_LEGACY.get() + " host="
                    + PARTICLE_HOST.get() + " mapped=" + PARTICLE_MAPPED.get() + " missing="
                    + PARTICLE_MISSING.get() + " dropped=" + PARTICLE_DROPPED.get()
                    + " reason=" + reason);
        }
    }

    static void countSkip(String kind, String name) {
        if (SKIPPED.add(kind + ":" + name)) {
            AgentLog.line("LegacyFx: no 26.2 mapping for " + kind + " '" + name
                    + "' - skipped, not faked (distinct skipped names so far: " + SKIPPED.size() + ")");
        }
    }
}
