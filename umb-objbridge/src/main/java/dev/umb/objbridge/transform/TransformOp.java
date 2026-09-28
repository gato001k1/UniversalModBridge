package dev.umb.objbridge.transform;

/**
 * One OpenGL fixed-function transform recorded by
 * {@code dev.umb.rendermap.RendererTransformExtractor}.
 *
 * <p>{@code op} is one of {@code glScalef/glScaled/glTranslatef/glTranslated/glRotatef/glRotated/
 * glPushMatrix/glPopMatrix}. Arity is guaranteed by the extractor: 3 args for scale/translate, 4 for
 * rotate (angle-degrees, x, y, z axis), 0 for push/pop. An argument computed at runtime is recorded as
 * JSON {@code null} and decoded here as {@link Float#NaN} - never guessed, never dropped (dropping
 * would silently shift the remaining args into the wrong slot).
 */
public record TransformOp(String method, String op, float[] args, boolean dynamic, String note) {

    public boolean isPush() { return "glPushMatrix".equals(op); }
    public boolean isPop() { return "glPopMatrix".equals(op); }
    public boolean isTranslate() { return op != null && op.startsWith("glTranslate"); }
    public boolean isScale() { return op != null && op.startsWith("glScale"); }
    public boolean isRotate() { return op != null && op.startsWith("glRotate"); }

    /** True when any argument this op actually needs is the {@link Float#NaN} dynamic sentinel. */
    public boolean hasUnresolvedArg() {
        for (float a : args) {
            if (Float.isNaN(a)) return true;
        }
        return false;
    }
}
