package net.minecraftforge.fml.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * UMB test-corpus stub (CC0-1.0): shape-compatible stand-in for the 1.12-era
 * Forge {@code @Mod} entrypoint annotation ({@code net.minecraftforge.fml.common}
 * is the 1.12 FML package — FML moved out of {@code cpw.mods.fml} in 1.12; the
 * forge-112 research notes verify it from the 1.12.x-branch repo tree).
 * Sufficient to compile self-authored fixtures.
 * NEVER ship this class in a distributed jar (BLOCKERS.md B2).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Mod {
    String modid();

    String name() default "";

    String version() default "";

    /** Single lifecycle dispatch annotation (same shape as 1.7.10). */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface EventHandler {
    }
}
