package dev.umb.hostagent;

import dev.umb.hostagent.content.LegacyIds;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyIdsTest {

    @Test
    void junkCharactersBecomeUnderscores() {
        LegacyIds ids = new LegacyIds();
        // the real junk record in hbm-snapshot.json
        assertEquals("tile._undef", ids.pathFor("hbm:tile.#undef"));
        assertEquals(1, ids.renameCount());
        assertEquals("tile._undef", ids.renames().get("hbm:tile.#undef"));
    }

    @Test
    void uppercaseIsLowercased() {
        LegacyIds ids = new LegacyIds();
        assertEquals("tile.fatman", ids.pathFor("hbm:tile.fatMan"));
        assertEquals("item.chem_icon_yellowcake", ids.pathFor("hbm:item.chem_icon_YELLOWCAKE"));
    }

    @Test
    void legalCharactersSurviveUntouched() {
        LegacyIds ids = new LegacyIds();
        assertEquals("tile.block_c4", ids.pathFor("hbm:tile.block_c4"));
        assertEquals("rbmk/foo-bar.baz", ids.pathFor("hbm:rbmk/foo-bar.baz"));
        assertEquals(0, ids.renameCount());
    }

    @Test
    void duplicatesGetNumericSuffixes() {
        LegacyIds ids = new LegacyIds();
        assertEquals("a_b", ids.pathFor("hbm:a b"));
        assertEquals("a_b_2", ids.pathFor("hbm:a#b"));
        assertEquals("a_b_3", ids.pathFor("hbm:a+b"));
        // stable: the same legacy id keeps its first assignment
        assertEquals("a_b", ids.pathFor("hbm:a b"));
    }

    @Test
    void unusableIdsReturnNull() {
        LegacyIds ids = new LegacyIds();
        assertNull(ids.pathFor(null));
        assertNull(ids.pathFor(""));
        assertNull(ids.pathFor("hbm:"));
    }

    @Test
    void namespaceRejectsSlash() {
        assertEquals("hbm", LegacyIds.sanitizeNamespace("HBM"));
        assertEquals("a_b", LegacyIds.sanitizeNamespace("a/b"));
        assertTrue(LegacyIds.sanitizePath("a/b").equals("a/b"));
    }

    @Test
    void texturePathStripsNamespaceAndSanitises() {
        assertEquals("fatman", LegacyIds.texturePath("hbm:fatMan"));
        assertEquals("rbmk/rod", LegacyIds.texturePath("hbm:rbmk/rod"));
        assertEquals("stone", LegacyIds.texturePath("stone"));
    }

    // ---------------------------------------------------------- laneCasing: namespace generality

    /**
     * The Iron Chests case (GENERALITY-REPORT.md finding 8): 1.7.10 modids are free-form and mixed
     * case is common (IronChest, Railcraft, BiblioCraft, ...). The 26.2 namespace derived from one
     * must always be a valid, deterministic, lowercase {@code Identifier} namespace, no matter what
     * the raw legacy modid looked like.
     */
    @Test
    void mixedCaseModidsSanitizeToDeterministicLowercaseNamespaces() {
        assertEquals("ironchest", LegacyIds.sanitizeNamespace("IronChest"));
        assertEquals("biblio_craft", LegacyIds.sanitizeNamespace("Biblio Craft"));
        assertEquals("railcraft", LegacyIds.sanitizeNamespace("RAILCRAFT"));
        // idempotent: sanitizing an already-sanitized namespace is a no-op, so callers never need
        // to know whether the string they were handed is raw or already-sanitized.
        assertEquals("ironchest", LegacyIds.sanitizeNamespace(LegacyIds.sanitizeNamespace("IronChest")));
    }

    /**
     * Two legacy ids that differ ONLY by case must not silently collide into the same registry
     * path: the sanitizer must still detect the collision and disambiguate it deterministically
     * (numeric suffix), exactly like any other collision, and record BOTH renames loudly.
     */
    @Test
    void caseOnlyCollisionIsDetectedAndDisambiguatedDeterministically() {
        LegacyIds ids = new LegacyIds();
        assertEquals("tile.fatman", ids.pathFor("hbm:tile.FatMan"));
        // "hbm:tile.fatman" sanitizes to the SAME path as the entry above ("tile.fatman"), purely
        // from case-folding - must not overwrite it or be dropped silently.
        assertEquals("tile.fatman_2", ids.pathFor("hbm:tile.fatman"));
        assertEquals(2, ids.renameCount(), "both entries differ from their own raw path, so both are logged");
        assertEquals("tile.fatman", ids.renames().get("hbm:tile.FatMan"));
        assertEquals("tile.fatman_2", ids.renames().get("hbm:tile.fatman"));
        // stable and distinct: repeating either lookup returns its own first assignment, never the
        // other one's.
        assertEquals("tile.fatman", ids.pathFor("hbm:tile.FatMan"));
        assertEquals("tile.fatman_2", ids.pathFor("hbm:tile.fatman"));
    }
}
