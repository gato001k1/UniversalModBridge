package dev.umb.hostagent.effects;

import dev.umb.bridge.api.EffectData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/** Host-owned custom payload for typed effects that survived the legacy boundary. */
public record LegacyEffectsPayload(List<EffectData> effects) implements CustomPacketPayload {
    public static final Type<LegacyEffectsPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("umb", "legacy_effects"));
    public static final StreamCodec<FriendlyByteBuf, LegacyEffectsPayload> CODEC =
            StreamCodec.of(LegacyEffectsPayload::write, LegacyEffectsPayload::read);
    public LegacyEffectsPayload { effects = List.copyOf(effects == null ? List.of() : effects); }
    @Override public Type<LegacyEffectsPayload> type() { return TYPE; }

    private static void write(FriendlyByteBuf b, LegacyEffectsPayload p) {
        if (p.effects.size() > 256) throw new IllegalArgumentException("too many effects");
        b.writeVarInt(p.effects.size());
        for (EffectData e : p.effects) {
            b.writeUtf(e.kind, 32).writeUtf(e.playerId == null ? "" : e.playerId, 128)
                    .writeUtf(e.name == null ? "" : e.name, 512).writeLong(e.tick)
                    .writeDouble(e.x).writeDouble(e.y).writeDouble(e.z)
                    .writeDouble(e.vx).writeDouble(e.vy).writeDouble(e.vz)
                    .writeFloat(e.a).writeFloat(e.b).writeVarInt(e.timer).writeByteArray(e.payload());
        }
    }
    private static LegacyEffectsPayload read(FriendlyByteBuf b) {
        int n = b.readVarInt(); if (n < 0 || n > 256) throw new IllegalArgumentException("effect count");
        List<EffectData> out = new ArrayList<>();
        for (int i=0;i<n;i++) out.add(new EffectData(b.readUtf(32), b.readUtf(128), b.readUtf(512),
                b.readLong(), b.readDouble(), b.readDouble(), b.readDouble(), b.readDouble(), b.readDouble(),
                b.readDouble(), b.readFloat(), b.readFloat(), b.readVarInt(), b.readByteArray(1 << 20)));
        return new LegacyEffectsPayload(out);
    }
}
