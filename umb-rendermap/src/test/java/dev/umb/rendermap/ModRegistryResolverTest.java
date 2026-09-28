package dev.umb.rendermap;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves field -> registry-id attribution no longer depends on the holder class being named
 * {@code ModBlocks}/{@code ModItems} (or any other specific name), and no longer fabricates a
 * {@code hbm:} namespace. This is the fix for the coordinator's mid-task addendum: a second mod
 * (Iron Chests) keeps its block reference on its {@code @Mod} class directly and registers it
 * with a literal registry name rather than {@code getUnlocalizedName()} — both shapes are
 * exercised here with synthetic classes named nothing like HBM's own.
 */
class ModRegistryResolverTest implements Opcodes {

    private static final String T_BLOCK = "Lnet/minecraft/block/Block;";
    private static final String T_ITEM = "Lnet/minecraft/item/Item;";
    private static final String GR = "cpw/mods/fml/common/registry/GameRegistry";

    private static Snapshot snapshotWithLocalName(String localName, String fullId) {
        Snapshot snap = new Snapshot();
        snap.localNameToIds.computeIfAbsent(localName, k -> new java.util.ArrayList<>()).add(fullId);
        return snap;
    }

    @Test
    void holderClassNamedNothingLikeModItemsOrModBlocks_stillAttributes() {
        // com.example.Registry (NOT ModItems/ModBlocks) holds a static Item field, renamed via the
        // vanilla setUnlocalizedName builder chain — the shape ModRegistryResolver was built for,
        // but on a holder class under a completely different name/package.
        ClassNode holderInit = TestAsm.staticMethodClass("com/example/Registry", mv -> {
            mv.visitTypeInsn(NEW, "com/example/SomeItem");
            mv.visitInsn(DUP);
            mv.visitMethodInsn(INVOKESPECIAL, "com/example/SomeItem", "<init>", "()V", false);
            mv.visitLdcInsn("widget");
            mv.visitMethodInsn(INVOKEVIRTUAL, "com/example/SomeItem", "func_77655_b",
                    "(Ljava/lang/String;)Lnet/minecraft/item/Item;", false);
            mv.visitFieldInsn(PUTSTATIC, "com/example/Registry", "myItem", T_ITEM);
        });
        // add the static field declaration so ModRegistryResolver's field scan finds it
        holderInit.fields.add(new org.objectweb.asm.tree.FieldNode(ACC_PUBLIC | ACC_STATIC, "myItem", T_ITEM, null, null));

        JarIndex jar = TestAsm.jarOf(holderInit);
        ModRegistryResolver mods = new ModRegistryResolver(jar);
        mods.scanAll();
        RegistryScanner reg = new RegistryScanner(jar);
        reg.scanAll();
        Snapshot snap = snapshotWithLocalName("item.widget", "example:item.widget");
        mods.resolveIds(snap, reg);

        ModRegistryResolver.Entry e = mods.byField.get("com/example/Registry.myItem");
        assertNotNull(e);
        assertEquals("example:item.widget", e.id);
    }

    @Test
    void fieldOnAModStyleClassRegisteredWithALiteralName_resolvesViaRegistryScannerFallback() {
        // Mirrors Iron Chests exactly: the block reference lives on the mod's own main class
        // (not a separately-named holder), its name is never set via setBlockName/
        // setUnlocalizedName in the field's assignment expression (real Iron Chests sets it
        // inside the block's own constructor, invisible from here), and the registration call
        // passes an explicit literal name instead of thing.getUnlocalizedName().
        ClassNode exampleBlockCtor = TestAsm.bareClass("com/example/ExampleBlock",
                "net/minecraft/block/Block");
        ClassNode exampleMod = TestAsm.classWithMethod("com/example/ExampleMod", null, null,
                "preInit", "()V", ACC_PUBLIC | ACC_STATIC, mv -> {
                    mv.visitTypeInsn(NEW, "com/example/ExampleBlock");
                    mv.visitInsn(DUP);
                    mv.visitMethodInsn(INVOKESPECIAL, "com/example/ExampleBlock", "<init>", "()V", false);
                    mv.visitFieldInsn(PUTSTATIC, "com/example/ExampleMod", "exampleBlock",
                            "Lcom/example/ExampleBlock;");
                });
        exampleMod.fields.add(new org.objectweb.asm.tree.FieldNode(
                ACC_PUBLIC | ACC_STATIC, "exampleBlock", "Lcom/example/ExampleBlock;", null, null));
        // a second method performs the actual GameRegistry call with a literal name, 3-arg overload
        org.objectweb.asm.tree.MethodNode load = new org.objectweb.asm.tree.MethodNode(
                ACC_PUBLIC | ACC_STATIC, "load", "()V", null, null);
        exampleMod.methods.add(load);
        org.objectweb.asm.MethodVisitor mv = load;
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, "com/example/ExampleMod", "exampleBlock", "Lcom/example/ExampleBlock;");
        mv.visitLdcInsn(org.objectweb.asm.Type.getObjectType("net/minecraft/item/ItemBlock"));
        mv.visitLdcInsn("ExampleBlockName");
        mv.visitMethodInsn(INVOKESTATIC, GR, "registerBlock",
                "(Lnet/minecraft/block/Block;Ljava/lang/Class;Ljava/lang/String;)Lnet/minecraft/block/Block;",
                false);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        JarIndex jar = TestAsm.jarOf(exampleBlockCtor, exampleMod);
        ModRegistryResolver mods = new ModRegistryResolver(jar);
        mods.scanAll();
        RegistryScanner reg = new RegistryScanner(jar);
        reg.scanAll();

