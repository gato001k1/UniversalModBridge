package dev.umb.legacy1165.legacyside;

import dev.umb.bridge.api.EntityRenderCapture;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Headless end-to-end entity capture: boots the REAL bridge (the same path the live era takes,
 * dist from {@code -Dumb.1165.dist}), creates one entity of the registry id named by
 * {@code -Dumb.1165.probe.entity} in the bridge's own world exactly like a spawn egg does,
 * wraps it in the live {@link EntityHandle1165} and runs its render capture. Universal: the
 * entity id is an input, no mod is named here. Called reflectively from inside the booted
 * {@code Legacy1165Loader}.
 */
public final class EntityCaptureProbe1165 {

    /** 30 s of game time: random AI/animation paths (e.g. an idle animation start) get a chance to run. */
    static final int TICKS = 600;

    private EntityCaptureProbe1165() {
    }

    public static String run() throws Exception {
        String id = System.getProperty("umb.1165.probe.entity", "").trim();
        if (id.isEmpty()) throw new IllegalStateException("missing -Dumb.1165.probe.entity");
        Legacy1165BridgeImpl bridge = new Legacy1165BridgeImpl();
        M1165Probe.FakeHostWorld host = new M1165Probe.FakeHostWorld();
        bridge.boot(host);
        // Flat stone ground under the probe entity (host-owned blocks, like live terrain): a mob
        // that sees only air cannot stand, path or walk.
        for (int x = 84; x <= 116; x++) {
            for (int z = 84; z <= 116; z++) host.setBlock(x, 63, z, "minecraft:stone", 0, 3);
        }
        EntityType<?> type = ForgeRegistries.ENTITIES.getValue(new ResourceLocation(id));
        if (type == null) throw new IllegalStateException("no entity type " + id);
        Entity entity = type.func_200721_a(bridge.umbWorld());
        if (entity == null) throw new IllegalStateException(id + " create() returned null");
        entity.func_70012_b(100.5D, 64.0D, 100.5D, 0.0F, 0.0F);
        bridge.umbWorld().func_217376_c(entity);
        EntityHandle1165 handle = new EntityHandle1165(entity);
        EntityRenderCapture capture = handle.renderCapture(0.0F);
        StringBuilder report = new StringBuilder("ENTITY-CAPTURE-PROBE\n");
        report.append("entity=").append(entity.getClass().getName()).append('\n');
        report.append("stateKey=").append(capture.stateKey).append('\n');
        report.append("draws=").append(capture.draws.size()).append('\n');
        report.append("vertices=").append(capture.vertexCount()).append('\n');
        for (int i = 0; i < capture.draws.size() && i < 8; i++) {
            report.append("draw").append(i).append(" texture=").append(capture.draws.get(i).texture)
                    .append(" verts=").append(capture.draws.get(i).vertexCount).append('\n');
        }
        // Captures must not depend on where the entity stands: one near the world origin sits
        // inside vanilla's name-tag range of an unpositioned camera (live: those captured 0).
        Entity nearOrigin = type.func_200721_a(bridge.umbWorld());
        nearOrigin.func_70012_b(0.5D, 64.0D, 0.5D, 0.0F, 0.0F);
        bridge.umbWorld().func_217376_c(nearOrigin);
        EntityRenderCapture originCapture = new EntityHandle1165(nearOrigin).renderCapture(0.0F);
        report.append("nearOriginVertices=").append(originCapture.vertexCount())
                .append(" nearOriginStateKey=").append(originCapture.stateKey).append('\n');
        // The same handle the host ticks every server tick: a first-tick throw poisons it
        // and freezes the twin, so the probe ticks it like the host does.
        // Live, a player stands near the mob (it looks at them); mirror that: vanilla idles
        // (no wandering) once no player has been within 32 blocks for 100 ticks.
        bridge.umbPlayer().func_70107_b(100.5D, 64.0D, 105.5D);
        // Seeded: the wander goal starts with a per-tick random chance; a fixed seed keeps the
        // movement assertion deterministic.
        if (entity instanceof net.minecraft.entity.LivingEntity) {
            ((net.minecraft.entity.LivingEntity) entity).func_70681_au().setSeed(1165L);
        }
        double startX = entity.func_226277_ct_(), startZ = entity.func_226281_cx_();
        double maxMoved = 0.0D;
        for (int i = 0; i < TICKS; i++) {
            handle.tick();
            double dx = entity.func_226277_ct_() - startX, dz = entity.func_226281_cx_() - startZ;
            maxMoved = Math.max(maxMoved, Math.sqrt(dx * dx + dz * dz));
        }
        report.append("start=").append(startX).append(",64.0,").append(startZ)
                .append(" end=").append(entity.func_226277_ct_()).append(',').append(entity.func_226278_cu_())
                .append(',').append(entity.func_226281_cx_()).append('\n');
        report.append("maxMoved=").append(maxMoved).append(" onGround=").append(entity.func_233570_aj_())
                .append('\n');
        if (entity instanceof net.minecraft.entity.MobEntity) {
            net.minecraft.entity.MobEntity mob = (net.minecraft.entity.MobEntity) entity;
            net.minecraft.entity.player.PlayerEntity closest =
                    bridge.umbWorld().func_217362_a(entity, -1.0D);
            StringBuilder goals = new StringBuilder();
            java.util.Iterator<net.minecraft.entity.ai.goal.PrioritizedGoal> it =
                    mob.field_70714_bg.func_220888_c().iterator();
            while (it.hasNext()) {
                net.minecraft.entity.ai.goal.PrioritizedGoal pg = it.next();
                goals.append(pg.func_220772_j().getClass().getSimpleName())
                        .append(pg.func_220773_g() ? "*" : "").append(' ');
            }
            report.append("idleTime=").append(mob.func_70654_ax())
                    .append(" closestPlayer=").append(closest == null ? "none"
                            : String.valueOf(Math.sqrt(closest.func_70068_e(entity))))
                    .append(" goals=").append(goals).append('\n');
        }
        if (entity instanceof net.minecraft.entity.MobEntity) {
            net.minecraft.pathfinding.PathNavigator nav =
                    ((net.minecraft.entity.MobEntity) entity).func_70661_as();
            report.append("navigator=").append(nav == null ? "null" : nav.getClass().getName())
                    .append(" noPath=").append(nav == null || nav.func_75500_f()).append('\n');
        }
        String poison = handle.poisonReason();
        report.append("ticks=").append(TICKS).append(" poisoned=").append(poison != null).append('\n');
        // Mod code broadcasts through the current server's player list from entity ticks
        // (random, e.g. when an animation starts): it must be real, and empty in this universe.
        net.minecraft.server.MinecraftServer server =
                net.minecraftforge.fml.server.ServerLifecycleHooks.getCurrentServer();
        net.minecraft.server.management.PlayerList players =
                server == null ? null : server.func_184103_al();
        report.append("serverPlayers=")
                .append(players == null ? "null" : String.valueOf(players.func_181057_v().size()))
                .append('\n');
        if (poison != null) report.append("poisonReason=").append(poison).append('\n');
        return report.toString();
    }
}
