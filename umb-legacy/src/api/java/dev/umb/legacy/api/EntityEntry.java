package dev.umb.legacy.api;

/** One mod entity from the live {@code cpw.mods.fml.common.registry.EntityRegistry}. */
public final class EntityEntry {

    private final String modid;
    private final String name;
    private final String className;
    private final int modEntityId;
    private final int trackingRange;
    private final int updateFrequency;
    private final boolean sendsVelocityUpdates;

    public EntityEntry(String modid, String name, String className, int modEntityId,
                       int trackingRange, int updateFrequency, boolean sendsVelocityUpdates) {
        this.modid = modid;
        this.name = name;
        this.className = className;
        this.modEntityId = modEntityId;
        this.trackingRange = trackingRange;
        this.updateFrequency = updateFrequency;
        this.sendsVelocityUpdates = sendsVelocityUpdates;
    }

    public String modid() { return modid; }
    public String name() { return name; }
    public String className() { return className; }
    public int modEntityId() { return modEntityId; }
    public int trackingRange() { return trackingRange; }
    public int updateFrequency() { return updateFrequency; }
    public boolean sendsVelocityUpdates() { return sendsVelocityUpdates; }
}
