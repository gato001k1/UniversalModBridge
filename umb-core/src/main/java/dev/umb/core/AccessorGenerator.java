package dev.umb.core;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * M8-4: bridge for {@code @Accessor} / {@code @Invoker} mixins.
 * Generates synthetic accessor methods directly on the target class via ASM 9.9.
 *
 * <p>Mixin docs (§3.6): abstract interface methods named get* / is*, set*, call* / invoke*
 * in a {@code @Mixin(target)} become generated concrete methods injected into the target.
 * Target member name defaults to derived name (prefix stripped + decapitalized) unless
 * annotation {@code value} overrides. D4: unresolvable targets report evidence, skip.
 *
 * <p>Synthetic fixtures only (CC0), stubs never ship.
 */
public final class AccessorGenerator {

    private AccessorGenerator() {}

    public static final String EVIDENCE_KIND = "accessor-bridge";

    public enum Kind { GETTER, SETTER, INVOKER }

    public record AccessorRequest(
            String mixinClass,
            String accessorMethod,
            String accessorDesc,
            boolean staticAccessor,
            Kind kind,
            String targetName,
            String targetDesc,
            boolean isField
    ) implements java.io.Serializable {}

    public record GenerateResult(int generated, List<ModAnalysis.Evidence> evidence) {}

    // ------------------------------------------------------------------ extraction from mixin ClassNode

    private static final String ACCESSOR_DESC = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
    private static final String INVOKER_DESC = "Lorg/spongepowered/asm/mixin/gen/Invoker;";

