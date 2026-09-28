package dev.umb.legacy.test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Writes a small *-input-plans.json fixture (namespace + keybindings only, no plans). */
final class LegacyInputTestPlans {
    static final String ALPHA_ID = "legacy:key:test.key.alpha:30:test.cat";
    static final String BETA_ID = "legacy:key:test.key.beta:-100:test.cat";

    static final String BOOL_ID = "legacy:key:test.key.bool:40:test.cat";

    private LegacyInputTestPlans() { }

    static Path writeTempPlans() throws Exception {
        Path dir = Files.createTempDirectory("umb-input-test");
        Path file = dir.resolve("testns-input-plans.json");
        String json = "{\n"
                + "  \"schema\": 1,\n"
                + "  \"sourceJar\": \"test-fixture.jar\",\n"
                + "  \"namespace\": \"testns\",\n"
                + "  \"keybindings\": [\n"
                + entry("dev/umb/legacy/test/LegacyInputTestKeys#alphaKey",
                        "test.key.alpha", 30, "test.cat", ALPHA_ID, true) + ",\n"
                + entry("dev/umb/legacy/test/LegacyInputTestKeys#betaMouse",
                        "test.key.beta", -100, "test.cat", BETA_ID, true) + ",\n"
                + entry("dev/umb/legacy/test/LegacyInputTestKeys#notABinding",
                        "test.key.str", 31, "test.cat",
                        "legacy:key:test.key.str:31:test.cat", true) + ",\n"
                + entry("dev/umb/legacy/test/NoSuchHolder#missing",
                        "test.key.ghost", 32, "test.cat",
                        "legacy:key:test.key.ghost:32:test.cat", true) + ",\n"
                + entry("dev/umb/legacy/test/LegacyInputTestKeys#alphaKey",
                        "test.key.off", 33, "test.cat",
                        "legacy:key:test.key.off:33:test.cat", false) + "\n"
                + "  ],\n"
                + "  \"plans\": []\n"
                + "}\n";
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static String entry(String field, String description, int keyCode,
            String category, String stableId, boolean resolved) {
        return "    {\"field\": \"" + field + "\", \"description\": \"" + description
                + "\", \"keyCode\": " + keyCode + ", \"category\": \"" + category
                + "\", \"stableId\": \"" + stableId + "\", \"resolved\": " + resolved + "}";
    }

    /** A plans file with one boolean-arg plan for a test-local message class. */
    static Path writeBoolPlans(String messageClass) throws Exception {
        Path dir = Files.createTempDirectory("umb-bool-test");
        Path file = dir.resolve("boolt-input-plans.json");
        String json = "{\n"
                + "  \"schema\": 1,\n"
                + "  \"sourceJar\": \"test-fixture.jar\",\n"
                + "  \"namespace\": \"boolt\",\n"
                + "  \"keybindings\": [\n"
                + entry("dev/umb/legacy/test/NoSuchHolder#bool",
                        "test.key.bool", 40, "test.cat", BOOL_ID, true) + "\n"
                + "  ],\n"
                + "  \"plans\": [\n"
                + "    {\"keybinding\": \"" + BOOL_ID + "\", \"trigger\": \"pressed\",\n"
                + "     \"messageClass\": \"" + messageClass + "\",\n"
                + "     \"messageConstructor\": \"(Z)V\",\n"
                + "     \"site\": \"test\",\n"
                + "     \"constructorArgs\": [{\"type\": \"boolean\","
                + " \"source\": \"input.pressedBoolean\"}],\n"
                + "     \"resolved\": true}\n"
                + "  ]\n"
                + "}\n";
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));
        return file;
    }
}
