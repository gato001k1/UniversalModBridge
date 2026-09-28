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
 @Test void realIronChestTesrIsTransformableOffline() throws Exception {
  File repo=new File(System.getProperty("umb.repo",".")); File jar=new File(repo,"research/mods-1122/ironchest-1.12.2-7.0.72.847.jar");
  if(!jar.isFile()) return;
  byte[] original; try(JarFile jf=new JarFile(jar); InputStream in=jf.getInputStream(jf.getJarEntry("cpw/mods/ironchest/client/renderer/chest/TileEntityIronChestRenderer.class"))){original=in.readAllBytes();}
  byte[] transformed=new Legacy1122RenderTransformer().transform("cpw.mods.ironchest.client.renderer.chest.TileEntityIronChestRenderer","cpw.mods.ironchest.client.renderer.chest.TileEntityIronChestRenderer",original);
  assertNotEquals(original.length,transformed.length);
 }
}
