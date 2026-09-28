package dev.umb.hostagent.input;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.FriendlyByteBuf;
import java.util.List;

/** Called from patched 26.2 packet codec static initialisers; registration is generic and id-based. */
public final class LegacyPayloadRegistration {
    private LegacyPayloadRegistration() {}
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static List add(List list) {
        list.add(new CustomPacketPayload.TypeAndCodec(LegacyInputPayload.TYPE, LegacyInputPayload.CODEC));
        list.add(new CustomPacketPayload.TypeAndCodec(dev.umb.hostagent.effects.LegacyEffectsPayload.TYPE,
                dev.umb.hostagent.effects.LegacyEffectsPayload.CODEC));
        return list;
    }
}
