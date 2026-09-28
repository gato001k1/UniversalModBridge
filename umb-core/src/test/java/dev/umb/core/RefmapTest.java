package dev.umb.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M8-2: refmap load + fallback. Synthetic JSON only (no third-party jars).
 */
class RefmapTest {

    private static final String SIMPLE = """
            {
              "mappings": {
                "com/example/mixin/TestMixin": {
                  "func_71410_x": "method_1234_foo",
                  "field_1234_bar": "field_4567_qux"
                }
              },
              "data": {
                "searge": {
                  "com/example/mixin/TestMixin": {
                    "func_71410_x": "srgMethod"
                  }
                }
              }
            }
            """;

    private static final String BARE_BLOCK = """
            {
              "notch": {
                "com/example/mixin/TestMixin": {
                  "func_71410_x": "notchMethod"
                }
              }
            }
            """;

    @Test
    void loadAndRemapViaMappingsAndDataContext() {
        Refmap r = Refmap.loadFromJson(SIMPLE);
        assertTrue(r.isPresent()); assertEquals(3, r.entryCount());
        assertEquals("method_1234_foo", r.remap("com/example/mixin/TestMixin", "func_71410_x"));
        assertEquals("srgMethod", r.remap("com/example/mixin/TestMixin", "func_71410_x", "searge"));
        // passthrough when no entry
        assertEquals("unknownRef", r.remap("com/example/mixin/TestMixin", "unknownRef"));
    }

    @Test
    void bareContextBlockIsIngested() {
        Refmap r = Refmap.loadFromJson(BARE_BLOCK);
        assertTrue(r.isPresent());
        assertEquals("notchMethod", r.remap("com/example/mixin/TestMixin", "func_71410_x", "notch"));
    }

    @Test
    void emptyAndMalformed() {
        Refmap empty = Refmap.loadFromJson("");
        assertTrue(empty.isAbsent());
        Refmap mal = Refmap.loadFromJson("not json at all {{{");
        assertTrue(mal.isMalformed()); assertNotNull(mal.error());
        // EMPTY passthrough
        assertEquals("foo", Refmap.EMPTY.remap("any/Cls", "foo"));
        assertEquals(0, Refmap.EMPTY.entryCount());
    }

    @Test
    void classRefFallbackSearch() {
        // When classRef not found, refmap searches all classRefs for the reference (passthrough helper)
        String json = """
                {
                  "mappings": {
                    "com/example/mixin/A": { "method_1": "remapped1" }
                  }
                }
                """;
        Refmap r = Refmap.loadFromJson(json);
        assertEquals("remapped1", r.remap(null, "method_1"));
        assertEquals("other", r.remap("com/example/mixin/B", "other"));
    }
}
