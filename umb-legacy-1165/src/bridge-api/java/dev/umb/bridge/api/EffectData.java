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
