package cpw.mods.fml.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * UMB test-corpus stub (CC0-1.0): shape-compatible stand-in for the 1.7.10-era
 * FML {@code @Mod} entrypoint annotation ({@code cpw.mods.fml.common} is the
 * 1.7.10 FML package — the forge-17 research notes verify it from the
 * 1.7.10-branch repo tree). Sufficient to compile self-authored fixtures.
 * NEVER ship this class in a distributed jar (BLOCKERS.md B2).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Mod {
    String modid();

    String name() default "";

    String version() default "";

    /** Single lifecycle dispatch annotation (1.7.10 has no @PreInit/@Init). */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface EventHandler {
    }
}
