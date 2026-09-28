package dev.umb.core;

/**
 * M8-2: kind of an injector handler. Only the six Mixin injection family members
 * plus Overwrite are modeled; everything else is OTHER (D4 — never guess).
 */
public enum InjectionPointKind {
    INJECT("Lorg/spongepowered/asm/mixin/injection/Inject;"),
    REDIRECT("Lorg/spongepowered/asm/mixin/injection/Redirect;"),
    MODIFY_ARG("Lorg/spongepowered/asm/mixin/injection/ModifyArg;"),
    MODIFY_ARGS("Lorg/spongepowered/asm/mixin/injection/ModifyArgs;"),
    MODIFY_VARIABLE("Lorg/spongepowered/asm/mixin/injection/ModifyVariable;"),
    MODIFY_CONSTANT("Lorg/spongepowered/asm/mixin/injection/ModifyConstant;"),
    OVERWRITE("Lorg/spongepowered/asm/mixin/Overwrite;"),
    /** Shadow/accessor/invoker or unknown annotation. */
    OTHER(null);

    private final String descriptor;

    InjectionPointKind(String descriptor) { this.descriptor = descriptor; }

    public String descriptor() { return descriptor; }

    public static InjectionPointKind fromDescriptor(String desc) {
        for (InjectionPointKind k : values()) {
            if (desc != null && desc.equals(k.descriptor)) return k;
        }
        return OTHER;
    }

    public boolean isInjector() {
        return this == INJECT || this == REDIRECT || this == MODIFY_ARG
                || this == MODIFY_ARGS || this == MODIFY_VARIABLE || this == MODIFY_CONSTANT;
    }
}
