package dev.umb.rendermap;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.ClassNode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves {@link JarIndex#engine} lets hierarchy walks see through a vanilla intermediate
 * superclass the mod jar itself never bundles — the Iron Chests regression
 * ({@code BlockIronChest extends BlockContainer extends Block}, where {@code BlockContainer}
 * ships only in Minecraft's own jar). Also proves the walk correctly reports "not a match" when
 * no engine classpath is loaded and the chain genuinely dead-ends, i.e. this is a strict widening
 * of what resolves, never a source of false positives.
 */
class JarIndexTest {

    @Test
    void isSubclassOf_seesThroughAVanillaIntermediateOnlyPresentInTheEngineClasspath() {
        ClassNode myBlock = TestAsm.bareClass("com/example/MyBlock", "net/minecraft/block/BlockContainer");
        JarIndex jar = TestAsm.jarOf(myBlock);

        assertFalse(jar.isSubclassOf("com/example/MyBlock", "net/minecraft/block/Block"),
                "without an engine classpath the chain must honestly dead-end, not guess");

        ClassNode blockContainer = TestAsm.bareClass(
                "net/minecraft/block/BlockContainer", "net/minecraft/block/Block");
        jar.engine.put(blockContainer.name, blockContainer);

        assertTrue(jar.isSubclassOf("com/example/MyBlock", "net/minecraft/block/Block"),
                "once the engine classpath supplies BlockContainer, the walk must see through it");
    }

    @Test
    void engineClasspathNeverShadowsTheModsOwnClassOfTheSameName() {
        ClassNode modOwnVersion = TestAsm.bareClass("com/example/Thing", "net/minecraft/item/Item");
        JarIndex jar = TestAsm.jarOf(modOwnVersion);
        ClassNode engineDecoy = TestAsm.bareClass("com/example/Thing", "java/lang/Object");
        jar.engine.put(engineDecoy.name, engineDecoy);

        assertSame(modOwnVersion, jar.cls("com/example/Thing"), "the mod's own class must always win");
    }

    @Test
    void implementorsOfFollowsAnInterfaceDeclaredOnAnEngineSuperclass() {
        ClassNode engineBase = TestAsm.bareClass("vanilla/Base", null, "vanilla/SomeMarker");
        ClassNode sub = TestAsm.bareClass("com/example/Sub", "vanilla/Base");
        JarIndex jar = TestAsm.jarOf(sub);
        jar.engine.put(engineBase.name, engineBase);

        assertTrue(jar.implementorsOf("vanilla/SomeMarker").stream().anyMatch(c -> c.name.equals("com/example/Sub")));
    }
}
