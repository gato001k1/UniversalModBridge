package dev.umb.legacy1122.legacyside;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/** Redirects 1.12.2 render calls to the data-only capture sink before native GL executes. */
public final class Legacy1122RenderTransformer implements IClassTransformer {
    private static final String GL="net/minecraft/client/renderer/GlStateManager";
    private static final String BB="net/minecraft/client/renderer/BufferBuilder";
    private static final String TESS="net/minecraft/client/renderer/Tessellator";
    private static final String TEXMAN="net/minecraft/client/renderer/texture/TextureManager";
    private static final String SINK="dev/umb/legacy1122/legacyside/Legacy1122RenderCapture";
    @Override public byte[] transform(String name,String transformedName,byte[] bytes){
        String target=transformedName==null?name:transformedName;
        if(bytes==null||target==null||(!target.contains("renderer")&&!target.contains("Renderer")
                &&!target.contains("Model")))return bytes;
        ClassReader cr=new ClassReader(bytes); ClassWriter cw=new ClassWriter(cr,ClassWriter.COMPUTE_FRAMES);
        cr.accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM5,cw){
            @Override public MethodVisitor visitMethod(int a,String n,String d,String s,String[] e){
                final MethodVisitor out=super.visitMethod(a,n,d,s,e);
                return new MethodVisitor(Opcodes.ASM5,out){
                    @Override public void visitMethodInsn(int op,String owner,String mn,String md,boolean itf){
                        if(owner.equals(GL)&&op==Opcodes.INVOKESTATIC){String[] shim=gl(mn,md);if(shim!=null){out.visitMethodInsn(Opcodes.INVOKESTATIC,SINK,shim[0],shim[1],false);return;}}
                        if(owner.equals(BB)&&op==Opcodes.INVOKEVIRTUAL){String[] shim=bb(mn,md);if(shim!=null){
                            out.visitMethodInsn(Opcodes.INVOKESTATIC,SINK,shim[0],shim[1],false);
                            if(Type.getReturnType(md).getSort()!=Type.VOID)out.visitTypeInsn(Opcodes.CHECKCAST,BB); return;}}
                        if(owner.equals(TESS)&&op==Opcodes.INVOKEVIRTUAL){String[] shim=tess(mn,md);if(shim!=null){out.visitInsn(Opcodes.POP);out.visitMethodInsn(Opcodes.INVOKESTATIC,SINK,shim[0],shim[1],false);return;}}
                        if(owner.equals(TEXMAN)&&op==Opcodes.INVOKEVIRTUAL){String[] shim=texman(mn,md);if(shim!=null){out.visitInsn(Opcodes.SWAP);out.visitInsn(Opcodes.POP);out.visitMethodInsn(Opcodes.INVOKESTATIC,SINK,shim[0],shim[1],false);return;}}
                        super.visitMethodInsn(op,owner,mn,md,itf);
                    }
                };}
        },0); return cw.toByteArray();
    }
    private static String[] gl(String n,String d){
        if(n.equals("func_179094_E")||n.equals("pushMatrix"))return new String[]{"push","()V"};
        if(n.equals("func_179121_F")||n.equals("popMatrix"))return new String[]{"pop","()V"};
        if(n.equals("enableCull"))return new String[]{"enableCull","()V"};
        if(n.equals("disableCull"))return new String[]{"disableCull","()V"};
        if(d.equals("(I)V"))return new String[]{"matrixOp","(I)V"};
        if(d.equals("(FFF)V"))return new String[]{"matrixOp","(FFF)V"};
        if(d.equals("(FFFF)V"))return new String[]{"matrixOp","(FFFF)V"};
        return null;
    }
    private static String[] tess(String n,String d){
        // Tessellator.draw() flushes the current vertex batch to GL. Inside a capture
        // session that flush becomes a mesh flush instead; outside one the sink no-ops.
        if((n.equals("draw")||n.equals("func_78381_a"))&&d.equals("()V"))return new String[]{"draw","()V"};
        return null;
    }
    private static String[] texman(String n,String d){
        // TextureManager.bindTexture would upload to real GL. Record the bound id so
        // draws carry their texture; outside a session this is a no-op.
        if((n.equals("bindTexture")||n.equals("func_110577_a"))
                &&d.equals("(Lnet/minecraft/util/ResourceLocation;)V"))return new String[]{"recordTexture","(Ljava/lang/Object;)V"};
        return null;
    }
    private static String[] bb(String n,String d){
        if(n.equals("begin")&&d.equals("(ILnet/minecraft/client/renderer/vertex/VertexFormat;)V"))return new String[]{"begin","(Ljava/lang/Object;ILjava/lang/Object;)V"};
        if((n.equals("func_181662_b")||n.equals("pos"))&&d.equals("(DDD)Lnet/minecraft/client/renderer/BufferBuilder;"))return new String[]{"pos","(Ljava/lang/Object;DDD)Ljava/lang/Object;"};
        if((n.equals("func_187315_a")||n.equals("tex"))&&d.equals("(DD)Lnet/minecraft/client/renderer/BufferBuilder;"))return new String[]{"tex","(Ljava/lang/Object;DD)Ljava/lang/Object;"};
        if((n.equals("func_181666_a")||n.equals("color"))&&d.equals("(FFFF)Lnet/minecraft/client/renderer/BufferBuilder;"))return new String[]{"color","(Ljava/lang/Object;FFFF)Ljava/lang/Object;"};
        if((n.equals("func_181663_c")||n.equals("normal"))&&d.equals("(FFF)Lnet/minecraft/client/renderer/BufferBuilder;"))return new String[]{"normal","(Ljava/lang/Object;FFF)Ljava/lang/Object;"};
        if((n.equals("func_181675_d")||n.equals("endVertex"))&&d.equals("()V"))return new String[]{"endVertex","(Ljava/lang/Object;)V"};
        return null;
    }
}
