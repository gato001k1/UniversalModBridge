package dev.umb.legacy1165.legacyside;
import java.io.ByteArrayOutputStream;
import java.util.Locale;
import dev.umb.bridge.api.EntityHandle;
import dev.umb.bridge.api.EntityRenderCapture;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;
/** EntityHandle over a real 1.16.5 legacy Entity, mirroring the 1.7.10 handle contract. */
final class EntityHandle1165 implements EntityHandle {
    private final Entity entity;
    private final String legacyId;
    private final String ownerNamespace;
    private volatile boolean poisoned;
    private volatile String poisonReason;
    EntityHandle1165(Entity entity) {
        this.entity = entity;
        this.legacyId = registryId(entity);
        this.ownerNamespace = ownerNamespace(entity, legacyId);
    }
    Entity raw() {
        return entity;
    }
    @Override
    public void tick() {
        if (poisoned || !isValid()) return;
        try {
            entity.func_70071_h_();
        } catch (Throwable t) {
            poisoned = true;
            poisonReason = String.valueOf(t);
            System.err.println("[UMB-ENTITY-1165] tick failed for " + entity.getClass().getName()
                    + ": " + t);
        }
    }
    @Override
    public byte[] saveNbt() {
        try {
            CompoundNBT tag = new CompoundNBT();
            if (!entity.func_70039_c(tag)) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            CompressedStreamTools.func_74799_a(tag, bos);
            return bos.toByteArray();
        } catch (Throwable t) {
            System.err.println("[UMB-ENTITY-1165] saveNbt failed for " + entity.getClass().getName()
                    + ": " + t);
            return null;
        }
    }
    @Override
    public boolean isValid() {
        return !poisoned && !entity.field_70128_L;
    }
    @Override public double getX() { return entity.func_226277_ct_(); }
    @Override public double getY() { return entity.func_226278_cu_(); }
    // func_226279_cv_ is the random-offset helper in 1.16.5, not the Z accessor.
    // The stable position Z member is func_226281_cx_; using the former put every host twin
    // at a random Z even though the legacy entity itself was spawned at the clicked position.
    @Override public double getZ() { return entity.func_226281_cx_(); }
    @Override public double getMotionX() { return entity.func_213322_ci().func_82615_a(); }
    @Override public double getMotionY() { return entity.func_213322_ci().func_82617_b(); }
    @Override public double getMotionZ() { return entity.func_213322_ci().func_82616_c(); }
    @Override public float getYaw() { return entity.field_70177_z; }
    @Override public float getPitch() { return entity.field_70125_A; }
    @Override
    public String legacyEntityId() {
        return legacyId;
    }
    @Override
    public String legacyEntityClassName() {
        return entity.getClass().getName();
    }
    @Override
    public String ownerNamespace() {
        return ownerNamespace;
    }
    @Override
    public EntityRenderCapture renderCapture(float partialTick) {
        return LegacyEntityCapture1165.capture(entity, legacyId, partialTick);
    }
    @Override
    public void hostRemoved() {
        try {
            entity.func_70106_y();
        } catch (Throwable ignored) {
            // Removal is best effort and must never cross the bridge boundary.
        }
    }
    String poisonReason() {
        return poisonReason;
    }
    private static String registryId(Entity entity) {
        try {
            ResourceLocation key = ForgeRegistries.ENTITIES.getKey(entity.func_200600_R());
            if (key != null) return key.toString();
        } catch (Throwable ignored) {
            // Fall back to the runtime class identity for diagnostic-only entities.
        }
        return entity == null ? "unknown" : entity.getClass().getName();
    }
    private static String ownerNamespace(Entity entity, String legacyId) {
        int colon = legacyId == null ? -1 : legacyId.indexOf(':');
        if (colon > 0) return legacyId.substring(0, colon);
        String name = entity == null ? "" : entity.getClass().getName().toLowerCase(Locale.ROOT);
        if (name.contains("alexsmobs")) return "alexsmobs";
        if (name.contains("immersiveengineering")) return "immersiveengineering";
        if (name.contains("torchmaster")) return "torchmaster";
        if (name.contains("ironchest")) return "ironchest";
        int dot = name.indexOf('.');
        return dot > 0 ? name.substring(0, dot) : null;
    }
}
