package dev.umb.rendermap;

import dev.umb.rendermap.itemeffects.HeldItemStateExtractor;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class HeldItemStateExtractorTest {
    @Test
    void emitsNamedPartsAndSeparatesDynamicOps() throws Exception {
        Path dir = Files.createTempDirectory("held-item-state");
        Path map = dir.resolve("map.json");
        Path display = dir.resolve("display.json");
        Path out = dir.resolve("held-item-states.json");
        Files.writeString(map, "{\"items\":[{\"id\":\"demo:gun\",\"rendererClass\":\"demo.Renderer\",\"groups\":[\"Body\",\"Bolt\"],\"models\":[{\"path\":\"demo:models/gun.obj\"}],\"dynamic\":false}]}" );
        Files.writeString(display, "{\"renderers\":{\"demo.Renderer\":{\"firstperson_righthand\":[{\"op\":\"glTranslatef\",\"args\":[1,2,3],\"dynamic\":false},{\"op\":\"glRotatef\",\"dynamic\":true,\"note\":\"call:getInteger\"}]}}}");

        HeldItemStateExtractor.extract(dir.resolve("unused.jar"), map, display, out);
        String json = Files.readString(out);
        assertTrue(json.contains("\"Body\""));
        assertTrue(json.contains("\"Bolt\""));
        assertTrue(json.contains("\"partTransforms\""));
        assertTrue(json.contains("legacy_nbt:unknown"));
        assertTrue(json.contains("\"animations\": []"));
    }
}
