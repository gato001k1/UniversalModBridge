package dev.umb.objbridge.itemeffects;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.lang.reflect.Field;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

/** Replays sealed legacy IItemRenderer draws through the native 26.2 special-model seam. */
final class HeldItemSpecialRenderer implements SpecialModelRenderer<ItemStack> {
    private static final Set<String> WARMED = ConcurrentHashMap.newKeySet();
    private final ItemDisplayContext context;
    private final float partial;
    HeldItemSpecialRenderer(ItemDisplayContext context, float partial) {
        this.context = context; this.partial = partial;
    }

    @Override public void submit(ItemStack stack, PoseStack pose, SubmitNodeCollector collector,
                                 int light, int overlay, boolean foil, int seed) {
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        String key = id + "|" + stack.getDamageValue() + "|" + LegacyItemCaptureClient.renderType(context);
        boolean transformOnly = !WARMED.add(key);
        Object capture = LegacyItemCaptureClient.capture(stack, context, partial, transformOnly);
        if (capture == null) return;
        try {
            List<?> draws = (List<?>) field(capture, "draws").get(capture);
            for (Object draw : draws) submitDraw(draw, pose, collector, light);
        } catch (Throwable ignored) { }
    }

    @Override public void getExtents(Consumer<Vector3fc> consumer) {
        consumer.accept(new Vector3f(-1, -1, -1));
        consumer.accept(new Vector3f(1, 1, 1));
    }

    @Override public ItemStack extractArgument(ItemStack stack) { return stack; }

    private static void submitDraw(Object draw, PoseStack pose, SubmitNodeCollector collector, int light)
            throws Exception {
        String raw = (String) field(draw, "texture").get(draw);
        float[] data = (float[]) field(draw, "vertices").get(draw);
        float[] matrix = (float[]) field(draw, "matrix").get(draw);
        int count = field(draw, "vertexCount").getInt(draw);
        if (raw == null || data == null || count <= 0) return;
        Identifier texture = Identifier.parse(normalize(raw));
        boolean cull = hasCull(draw);
        RenderType type = cull ? RenderTypes.entityCutoutCull(texture) : RenderTypes.entityCutout(texture);
        collector.submitCustomGeometry(pose, type, (p, out) -> emit(data, count, matrix, p, out, light));
    }

    private static void emit(float[] data, int count, float[] matrix, PoseStack.Pose pose,
                             com.mojang.blaze3d.vertex.VertexConsumer out, int light) {
        int n = Math.min(count, data.length / 8);
        for (int i = 0; i < n; i++) {
            int k = i * 8;
            float x=data[k], y=data[k+1], z=data[k+2], nx=data[k+5], ny=data[k+6], nz=data[k+7];
            if (matrix != null && matrix.length >= 16) {
                float tx=matrix[0]*x+matrix[4]*y+matrix[8]*z+matrix[12];
                float ty=matrix[1]*x+matrix[5]*y+matrix[9]*z+matrix[13];
                float tz=matrix[2]*x+matrix[6]*y+matrix[10]*z+matrix[14];
                float tnx=matrix[0]*nx+matrix[4]*ny+matrix[8]*nz;
                float tny=matrix[1]*nx+matrix[5]*ny+matrix[9]*nz;
                float tnz=matrix[2]*nx+matrix[6]*ny+matrix[10]*nz;
                x=tx; y=ty; z=tz; nx=tnx; ny=tny; nz=tnz;
            }
            out.addVertex(pose,x,y,z).setColor(0xFFFFFFFF).setUv(data[k+3],data[k+4])
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose,nx,ny,nz);
        }
    }

    private static boolean hasCull(Object draw) {
        try { return field(draw, "cull").getBoolean(draw); } catch (Throwable ignored) { return false; }
    }
    private static Field field(Object value, String name) throws Exception {
        Field f=value.getClass().getField(name); f.setAccessible(true); return f;
    }
    private static String normalize(String texture) {
        int colon=texture.indexOf(':'); if(colon<0)return texture;
        String ns=texture.substring(0,colon), path=texture.substring(colon+1);
        if(path.startsWith("textures/")) path=path.substring(9);
        if(path.toLowerCase(java.util.Locale.ROOT).endsWith(".png")) path=path.substring(0,path.length()-4);
        return ns+":"+path;
    }
}
