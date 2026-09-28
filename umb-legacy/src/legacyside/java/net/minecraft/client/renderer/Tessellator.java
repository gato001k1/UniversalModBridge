package net.minecraft.client.renderer;

import dev.umb.legacy.legacyside.render.LegacyRenderCapture;

/** Headless legacy Tessellator ABI; its immediate-mode writes go to the active capture. */
public class Tessellator {
    public static final Tessellator field_78398_a = new Tessellator();
    public boolean defaultTexture;
    public int textureID;
    public Tessellator() {}
    public int func_78381_a() { LegacyRenderCapture.draw(); return 0; }
    public void func_147565_a(net.minecraft.client.shader.TesselatorVertexState s) {}
    public void func_78382_b() { LegacyRenderCapture.begin(7); }
    public void func_78371_b(int mode) { LegacyRenderCapture.begin(mode); }
    public void func_78385_a(double u,double v) { LegacyRenderCapture.uv(u,v); }
    public void func_78380_c(int x) {}
    public void func_78386_a(float r,float g,float b) { LegacyRenderCapture.color(r,g,b,1); }
    public void func_78369_a(float r,float g,float b,float a) { LegacyRenderCapture.color(r,g,b,a); }
    public void func_78376_a(int r,int g,int b) { LegacyRenderCapture.func_78376_a(this,r,g,b); }
    public void func_78370_a(int r,int g,int b,int a) { LegacyRenderCapture.func_78370_a(this,r,g,b,a); }
    public void func_154352_a(byte r,byte g,byte b) { LegacyRenderCapture.func_154352_a(this,r,g,b); }
    public void func_78374_a(double x,double y,double z,double u,double v) { LegacyRenderCapture.func_78374_a(this,x,y,z,u,v); }
    public void func_78377_a(double x,double y,double z) { LegacyRenderCapture.func_78377_a(this,x,y,z); }
    public void func_78378_d(int rgb) { LegacyRenderCapture.func_78378_d(this,rgb); }
    public void func_78384_a(int rgb,int alpha) { LegacyRenderCapture.func_78384_a(this,rgb,alpha); }
    public void func_78383_c() {}
    public void func_78375_b(float x,float y,float z) { LegacyRenderCapture.normal(x,y,z); }
    public void func_78373_b(double x,double y,double z) { LegacyRenderCapture.func_78373_b(this,x,y,z); }
    public void func_78372_c(float x,float y,float z) { LegacyRenderCapture.func_78372_c(this,x,y,z); }
}
