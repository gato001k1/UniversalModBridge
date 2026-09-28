package net.umb.fixtures.hello;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal modern-Fabric entrypoint fixture (spec §107 corpus, self-authored CC0-1.0).
 * Exercises exactly what a P01 analyzer must see: ModInitializer entrypoint,
 * slf4j logging, fabric.mod.json metadata. Deliberately touches no Minecraft classes.
 */
public class FabricHelloMod implements ModInitializer {
    public static final String MOD_ID = "umb-fixture-hello";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("[umb-fixture] hello from modern fabric fixture");
    }
}
