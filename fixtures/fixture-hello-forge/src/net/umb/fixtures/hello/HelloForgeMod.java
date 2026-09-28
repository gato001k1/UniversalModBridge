package net.umb.fixtures.hello;

import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal modern-Forge entrypoint fixture (spec §107 corpus, self-authored CC0-1.0).
 * Exercises exactly what a P01 analyzer must see: @Mod-annotated entry class,
 * META-INF/mods.toml metadata, slf4j logging. Deliberately touches no Minecraft
 * classes — same discipline as the Fabric twin, so the fixture stays valid as the
 * host version drifts.
 */
@Mod(HelloForgeMod.MOD_ID)
public class HelloForgeMod {
    public static final String MOD_ID = "umb-fixture-hello-forge";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public HelloForgeMod() {
        LOGGER.info("[umb-fixture] hello from modern forge fixture");
    }
}
