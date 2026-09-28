package net.minecraftforge.fml.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * UMB test-corpus stub (spec §108): shape-compatible stand-in for the modern-Forge
 * {@code @Mod} entrypoint annotation, sufficient to compile self-authored fixtures.
 * CC0-1.0. NEVER ship this class in a distributed jar — see fixtures/fabric-stubs.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Mod {
    String value() default "";
}
