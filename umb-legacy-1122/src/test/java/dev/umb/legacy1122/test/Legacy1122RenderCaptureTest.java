package dev.umb.legacy1122.test;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import dev.umb.bridge.api.EntityRenderCapture;
import dev.umb.legacy1122.legacyside.Legacy1122RenderCapture;
import dev.umb.legacy1122.legacyside.Legacy1122RenderTransformer;
import java.io.File;
import java.io.InputStream;
import java.util.jar.JarFile;
class Legacy1122RenderCaptureTest {
 @Test void syntheticTesrOpenAndClosedGeometryDiffer(){EntityRenderCapture a=render(0),b=render(1.2f);assertEquals(4,a.vertexCount());assertEquals(4,b.vertexCount());assertTrue(a.draws.get(0).cull);assertNotEquals(a.draws.get(0).vertices[1],b.draws.get(0).vertices[1]);}
 private static EntityRenderCapture render(float lid){Legacy1122RenderCapture.begin("synthetic.Tesr",lid==0?"closed":"open");Legacy1122RenderCapture.push();Legacy1122RenderCapture.enableCull();Object b=new Object();Legacy1122RenderCapture.begin(b,7,null);for(int i=0;i<4;i++){Legacy1122RenderCapture.pos(b,i&1,1+lid,(i>>1)&1);Legacy1122RenderCapture.tex(b,0,0);Legacy1122RenderCapture.normal(b,0,1,0);Legacy1122RenderCapture.endVertex(b);}Legacy1122RenderCapture.finish(b);Legacy1122RenderCapture.pop();return Legacy1122RenderCapture.end();}
  @Test void sinkTextureRecordedOnDraws(){Legacy1122RenderCapture.begin("synthetic.Mob","idle");Legacy1122RenderCapture.recordTexture("mob:textures/entity/pig.png");Object b=new Object();Legacy1122RenderCapture.begin(b,7,null);for(int i=0;i<4;i++){Legacy1122RenderCapture.pos(b,i&1,(i>>1)&1,0);Legacy1122RenderCapture.tex(b,0,0);Legacy1122RenderCapture.normal(b,0,0,1);Legacy1122RenderCapture.endVertex(b);}Legacy1122RenderCapture.finish(b);EntityRenderCapture r=Legacy1122RenderCapture.end();assertEquals(1,r.draws.size());assertEquals("mob:textures/entity/pig.png",r.draws.get(0).texture);assertEquals(4,r.vertexCount());}
  @Test void sinkDrawFlushSplitsModes(){Legacy1122RenderCapture.begin("synthetic.Mob","idle");Object b=new Object();Legacy1122RenderCapture.begin(b,4,null);for(int i=0;i<3;i++){Legacy1122RenderCapture.pos(b,i,0,0);Legacy1122RenderCapture.tex(b,0,0);Legacy1122RenderCapture.normal(b,0,0,1);Legacy1122RenderCapture.endVertex(b);}Legacy1122RenderCapture.draw();Legacy1122RenderCapture.begin(b,7,null);for(int i=0;i<4;i++){Legacy1122RenderCapture.pos(b,i&1,(i>>1)&1,0);Legacy1122RenderCapture.tex(b,0,0);Legacy1122RenderCapture.normal(b,0,0,1);Legacy1122RenderCapture.endVertex(b);}Legacy1122RenderCapture.finish(b);EntityRenderCapture r=Legacy1122RenderCapture.end();assertEquals(2,r.draws.size());assertEquals(4,r.draws.get(0).vertexCount);assertEquals(4,r.draws.get(1).vertexCount);assertNull(r.draws.get(0).texture);assertEquals(8,r.vertexCount());}
  @Test void transformerRoutesModelGlAndTextureCalls() throws Exception {
   File repo=new File(System.getProperty("umb.repo",".")); File jar=new File(repo,"research/mods-1122/ironchest-1.12.2-7.0.72.847.jar");
   if(!jar.isFile()) return;
   byte[] original; try(JarFile jf=new JarFile(jar); InputStream in=jf.getInputStream(jf.getJarEntry("cpw/mods/ironchest/client/renderer/chest/TileEntityIronChestRenderer.class"))){original=in.readAllBytes();}
   byte[] transformed=new Legacy1122RenderTransformer().transform("cpw.mods.ironchest.client.renderer.chest.TileEntityIronChestRenderer","cpw.mods.ironchest.client.renderer.chest.TileEntityIronChestRenderer",original);
   assertNotEquals(original.length,transformed.length);
   org.objectweb.asm.ClassReader cr=new org.objectweb.asm.ClassReader(transformed);
   final java.util.List<String> leftover=new java.util.ArrayList<String>();
   cr.accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9){
    @Override public org.objectweb.asm.MethodVisitor visitMethod(int a,String n,String d,String s,String[] e){
     final org.objectweb.asm.MethodVisitor out=super.visitMethod(a,n,d,s,e);
     return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9,out){
      @Override public void visitMethodInsn(int op,String owner,String mn,String md,boolean itf){
       if(owner.equals("net/minecraft/client/renderer/Tessellator")&&(mn.equals("draw")||mn.equals("func_78381_a")))leftover.add(owner+"."+mn);
       if(owner.equals("net/minecraft/client/renderer/texture/TextureManager")&&(mn.equals("bindTexture")||mn.equals("func_110577_a")))leftover.add(owner+"."+mn);
       if(owner.equals("net/minecraft/client/renderer/BufferBuilder")&&(mn.equals("begin")||mn.equals("func_181662_b")||mn.equals("pos")||mn.equals("func_187315_a")||mn.equals("tex")||mn.equals("func_181666_a")||mn.equals("color")||mn.equals("func_181663_c")||mn.equals("normal")||mn.equals("func_181675_d")||mn.equals("endVertex")))leftover.add(owner+"."+mn);
       if(owner.equals("net/minecraft/client/renderer/GlStateManager")&&(mn.equals("pushMatrix")||mn.equals("func_179094_E")||mn.equals("popMatrix")||mn.equals("func_179121_F")||mn.equals("enableCull")||mn.equals("disableCull")))leftover.add(owner+"."+mn);
       super.visitMethodInsn(op,owner,mn,md,itf);
      }};}},0);
   assertTrue(leftover.isEmpty(),"unrouted GL calls remain: "+leftover);
  }
 @Test void realIronChestTesrIsTransformableOffline() throws Exception {
  File repo=new File(System.getProperty("umb.repo",".")); File jar=new File(repo,"research/mods-1122/ironchest-1.12.2-7.0.72.847.jar");
  if(!jar.isFile()) return;
  byte[] original; try(JarFile jf=new JarFile(jar); InputStream in=jf.getInputStream(jf.getJarEntry("cpw/mods/ironchest/client/renderer/chest/TileEntityIronChestRenderer.class"))){original=in.readAllBytes();}
  byte[] transformed=new Legacy1122RenderTransformer().transform("cpw.mods.ironchest.client.renderer.chest.TileEntityIronChestRenderer","cpw.mods.ironchest.client.renderer.chest.TileEntityIronChestRenderer",original);
  assertNotEquals(original.length,transformed.length);
 }
}