        // sanity: the builder-chain scan alone could not name this field (no setBlockName call)
        ModRegistryResolver.Entry preFallback = mods.byField.get("com/example/ExampleMod.exampleBlock");
        assertNotNull(preFallback);
        assertNull(preFallback.rawName, "no setBlockName/setUnlocalizedName call exists in this expression");

        Snapshot snap = snapshotWithLocalName("ExampleBlockName", "example:ExampleBlockName");
        mods.resolveIds(snap, reg);

        assertEquals("example:ExampleBlockName", preFallback.id,
                "the literal name RegistryScanner saw at the registration call site must be used"
                        + " as a fallback when the builder-chain scan found nothing");
    }

    @Test
    void constructorNamesItselfInternally_resolvesViaTheBoundedOneHopScan() {
        // com.example4.ExampleBlock4's own 0-arg constructor calls
        // this.setBlockName("myBlockLocalName") directly — mirrors Railcraft's real BlockCube
        // exactly (confirmed by direct bytecode inspection of the real jar: its <init> calls
        // this.func_149663_c("railcraft.cube")), the concrete case the mandate names to build
        // against. No chained call exists at the field's OWN construction expression (no
        // `.setBlockName(...)` right after `new ExampleBlock4()`), so only the bounded one-hop
        // scan into the class's own <init> can find the name.
        ClassNode exampleBlock = classNamingItselfInCtor("com/example4/ExampleBlock4",
                "net/minecraft/block/Block", "myBlockLocalName");
        ClassNode holder = TestAsm.staticMethodClass("com/example4/Registry4", mv -> {
            mv.visitTypeInsn(NEW, "com/example4/ExampleBlock4");
            mv.visitInsn(DUP);
            mv.visitMethodInsn(INVOKESPECIAL, "com/example4/ExampleBlock4", "<init>", "()V", false);
            mv.visitFieldInsn(PUTSTATIC, "com/example4/Registry4", "myBlock", "Lcom/example4/ExampleBlock4;");
        });
        holder.fields.add(new org.objectweb.asm.tree.FieldNode(
                ACC_PUBLIC | ACC_STATIC, "myBlock", "Lcom/example4/ExampleBlock4;", null, null));

        JarIndex jar = TestAsm.jarOf(exampleBlock, holder);
        ModRegistryResolver mods = new ModRegistryResolver(jar);
        mods.scanAll();
        RegistryScanner reg = new RegistryScanner(jar);
        reg.scanAll();

        // scanAll() already ran the bounded one-hop constructor scan (fill() tries it immediately
        // when the field's own construction expression chains no setBlockName/setUnlocalizedName
        // call) and found the name — proving the hop itself fired, before resolveIds() ever joins
        // it against a real snapshot id.
        ModRegistryResolver.Entry pre = mods.byField.get("com/example4/Registry4.myBlock");
        assertNotNull(pre);
        assertEquals("myBlockLocalName", pre.rawName,
                "only recoverable via the bounded one-hop scan of the class's own constructor");
        assertEquals(1, pre.hopsUsed);

        Snapshot snap = snapshotWithLocalName("tile.myBlockLocalName", "example4:tile.myBlockLocalName");
        mods.resolveIds(snap, reg);

        assertEquals("example4:tile.myBlockLocalName", pre.id);
        assertEquals(1, pre.hopsUsed, "the id was only recoverable via the bounded one-hop constructor scan");
    }

    /** A class whose OWN 0-arg {@code <init>} calls {@code this.func_149663_c(localName)} — the
     *  "class names itself internally" shape, built with raw ASM (not {@link TestAsm}, which
     *  always emits a trivial constructor) since the constructor body itself is what's under test. */
    private static ClassNode classNamingItselfInCtor(String name, String superName, String localName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V1_8, ACC_PUBLIC, name, null, superName, null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, superName, "<init>", "()V", false);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitLdcInsn(localName);
        mv.visitMethodInsn(INVOKEVIRTUAL, name, "func_149663_c",
                "(Ljava/lang/String;)Lnet/minecraft/block/Block;", false);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        ClassNode cn = new ClassNode();
        new ClassReader(cw.toByteArray()).accept(cn, 0);
        return cn;
    }

    @Test
    void neverFabricatesANamespace_unresolvedWhenSnapshotHasNoMatch() {
        ClassNode holder = TestAsm.staticMethodClass("com/example/Registry", mv -> {
            mv.visitTypeInsn(NEW, "com/example/SomeBlock");
            mv.visitInsn(DUP);
            mv.visitMethodInsn(INVOKESPECIAL, "com/example/SomeBlock", "<init>", "()V", false);
            mv.visitLdcInsn("lonely");
            mv.visitMethodInsn(INVOKEVIRTUAL, "com/example/SomeBlock", "func_149663_c",
                    "(Ljava/lang/String;)Lnet/minecraft/block/Block;", false);
            mv.visitFieldInsn(PUTSTATIC, "com/example/Registry", "myBlock", T_BLOCK);
        });
        holder.fields.add(new org.objectweb.asm.tree.FieldNode(ACC_PUBLIC | ACC_STATIC, "myBlock", T_BLOCK, null, null));

        JarIndex jar = TestAsm.jarOf(holder);
        ModRegistryResolver mods = new ModRegistryResolver(jar);
        mods.scanAll();
        RegistryScanner reg = new RegistryScanner(jar);
        reg.scanAll();
        Snapshot emptySnap = new Snapshot(); // no matching local name at all
        mods.resolveIds(emptySnap, reg);

        ModRegistryResolver.Entry e = mods.byField.get("com/example/Registry.myBlock");
        assertNull(e.id, "must stay honestly unresolved rather than guess any namespace");
        assertNotNull(e.unresolvedReason);
    }

    /**
     * Railcraft's real remaining item-attribution gap (mandate #1): its own registration wrapper
     * strips the mod's OWN self-tag segment out of the raw name before ever calling
     * {@code GameRegistry} — confirmed by direct bytecode inspection of
     * {@code mods.railcraft.common.plugins.forge.ItemRegistry.registerItem} (runs
     * {@code item.getUnlocalizedName()} through {@code MiscTools.cleanTag}, which removes any
     * {@code "railcraft."}-shaped segment via regex, then the vanilla {@code item.}/{@code tile.}
     * prefix). Neither "railcraft" nor that class/method is named anywhere in production code:
     * {@link ModRegistryResolver#dominantRawNamePrefix} derives the segment purely from this
     * mod's OWN already-captured raw names (four here, meeting {@code RAW_PREFIX_MIN_SAMPLES}),
     * and {@link ModRegistryResolver#resolveIds} tries the stripped form only as a lower-priority
     * fallback candidate once the ordinary vanilla-prefixed ones fail.
     */
    @Test
    void modOwnSelfTagIsDerivedFromRawNamesAndTriedAsAStrippedFallbackCandidate() {
        String[] names = {"dust", "ingot", "nugget", "circuit"};
        List<ClassNode> classes = new java.util.ArrayList<>();
        ClassNode holder = TestAsm.staticMethodClass("com/example5/Registry5", mv -> {
            for (int i = 0; i < names.length; i++) {
                mv.visitTypeInsn(NEW, "com/example5/Thing" + i);
                mv.visitInsn(DUP);
                mv.visitMethodInsn(INVOKESPECIAL, "com/example5/Thing" + i, "<init>", "()V", false);
                mv.visitLdcInsn("modtag." + names[i]);
                mv.visitMethodInsn(INVOKEVIRTUAL, "com/example5/Thing" + i, "func_77655_b",
                        "(Ljava/lang/String;)Lnet/minecraft/item/Item;", false);
                mv.visitFieldInsn(PUTSTATIC, "com/example5/Registry5", "thing" + i,
                        "Lcom/example5/Thing" + i + ";");
            }
        });
        for (int i = 0; i < names.length; i++) {
            classes.add(TestAsm.bareClass("com/example5/Thing" + i, "net/minecraft/item/Item"));
            holder.fields.add(new org.objectweb.asm.tree.FieldNode(ACC_PUBLIC | ACC_STATIC, "thing" + i,
                    "Lcom/example5/Thing" + i + ";", null, null));
        }
        classes.add(holder);

        JarIndex jar = TestAsm.jarOf(classes.toArray(new ClassNode[0]));
        ModRegistryResolver mods = new ModRegistryResolver(jar);
        mods.scanAll();
        RegistryScanner reg = new RegistryScanner(jar);
        reg.scanAll();

        // The real registered ids carry NEITHER the vanilla item./tile. prefix NOR the mod's own
        // "modtag." self-tag — only the stripped tail, exactly Railcraft's own item shape.
        Snapshot snap = new Snapshot();
        for (String n : names) snap.localNameToIds.computeIfAbsent(n, k -> new java.util.ArrayList<>())
                .add("example5:" + n);

        mods.resolveIds(snap, reg, null);

        for (int i = 0; i < names.length; i++) {
            ModRegistryResolver.Entry e = mods.byField.get("com/example5/Registry5.thing" + i);
            assertNotNull(e);
            assertEquals("example5:" + names[i], e.id,
                    "must resolve via the derived-and-stripped self-tag candidate, never a guessed namespace");
        }
    }
}
