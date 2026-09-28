package dev.umb.legacy1122.legacyside;

import dev.umb.bridge.api.EntityRenderCapture;
import java.util.ArrayList;
import java.util.List;

/** Native-free 1.12.2 TESR capture sink used by the bytecode shim. */
public final class Legacy1122RenderCapture {
    private static final int GL_TRIANGLES = 4;
    private static final int GL_TRIANGLE_STRIP = 5;
    private static final int GL_TRIANGLE_FAN = 6;
    private static final int GL_QUADS = 7;
    private static final int GL_QUAD_STRIP = 8;
    private static final int GL_POLYGON = 9;
    private static final ThreadLocal<Capture> ACTIVE = new ThreadLocal<Capture>();
    private Legacy1122RenderCapture() { }

    public static void begin(String entityClass, String stateKey) {
        ACTIVE.set(new Capture(entityClass, stateKey));
    }
    public static EntityRenderCapture end() {
        Capture c = ACTIVE.get();
        ACTIVE.remove();
        return c == null ? EntityRenderCapture.empty("", "") : c.result();
    }
    public static void matrixOp() { Capture c=ACTIVE.get(); if(c!=null)c.ops++; }
    public static void matrixOp(int ignored) { matrixOp(); }
    public static void matrixOp(float a,float b,float c) { matrixOp(); }
    public static void matrixOp(float a,float b,float c,float d) { matrixOp(); }
    public static void push() { Capture c=ACTIVE.get(); if(c!=null)c.pushes++; }
    public static void pop() { Capture c=ACTIVE.get(); if(c!=null)c.pops++; }
    public static void enableCull() { Capture c=ACTIVE.get(); if(c!=null)c.cull=true; }
    public static void disableCull() { Capture c=ACTIVE.get(); if(c!=null)c.cull=false; }
    public static void begin(Object ignored, int mode, Object format) { Capture c=ACTIVE.get(); if(c!=null)c.begin(mode); }
    public static Object pos(Object b, double x, double y, double z) { Capture c=ACTIVE.get(); if(c!=null)c.pos(x,y,z); return b; }
    public static Object tex(Object b, double u, double v) { Capture c=ACTIVE.get(); if(c!=null)c.tex(u,v); return b; }
    public static Object normal(Object b, float x, float y, float z) { Capture c=ACTIVE.get(); if(c!=null)c.normal(x,y,z); return b; }
    public static Object color(Object b, int r, int g, int bl, int a) { return b; }
    public static void endVertex(Object b) { Capture c=ACTIVE.get(); if(c!=null)c.vertex(); }
    public static void finish(Object b) { Capture c=ACTIVE.get(); if(c!=null)c.draw(); }

    private static final class Capture {
        final String entityClass, stateKey; final List<EntityRenderCapture.Draw> draws=new ArrayList<EntityRenderCapture.Draw>();
        final List<Float> data=new ArrayList<Float>(); int ops,pushes,pops,mode; boolean cull;
        double x,y,z,u,v; float nx,ny,nz;
        Capture(String e,String s){entityClass=e;stateKey=s;}
        void begin(int m){mode=m;}
        void pos(double a,double b,double c){x=a;y=b;z=c;}
        void tex(double a,double b){u=a;v=b;}
        void normal(float a,float b,float c){nx=a;ny=b;nz=c;}
        void vertex(){data.add((float)x);data.add((float)y);data.add((float)z);data.add((float)u);data.add((float)v);data.add(nx);data.add(ny);data.add(nz);}
        void draw(){
            if(data.isEmpty())return;
            List<Float> assembled=asQuads(mode,data);
            float[] a=assembled.isEmpty()?null:new float[assembled.size()];
            if(a!=null)for(int i=0;i<a.length;i++)a[i]=assembled.get(i).floatValue();
            draws.add(EntityRenderCapture.Draw.owned(null,a,assembled.size()/8,null,cull));
            data.clear();
        }
        private static List<Float> asQuads(int mode,List<Float> v){
            int n=v.size()/8;
            if(n==0)return new ArrayList<Float>();
            List<Float> out=new ArrayList<Float>(n*8);
            if(mode==GL_QUADS){for(int i=0;i+3<n;i+=4)add(out,v,i,i+1,i+2,i+3);return out;}
            if(mode==GL_TRIANGLES){for(int i=0;i+2<n;i+=3)addTri(out,v,i,i+1,i+2);return out;}
            if(mode==GL_TRIANGLE_FAN||mode==GL_POLYGON){for(int i=1;i+1<n;i++)addTri(out,v,0,i,i+1);return out;}
            if(mode==GL_TRIANGLE_STRIP){for(int i=0;i+2<n;i++)if((i&1)==0)addTri(out,v,i,i+1,i+2);else addTri(out,v,i+1,i,i+2);return out;}
            if(mode==GL_QUAD_STRIP){for(int i=0;i+3<n;i+=2)add(out,v,i,i+1,i+3,i+2);}
            return out;
        }
        private static void addTri(List<Float> out,List<Float> v,int a,int b,int c){add(out,v,a,b,c,c);}
        private static void add(List<Float> out,List<Float> v,int... indices){for(int index:indices)for(int k=0;k<8;k++)out.add(v.get(index*8+k));}
        EntityRenderCapture result(){draw();return new EntityRenderCapture(entityClass,stateKey,true,ops,pushes,pops,draws);}
    }
}
