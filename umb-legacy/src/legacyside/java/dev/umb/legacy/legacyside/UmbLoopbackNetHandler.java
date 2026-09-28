package dev.umb.legacy.legacyside;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.Packet;

import dev.umb.legacy.legacyside.input.LegacyInputDiag;

/** Minimal server connection facade for MP code that announces state through the player handler. */
final class UmbLoopbackNetHandler extends NetHandlerPlayServer {
    private UmbLoopbackNetHandler() {
        super(null, null, null);
    }

    static UmbLoopbackNetHandler create(EntityPlayerMP player) {
        UmbLoopbackNetHandler handler = UmbUnsafe.allocate(UmbLoopbackNetHandler.class);
        UmbUnsafe.setField(handler, UmbUnsafe.field(NetHandlerPlayServer.class, "field_147369_b"), player);
        return handler;
    }

    @Override
    public void func_147359_a(Packet packet) {
        if (packet != null && LegacyInputDiag.oncePer(
                "loopback-vanilla-packet:" + packet.getClass().getName(), 1_000_000_000L)) {
            LegacyInputDiag.log("loopback vanilla packet=" + packet.getClass().getName());
        }
    }

    @Override
    public void func_147364_a(double x, double y, double z, float yaw, float pitch) {
        // The host already owns player position; no network transport exists in this universe.
    }
}
