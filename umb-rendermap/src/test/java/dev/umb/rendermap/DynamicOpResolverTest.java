package dev.umb.rendermap;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Moving-parts lane: {@link DynamicOpResolver} binds dynamic GL arguments to tile-entity
 * fields, using generated ASM bytecode (no mod jar needed).
 */
class DynamicOpResolverTest implements Opcodes {

    private static final String GL11 = "org/lwjgl/opengl/GL11";
    private static final String TE = "test/FakeTE";
    private static final String TESR = "test/FakeTESR";

    private static JarIndex indexWithTe() {
        JarIndex jar = new JarIndex();
        ClassNode te = new ClassNode();
        te.visit(V1_8, ACC_PUBLIC, TE, null, "net/minecraft/tileentity/TileEntity", null);
        te.visitEnd();
        jar.classes.put(TE, te);
        return jar;
    }

    private static ClassNode tesrWith(java.util.function.Consumer<MethodVisitor> body) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V1_8, ACC_PUBLIC, TESR, null, "java/lang/Object", null);
        // render(LFakeTE;DDDF)V : slots 1=te 2..3=x 4..5=y 6..7=z 8=partial
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "render",
                "(L" + TE + ";DDDF)V", null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitInsn(RETURN);
        mv.visitMaxs(16, 16);
        mv.visitEnd();
        cw.visitEnd();
        ClassNode cn = new ClassNode();
        new org.objectweb.asm.ClassReader(cw.toByteArray()).accept(cn, 0);
        return cn;
    }

    private static MethodNode renderMethod(ClassNode cn) {
        for (MethodNode mn : cn.methods) if ("render".equals(mn.name)) return mn;
        throw new IllegalStateException("no render method");
    }

    @Test
    void bindsTeParamPartialAndPosRolesByDescriptor() {
        JarIndex jar = indexWithTe();
        ClassNode cn = tesrWith(mv -> { });
        MethodNode mn = renderMethod(cn);
        assertEquals(0, DynamicOpResolver.teArg(mn, jar));
        assertEquals(4, DynamicOpResolver.partialArg(mn));
        Map<Integer, String> pos = DynamicOpResolver.posArgs(mn);
        assertEquals("pos0", pos.get(1));
        assertEquals("pos1", pos.get(2));
        assertEquals("pos2", pos.get(3));
    }

    @Test
    void resolvesInterpolatedAngleWithGroupAttribution() {
        JarIndex jar = indexWithTe();
        // angle = prev + (cur - prev) * partial; glRotatef(angle, 0,-1,0); renderPart("Dish")
        ClassNode cn = tesrWith(mv -> {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(GETFIELD, TE, "prev", "F");
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(GETFIELD, TE, "cur", "F");
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(GETFIELD, TE, "prev", "F");
            mv.visitInsn(FSUB);
            mv.visitVarInsn(FLOAD, 8);
            mv.visitInsn(FMUL);
            mv.visitInsn(FADD);
            mv.visitLdcInsn(0.0f);
            mv.visitLdcInsn(-1.0f);
            mv.visitLdcInsn(0.0f);
            mv.visitMethodInsn(INVOKESTATIC, GL11, "glRotatef", "(FFFF)V", false);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitLdcInsn("Dish");
            mv.visitMethodInsn(INVOKEVIRTUAL, "test/FakeModel", "renderPart", "(Ljava/lang/String;)V", false);
        });
        MethodNode mn = renderMethod(cn);
        DynamicOpResolver.Resolved r = DynamicOpResolver.resolve(cn, mn, jar, 0, 4,
                DynamicOpResolver.posArgs(mn));
        assertEquals("test.FakeTE", r.teClass);
        assertTrue(r.fields.containsKey("FakeTE.prev"));
        assertTrue(r.fields.containsKey("FakeTE.cur"));
        assertEquals(0, r.skipped.size());
        DynamicOpResolver.Op rotate = null;
        for (DynamicOpResolver.Op op : r.ops) {
            if ("glRotatef".equals(op.gl) && "Dish".equals(op.group)) rotate = op;
        }
        assertNotNull(rotate, "dish rotate op with group attribution");
        assertFalse(rotate.anchored);
        DynamicOpResolver.Arg angle = rotate.args.get(0);
        assertNotNull(angle.expr);
        assertTrue(angle.expr instanceof DynamicOpResolver.OpExpr);
        assertEquals("fadd", ((DynamicOpResolver.OpExpr) angle.expr).op);
        for (int i = 1; i < 4; i++) {
            DynamicOpResolver.Arg c = rotate.args.get(i);
            assertNotNull(c.expr);
            assertTrue(c.expr instanceof DynamicOpResolver.ConstExpr);
        }
    }

    @Test
    void unknownCallsAndWritesStayHonestSkips() {
        JarIndex jar = indexWithTe();
        ClassNode cn = tesrWith(mv -> {
            // tile.field = 1.0f (in-method write) then glRotatef(tile.field, 0,1,0)
            mv.visitVarInsn(ALOAD, 1);
            mv.visitLdcInsn(1.0f);
            mv.visitFieldInsn(PUTFIELD, TE, "w", "F");
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(GETFIELD, TE, "w", "F");
            mv.visitLdcInsn(0.0f);
            mv.visitLdcInsn(1.0f);
            mv.visitLdcInsn(0.0f);
            mv.visitMethodInsn(INVOKESTATIC, GL11, "glRotatef", "(FFFF)V", false);
            // glRotatef(engine.foo(), 0,1,0): unresolvable call arg
            mv.visitMethodInsn(INVOKESTATIC, "test/Engine", "foo", "()F", false);
            mv.visitLdcInsn(0.0f);
            mv.visitLdcInsn(1.0f);
            mv.visitLdcInsn(0.0f);
            mv.visitMethodInsn(INVOKESTATIC, GL11, "glRotatef", "(FFFF)V", false);
        });
        MethodNode mn = renderMethod(cn);
        DynamicOpResolver.Resolved r = DynamicOpResolver.resolve(cn, mn, jar, 0, 4,
                DynamicOpResolver.posArgs(mn));
        assertTrue(r.skipped.containsKey("field-written-in-method"));
        boolean hasCallSkip = false;
        for (String k : r.skipped.keySet()) if (k.startsWith("call:test/Engine.foo")) hasCallSkip = true;
        assertTrue(hasCallSkip, "engine call skip, got: " + r.skipped);
        assertTrue(r.fields.isEmpty());
    }

    @Test
    void noTeParamResolvesNothing() {
        JarIndex jar = indexWithTe();
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V1_8, ACC_PUBLIC, TESR, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "render", "(DDD)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(DLOAD, 1);
        mv.visitLdcInsn(0.0);
        mv.visitLdcInsn(0.0);
        mv.visitMethodInsn(INVOKESTATIC, GL11, "glTranslated", "(DDD)V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(16, 16);
        mv.visitEnd();
        cw.visitEnd();
        ClassNode cn = new ClassNode();
        new org.objectweb.asm.ClassReader(cw.toByteArray()).accept(cn, 0);
        MethodNode mn = renderMethod(cn);
        assertEquals(-1, DynamicOpResolver.teArg(mn, jar));
        DynamicOpResolver.Resolved r = DynamicOpResolver.resolve(cn, mn, jar, -1, -1,
                DynamicOpResolver.posArgs(mn));
        // Positional roles need no TE: all three translate args resolve as pos roles.
        assertEquals(3, r.resolvedArgs);
        assertTrue(r.fields.isEmpty());
        assertEquals(0, r.skipped.size());
    }

    @Test
    void pureCallWhitelistEvaluates() {        JarIndex jar = indexWithTe();
        ClassNode cn = tesrWith(mv -> {
            // glRotated(Math.toDegrees(tile.yaw), 0,1,0)
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(GETFIELD, TE, "yaw", "D");
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "toDegrees", "(D)D", false);
            mv.visitLdcInsn(0.0);
            mv.visitLdcInsn(1.0);
            mv.visitLdcInsn(0.0);
            mv.visitMethodInsn(INVOKESTATIC, GL11, "glRotated", "(DDDD)V", false);
        });
        MethodNode mn = renderMethod(cn);
        DynamicOpResolver.Resolved r = DynamicOpResolver.resolve(cn, mn, jar, 0, 4,
                DynamicOpResolver.posArgs(mn));
        DynamicOpResolver.Op rotate = null;
        for (DynamicOpResolver.Op op : r.ops) {
            if ("glRotated".equals(op.gl) && op.group == null) rotate = op;
        }
        assertNotNull(rotate);
        DynamicOpResolver.Arg angle = rotate.args.get(0);
        assertNotNull(angle.expr);
        assertTrue(angle.expr instanceof DynamicOpResolver.CallExpr);
        assertEquals("toDegrees", ((DynamicOpResolver.CallExpr) angle.expr).name);
        assertTrue(r.fields.containsKey("FakeTE.yaw"));
    }

    @Test
    void staticTrackArrayReadRegistersChannel() {
        JarIndex jar = indexWithTe();
        ClassNode cn = tesrWith(mv -> {
            // d = Track.eval("DOOR", te.anim)[1]; glTranslated(d, 0, 0)
            mv.visitLdcInsn("DOOR");
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(GETFIELD, TE, "anim", "Ltest/FakeAnim;");
            mv.visitMethodInsn(INVOKESTATIC, "test/Track", "eval",
                    "(Ljava/lang/String;Ltest/FakeAnim;)[D", false);
            mv.visitInsn(ICONST_1);
            mv.visitInsn(DALOAD);
            mv.visitLdcInsn(0.0);
            mv.visitLdcInsn(0.0);
            mv.visitMethodInsn(INVOKESTATIC, GL11, "glTranslated", "(DDD)V", false);
        });
        MethodNode mn = renderMethod(cn);
        DynamicOpResolver.Resolved r = DynamicOpResolver.resolve(cn, mn, jar, 0, 4,
                DynamicOpResolver.posArgs(mn));
        String key = "test.Track.eval.DOOR[1]";
        assertTrue(r.channels.containsKey(key), "channel registered, got: " + r.channels.keySet());
        DynamicOpResolver.Channel c = r.channels.get(key);
        assertEquals("test.Track", c.staticOwner);
        assertEquals("eval", c.staticMethod);
        assertEquals("DOOR", c.stringArg);
        assertEquals(List.of("anim"), c.animHops);
        DynamicOpResolver.Op translate = null;
        for (DynamicOpResolver.Op op : r.ops) {
            if ("glTranslated".equals(op.gl)) translate = op;
        }
        assertNotNull(translate);
        DynamicOpResolver.Arg slide = translate.args.get(0);
        assertNotNull(slide.expr);
        assertTrue(slide.expr instanceof DynamicOpResolver.ChannelRefExpr);
        assertEquals(key, ((DynamicOpResolver.ChannelRefExpr) slide.expr).key);
        assertFalse(((DynamicOpResolver.ChannelRefExpr) slide.expr).substituted);
    }

    @Test
    void poisonedLocalWithOneChannelSubstitutes() {        JarIndex jar = indexWithTe();
        ClassNode cn = tesrWith(mv -> {
            // double slide;
            // slide = -Track.eval("DOOR", te.anim)[1];  // store 1 (branch A)
            // slide = -Track.eval("DOOR", te.anim)[1];  // store 2 (branch B, same shape)
            // glTranslated(slide, 0, 0)
            for (int s = 0; s < 2; s++) {
                mv.visitLdcInsn("DOOR");
                mv.visitVarInsn(ALOAD, 1);
                mv.visitFieldInsn(GETFIELD, TE, "anim", "Ltest/FakeAnim;");
                mv.visitMethodInsn(INVOKESTATIC, "test/Track", "eval",
                        "(Ljava/lang/String;Ltest/FakeAnim;)[D", false);
                mv.visitInsn(ICONST_1);
                mv.visitInsn(DALOAD);
                mv.visitInsn(DNEG);
                mv.visitVarInsn(DSTORE, 9);
            }
            mv.visitVarInsn(DLOAD, 9);
            mv.visitLdcInsn(0.0);
            mv.visitLdcInsn(0.0);
            mv.visitMethodInsn(INVOKESTATIC, GL11, "glTranslated", "(DDD)V", false);
        });
        MethodNode mn = renderMethod(cn);
        DynamicOpResolver.Resolved r = DynamicOpResolver.resolve(cn, mn, jar, 0, 4,
                DynamicOpResolver.posArgs(mn));
        String key = "test.Track.eval.DOOR[1]";
        assertTrue(r.channels.containsKey(key), "channel registered, got: " + r.channels.keySet());
        DynamicOpResolver.Op translate = null;
        for (DynamicOpResolver.Op op : r.ops) {
            if ("glTranslated".equals(op.gl)) translate = op;
        }
        assertNotNull(translate);
        DynamicOpResolver.Arg slide = translate.args.get(0);
        assertNotNull(slide.expr, "poisoned slide local substitutes its single channel");
        assertTrue(slide.expr instanceof DynamicOpResolver.OpExpr);
        assertEquals("dneg", ((DynamicOpResolver.OpExpr) slide.expr).op);
        DynamicOpResolver.OpExpr neg = (DynamicOpResolver.OpExpr) slide.expr;
        assertEquals(1, neg.args.size());
        assertTrue(neg.args.get(0) instanceof DynamicOpResolver.ChannelRefExpr);
        assertEquals(key, ((DynamicOpResolver.ChannelRefExpr) neg.args.get(0)).key);
        assertTrue(((DynamicOpResolver.ChannelRefExpr) neg.args.get(0)).substituted);
    }

    private static ClassNode dispatchTesrWith(java.util.function.Consumer<MethodVisitor> body) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V1_8, ACC_PUBLIC, TESR, null, "java/lang/Object", null);
        // render(LFakeTE;Lbuf)V : 0=this 1=te 2=buf
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "render",
                "(L" + TE + ";Ljava/nio/DoubleBuffer;)V", null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitInsn(RETURN);
        mv.visitMaxs(16, 16);
        mv.visitEnd();
        cw.visitEnd();
        ClassNode cn = new ClassNode();
        new org.objectweb.asm.ClassReader(cw.toByteArray()).accept(cn, 0);
        return cn;
    }

    private static MethodNode dispatchMethod(ClassNode cn) {
        for (MethodNode mn : cn.methods) if ("render".equals(mn.name)) return mn;
        throw new IllegalStateException("no render method");
    }

    @Test
    void helperRenderDispatchResolvesToZeroArgCall() {
        JarIndex jar = indexWithTe();
        ClassNode cn = dispatchTesrWith(mv -> {
            // Helper h = te.getHelper(); h.render(te, buf);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, TE, "getHelper", "()Ltest/Helper;", false);
            mv.visitVarInsn(ASTORE, 9);
            mv.visitVarInsn(ALOAD, 9);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitMethodInsn(INVOKEINTERFACE, "test/Helper", "render",
                    "(L" + TE + ";Ljava/nio/DoubleBuffer;)V", true);
        });
        MethodNode mn = dispatchMethod(cn);
        DynamicOpResolver.Resolved r = DynamicOpResolver.resolve(cn, mn, jar, 0, -1,
                DynamicOpResolver.posArgs(mn));
        assertNotNull(r.dispatch, "helper dispatch recorded");
        assertEquals("test.FakeTE", r.dispatch.owner.replace('/', '.'));
        assertEquals("getHelper", r.dispatch.method);
        // Receiver is the tile itself: no hops.
        assertTrue(r.dispatch.objectHops.isEmpty());
    }

    @Test
    void helperDispatchThroughAccessorChain() {
        JarIndex jar = indexWithTe();
        ClassNode cn = dispatchTesrWith(mv -> {
            // Helper h = te.getDecl().getHelper(); h.render(te, buf);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, TE, "getDecl", "()Ltest/Decl;", false);
            mv.visitMethodInsn(INVOKEVIRTUAL, "test/Decl", "getHelper", "()Ltest/Helper;", false);
            mv.visitVarInsn(ASTORE, 9);
            mv.visitVarInsn(ALOAD, 9);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitMethodInsn(INVOKEINTERFACE, "test/Helper", "render",
                    "(L" + TE + ";Ljava/nio/DoubleBuffer;)V", true);
        });
        MethodNode mn = dispatchMethod(cn);
        DynamicOpResolver.Resolved r = DynamicOpResolver.resolve(cn, mn, jar, 0, -1,
                DynamicOpResolver.posArgs(mn));
        assertNotNull(r.dispatch, "chained helper dispatch recorded");
        assertEquals("test.Decl", r.dispatch.owner.replace('/', '.'));
        assertEquals("getHelper", r.dispatch.method);
        assertEquals(List.of("getDecl"), r.dispatch.objectHops);
        assertEquals(List.of("accessor"), r.dispatch.objectKinds);
    }

    @Test
    void noDispatchWithoutHelperCall() {
        JarIndex jar = indexWithTe();
        ClassNode cn = tesrWith(mv -> {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(GETFIELD, TE, "yaw", "D");
            mv.visitLdcInsn(0.0);
            mv.visitLdcInsn(1.0);
            mv.visitLdcInsn(0.0);
            mv.visitMethodInsn(INVOKESTATIC, GL11, "glRotated", "(DDDD)V", false);
        });
        MethodNode mn = renderMethod(cn);
        DynamicOpResolver.Resolved r = DynamicOpResolver.resolve(cn, mn, jar, 0, 4,
                DynamicOpResolver.posArgs(mn));
        assertNull(r.dispatch);
    }

    // ---- animation-recipe fixtures (synthetic TE + packet method + evaluator) ----

    private static final String ANIM_TE = "test/AnimTE";
    private static final String ANIM = "test/FakeAnim2";
    private static final String DECL = "test/Decl2";
    private static final String TRACKS = "test/Tracks2";
    private static final String CLOCK = "test/Clock2";

    private static JarIndex indexWithAnimTe() {
        JarIndex jar = new JarIndex();
        // FakeAnim2 { long start; }
        ClassNode anim = new ClassNode();
        anim.visit(V1_8, ACC_PUBLIC, ANIM, null, "java/lang/Object", null);
        anim.visitField(ACC_PUBLIC, "start", "J", null, null).visitEnd();
        anim.visitEnd();
        jar.classes.put(ANIM, anim);
        // AnimTE extends TileEntity { byte state; byte skin; FakeAnim2 anim; }
        ClassNode te = new ClassNode();
        te.visit(V1_8, ACC_PUBLIC, ANIM_TE, null, "net/minecraft/tileentity/TileEntity", null);
        te.visitField(ACC_PUBLIC, "state", "B", null, null).visitEnd();
        te.visitField(ACC_PUBLIC, "skin", "B", null, null).visitEnd();
        te.visitField(ACC_PUBLIC, "anim", "L" + ANIM + ";", null, null).visitEnd();
        // handlePacket(byte s): state = s; anim = Decl2.provide(s, skin);
        MethodNode hm = new MethodNode(ACC_PUBLIC, "handlePacket", "(B)V", null, null);
        hm.visitCode();
        hm.visitVarInsn(ALOAD, 0);
        hm.visitVarInsn(ILOAD, 1);
        hm.visitFieldInsn(PUTFIELD, ANIM_TE, "state", "B");
        hm.visitVarInsn(ALOAD, 0);
        hm.visitVarInsn(ALOAD, 0);
        hm.visitFieldInsn(GETFIELD, ANIM_TE, "decl", "L" + DECL + ";");
        hm.visitVarInsn(ILOAD, 1);
        hm.visitVarInsn(ALOAD, 0);
        hm.visitFieldInsn(GETFIELD, ANIM_TE, "skin", "B");
        hm.visitMethodInsn(INVOKEVIRTUAL, DECL, "provide", "(BB)L" + ANIM + ";", false);
        hm.visitFieldInsn(PUTFIELD, ANIM_TE, "anim", "L" + ANIM + ";");
        hm.visitInsn(RETURN);
        hm.visitMaxs(16, 16);
        hm.visitEnd();
        te.methods.add(hm);
        te.visitEnd();
        jar.classes.put(ANIM_TE, te);
        // Decl2 { FakeAnim2 provide(byte, byte) } — body irrelevant, seen only by name.
        ClassNode decl = new ClassNode();
        decl.visit(V1_8, ACC_PUBLIC, DECL, null, "java/lang/Object", null);
        decl.visitEnd();
        jar.classes.put(DECL, decl);
        return jar;
    }

    @Test
    void animRecipeProvenFromPacketAssigner() {
        JarIndex jar = indexWithAnimTe();
        // Evaluator Tracks2.eval(String, FakeAnim2)[D reading Clock2.tick() + anim.start.
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V1_8, ACC_PUBLIC, TRACKS, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "eval",
                "(Ljava/lang/String;L" + ANIM + ";)[D", null, null);
        mv.visitCode();
        mv.visitMethodInsn(INVOKESTATIC, CLOCK, "tick", "()J", false);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitFieldInsn(GETFIELD, ANIM, "start", "J");
        mv.visitInsn(LSUB);
        mv.visitInsn(POP2);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(16, 16);
        mv.visitEnd();
        cw.visitEnd();
        ClassNode eval = new ClassNode();
        new org.objectweb.asm.ClassReader(cw.toByteArray()).accept(eval, 0);
        jar.classes.put(TRACKS, eval);
        // NOTE: the AnimTE fixture above lacks the decl field; add it for the receiver.
        ClassNode te = jar.classes.get(ANIM_TE);
        te.visitField(ACC_PUBLIC, "decl", "L" + DECL + ";", null, null).visitEnd();

        DynamicOpResolver.AnimRecipe r = DynamicOpResolver.animRecipe(jar, ANIM_TE,
                List.of("anim"), TRACKS.replace('/', '.'), "eval");
        assertNotNull(r, "recipe proven");
        assertEquals(DECL.replace('/', '.'), r.providerOwner.replace('/', '.'));
        assertEquals("provide", r.providerMethod);
        assertEquals(List.of("decl"), r.receiverHops);
        assertEquals(2, r.argHops.size());
        assertEquals(List.of("state"), r.argHops.get(0));
        assertEquals(List.of("skin"), r.argHops.get(1));
        assertEquals(CLOCK.replace('/', '.'), r.clockOwner.replace('/', '.'));
        assertEquals("tick", r.clockMethod);
        assertEquals("start", r.clockField);
    }

    @Test
    void animRecipeAbsentWithoutProof() {
        JarIndex jar = indexWithTe();
        // Unknown field: no recipe.
        assertNull(DynamicOpResolver.animRecipe(jar, TE, List.of("nope"),
                "test.Track", "eval"));
    }

    @Test
    void tesrPrefixCenteringAndFacingSwitch() {
        JarIndex jar = indexWithTe();
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V1_8, ACC_PUBLIC, TESR, null, "java/lang/Object", null);
        // render(LFakeTE;DDDF)V : 0=this 1=te 2-3=x 4-5=y 6-7=z 8=partial
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "render",
                "(L" + TE + ";DDDF)V", null, null);
        mv.visitCode();
        // Center: translate(x+0.5, y, z+0.5).
        mv.visitVarInsn(DLOAD, 2);
        mv.visitLdcInsn(0.5);
        mv.visitInsn(DADD);
        mv.visitVarInsn(DLOAD, 4);
        mv.visitVarInsn(DLOAD, 6);
        mv.visitLdcInsn(0.5);
        mv.visitInsn(DADD);
        mv.visitMethodInsn(INVOKESTATIC, GL11, "glTranslated", "(DDD)V", false);
        // Facing: switch (te.getMeta() - 10) { case 2: rotate90; case 3: rotate180; }
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, TE, "getMeta", "()I", false);
        mv.visitIntInsn(BIPUSH, 10);
        mv.visitInsn(ISUB);
        Label dflt = new Label();
        Label c2 = new Label();
        Label c3 = new Label();
        mv.visitTableSwitchInsn(2, 3, dflt, c2, c3);
        mv.visitLabel(c2);
        mv.visitLdcInsn(90.0f);
        mv.visitLdcInsn(0.0f);
        mv.visitLdcInsn(1.0f);
        mv.visitLdcInsn(0.0f);
        mv.visitMethodInsn(INVOKESTATIC, GL11, "glRotatef", "(FFFF)V", false);
        mv.visitJumpInsn(GOTO, dflt);
        mv.visitLabel(c3);
        mv.visitLdcInsn(180.0f);
        mv.visitLdcInsn(0.0f);
        mv.visitLdcInsn(1.0f);
        mv.visitLdcInsn(0.0f);
        mv.visitMethodInsn(INVOKESTATIC, GL11, "glRotatef", "(FFFF)V", false);
        mv.visitJumpInsn(GOTO, dflt);
        mv.visitLabel(dflt);
        // Dispatch so the prefix scope engages.
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, TE, "getHelper", "()Ltest/Helper;", false);
        mv.visitVarInsn(ASTORE, 9);
        mv.visitVarInsn(ALOAD, 9);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitInsn(ACONST_NULL);
        mv.visitMethodInsn(INVOKEINTERFACE, "test/Helper", "render",
                "(L" + TE + ";Ljava/lang/Object;)V", true);
        mv.visitInsn(RETURN);
        mv.visitMaxs(16, 16);
        mv.visitEnd();
        cw.visitEnd();
        ClassNode cn = new ClassNode();
        new org.objectweb.asm.ClassReader(cw.toByteArray()).accept(cn, 0);
        MethodNode mn = renderMethod(cn);
        DynamicOpResolver.Resolved r = DynamicOpResolver.resolve(cn, mn, jar, 0, 4,
                DynamicOpResolver.posArgs(mn));
        assertNotNull(r.dispatch, "dispatch present so prefix scope engages");
        assertNotNull(r.prefix, "caller prefix proven");
        assertArrayEquals(new double[]{0.5, 0.0, 0.5}, r.prefix.translate, 1e-12);
        assertEquals(Integer.valueOf(10), r.prefix.metaBase);
        assertEquals(2, r.prefix.facing.size());
        assertArrayEquals(new double[]{90.0, 0.0, 1.0, 0.0}, r.prefix.facing.get(2), 1e-12);
        assertArrayEquals(new double[]{180.0, 0.0, 1.0, 0.0}, r.prefix.facing.get(3), 1e-12);
    }

    @Test
    void tesrPrefixAbsentWithoutDispatch() {
        JarIndex jar = indexWithTe();
        ClassNode cn = tesrWith(mv -> {
            mv.visitVarInsn(DLOAD, 1);
            mv.visitLdcInsn(0.0);
            mv.visitLdcInsn(0.0);
            mv.visitMethodInsn(INVOKESTATIC, GL11, "glTranslated", "(DDD)V", false);
        });
        MethodNode mn = renderMethod(cn);
        DynamicOpResolver.Resolved r = DynamicOpResolver.resolve(cn, mn, jar, 0, 4,
                DynamicOpResolver.posArgs(mn));
        assertNull(r.dispatch);
        assertNull(r.prefix);
    }
}
