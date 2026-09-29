package dev.umb.bridge.api;

/** Plain legacy-to-host client effect. Unknown packets use kind=unknown and remain countable. */
public final class EffectData {
    public final String kind, playerId, name; public final long tick; public final double x,y,z,vx,vy,vz;
    public final float a,b; public final int timer; public final byte[] payload;
    public EffectData(String kind, String playerId, String name, long tick, double x, double y, double z,
            double vx, double vy, double vz, float a, float b, int timer, byte[] payload) {
        this.kind=kind; this.playerId=playerId; this.name=name; this.tick=tick; this.x=x; this.y=y; this.z=z;
        this.vx=vx; this.vy=vy; this.vz=vz; this.a=a; this.b=b; this.timer=timer;
        this.payload=payload == null ? new byte[0] : payload.clone();
    }
    public byte[] payload() { return payload.clone(); }
}

// MIRROR of the canonical umb-bridge-api owned by Lane A (umb-legacy) -- do not hand-edit.
// Synced verbatim by tools/windows/build-hostagent.ps1 from
// umb-legacy/src/bridge-api/java/dev/umb/bridge/api/ on every build. If you need to change the
// boundary contract, change it there (and change BOTH sides together per DESIGN.md).