    /** Extract all @Accessor/@Invoker requests from a mixin class node. */
    public static List<AccessorRequest> extract(ClassNode mixinNode) {
        Objects.requireNonNull(mixinNode, "mixinNode");
        List<AccessorRequest> out = new ArrayList<>();
        for (MethodNode mn : mixinNode.methods) {
            AnnotationNode acc = findAnn(mn, ACCESSOR_DESC);
            AnnotationNode inv = findAnn(mn, INVOKER_DESC);
            if (acc == null && inv == null) continue;
            boolean isInvoker = inv != null;
            AnnotationNode ann = isInvoker ? inv : acc;
            String explicitValue = strValue(ann, "value");
            boolean staticAccessor = (mn.access & Opcodes.ACC_STATIC) != 0;
            String derivedTarget = explicitValue != null && !explicitValue.isBlank()
                    ? explicitValue.trim()
                    : deriveTargetName(mn.name, isInvoker);
            // Invokers target methods; accessors target fields.
            // Parse explicit value as MemberInfo if it contains descriptor info
            // (e.g. "field_1234_x:I" or "method_1234_foo()V"). For plain names, descriptor stays implied.
            String targetName = derivedTarget;
            String targetDesc = null;
            boolean isField = !isInvoker;
            if (derivedTarget != null) {
                // Try parse as MemberSelector to extract name/desc; plain names will parse as name-only
                try {
                    MemberSelector ms = MemberSelector.parse(derivedTarget);
                    if (ms.valid()) {
                        targetName = ms.name();
                        if (ms.descriptor() != null) {
                            targetDesc = ms.descriptor();
                            isField = ms.isField();
                        }
                    }
                } catch (Exception ignored) {}
            }
            // Infer descriptor from accessor signature when not explicit
            if (targetDesc == null) {
                if (isInvoker) {
                    // invoker desc == target method desc (same args/return)
                    targetDesc = mn.desc;
                } else {
                    // field: getter return type, setter single param type
                    Type mType = Type.getMethodType(mn.desc);
                    if (mn.name.startsWith("set") || mn.name.startsWith("Set")) {
                        Type[] args = mType.getArgumentTypes();
                        if (args.length == 1) targetDesc = args[0].getDescriptor();
                        else if (args.length == 0) targetDesc = mType.getReturnType().getDescriptor();
                        else targetDesc = args[0].getDescriptor();
                    } else {
                        targetDesc = mType.getReturnType().getDescriptor();
                    }
                }
            }
            Kind kind;
            if (isInvoker) kind = Kind.INVOKER;
            else if (mn.name.startsWith("set") || mn.name.startsWith("Set")) kind = Kind.SETTER;
            else kind = Kind.GETTER;
            out.add(new AccessorRequest(mixinNode.name, mn.name, mn.desc, staticAccessor, kind, targetName, targetDesc, isField));
        }
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------ generation onto target

    /** Generate accessor methods onto target ClassNode. Mutates target in place. */
    public static GenerateResult generate(ClassNode target, List<AccessorRequest> requests, List<ModAnalysis.Evidence> outEvidence) {
        Objects.requireNonNull(target, "target");
        if (requests == null || requests.isEmpty()) return new GenerateResult(0, List.of());
        int generated = 0;
        List<ModAnalysis.Evidence> ev = outEvidence != null ? outEvidence : new ArrayList<>();
        List<ModAnalysis.Evidence> local = outEvidence != null ? outEvidence : ev;
        for (AccessorRequest req : requests) {
            if (req.isField()) {
                FieldNode field = findField(target, req.targetName(), req.targetDesc());
                if (field == null) {
                    FieldNode byName = findFieldByName(target, req.targetName());
                    if (byName != null) {
                        local.add(new ModAnalysis.Evidence(EVIDENCE_KIND,
                                "accessor target field descriptor mismatch '" + req.targetName()
                                        + "' expected " + req.targetDesc() + " actual " + byName.desc
                                        + " for " + req.mixinClass() + "#" + req.accessorMethod()
                                        + " on target " + target.name, 1.0));
                    } else {
                        local.add(new ModAnalysis.Evidence(EVIDENCE_KIND,
                                "accessor target field not found '" + req.targetName() + ":" + req.targetDesc()
                                        + "' for " + req.mixinClass() + "#" + req.accessorMethod()
                                        + " on target " + target.name, 0.9));
                    }
                    continue;
                }
                // Validate accessor descriptor against field descriptor (D4 on mismatch)
                if (!validateFieldAccessorDesc(req, field, local, target.name)) continue;
                boolean fieldStatic = (field.access & Opcodes.ACC_STATIC) != 0;
                if (req.kind() == Kind.GETTER) {
                    MethodNode accessor = buildGetter(target.name, req, field, fieldStatic);
                    if (methodExists(target, accessor.name, accessor.desc)) {
                        local.add(new ModAnalysis.Evidence(EVIDENCE_KIND,
                                "accessor already exists " + accessor.name + accessor.desc + " on " + target.name, 0.8));
                        continue;
                    }
                    target.methods.add(accessor);
                    generated++;
                } else if (req.kind() == Kind.SETTER) {
                    MethodNode accessor = buildSetter(target.name, req, field, fieldStatic);
                    if (methodExists(target, accessor.name, accessor.desc)) {
                        local.add(new ModAnalysis.Evidence(EVIDENCE_KIND,
                                "accessor already exists " + accessor.name + accessor.desc + " on " + target.name, 0.8));
                        continue;
                    }
                    target.methods.add(accessor);
                    generated++;
                }
            } else {
                MethodNode targetMethod = findMethod(target, req.targetName(), req.targetDesc());
                if (targetMethod == null) {
                    MethodNode byName = findMethodByName(target, req.targetName());
                    if (byName != null) {
                        local.add(new ModAnalysis.Evidence(EVIDENCE_KIND,
                                "invoker target method descriptor mismatch '" + req.targetName()
                                        + "' expected " + req.targetDesc() + " actual " + byName.desc
                                        + " for " + req.mixinClass() + "#" + req.accessorMethod()
                                        + " on target " + target.name, 1.0));
                    } else {
                        local.add(new ModAnalysis.Evidence(EVIDENCE_KIND,
                                "invoker target method not found '" + req.targetName() + req.targetDesc()
                                        + "' for " + req.mixinClass() + "#" + req.accessorMethod()
                                        + " on target " + target.name, 0.9));
                    }
                    continue;
                }
                if (!validateInvokerDesc(req, targetMethod, local, target.name)) continue;
                boolean targetStatic = (targetMethod.access & Opcodes.ACC_STATIC) != 0;
                MethodNode invoker = buildInvoker(target.name, req, targetMethod, targetStatic);
                if (methodExists(target, invoker.name, invoker.desc)) {
                    local.add(new ModAnalysis.Evidence(EVIDENCE_KIND,
                            "invoker already exists " + invoker.name + invoker.desc + " on " + target.name, 0.8));
                    continue;
                }
                target.methods.add(invoker);
                generated++;
            }
        }
        return new GenerateResult(generated, List.copyOf(local));
    }

    public static GenerateResult generate(ClassNode target, List<AccessorRequest> requests) {
        List<ModAnalysis.Evidence> ev = new ArrayList<>();
        GenerateResult r = generate(target, requests, ev);
        return new GenerateResult(r.generated(), List.copyOf(ev));
    }

    // ------------------------------------------------------------------ builders

    private static MethodNode buildGetter(String owner, AccessorRequest req, FieldNode field, boolean fieldStatic) {
        boolean staticAccessor = req.staticAccessor();
        // If accessor is declared static, generated method is static; else instance.
        int access = Opcodes.ACC_PUBLIC | (staticAccessor ? Opcodes.ACC_STATIC : 0);
        // Getter desc is already the accessor's desc: ()FieldType for instance, ()FieldType for static
        MethodNode mn = new MethodNode(access, req.accessorMethod(), req.accessorDesc(), null, null);
        Type retType = Type.getMethodType(req.accessorDesc()).getReturnType();
        if (!staticAccessor && !fieldStatic) {
            mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            mn.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, owner, field.name, field.desc));
        } else if (staticAccessor && fieldStatic) {
            mn.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, owner, field.name, field.desc));
        } else if (!staticAccessor && fieldStatic) {
            // instance accessor bridging to static field — no this needed
            mn.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, owner, field.name, field.desc));
        } else {
            // static accessor bridging to instance field — not normally valid; emit GETFIELD with null check omitted
            // treat as instance via first arg as instance? Simplify: GETSTATIC fallback
            mn.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, owner, field.name, field.desc));
        }
        mn.instructions.add(new InsnNode(retType.getOpcode(Opcodes.IRETURN)));
        mn.visitMaxs(0, 0);
        return mn;
    }

    private static MethodNode buildSetter(String owner, AccessorRequest req, FieldNode field, boolean fieldStatic) {
        boolean staticAccessor = req.staticAccessor();
        int access = Opcodes.ACC_PUBLIC | (staticAccessor ? Opcodes.ACC_STATIC : 0);
        MethodNode mn = new MethodNode(access, req.accessorMethod(), req.accessorDesc(), null, null);
        Type mType = Type.getMethodType(req.accessorDesc());
        Type argType = mType.getArgumentTypes().length > 0 ? mType.getArgumentTypes()[0] : Type.getType(field.desc);
        // For instance accessor with instance field: this.field = arg
        // receiver slot 0 is this (if instance), arg slot 1 (or 1/2 for wide)
        if (!staticAccessor && !fieldStatic) {
            mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            mn.instructions.add(new VarInsnNode(argType.getOpcode(Opcodes.ILOAD), 1));
            mn.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, field.name, field.desc));
        } else if (staticAccessor && fieldStatic) {
            mn.instructions.add(new VarInsnNode(argType.getOpcode(Opcodes.ILOAD), 0));
            mn.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC, owner, field.name, field.desc));
        } else if (!staticAccessor && fieldStatic) {
            mn.instructions.add(new VarInsnNode(argType.getOpcode(Opcodes.ILOAD), 1));
            mn.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC, owner, field.name, field.desc));
        } else {
            mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            mn.instructions.add(new VarInsnNode(argType.getOpcode(Opcodes.ILOAD), 1));
            mn.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, field.name, field.desc));
        }
        mn.instructions.add(new InsnNode(Opcodes.RETURN));
        mn.visitMaxs(0, 0);
        return mn;
    }

    private static MethodNode buildInvoker(String owner, AccessorRequest req, MethodNode targetMethod, boolean targetStatic) {
        boolean staticAccessor = req.staticAccessor();
        int access = Opcodes.ACC_PUBLIC | (staticAccessor ? Opcodes.ACC_STATIC : 0);
        MethodNode mn = new MethodNode(access, req.accessorMethod(), req.accessorDesc(), null, null);
        Type mType = Type.getMethodType(req.accessorDesc());
        Type[] args = mType.getArgumentTypes();
        Type retType = mType.getReturnType();
        int slot = 0;
        if (!staticAccessor) {
            mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            slot = 1;
        }
        for (Type arg : args) {
            mn.instructions.add(new VarInsnNode(arg.getOpcode(Opcodes.ILOAD), slot));
            slot += arg.getSize();
        }
        int opcode = targetStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKEVIRTUAL;
        // Use INVOKESPECIAL only for private? After widening it is virtual.
        mn.instructions.add(new MethodInsnNode(opcode, owner, targetMethod.name, targetMethod.desc, false));
        if (retType.equals(Type.VOID_TYPE)) {
            mn.instructions.add(new InsnNode(Opcodes.RETURN));
        } else {
            mn.instructions.add(new InsnNode(retType.getOpcode(Opcodes.IRETURN)));
        }
        mn.visitMaxs(0, 0);
        return mn;
    }

    // ------------------------------------------------------------------ helpers

    private static FieldNode findField(ClassNode cn, String name, String desc) {
        for (FieldNode fn : cn.fields) {
            if (!fn.name.equals(name)) continue;
            if (desc != null && !fn.desc.equals(desc)) continue;
            return fn;
        }
        return null;
    }

    private static FieldNode findFieldByName(ClassNode cn, String name) {
        for (FieldNode fn : cn.fields) if (fn.name.equals(name)) return fn;
        return null;
    }

    private static MethodNode findMethod(ClassNode cn, String name, String desc) {
        for (MethodNode mn : cn.methods) {
            if (!mn.name.equals(name)) continue;
            if (desc != null && !mn.desc.equals(desc)) continue;
            return mn;
        }
        return null;
    }

    private static MethodNode findMethodByName(ClassNode cn, String name) {
        for (MethodNode mn : cn.methods) if (mn.name.equals(name)) return mn;
        return null;
    }

    private static boolean validateFieldAccessorDesc(AccessorRequest req, FieldNode field,
                                                     List<ModAnalysis.Evidence> out, String targetInternal) {
        Type accType = Type.getMethodType(req.accessorDesc());
        if (req.kind() == Kind.GETTER) {
            String retDesc = accType.getReturnType().getDescriptor();
            if (!retDesc.equals(field.desc)) {
                out.add(new ModAnalysis.Evidence(EVIDENCE_KIND,
                        "accessor descriptor mismatch for field '" + req.targetName()
                                + "' accessor " + req.accessorMethod() + req.accessorDesc()
                                + " return " + retDesc + " vs field " + field.desc
                                + " on target " + targetInternal, 1.0));
                return false;
            }
            if (accType.getArgumentTypes().length != (req.staticAccessor() ? 0 : 0)
                    && accType.getArgumentTypes().length != 0) {
                // Instance getter with args or static getter with args is a shape mismatch
                // GETTER must have 0 args (static or instance). Instance getter still 0 args in descriptor.
                if (accType.getArgumentTypes().length != 0) {
                    out.add(new ModAnalysis.Evidence(EVIDENCE_KIND,
                            "accessor descriptor mismatch getter must have 0 args: "
                                    + req.accessorMethod() + req.accessorDesc() + " on target " + targetInternal, 1.0));
                    return false;
                }
            }
        } else if (req.kind() == Kind.SETTER) {
            Type[] args = accType.getArgumentTypes();
            int expectedArgs = 1;
            if (args.length != expectedArgs) {
                out.add(new ModAnalysis.Evidence(EVIDENCE_KIND,
                        "accessor descriptor mismatch setter must have 1 arg: "
                                + req.accessorMethod() + req.accessorDesc() + " on target " + targetInternal, 1.0));
                return false;
            }
            String argDesc = args[0].getDescriptor();
            if (!argDesc.equals(field.desc)) {
                out.add(new ModAnalysis.Evidence(EVIDENCE_KIND,
                        "accessor descriptor mismatch for field '" + req.targetName()
                                + "' setter arg " + argDesc + " vs field " + field.desc
                                + " (" + req.accessorMethod() + req.accessorDesc() + ") on target " + targetInternal, 1.0));
                return false;
            }
            if (!accType.getReturnType().equals(Type.VOID_TYPE)) {
                out.add(new ModAnalysis.Evidence(EVIDENCE_KIND,
                        "accessor descriptor mismatch setter must return void: "
                                + req.accessorMethod() + req.accessorDesc() + " on target " + targetInternal, 1.0));
                return false;
            }
        }
        return true;
    }

    private static boolean validateInvokerDesc(AccessorRequest req, MethodNode targetMethod,
                                               List<ModAnalysis.Evidence> out, String targetInternal) {
        // Invoker descriptor must exactly equal target method descriptor
        if (!req.accessorDesc().equals(targetMethod.desc)) {
            out.add(new ModAnalysis.Evidence(EVIDENCE_KIND,
                    "invoker descriptor mismatch '" + req.targetName()
                            + "' accessor " + req.accessorMethod() + req.accessorDesc()
                            + " vs target " + targetMethod.desc
                            + " on target " + targetInternal, 1.0));
            return false;
        }
        return true;
    }

    private static boolean methodExists(ClassNode cn, String name, String desc) {
        for (MethodNode mn : cn.methods) {
            if (mn.name.equals(name) && mn.desc.equals(desc)) return true;
        }
        return false;
    }

    private static AnnotationNode findAnn(MethodNode mn, String desc) {
        if (mn.visibleAnnotations != null) {
            for (AnnotationNode a : mn.visibleAnnotations) if (a.desc.equals(desc)) return a;
        }
        if (mn.invisibleAnnotations != null) {
            for (AnnotationNode a : mn.invisibleAnnotations) if (a.desc.equals(desc)) return a;
        }
        return null;
    }

    private static String strValue(AnnotationNode ann, String key) {
        if (ann.values == null) return null;
        for (int i = 0; i + 1 < ann.values.size(); i += 2) {
            if (ann.values.get(i).equals(key)) {
                Object v = ann.values.get(i + 1);
                if (v instanceof String s) return s;
            }
        }
        return null;
    }

    private static String deriveTargetName(String accessorName, boolean isInvoker) {
        String base;
        if (!isInvoker) {
            if (accessorName.startsWith("get") && accessorName.length() > 3) base = accessorName.substring(3);
            else if (accessorName.startsWith("is") && accessorName.length() > 2) base = accessorName.substring(2);
            else if (accessorName.startsWith("set") && accessorName.length() > 3) base = accessorName.substring(3);
            else base = accessorName;
        } else {
            if (accessorName.startsWith("call") && accessorName.length() > 4) base = accessorName.substring(4);
            else if (accessorName.startsWith("invoke") && accessorName.length() > 6) base = accessorName.substring(6);
            else base = accessorName;
        }
        if (base.isEmpty()) return accessorName;
        return Character.toLowerCase(base.charAt(0)) + base.substring(1);
    }
}

