package dev.umb.legacy.legacyside;

import dev.umb.bridge.api.HostWorld;

/** Universal 1.7.10 playAuxSFX bridge; event ids are vanilla protocol ids, not mod ids. */
final class LegacyAuxSfx {
    private LegacyAuxSfx() {}

    static void dispatch(HostWorld host, int eventId, int x, int y, int z, int data) {
        if (host == null) return;
        switch (eventId) {
            case 1000: // click
            case 1001: // click2 / failed click
                host.playSound(x, y, z, "random.click", 1.0F, 1.0F);
                break;
            case 1002: // bow
                host.playSound(x, y, z, "random.bow", 1.0F, 1.0F);
                break;
            case 1003: // door
                host.playSound(x, y, z, "random.door_open", 1.0F, 1.0F);
                break;
            case 1004: // fizz
                host.playSound(x, y, z, "random.fizz", 1.0F, 1.0F);
                break;
            case 1005: // record ids are data-driven; retain the legacy name for pack resolution.
                host.playSound(x, y, z, "record." + data, 1.0F, 1.0F);
                break;
            case 2000: // smoke direction event
                host.spawnParticle("smoke", x + 0.5D, y + 0.5D, z + 0.5D, 0.0D, 0.0D, 0.0D);
                break;
            case 2001: // block break event; block metadata remains in data for future richer options.
                host.spawnParticle("explode", x + 0.5D, y + 0.5D, z + 0.5D, 0.0D, 0.0D, 0.0D);
                break;
            case 2002: // potion break
                host.spawnParticle("spell", x + 0.5D, y + 0.5D, z + 0.5D, 0.0D, 0.0D, 0.0D);
                break;
            case 2003: // ender eye / portal signal
                host.playSound(x, y, z, "portal.trigger", 1.0F, 1.0F);
                host.spawnParticle("portal", x + 0.5D, y + 0.5D, z + 0.5D, 0.0D, 0.0D, 0.0D);
                break;
            case 2004: // mob spell
                host.spawnParticle("mobSpell", x + 0.5D, y + 0.5D, z + 0.5D, 0.0D, 0.0D, 0.0D);
                break;
            case 2005: // bonemeal / grow effect
                host.spawnParticle("happyVillager", x + 0.5D, y + 0.5D, z + 0.5D, 0.0D, 0.0D, 0.0D);
                break;
            default:
                host.log("UMB-FX playAuxSFX unmapped event=" + eventId + " data=" + data);
        }
    }
}
