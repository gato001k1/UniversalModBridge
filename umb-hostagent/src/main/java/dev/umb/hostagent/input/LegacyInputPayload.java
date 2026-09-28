package dev.umb.hostagent.input;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.LinkedHashMap;
import java.util.Map;

/** 26.2 custom-payload wire form of {@link LegacyInputFrame}. */
public record LegacyInputPayload(LegacyInputFrame frame) implements CustomPacketPayload {
    public static final Type<LegacyInputPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("umb", "legacy_input"));
    public static final StreamCodec<FriendlyByteBuf, LegacyInputPayload> CODEC =
            StreamCodec.of(LegacyInputPayload::write, LegacyInputPayload::read);

    @Override public Type<LegacyInputPayload> type() { return TYPE; }

    private static void write(FriendlyByteBuf b, LegacyInputPayload p) {
        LegacyInputFrame f = p.frame();
        b.writeLong(f.tick()).writeUtf(f.playerId(), 128).writeUtf(f.heldItemId(), 256);
        b.writeVarInt(f.heldDamage()).writeVarInt(f.heldCount()).writeByteArray(f.heldNbt());
        b.writeBoolean(f.useDown()).writeBoolean(f.usePressed()).writeBoolean(f.attackDown())
                .writeBoolean(f.attackPressed()).writeBoolean(f.sneakDown());
        b.writeDouble(f.lookX()).writeDouble(f.lookY()).writeDouble(f.lookZ())
                .writeFloat(f.yaw()).writeFloat(f.pitch()).writeVarInt(f.selectedSlot());
        if (f.legacyKeys().size() > 256) throw new IllegalArgumentException("too many legacy keys");
        b.writeVarInt(f.legacyKeys().size());
        f.legacyKeys().forEach((name, down) -> b.writeUtf(name, 256).writeBoolean(down));
    }

    private static LegacyInputPayload read(FriendlyByteBuf b) {
        long tick = b.readLong(); String player = b.readUtf(128); String item = b.readUtf(256);
        int damage = b.readVarInt(), count = b.readVarInt(); byte[] nbt = b.readByteArray(1 << 20);
        boolean use = b.readBoolean(), useEdge = b.readBoolean(), attack = b.readBoolean();
        boolean attackEdge = b.readBoolean(), sneak = b.readBoolean();
        double x = b.readDouble(), y = b.readDouble(), z = b.readDouble();
        float yaw = b.readFloat(), pitch = b.readFloat(); int slot = b.readVarInt();
        int n = b.readVarInt(); if (n < 0 || n > 256) throw new IllegalArgumentException("key count");
        Map<String, Boolean> keys = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) keys.put(b.readUtf(256), b.readBoolean());
        return new LegacyInputPayload(new LegacyInputFrame(tick, player, item, damage, count, nbt,
                use, useEdge, attack, attackEdge, sneak, x, y, z, yaw, pitch, slot, keys));
    }
}
