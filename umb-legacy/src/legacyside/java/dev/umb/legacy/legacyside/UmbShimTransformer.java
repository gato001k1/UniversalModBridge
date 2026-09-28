package dev.umb.legacy.legacyside;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import net.minecraft.launchwrapper.IClassTransformer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * A LaunchWrapper transformer that swaps the bodies of the three legacy methods whose
 * implementation depends on JDK internals that no longer exist, leaving every signature untouched.
 *
 * <p>Exactly three classes in FML 7.10.99.99 + Forge 10.13.4.1614 reach for removed APIs (verified
 * by grepping the 1.7.10 branch for {@code sun.reflect} and {@code getDeclaredField("modifiers")}):</p>
 * <table>
 *   <tr><th>class</th><th>method rewritten</th><th>why</th></tr>
 *   <tr><td>net.minecraftforge.common.util.EnumHelper</td>
 *       <td>setup, makeEnum, setFailsafeFieldValue</td>
 *       <td>sun.reflect.{ReflectionFactory.newConstructorAccessor, ConstructorAccessor,
 *           FieldAccessor} + Field.modifiers. Blocks CONSTRUCTING: HBM's MainRegistry clinit calls
 *           addToolMaterial.</td></tr>
 *   <tr><td>cpw.mods.fml.common.registry.ObjectHolderRef</td><td>makeWritable</td>
 *       <td>sun.reflect.ReflectionFactory.newFieldAccessor + Field.modifiers. Blocks PREINIT:
 *           Loader.preinitializeMods -> ObjectHolderRegistry.findObjectHolders.</td></tr>
 *   <tr><td>cpw.mods.fml.common.registry.ItemStackHolderRef</td><td>makeWritable</td>
 *       <td>identical code, reached from ItemStackHolderInjector at PREINIT and POSTINIT.</td></tr>
 * </table>
 *
 * <p>Body replacement rather than whole-class shadowing, deliberately. Shadowing
 * {@code EnumHelper} with our own copy cannot even be compiled: its public API names nested vanilla
 * enums ({@code EntityPainting.EnumArt}, {@code BlockPressurePlate.Sensitivity},
 * {@code StructureStrongholdPieces.Stronghold.Door}) whose OUTER classes carry no
 * {@code InnerClasses} attribute in the obfuscated Mojang jar - Mojang stripped it - so javac
 * cannot resolve the nested names, and changing the signatures would break every mod compiled
 * against Forge's descriptors. Rewriting method bodies keeps Forge's logic, its ordinals, its
 * {@code $VALUES} handling and its public descriptors exactly as they are.</p>
 *
 * <p>This is also the mechanism the behaviour lanes will want: a named, auditable list of
 * "legacy method body -&gt; umb implementation" swaps, applied by the real LaunchWrapper pipeline.</p>
 *
 * <p>G2 step 4 adds three more targets, same discipline: the STATIC
 * {@code cpw.mods.fml.common.network.internal.FMLNetworkHandler.openGui} body becomes a call to
 * {@link UmbGui#openGui} (the GUI interception point - see {@code UmbGui}'s javadoc); and
 * {@code com.hbm.handler.threading.PacketThreading}'s {@code createAllAroundThreadedPacket}/
 * {@code createSendToThreadedPacket} and {@code com.hbm.main.NetworkHandler}'s
 * {@code sendToServer}/{@code sendToDimension}/{@code sendToAllAround} (both overloads)/
 * {@code sendTo}/{@code sendToAll} are routed into the generic loopback. The HBM class remains only
 * as a fallback because it is a custom wrapper that bypasses SimpleNetworkWrapper.</p>
 */
public final class UmbShimTransformer implements IClassTransformer {

    private static final String ENUM_HELPER = "net.minecraftforge.common.util.EnumHelper";
    private static final String OBJECT_HOLDER_REF = "cpw.mods.fml.common.registry.ObjectHolderRef";
    private static final String ITEMSTACK_HOLDER_REF = "cpw.mods.fml.common.registry.ItemStackHolderRef";

    /**
 * the GUI interception point
 */
    private static final String FML_NETWORK_HANDLER = "cpw.mods.fml.common.network.internal.FMLNetworkHandler";
    private static final String CLIENT_REGISTRY = "cpw.mods.fml.client.registry.ClientRegistry";
    private static final String RENDERING_REGISTRY = "cpw/mods/fml/client/registry/RenderingRegistry";
    private static final String SIMPLE_NETWORK_WRAPPER = "cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper";
    private static final String FML_EVENT_CHANNEL = "cpw.mods.fml.common.network.FMLEventChannel";
    /** Fallback only: HBM's custom wrapper bypasses SimpleNetworkWrapper. */
    private static final String PACKET_THREADING = "com.hbm.handler.threading.PacketThreading";
    private static final String HBM_NETWORK_HANDLER = "com.hbm.main.NetworkHandler";
    /**
 * the universe has no display, so the real LWJGL2 queries can never report a key.
 * Their bodies become calls into the player-contextual mirror state .
 */
    private static final String LWJGL_KEYBOARD = "org.lwjgl.input.Keyboard";
    private static final String LWJGL_MOUSE = "org.lwjgl.input.Mouse";
    private static final String LWJGL_SYS = "org.lwjgl.Sys";
    private static final String LWJGL_DISPLAY = "org.lwjgl.opengl.Display";

    private static final String ENUM_SHIM = "dev/umb/legacy/legacyside/EnumHelperShim";
    private static final String FIELD_SHIM = "dev/umb/legacy/legacyside/UmbFieldWriteShim";
    private static final String GUI_SHIM = "dev/umb/legacy/legacyside/UmbGui";
    private static final String INPUT_SHIM = "dev/umb/legacy/legacyside/input/LegacyKeyBindingRegistry";
    private static final String LWJGL_SHIM = "dev/umb/legacy/legacyside/input/LegacyLwjglState";
    private static final String NETWORK_SHIM = "dev/umb/legacy/legacyside/network/LegacyNetworkLoopback";
    private static final String RENDER_SHIM = "dev/umb/legacy/legacyside/render/LegacyRenderCapture";
    private static final String INTERACTION_DIAG =
            "dev/umb/legacy/legacyside/LegacyInteractionDiag";
    private static final String RIDER_TRANSITION_DIAG =
            "dev/umb/legacy/legacyside/LegacyRiderTransitionDiag";
    private static final String RIDER_FIELD = "field_70153_n";
    private static final String RIDER_FIELD_DESC = "Lnet/minecraft/entity/Entity;";
    private static final String DESC_RIDER_WRITE =
            "(Lnet/minecraft/entity/Entity;Lnet/minecraft/entity/Entity;)V";
    private static final String GL11 = "org/lwjgl/opengl/GL11";
    private static final String GL13 = "org/lwjgl/opengl/GL13";
    private static final String BUFFER_UTILS = "org/lwjgl/BufferUtils";
    private static final String TESSELLATOR = "net/minecraft/client/renderer/Tessellator";
    private static final String MINECRAFT = "net/minecraft/client/Minecraft";
    private static final String RESOURCE_MANAGER =
            "Lnet/minecraft/client/resources/IResourceManager;";
    private static final String TEXTURE_MANAGER = "net/minecraft/client/renderer/texture/TextureManager";
    private static final String RENDER = "net/minecraft/client/renderer/entity/Render";
    private static final String OPEN_GL_HELPER = "net/minecraft/client/renderer/OpenGlHelper";
    private static final String MODEL_CUSTOM = "net/minecraftforge/client/model/IModelCustom";
    private static final String VBO_DELEGATE_FIELD = "umb$renderDelegate";
    /** One line per structural GL-backed model candidate and outcome. */
    private static final Set<String> VBO_DIAGNOSTICS =
            Collections.synchronizedSet(new HashSet<String>());
    private static final Set<String> RENDER_DIAGNOSTICS =
            Collections.synchronizedSet(new HashSet<String>());

    private static final String DESC_VOID = "()V";
    private static final String DESC_MAKE_ENUM =
            "(Ljava/lang/Class;Ljava/lang/String;I[Ljava/lang/Class;[Ljava/lang/Object;)Ljava/lang/Enum;";
    private static final String DESC_SET_FAILSAFE =
            "(Ljava/lang/reflect/Field;Ljava/lang/Object;Ljava/lang/Object;)V";
    private static final String DESC_MAKE_WRITABLE = "(Ljava/lang/reflect/Field;)V";
    private static final String DESC_MAKE_WRITABLE_SHIM = "(Ljava/lang/reflect/Field;Ljava/lang/Class;)V";
    private static final String DESC_OPEN_GUI =
            "(Lnet/minecraft/entity/player/EntityPlayer;Ljava/lang/Object;ILnet/minecraft/world/World;III)V";
    private static final String DESC_INTERACT =
            "(Lnet/minecraft/entity/player/EntityPlayer;)Z";

/** Legacy compatibility behavior. */
    private static final String[] PACKET_THREADING_NOOPS = {
            "createAllAroundThreadedPacket", "createSendToThreadedPacket"
    };

    /** Instance methods on {@code com.hbm.main.NetworkHandler} rewritten to a no-op body. */
    private static final String[] NETWORK_HANDLER_NOOPS = {
            "sendToServer", "sendToDimension", "sendToAllAround", "sendTo", "sendToAll"
    };

    /** Matches any void-returning descriptor, regardless of parameters - used to catch every
     *  overload of a no-op-target method name (e.g. NetworkHandler.sendToAllAround has 2). */
    private static final Pattern DESC_VOID_RETURN = Pattern.compile("\\(.*\\)V");

    /**
 * GENERALITY fix : {@link #PACKET_THREADING}/{@link #HBM_NETWORK_HANDLER} stay hardcoded and are still applied unconditionally - removing HBM's own working no-op protection is not the fix, and doing so would violate "keep default behaviour for the test mod...
 */
    private static volatile Map<String, String[]> extraNoopTargets =
            parseExtraNoopTargets(System.getProperty("umb.legacy.extraNetworkNoopClasses", ""));

    /** Test-only: overrides the parsed extra-noop-target map directly, without needing the system
     *  property set before this class's static initializer first ran (JUnit runs many test
     *  classes in one JVM, so the property-at-classload-time path is otherwise untestable once
     *  another test has already triggered class init). Pass null to restore "nothing configured". */
    public static void setExtraNoopTargetsForTest(Map<String, String[]> targets) {
        extraNoopTargets = (targets == null) ? Collections.<String, String[]>emptyMap() : targets;
    }

    public static Map<String, String[]> parseExtraNoopTargets(String spec) {
        Map<String, String[]> out = new java.util.LinkedHashMap<String, String[]>();
        if (spec == null || spec.trim().isEmpty()) {
            return out;
        }
        for (String entry : spec.split(";")) {
            String e = entry.trim();
            if (e.isEmpty()) {
                continue;
            }
            int eq = e.indexOf('=');
            if (eq <= 0 || eq == e.length() - 1) {
                continue; // malformed entry - ignored rather than crashing class-loading
            }
            String className = e.substring(0, eq).trim();
            String[] methods = e.substring(eq + 1).split(",");
            for (int i = 0; i < methods.length; i++) {
                methods[i] = methods[i].trim();
            }
            if (!className.isEmpty() && methods.length > 0) {
                out.put(className, methods);
            }
        }
        return out;
    }

    private static boolean isOneOf(String name, String[] names) {
        for (String n : names) {
            if (n.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** What actually got rewritten, for the boot report. */
    private static final List<String> APPLIED = new ArrayList<String>();

    public static List<String> applied() {
        synchronized (APPLIED) {
            return Collections.unmodifiableList(new ArrayList<String>(APPLIED));
        }
    }

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) {
            return null;
        }
        String target = transformedName != null ? transformedName : name;
        boolean enumHelper = ENUM_HELPER.equals(target) || ENUM_HELPER.equals(name);
        boolean objectHolder = OBJECT_HOLDER_REF.equals(target) || OBJECT_HOLDER_REF.equals(name);
        boolean itemStackHolder = ITEMSTACK_HOLDER_REF.equals(target) || ITEMSTACK_HOLDER_REF.equals(name);
        boolean fmlNetworkHandler = FML_NETWORK_HANDLER.equals(target) || FML_NETWORK_HANDLER.equals(name);
        boolean clientRegistry = CLIENT_REGISTRY.equals(target) || CLIENT_REGISTRY.equals(name);
        boolean simpleNetworkWrapper = SIMPLE_NETWORK_WRAPPER.equals(target)
                || SIMPLE_NETWORK_WRAPPER.equals(name);
        boolean fmlEventChannel = FML_EVENT_CHANNEL.equals(target) || FML_EVENT_CHANNEL.equals(name);
        boolean packetThreading = PACKET_THREADING.equals(target) || PACKET_THREADING.equals(name);
        boolean hbmNetworkHandler = HBM_NETWORK_HANDLER.equals(target) || HBM_NETWORK_HANDLER.equals(name);
        boolean lwjglKeyboard = LWJGL_KEYBOARD.equals(target) || LWJGL_KEYBOARD.equals(name);
        boolean lwjglMouse = LWJGL_MOUSE.equals(target) || LWJGL_MOUSE.equals(name);
        boolean lwjglSys = LWJGL_SYS.equals(target) || LWJGL_SYS.equals(name);
        boolean lwjglDisplay = LWJGL_DISPLAY.equals(target) || LWJGL_DISPLAY.equals(name);
        boolean minecraft = MINECRAFT.equals(target) || MINECRAFT.equals(name)
                || MINECRAFT.replace('/', '.').equals(target)
                || MINECRAFT.replace('/', '.').equals(name);
        Map<String, String[]> extraTargets = extraNoopTargets;
        String[] extraNoopMethods = extraTargets.get(target);
        if (extraNoopMethods == null) {
            extraNoopMethods = extraTargets.get(name);
        }
        ClassNode cn = new ClassNode();
        new ClassReader(basicClass).accept(cn, 0);
        int originalLwjglCalls = countLwjglCalls(cn);
        boolean gl15ModelCandidate = hasGl15Calls(cn);
        boolean vboAdapterPatched = rewriteVboAdapter(cn);
        if (gl15ModelCandidate) {
            logVboDiagnostic(target, cn, vboAdapterPatched);
        }
        boolean renderPatched = rewriteRenderCalls(cn);
        // The normal pass handles the known immediate-mode ABI.  Keep a second fail-closed
        // pass because model loaders frequently hide GL calls in static initializers or through
        // a less-common GL12/GL14/GL20 entry point.  No legacy class may retain a static LWJGL
        // call: class initialization can happen before the first captured vertex exists.
        int residualLwjglPatched = scrubResidualLwjglCalls(cn);
        if (originalLwjglCalls > 0) {
            logRenderDiagnostic(target, originalLwjglCalls, countLwjglCalls(cn),
                    residualLwjglPatched);
        }
        int rendererRegistrationPatched = protectRendererRegistrations(cn);
        int interactionPatched = instrumentInteractionReturns(cn);
        int riderWritePatched = instrumentRiderWrites(cn);
        if (!enumHelper && !objectHolder && !itemStackHolder
                && !fmlNetworkHandler && !clientRegistry && !simpleNetworkWrapper
                && !fmlEventChannel && !packetThreading && !hbmNetworkHandler
                && !lwjglKeyboard && !lwjglMouse && !lwjglSys && !lwjglDisplay && !minecraft
                && extraNoopMethods == null && !renderPatched && residualLwjglPatched == 0
                && interactionPatched == 0
                && rendererRegistrationPatched == 0 && !vboAdapterPatched
                && riderWritePatched == 0) {
            return basicClass;
        }

        int patched = 0;
        int expected;
        String what;
        if (rendererRegistrationPatched > 0) {
            synchronized (APPLIED) {
                APPLIED.add("renderer-registration-isolation=" + rendererRegistrationPatched);
            }
        }
        if (riderWritePatched > 0
                && rendererRegistrationPatched == 0 && interactionPatched == 0
                && !enumHelper && !objectHolder && !itemStackHolder
                && !fmlNetworkHandler && !clientRegistry && !simpleNetworkWrapper
                && !fmlEventChannel && !packetThreading && !hbmNetworkHandler
                && !lwjglKeyboard && !lwjglMouse && !lwjglSys && !lwjglDisplay && !minecraft
                && extraNoopMethods == null && !renderPatched && residualLwjglPatched == 0
                && !vboAdapterPatched) {
            expected = -1;
            patched = riderWritePatched;
            what = "Entity.field_70153_n writes -> " + RIDER_TRANSITION_DIAG;
        } else if (rendererRegistrationPatched > 0 && interactionPatched == 0
                && !enumHelper && !objectHolder && !itemStackHolder
                && !fmlNetworkHandler && !clientRegistry && !simpleNetworkWrapper
                && !fmlEventChannel && !packetThreading && !hbmNetworkHandler
                && !lwjglKeyboard && !lwjglMouse && !lwjglSys && !lwjglDisplay && !minecraft
                && extraNoopMethods == null && !renderPatched && residualLwjglPatched == 0) {
            expected = -1;
            patched = rendererRegistrationPatched;
            what = "entity renderer registration isolation=" + rendererRegistrationPatched;
        } else if (interactionPatched > 0 && !enumHelper && !objectHolder && !itemStackHolder
                && !fmlNetworkHandler && !clientRegistry && !simpleNetworkWrapper
                && !fmlEventChannel && !packetThreading && !hbmNetworkHandler
                && !lwjglKeyboard && !lwjglMouse && !lwjglSys && !lwjglDisplay && !minecraft
                && extraNoopMethods == null && !renderPatched && residualLwjglPatched == 0) {
            expected = -1;
            patched = interactionPatched;
            what = "func_130002_c return paths -> " + INTERACTION_DIAG;
        } else if (enumHelper) {
            expected = 3;
            what = "{setup, makeEnum, setFailsafeFieldValue} -> " + ENUM_SHIM;
            for (int i = 0; i < cn.methods.size(); i++) {
                MethodNode m = (MethodNode) cn.methods.get(i);
                if ("setup".equals(m.name) && DESC_VOID.equals(m.desc)) {
                    reset(m);
                    m.instructions.add(call(ENUM_SHIM, "setup", DESC_VOID));
                    m.instructions.add(new InsnNode(Opcodes.RETURN));
                    patched++;
                } else if ("makeEnum".equals(m.name) && DESC_MAKE_ENUM.equals(m.desc)) {
                    reset(m);
                    m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));  // Class enumClass
                    m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));  // String value
                    m.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));  // int ordinal
                    m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));  // Class[] types
                    m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));  // Object[] values
                    m.instructions.add(call(ENUM_SHIM, "makeEnum", DESC_MAKE_ENUM));
                    m.instructions.add(new InsnNode(Opcodes.ARETURN));
                    patched++;
                } else if ("setFailsafeFieldValue".equals(m.name) && DESC_SET_FAILSAFE.equals(m.desc)) {
                    reset(m);
                    m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));  // Field field
                    m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));  // Object target
                    m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));  // Object value
                    m.instructions.add(call(ENUM_SHIM, "setFailsafeFieldValue", DESC_SET_FAILSAFE));
                    m.instructions.add(new InsnNode(Opcodes.RETURN));
                    patched++;
                }
            }
        } else if (objectHolder || itemStackHolder) {
            expected = 1;
            what = "{makeWritable} -> " + FIELD_SHIM;
            for (int i = 0; i < cn.methods.size(); i++) {
                MethodNode m = (MethodNode) cn.methods.get(i);
                if ("makeWritable".equals(m.name) && DESC_MAKE_WRITABLE.equals(m.desc)) {
                    reset(m);
                    m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));            // Field f
                    m.instructions.add(new LdcInsnNode(Type.getObjectType(cn.name))); // owner class
                    m.instructions.add(call(FIELD_SHIM, "makeWritable", DESC_MAKE_WRITABLE_SHIM));
                    m.instructions.add(new InsnNode(Opcodes.RETURN));
                    patched++;
                }
            }
        } else if (fmlNetworkHandler) {
            expected = 1;
            what = "{openGui} -> " + GUI_SHIM;
            for (int i = 0; i < cn.methods.size(); i++) {
                MethodNode m = (MethodNode) cn.methods.get(i);
                if ("openGui".equals(m.name) && DESC_OPEN_GUI.equals(m.desc)) {
                    reset(m);
                    m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));   // EntityPlayer player
                    m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));   // Object mod
                    m.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));   // int id
                    m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));   // World world
                    m.instructions.add(new VarInsnNode(Opcodes.ILOAD, 4));   // int x
                    m.instructions.add(new VarInsnNode(Opcodes.ILOAD, 5));   // int y
                    m.instructions.add(new VarInsnNode(Opcodes.ILOAD, 6));   // int z
                    m.instructions.add(call(GUI_SHIM, "openGui", DESC_OPEN_GUI));
                    m.instructions.add(new InsnNode(Opcodes.RETURN));
                    patched++;
                }
            }
        } else if (clientRegistry) {
            expected = -1;
            what = "ClientRegistry.registerKeyBinding -> stable input id";
            for (int i = 0; i < cn.methods.size(); i++) {
                MethodNode m = (MethodNode) cn.methods.get(i);
                if ("registerKeyBinding".equals(m.name)
                        && "(Lnet/minecraft/client/settings/KeyBinding;)V".equals(m.desc)) {
                    appendKeyBindingRegistration(m);
                    patched++;
                }
            }
        } else if (simpleNetworkWrapper) {
            expected = -1;
            what = "SimpleNetworkWrapper register/send -> generic loopback";
            for (int i = 0; i < cn.methods.size(); i++) {
                MethodNode m = (MethodNode) cn.methods.get(i);
                if ("registerMessage".equals(m.name)
                        && "(Lcpw/mods/fml/common/network/simpleimpl/IMessageHandler;Ljava/lang/Class;ILcpw/mods/fml/relauncher/Side;)V".equals(m.desc)) {
                    appendMessageRegistration(m);
                    patched++;
                } else if (isSimpleSend(m)) {
                    rewriteSimpleSend(m);
                    patched++;
                }
            }
        } else if (fmlEventChannel) {
            expected = -1;
            what = "FMLEventChannel send -> generic client-effect queue";
            for (int i = 0; i < cn.methods.size(); i++) {
                MethodNode m = (MethodNode) cn.methods.get(i);
                if (isProxySend(m)) {
                    rewriteProxySend(m);
                    patched++;
                }
            }
        } else if (packetThreading) {
            expected = PACKET_THREADING_NOOPS.length;
            what = "{" + String.join(", ", PACKET_THREADING_NOOPS) + "} -> no-op";
            for (int i = 0; i < cn.methods.size(); i++) {
                MethodNode m = (MethodNode) cn.methods.get(i);
                if (isOneOf(m.name, PACKET_THREADING_NOOPS) && DESC_VOID_RETURN.matcher(m.desc).matches()) {
                    reset(m);
                    m.instructions.add(new InsnNode(Opcodes.RETURN));
                    patched++;
                }
            }
        } else if (hbmNetworkHandler) {
            expected = -1;
            what = "custom-wrapper fallback -> generic loopback";
            for (int i = 0; i < cn.methods.size(); i++) {
                MethodNode m = (MethodNode) cn.methods.get(i);
                if ("registerMessage".equals(m.name)
                        && "(Ljava/lang/Class;Ljava/lang/Class;ILcpw/mods/fml/relauncher/Side;)V".equals(m.desc)) {
                    appendClassMessageRegistration(m);
                    patched++;
                } else if (isOneOf(m.name, NETWORK_HANDLER_NOOPS) && rewriteCustomSend(m)) {
                    patched++;
                }
            }
        } else if (lwjglKeyboard) {
            expected = LWJGL_KEYBOARD_REWRITES.length;
            what = "{Keyboard queries} -> " + LWJGL_SHIM;
            for (int i = 0; i < cn.methods.size(); i++) {
                MethodNode m = (MethodNode) cn.methods.get(i);
                String[] rewrite = lwjglRewrite(LWJGL_KEYBOARD_REWRITES, m.name, m.desc);
                if (rewrite != null) {
                    rewriteLwjglCall(m, rewrite);
                    patched++;
                }
            }
        } else if (lwjglMouse) {
            expected = LWJGL_MOUSE_REWRITES.length;
            what = "{Mouse queries} -> " + LWJGL_SHIM;
            for (int i = 0; i < cn.methods.size(); i++) {
                MethodNode m = (MethodNode) cn.methods.get(i);
                String[] rewrite = lwjglRewrite(LWJGL_MOUSE_REWRITES, m.name, m.desc);
                if (rewrite != null) {
                    rewriteLwjglCall(m, rewrite);
                    patched++;
                }
            }
        } else if (minecraft) {
            expected = -1;
            what = "Minecraft.func_71386_F -> monotonic Java clock";
            for (int i = 0; i < cn.methods.size(); i++) {
                MethodNode m = (MethodNode) cn.methods.get(i);
                if ("func_71386_F".equals(m.name) && "()J".equals(m.desc)) {
                    reset(m);
                    m.instructions.add(call(LWJGL_SHIM, "safeSystemTime", "()J"));
                    m.instructions.add(new InsnNode(Opcodes.LRETURN));
                    patched++;
                }
            }
        } else if (lwjglSys) {
            expected = -1;
            what = "{Sys class init, getTime, getTimerResolution} -> monotonic Java shim";
            for (int i = 0; i < cn.methods.size(); i++) {
                MethodNode m = (MethodNode) cn.methods.get(i);
                if ("<clinit>".equals(m.name)) {
                    reset(m);
                    m.instructions.add(new InsnNode(Opcodes.RETURN));
                    patched++;
                } else if ("getTime".equals(m.name) && "()J".equals(m.desc)) {
                    reset(m);
                    m.instructions.add(call(LWJGL_SHIM, "sysGetTime", "()J"));
                    m.instructions.add(new InsnNode(Opcodes.LRETURN));
                    patched++;
                } else if ("getTimerResolution".equals(m.name) && "()J".equals(m.desc)) {
                    reset(m);
                    m.instructions.add(call(LWJGL_SHIM, "sysGetTimerResolution", "()J"));
                    m.instructions.add(new InsnNode(Opcodes.LRETURN));
                    patched++;
                } else if ((m.access & Opcodes.ACC_STATIC) != 0
                        && (m.access & Opcodes.ACC_PUBLIC) != 0) {
                    reset(m);
                    appendDefaultReturn(m);
                    patched++;
                }
            }
        } else if (lwjglDisplay) {
            expected = -1;
            what = "{Display class init and calls} -> headless Java shim";
            for (int i = 0; i < cn.methods.size(); i++) {
                MethodNode m = (MethodNode) cn.methods.get(i);
                if ("<clinit>".equals(m.name)) {
                    reset(m);
                    m.instructions.add(new InsnNode(Opcodes.RETURN));
                    patched++;
                } else if ((m.access & Opcodes.ACC_STATIC) != 0
                        && (m.access & Opcodes.ACC_PUBLIC) != 0) {
                    reset(m);
                    if ("isActive".equals(m.name) && "()Z".equals(m.desc)) {
                        m.instructions.add(call(LWJGL_SHIM, "displayIsActive", "()Z"));
                    } else if ("getAvailableDisplayModes".equals(m.name)
                            && "()[Lorg/lwjgl/opengl/DisplayMode;".equals(m.desc)) {
                        m.instructions.add(call(LWJGL_SHIM, "displayModes",
                                "()[Lorg/lwjgl/opengl/DisplayMode;"));
                    } else if (("getDisplayMode".equals(m.name)
                            || "getDesktopDisplayMode".equals(m.name))
                            && "()Lorg/lwjgl/opengl/DisplayMode;".equals(m.desc)) {
                        m.instructions.add(call(LWJGL_SHIM, "displayMode",
                                "()Lorg/lwjgl/opengl/DisplayMode;"));
                    } else {
                        appendDefaultReturn(m);
                    }
                    if (m.instructions.getLast() == null
                            || !isReturnOpcode(m.instructions.getLast().getOpcode())) {
                        m.instructions.add(new InsnNode(returnOpcode(m.desc)));
                    }
                    patched++;
                }
            }
        } else if (renderPatched || residualLwjglPatched > 0 || vboAdapterPatched) {
            expected = -1;
            what = vboAdapterPatched
                    ? "native-free model adapter plus fail-closed legacy GL/Tessellator calls -> " + RENDER_SHIM
                    : "legacy GL/Tessellator texture calls -> " + RENDER_SHIM;
        } else {
            // GENERALITY fix : a caller-supplied extra no-op target (see
            // extraNoopTargets's javadoc above) - unlike the hardcoded HBM targets above, the exact
            // overload count of a user-supplied method list is not known ahead of time, so this
            // path only requires "at least one method actually matched" rather than an exact count.
            expected = -1;
            what = "{" + String.join(", ", extraNoopMethods) + "} -> no-op (via -Dumb.legacy.extraNetworkNoopClasses)";
            for (int i = 0; i < cn.methods.size(); i++) {
                MethodNode m = (MethodNode) cn.methods.get(i);
                if (isOneOf(m.name, extraNoopMethods) && DESC_VOID_RETURN.matcher(m.desc).matches()) {
                    reset(m);
                    m.instructions.add(new InsnNode(Opcodes.RETURN));
                    patched++;
                }
            }
            if (patched == 0) {
                throw new IllegalStateException("umb shim for " + target + " (extraNetworkNoopClasses) "
                        + "matched no void method named any of " + java.util.Arrays.toString(extraNoopMethods));
            }
        }

        if (expected >= 0 && patched != expected) {
            throw new IllegalStateException("umb shim for " + target + " expected to rewrite "
                    + expected + " method(s), rewrote " + patched + " - Forge build changed?");
        }

        // Render-call fail-closed shims can be inserted into methods that still contain branches;
        // recompute frames as well as maxima so Java 25's verifier never sees stale offsets.
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cn.accept(cw);
        synchronized (APPLIED) {
            if (riderWritePatched > 0) {
                APPLIED.add(target + " Entity.field_70153_n writes -> " + RIDER_TRANSITION_DIAG
                        + " count=" + riderWritePatched);
            }
            if (interactionPatched > 0) {
                APPLIED.add(target + " func_130002_c return paths -> " + INTERACTION_DIAG);
            }
            APPLIED.add(target + " " + what);
        }
        return cw.toByteArray();
    }

    /** Instruments the universal 1.7.10 passenger field, including direct mod field writes. */
    private static int instrumentRiderWrites(ClassNode cn) {
        int patched = 0;
        for (Object mo : cn.methods) {
            MethodNode method = (MethodNode) mo;
            if (method.instructions == null) continue;
            for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
                    instruction = instruction.getNext()) {
                if (!(instruction instanceof FieldInsnNode)) continue;
                FieldInsnNode field = (FieldInsnNode) instruction;
                if (field.getOpcode() != Opcodes.PUTFIELD
                        // javac may encode an inherited field reference with either the
                        // declaring Entity owner or the concrete legacy class. Match the
                        // field identity, not an owner spelling
                        || !RIDER_FIELD.equals(field.name)
                        || !RIDER_FIELD_DESC.equals(field.desc)) continue;
                InsnList before = new InsnList();
                before.add(new InsnNode(Opcodes.DUP2));
                method.instructions.insertBefore(instruction, before);
                InsnList after = new InsnList();
                after.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RIDER_TRANSITION_DIAG,
                        "afterPassengerWrite", DESC_RIDER_WRITE, false));
                method.instructions.insert(instruction, after);
                patched++;
            }
        }
        return patched;
    }

    /**
     * A number of legacy model loaders expose a VBO wrapper around a perfectly usable,
     * display-list-free model.  Constructing that wrapper invokes GL15 while the wrapper class is
     * being initialized; even rewriting the individual GL15 call sites is too late because the
     * LWJGL GL15 class itself is initialized before the first static call.  Detect the pattern by
     * its Forge model interface and GL15 use, never by a mod/class name, and turn the wrapper into
     * a pure delegate to its source model.  The source model's ordinary render methods are then
     * rewritten by {@link #rewriteRenderCalls(ClassNode)} into the Tessellator/GL capture shim.
     *
     * <p>This is deliberately structural: any legacy mod that wraps an {@code IModelCustom} in a
     * GL15-backed adapter receives the same native-free behavior.  The delegate field is added
     * with the interface type so the transformed bytecode remains valid across model-loader
     * implementations.</p>
     */
    private static boolean rewriteVboAdapter(ClassNode cn) {
        if (cn.interfaces == null || cn.interfaces.isEmpty()) return false;
        boolean modelInterface = false;
        for (Object itf : cn.interfaces) {
            String name = String.valueOf(itf);
            if (MODEL_CUSTOM.equals(name) || name.endsWith("/IModelCustomNamed")) {
                modelInterface = true;
                break;
            }
        }
        if (!modelInterface) return false;

        if (!hasGl15Calls(cn)) return false;

        MethodNode constructor = null;
        Type delegateType = null;
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!"<init>".equals(m.name)) continue;
            Type[] args = Type.getArgumentTypes(m.desc);
            if (args.length == 1 && args[0].getSort() == Type.OBJECT) {
                constructor = m;
                delegateType = args[0];
                break;
            }
        }
        if (constructor == null || delegateType == null || !"java/lang/Object".equals(cn.superName)) {
            return false;
        }

        boolean hasField = false;
        for (Object fo : cn.fields) {
            if (fo instanceof FieldNode && VBO_DELEGATE_FIELD.equals(((FieldNode) fo).name)) {
                hasField = true;
                break;
            }
        }
        if (!hasField) {
            cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, VBO_DELEGATE_FIELD,
                    "L" + MODEL_CUSTOM + ";", null, null));
        }

        reset(constructor);
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object",
                "<init>", "()V", false));
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        constructor.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, MODEL_CUSTOM));
        constructor.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, cn.name,
                VBO_DELEGATE_FIELD, "L" + MODEL_CUSTOM + ";"));
        constructor.instructions.add(new InsnNode(Opcodes.RETURN));

        boolean changed = true;
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if ("<init>".equals(m.name) || m.desc == null) continue;
            boolean hasGl = false;
            for (AbstractInsnNode n = m.instructions == null ? null : m.instructions.getFirst();
                    n != null; n = n.getNext()) {
                if (n instanceof MethodInsnNode
                        && "org/lwjgl/opengl/GL15".equals(((MethodInsnNode) n).owner)) {
                    hasGl = true;
                    break;
                }
            }
            if (!hasGl && !"getPartNames".equals(m.name) && !"renderAll".equals(m.name)
                    && !"renderOnly".equals(m.name) && !"renderPart".equals(m.name)
                    && !"renderAllExcept".equals(m.name)) {
                continue;
            }
            if ("renderAll".equals(m.name) && "()V".equals(m.desc)) {
                delegateCall(m, cn.name, "renderAll", "()V", false);
            } else if (("renderOnly".equals(m.name) || "renderAllExcept".equals(m.name))
                    && "([Ljava/lang/String;)V".equals(m.desc)) {
                delegateCall(m, cn.name, m.name, "([Ljava/lang/String;)V", true);
            } else if ("renderPart".equals(m.name) && "(Ljava/lang/String;)V".equals(m.desc)) {
                delegateCall(m, cn.name, "renderPart", "(Ljava/lang/String;)V", true);
            } else if ("getPartNames".equals(m.name) && "()Ljava/util/List;".equals(m.desc)) {
                String namedInterface = null;
                for (Object itf : cn.interfaces) {
                    String candidate = String.valueOf(itf);
                    if (candidate.endsWith("/IModelCustomNamed")) {
                        namedInterface = candidate;
                        break;
                    }
                }
                if (namedInterface != null) {
                    reset(m);
                    m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    m.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, cn.name,
                            VBO_DELEGATE_FIELD, "L" + MODEL_CUSTOM + ";"));
                    m.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, namedInterface));
                    m.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, namedInterface,
                            "getPartNames", "()Ljava/util/List;", true));
                    m.instructions.add(new InsnNode(Opcodes.ARETURN));
                }
            } else if (hasGl) {
                reset(m);
                appendDefaultReturn(m);
            }
        }
        return changed;
    }

    /** Structural test shared by the adapter and its live classloader diagnostic. */
    private static boolean hasGl15Calls(ClassNode cn) {
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            for (AbstractInsnNode n = m.instructions == null ? null : m.instructions.getFirst();
                    n != null; n = n.getNext()) {
                if (n instanceof MethodInsnNode
                        && "org/lwjgl/opengl/GL15".equals(((MethodInsnNode) n).owner)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int countLwjglCalls(ClassNode cn) {
        int count = 0;
        for (Object mo : cn.methods) {
            MethodNode method = (MethodNode) mo;
            if (method.instructions == null) continue;
            for (AbstractInsnNode n = method.instructions.getFirst(); n != null; n = n.getNext()) {
                if (n instanceof MethodInsnNode
                        && ((MethodInsnNode) n).getOpcode() == Opcodes.INVOKESTATIC
                        && isRenderLwjglOwner(((MethodInsnNode) n).owner)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static void logRenderDiagnostic(String target, int before, int after, int scrubbed) {
        ClassLoader loader = UmbShimTransformer.class.getClassLoader();
        String key = String.valueOf(target) + "|" + before + "|" + after + "|"
                + System.identityHashCode(loader);
        if (!RENDER_DIAGNOSTICS.add(key)) return;
        System.out.println("[UMB-LEGACY] native-free-render target=" + String.valueOf(target)
                + " before=" + before + " residual=" + after + " scrubbed=" + scrubbed
                + " transformerLoader=" + loaderId(loader));
    }

    /**
     * A transformer invocation is the only reliable place to tell whether the live legacy
     * classloader actually saw a model wrapper.  LaunchWrapper can swallow transformer
     * registration failures and a second loader can define the same mod class, so the offline
     * adapter test is not enough.  Keep this one-shot and structural: no mod/class allowlist is
     * used, and a missing line means the class bypassed this transformer entirely.
     */
    private void logVboDiagnostic(String target, ClassNode cn, boolean applied) {
        ClassLoader transformerLoader = getClass().getClassLoader();
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        String key = String.valueOf(target) + "|" + applied + "|"
                + System.identityHashCode(transformerLoader) + "|"
                + System.identityHashCode(contextLoader);
        if (!VBO_DIAGNOSTICS.add(key)) return;
        System.out.println("[UMB-LEGACY] model-adapter target=" + String.valueOf(target)
                + " applied=" + applied
                + " transformerLoader=" + loaderId(transformerLoader)
                + " contextLoader=" + loaderId(contextLoader)
                + " gl15=true interfaces=" + String.valueOf(cn.interfaces));
    }

    private static String loaderId(ClassLoader loader) {
        if (loader == null) return "bootstrap";
        return loader.getClass().getName() + "@" + System.identityHashCode(loader);
    }

    private static void delegateCall(MethodNode m, String owner, String method, String desc,
                                     boolean hasArgument) {
        reset(m);
        m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        m.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, owner, VBO_DELEGATE_FIELD,
                "L" + MODEL_CUSTOM + ";"));
        if (hasArgument) m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        m.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, MODEL_CUSTOM, method,
                desc, true));
        m.instructions.add(new InsnNode(returnOpcode(desc)));
    }

    /**
     * Isolates each individual legacy entity-renderer registration statement. Client proxy
     * methods are commonly one long sequence of {@code new RenderX()} plus
     * {@code RenderingRegistry.registerEntityRenderingHandler(...)} calls. In a native client a
     * single renderer may legitimately touch GL during construction; in this headless universe
     * that one failure must not prevent later registrations from running. A failed statement is
     * swallowed and execution resumes at the next statement boundary. No mod or renderer name
     * is inspected.
     */
    private static int protectRendererRegistrations(ClassNode cn) {
        int protectedCalls = 0;
        for (Object methodObject : cn.methods) {
            MethodNode method = (MethodNode) methodObject;
            if (method.instructions == null || method.instructions.size() == 0
                    || method.desc == null || !method.desc.endsWith(")V")) {
                continue;
            }
            List<MethodInsnNode> calls = new ArrayList<MethodInsnNode>();
            for (AbstractInsnNode node = method.instructions.getFirst(); node != null;
                    node = node.getNext()) {
                if (!(node instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) node;
                if (RENDERING_REGISTRY.equals(call.owner)
                        && "registerEntityRenderingHandler".equals(call.name)) {
                    calls.add(call);
                }
            }
            if (calls.isEmpty()) continue;

            AbstractInsnNode statementStart = method.instructions.getFirst();
            List<LabelNode> handlers = new ArrayList<LabelNode>();
            List<LabelNode> resumes = new ArrayList<LabelNode>();
            for (MethodInsnNode call : calls) {
                LabelNode start = new LabelNode();
                LabelNode end = new LabelNode();
                method.instructions.insertBefore(statementStart, start);
                AbstractInsnNode afterCall = call.getNext();
                if (afterCall == null) {
                    method.instructions.add(end);
                } else {
                    method.instructions.insertBefore(afterCall, end);
                }
                LabelNode resume = new LabelNode();
                if (afterCall == null) {
                    method.instructions.add(resume);
                } else {
                    method.instructions.insertBefore(afterCall, resume);
                }
                LabelNode handler = new LabelNode();
                method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler,
                        "java/lang/Throwable"));
                handlers.add(handler);
                resumes.add(resume);
                statementStart = afterCall == null ? end : afterCall;
                protectedCalls++;
            }
            // Handlers live after the original method body, so ordinary fall-through never
            // executes them. They discard the construction/registration failure and resume at
            // the next entity statement boundary.
            for (int i = 0; i < handlers.size(); i++) {
                method.instructions.add(handlers.get(i));
                method.instructions.add(call(RENDER_SHIM, "rendererRegistrationFailure",
                        "(Ljava/lang/Throwable;)V"));
                method.instructions.add(new JumpInsnNode(Opcodes.GOTO, resumes.get(i)));
            }
        }
        return protectedCalls;
    }

/** Legacy compatibility behavior. */
    private static int instrumentInteractionReturns(ClassNode cn) {
        int patched = 0;
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!"func_130002_c".equals(m.name) || !DESC_INTERACT.equals(m.desc)
                    || (m.access & Opcodes.ACC_STATIC) != 0) {
                continue;
            }
            List<AbstractInsnNode> returns = new ArrayList<AbstractInsnNode>();
            for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
                if (n.getOpcode() == Opcodes.IRETURN) {
                    returns.add(n);
                }
            }
            if (returns.isEmpty()) {
                continue;
            }
            int resultLocal = Math.max(m.maxLocals, 2);
            m.maxLocals = resultLocal + 1;
            for (int i = 0; i < returns.size(); i++) {
                AbstractInsnNode n = returns.get(i);
                InsnList trace = new InsnList();
                trace.add(new VarInsnNode(Opcodes.ISTORE, resultLocal));
                trace.add(new VarInsnNode(Opcodes.ALOAD, 0));
                trace.add(new VarInsnNode(Opcodes.ALOAD, 1));
                trace.add(new LdcInsnNode(cn.name.replace('/', '.')));
                trace.add(new LdcInsnNode(m.name));
                trace.add(new LdcInsnNode(i));
                trace.add(new VarInsnNode(Opcodes.ILOAD, resultLocal));
                trace.add(call(INTERACTION_DIAG, "record",
                        "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;IZ)V"));
                trace.add(new VarInsnNode(Opcodes.ILOAD, resultLocal));
                m.instructions.insertBefore(n, trace);
                patched++;
            }
        }
        return patched;
    }

    /**
 * LWJGL2 query rewrites: {target name, target desc, shim name, shim desc, arg kind}.
 * Arg kind "0" loads nothing, "1" loads the first int slot, "2" loads two int slots; the return opcode follows the shim descriptor (V -&gt; RETURN, anything else -&gt;...
 */
    private static final String[][] LWJGL_KEYBOARD_REWRITES = {
            {"isKeyDown", "(I)Z", "isKeyDown", "(I)Z", "1"},
            {"next", "()Z", "next", "()Z", "0"},
            {"getEventKey", "()I", "getEventKey", "()I", "0"},
            {"getEventKeyState", "()Z", "getEventKeyState", "()Z", "0"},
            {"isRepeatEvent", "()Z", "isRepeatEvent", "()Z", "0"},
            {"poll", "()V", "noop", "()V", "0"},
            {"create", "()V", "noop", "()V", "0"},
            {"destroy", "()V", "noop", "()V", "0"},
            {"isCreated", "()Z", "isCreated", "()Z", "0"},
            {"enableRepeatEvents", "(Z)V", "setRepeatEvents", "(Z)V", "1"},
            {"areRepeatEventsEnabled", "()Z", "areRepeatEventsEnabled", "()Z", "0"},
    };

    private static final String[][] LWJGL_MOUSE_REWRITES = {
            {"isButtonDown", "(I)Z", "isButtonDown", "(I)Z", "1"},
            {"next", "()Z", "mouseNext", "()Z", "0"},
            {"getEventButton", "()I", "getEventButton", "()I", "0"},
            {"getEventButtonState", "()Z", "getEventButtonState", "()Z", "0"},
            {"getDX", "()I", "zero", "()I", "0"},
            {"getDY", "()I", "zero", "()I", "0"},
            {"getDWheel", "()I", "zero", "()I", "0"},
            {"getEventDX", "()I", "zero", "()I", "0"},
            {"getEventDY", "()I", "zero", "()I", "0"},
            {"getEventX", "()I", "zero", "()I", "0"},
            {"getEventY", "()I", "zero", "()I", "0"},
            {"getEventDWheel", "()I", "zero", "()I", "0"},
            {"poll", "()V", "noop", "()V", "0"},
            {"create", "()V", "noop", "()V", "0"},
            {"destroy", "()V", "noop", "()V", "0"},
            {"updateCursor", "()V", "noop", "()V", "0"},
            {"setGrabbed", "(Z)V", "ignoreBoolean", "(Z)V", "1"},
            {"setCursorPosition", "(II)V", "ignoreTwoInts", "(II)V", "2"},
            {"isCreated", "()Z", "isCreated", "()Z", "0"},
    };

    private static String[] lwjglRewrite(String[][] table, String name, String desc) {
        for (int i = 0; i < table.length; i++) {
            if (table[i][0].equals(name) && table[i][1].equals(desc)) {
                return table[i];
            }
        }
        return null;
    }

    private static void rewriteLwjglCall(MethodNode m, String[] rewrite) {
        reset(m);
        if ("1".equals(rewrite[4])) {
            m.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        } else if ("2".equals(rewrite[4])) {
            m.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
            m.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        }
        m.instructions.add(call(LWJGL_SHIM, rewrite[2], rewrite[3]));
        m.instructions.add(new InsnNode(returnOpcode(rewrite[3])));
    }

    private static void appendDefaultReturn(MethodNode m) {
        switch (Type.getReturnType(m.desc).getSort()) {
        case Type.VOID:
            break;
        case Type.LONG:
            m.instructions.add(new InsnNode(Opcodes.LCONST_0));
            break;
        case Type.FLOAT:
            m.instructions.add(new InsnNode(Opcodes.FCONST_0));
            break;
        case Type.DOUBLE:
            m.instructions.add(new InsnNode(Opcodes.DCONST_0));
            break;
        case Type.ARRAY:
        case Type.OBJECT:
            m.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
            break;
        default:
            m.instructions.add(new InsnNode(Opcodes.ICONST_0));
            break;
        }
        m.instructions.add(new InsnNode(returnOpcode(m.desc)));
    }

    private static int returnOpcode(String descriptor) {
        switch (Type.getReturnType(descriptor).getSort()) {
        case Type.VOID:
            return Opcodes.RETURN;
        case Type.LONG:
            return Opcodes.LRETURN;
        case Type.FLOAT:
            return Opcodes.FRETURN;
        case Type.DOUBLE:
            return Opcodes.DRETURN;
        case Type.ARRAY:
        case Type.OBJECT:
            return Opcodes.ARETURN;
        default:
            return Opcodes.IRETURN;
        }
    }

    private static boolean isReturnOpcode(int opcode) {
        return opcode == Opcodes.RETURN || opcode == Opcodes.IRETURN || opcode == Opcodes.LRETURN
                || opcode == Opcodes.FRETURN || opcode == Opcodes.DRETURN || opcode == Opcodes.ARETURN;
    }

    private static MethodInsnNode call(String owner, String method, String desc) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, owner, method, desc, false);
    }

    /** Redirects only the legacy immediate-mode calls used while a client Render is captured. */
    private static boolean rewriteRenderCalls(ClassNode cn) {
        // Do not rewrite LWJGL's own method bodies. Render callers are redirected below; changing
        // GL13/GL11 internals would make their capability lookup hit the capture fallback.
        if (cn.name.startsWith("org/lwjgl/")) {
            return false;
        }
        // Vanilla world/provider classes are not render callers; leave them to the dedicated
        // Forge world-provider compatibility transformer.
        if (cn.name.startsWith("net/minecraft/world/")) {
            return false;
        }
        boolean changed = false;
        if (RENDER.equals(cn.name)) {
            for (Object mo : cn.methods) {
                MethodNode m = (MethodNode) mo;
                if ("func_110776_a".equals(m.name)
                        && "(Lnet/minecraft/util/ResourceLocation;)V".equals(m.desc)) {
                    reset(m);
                    m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    m.instructions.add(call(RENDER_SHIM, "bindTexture",
                            "(Lnet/minecraft/util/ResourceLocation;)V"));
                    m.instructions.add(new InsnNode(Opcodes.RETURN));
                    changed = true;
                }
            }
        }
        if (TEXTURE_MANAGER.equals(cn.name)) {
            // GUIs, fonts and HUDs bind through mc.getTextureManager() rather than
            // Render.bindTexture. Inside a capture only the name matters (the host resolves the
            // image); loading it would upload through native GL and fail. Outside a capture the
            // original body runs unchanged.
            for (Object mo : cn.methods) {
                MethodNode m = (MethodNode) mo;
                if ("func_110577_a".equals(m.name)
                        && "(Lnet/minecraft/util/ResourceLocation;)V".equals(m.desc)) {
                    InsnList head = new InsnList();
                    LabelNode proceed = new LabelNode();
                    head.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    head.add(call(RENDER_SHIM, "captureBindTexture",
                            "(Lnet/minecraft/util/ResourceLocation;)Z"));
                    head.add(new JumpInsnNode(Opcodes.IFEQ, proceed));
                    head.add(new InsnNode(Opcodes.RETURN));
                    head.add(proceed);
                    m.instructions.insert(head);
                    changed = true;
                }
            }
        }
        if (OPEN_GL_HELPER.equals(cn.name)) {
            for (Object mo : cn.methods) {
                MethodNode m = (MethodNode) mo;
                if ("func_77474_a".equals(m.name) && "()V".equals(m.desc)) {
                    reset(m);
                    m.instructions.add(new InsnNode(Opcodes.RETURN));
                    changed = true;
                } else if (("func_77473_a".equals(m.name) || "func_77472_b".equals(m.name))
                        && "(I)V".equals(m.desc)) {
                    reset(m);
                    m.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
                    m.instructions.add(call(RENDER_SHIM, "glActiveTexture", "(I)V"));
                    m.instructions.add(new InsnNode(Opcodes.RETURN));
                    changed = true;
                } else if ("func_77475_a".equals(m.name) && "(IFF)V".equals(m.desc)) {
                    reset(m);
                    m.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
                    m.instructions.add(new VarInsnNode(Opcodes.FLOAD, 1));
                    m.instructions.add(new VarInsnNode(Opcodes.FLOAD, 2));
                    m.instructions.add(call(RENDER_SHIM, "glMultiTexCoord2f", "(IFF)V"));
                    m.instructions.add(new InsnNode(Opcodes.RETURN));
                    changed = true;
                }
            }
        }
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            for (org.objectweb.asm.tree.AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
                if (n instanceof FieldInsnNode) {
                    FieldInsnNode field = (FieldInsnNode) n;
                    if (field.getOpcode() == Opcodes.GETSTATIC
                            && "Lnet/minecraft/client/Minecraft;".equals(field.desc)
                            && !"net/minecraft/client/Minecraft".equals(field.owner)) {
                        m.instructions.set(field, call(RENDER_SHIM, "currentMinecraft",
                                "()Lnet/minecraft/client/Minecraft;"));
                        changed = true;
                        continue;
                    }
                }
                if (!(n instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) n;
                if (MINECRAFT.equals(call.owner) && "func_110442_L".equals(call.name)
                        && ("()" + RESOURCE_MANAGER).equals(call.desc)
                        && call.getOpcode() == Opcodes.INVOKEVIRTUAL) {
                    // The live legacy client can expose a non-null field_110451_am while the
                    // accessor body still returns null. Preserve the receiver and route the
                    // read through the native-free capture boundary, which has the authoritative
                    // resource manager for this legacy render transaction.
                    call.owner = RENDER_SHIM;
                    call.name = "currentResourceManager";
                    call.itf = false;
                    call.setOpcode(Opcodes.INVOKESTATIC);
                    call.desc = "(L" + MINECRAFT + ";)" + RESOURCE_MANAGER;
                    changed = true;
                    continue;
                }
                if (TESSELLATOR.equals(call.owner) && renderTessellatorMethod(call.name, call.desc)) {
                    call.owner = RENDER_SHIM;
                    call.itf = false;
                    call.setOpcode(Opcodes.INVOKESTATIC);
                    call.desc = withReceiver(call.desc);
                    changed = true;
                    continue;
                }
                if (BUFFER_UTILS.equals(call.owner) && call.getOpcode() == Opcodes.INVOKESTATIC
                        && rewriteBufferUtilsCall(m, call)) {
                    changed = true;
                    continue;
                }
                if (isRenderLwjglOwner(call.owner) && call.getOpcode() == Opcodes.INVOKESTATIC) {
                    if (isDisplayFocusQuery(call)) {
                        call.owner = LWJGL_SHIM;
                        call.name = "displayIsActive";
                        changed = true;
                        continue;
                    }
                    if (call.owner.startsWith("org/lwjgl/opengl/")
                            && renderGlMethod(call.name, call.desc)) {
                        call.owner = RENDER_SHIM;
                        call.itf = false;
                        changed = true;
                        continue;
                    }
                    rewriteLwjglFallback(m, call);
                    changed = true;
                    continue;
                }
                if (call.owner.startsWith("org/lwjgl/opengl/") && renderGlMethod(call.name, call.desc)) {
                    call.owner = RENDER_SHIM;
                    call.itf = false;
                    changed = true;
                }
            }
        }
        return changed;
    }

    /** All render-time LWJGL calls must terminate in a pure-Java shim, even when a new mod uses
     * a GL/Sys/Display entry point not yet represented by the capture ABI. */
    private static boolean isRenderLwjglOwner(String owner) {
        return "org/lwjgl/Sys".equals(owner)
                || BUFFER_UTILS.equals(owner)
                || owner.startsWith("org/lwjgl/opengl/");
    }

    private static boolean rewriteBufferUtilsCall(MethodNode method, MethodInsnNode original) {
        String shim;
        String desc = original.desc;
        if ("createByteBuffer".equals(original.name)
                && "(I)Ljava/nio/ByteBuffer;".equals(desc)) shim = "createByteBuffer";
        else if ("createFloatBuffer".equals(original.name)
                && "(I)Ljava/nio/FloatBuffer;".equals(desc)) shim = "createFloatBuffer";
        else if ("createIntBuffer".equals(original.name)
                && "(I)Ljava/nio/IntBuffer;".equals(desc)) shim = "createIntBuffer";
        else if ("createShortBuffer".equals(original.name)
                && "(I)Ljava/nio/ShortBuffer;".equals(desc)) shim = "createShortBuffer";
        else if ("createLongBuffer".equals(original.name)
                && "(I)Ljava/nio/LongBuffer;".equals(desc)) shim = "createLongBuffer";
        else if ("createDoubleBuffer".equals(original.name)
                && "(I)Ljava/nio/DoubleBuffer;".equals(desc)) shim = "createDoubleBuffer";
        else return false;
        method.instructions.set(original, call(RENDER_SHIM, shim, desc));
        return true;
    }

    private static void rewriteLwjglFallback(MethodNode method, MethodInsnNode original) {
        Type[] args = Type.getArgumentTypes(original.desc);
        for (int i = args.length - 1; i >= 0; i--) {
            method.instructions.insertBefore(original,
                    new InsnNode(args[i].getSize() == 2 ? Opcodes.POP2 : Opcodes.POP));
        }
        Type result = Type.getReturnType(original.desc);
        String shim;
        String desc;
        switch (result.getSort()) {
            case Type.VOID:
                method.instructions.remove(original);
                return;
            case Type.LONG:
                shim = "safeLong"; desc = "()J"; break;
            case Type.FLOAT:
                shim = "safeFloat"; desc = "()F"; break;
            case Type.DOUBLE:
                shim = "safeDouble"; desc = "()D"; break;
            case Type.OBJECT:
            case Type.ARRAY:
                shim = "safeObject"; desc = "()Ljava/lang/Object;"; break;
            default:
                shim = "safeInt"; desc = "()I"; break;
        }
        MethodInsnNode replacement = call(RENDER_SHIM, shim, desc);
        method.instructions.set(original, replacement);
        if (result.getSort() == Type.OBJECT || result.getSort() == Type.ARRAY) {
            String cast = result.getSort() == Type.ARRAY ? result.getDescriptor() : result.getInternalName();
            method.instructions.insert(replacement, new TypeInsnNode(Opcodes.CHECKCAST, cast));
        }
    }

    /**
     * Removes any static LWJGL call that survived the named ABI rewrite.  This is intentionally
     * owner-wide rather than mod- or method-specific: GL12/GL14/GL20 and vendor extension calls
     * are just as unsafe as GL11 when a legacy model class initializes on the headless loader.
     */
    /** Display.isActive() is host window focus, not a render call (mouse controls gate on it). */
    private static boolean isDisplayFocusQuery(MethodInsnNode call) {
        return "org/lwjgl/opengl/Display".equals(call.owner)
                && "isActive".equals(call.name) && "()Z".equals(call.desc);
    }

    private static int scrubResidualLwjglCalls(ClassNode cn) {
        if (cn.name.startsWith("org/lwjgl/")) return 0;
        int patched = 0;
        for (Object mo : cn.methods) {
            MethodNode method = (MethodNode) mo;
            if (method.instructions == null) continue;
            for (AbstractInsnNode n = method.instructions.getFirst(); n != null; ) {
                AbstractInsnNode next = n.getNext();
                if (n instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) n;
                    if (call.getOpcode() == Opcodes.INVOKESTATIC && isRenderLwjglOwner(call.owner)) {
                        if (isDisplayFocusQuery(call)) {
                            // Window focus is real host state, not a render call: mouse-driven
                            // controls gate on it (vehicle sticks, turrets, camera tools).
                            call.owner = LWJGL_SHIM;
                            call.name = "displayIsActive";
                        } else {
                            rewriteLwjglFallback(method, call);
                        }
                        patched++;
                    }
                }
                n = next;
            }
        }
        return patched;
    }

    private static String withReceiver(String desc) {
        return "(L" + TESSELLATOR + ";" + desc.substring(1);
    }

    private static boolean renderTessellatorMethod(String name, String desc) {
        if ("func_78381_a".equals(name)) return "()I".equals(desc);
        if ("func_78382_b".equals(name)) return "()V".equals(desc);
        if ("func_78371_b".equals(name)) return "(I)V".equals(desc);
        if ("func_78385_a".equals(name)) return "(DD)V".equals(desc);
        if ("func_78380_c".equals(name)) return "(I)V".equals(desc);
        if ("func_78386_a".equals(name)) return "(FFF)V".equals(desc);
        if ("func_78369_a".equals(name)) return "(FFFF)V".equals(desc);
        if ("func_78376_a".equals(name)) return "(III)V".equals(desc);
        if ("func_78370_a".equals(name)) return "(IIII)V".equals(desc);
        if ("func_154352_a".equals(name)) return "(BBB)V".equals(desc);
        if ("func_78374_a".equals(name)) return "(DDDDD)V".equals(desc);
        if ("func_78377_a".equals(name)) return "(DDD)V".equals(desc);
        if ("func_78378_d".equals(name)) return "(I)V".equals(desc);
        if ("func_78384_a".equals(name)) return "(II)V".equals(desc);
        if ("func_78383_c".equals(name)) return "()V".equals(desc);
        if ("func_78375_b".equals(name)) return "(FFF)V".equals(desc);
        if ("func_78373_b".equals(name)) return "(DDD)V".equals(desc);
        if ("func_78372_c".equals(name)) return "(FFF)V".equals(desc);
        return false;
    }

    private static boolean renderGlMethod(String name, String desc) {
        if ("glPushMatrix".equals(name) || "glPopMatrix".equals(name)) return "()V".equals(desc);
        if ("glTranslatef".equals(name)) return "(FFF)V".equals(desc);
        if ("glTranslated".equals(name)) return "(DDD)V".equals(desc);
        if ("glScalef".equals(name)) return "(FFF)V".equals(desc);
        if ("glScaled".equals(name)) return "(DDD)V".equals(desc);
        if ("glRotatef".equals(name)) return "(FFFF)V".equals(desc);
        if ("glRotated".equals(name)) return "(DDDD)V".equals(desc);
        if ("glColor4f".equals(name)) return "(FFFF)V".equals(desc);
        if ("glColor4ub".equals(name)) return "(BBBB)V".equals(desc);
        if ("glEnable".equals(name) || "glDisable".equals(name) || "glShadeModel".equals(name)
                || "glCullFace".equals(name)) return "(I)V".equals(desc);
        if ("glGetInteger".equals(name)) return "(I)I".equals(desc);
        if ("glBlendFunc".equals(name)) return "(II)V".equals(desc);
        if ("glDepthMask".equals(name)) return "(Z)V".equals(desc);
        if ("glAlphaFunc".equals(name)) return "(IF)V".equals(desc);
        if ("glIsEnabled".equals(name)) return "(I)Z".equals(desc);
        if ("glNormal3f".equals(name)) return "(FFF)V".equals(desc);
        if ("glBegin".equals(name)) return "(I)V".equals(desc);
        if ("glEnd".equals(name)) return "()V".equals(desc);
        if ("glTexCoord2f".equals(name)) return "(FF)V".equals(desc);
        if ("glMultiTexCoord2f".equals(name)) return "(IFF)V".equals(desc);
        if ("glActiveTexture".equals(name)) return "(I)V".equals(desc);
        if ("glColor4b".equals(name)) return "(BBBB)V".equals(desc);
        if ("glColor4d".equals(name)) return "(DDDD)V".equals(desc);
        if ("glLineWidth".equals(name) || "glPointSize".equals(name)) return "(F)V".equals(desc);
        if ("glLineStipple".equals(name)) return "(IS)V".equals(desc);
        if ("glPolygonMode".equals(name)) return "(II)V".equals(desc);
        if ("glGetFloat".equals(name)) return "(I)F".equals(desc)
                || "(ILjava/nio/FloatBuffer;)V".equals(desc);
        if ("glEnableClientState".equals(name) || "glDisableClientState".equals(name)) return "(I)V".equals(desc);
        if ("glDrawArrays".equals(name)) return "(III)V".equals(desc);
        if ("glTexCoordPointer".equals(name)) return desc.startsWith("(II") && desc.endsWith(")V");
        if ("glVertexPointer".equals(name)) return desc.startsWith("(II") && desc.endsWith(")V");
        if ("glNormalPointer".equals(name)) return desc.startsWith("(I") && desc.endsWith(")V");
        if ("glColorPointer".equals(name)) return desc.startsWith("(II") && desc.endsWith(")V");
        if ("glVertex3f".equals(name)) return "(FFF)V".equals(desc);
        if ("glVertex3d".equals(name)) return "(DDD)V".equals(desc);
        if ("glBindTexture".equals(name)) return "(II)V".equals(desc);
        if ("glGenLists".equals(name)) return "(I)I".equals(desc);
        if ("glNewList".equals(name)) return "(II)V".equals(desc);
        if ("glEndList".equals(name)) return "()V".equals(desc);
        if ("glCallList".equals(name)) return "(I)V".equals(desc);
        if ("glDeleteLists".equals(name)) return "(II)V".equals(desc);
        return false;
    }

    private static void appendKeyBindingRegistration(MethodNode m) {
        m.instructions.insertBefore(m.instructions.getLast(), new VarInsnNode(Opcodes.ALOAD, 0));
        m.instructions.insertBefore(m.instructions.getLast(), call(INPUT_SHIM, "register",
                "(Lnet/minecraft/client/settings/KeyBinding;)Ljava/lang/String;"));
        m.instructions.insertBefore(m.instructions.getLast(), new InsnNode(Opcodes.POP));
    }

    private static void appendMessageRegistration(MethodNode m) {
        insertBeforeEachReturn(m, "registerSimpleMessage",
                "(Lcpw/mods/fml/common/network/simpleimpl/IMessageHandler;Ljava/lang/Class;ILcpw/mods/fml/relauncher/Side;)V");
    }

    private static void appendClassMessageRegistration(MethodNode m) {
        insertBeforeEachReturn(m, "registerSimpleMessageClass",
                "(Ljava/lang/Class;Ljava/lang/Class;ILcpw/mods/fml/relauncher/Side;)V");
    }

    /**
     * Calls NETWORK_SHIM.method(arg1..arg4) immediately before EVERY RETURN of {@code m}. The
     * previous version inserted before {@code instructions.getLast()}, which in ASM is often a
     * trailing LabelNode/LineNumberNode, not the RETURN - the hook then landed AFTER the return as
     * unreachable code with no stack-map frame (VerifyError in com/hbm/main/NetworkHandler
     * .registerMessage @71, M1ProbeTest). Straight-line code placed right before a RETURN needs no
     * new frame, so COMPUTE_MAXS remains sufficient.
     */
    private static void insertBeforeEachReturn(MethodNode m, String method, String desc) {
        java.util.List<org.objectweb.asm.tree.AbstractInsnNode> returns = new java.util.ArrayList<>();
        for (org.objectweb.asm.tree.AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n.getOpcode() == Opcodes.RETURN) returns.add(n);
        }
        for (org.objectweb.asm.tree.AbstractInsnNode ret : returns) {
            m.instructions.insertBefore(ret, new VarInsnNode(Opcodes.ALOAD, 1));
            m.instructions.insertBefore(ret, new VarInsnNode(Opcodes.ALOAD, 2));
            m.instructions.insertBefore(ret, new VarInsnNode(Opcodes.ILOAD, 3));
            m.instructions.insertBefore(ret, new VarInsnNode(Opcodes.ALOAD, 4));
            m.instructions.insertBefore(ret, call(NETWORK_SHIM, method, desc));
        }
    }

    private static boolean isSimpleSend(MethodNode m) {
        if (!DESC_VOID_RETURN.matcher(m.desc).matches()) return false;
        if ("sendToServer".equals(m.name)) {
            return "(Lcpw/mods/fml/common/network/simpleimpl/IMessage;)V".equals(m.desc);
        }
        if ("sendToAll".equals(m.name)) {
            return "(Lcpw/mods/fml/common/network/simpleimpl/IMessage;)V".equals(m.desc);
        }
        if ("sendTo".equals(m.name)) {
            return "(Lcpw/mods/fml/common/network/simpleimpl/IMessage;Lnet/minecraft/entity/player/EntityPlayerMP;)V".equals(m.desc);
        }
        if ("sendToDimension".equals(m.name)) {
            return "(Lcpw/mods/fml/common/network/simpleimpl/IMessage;I)V".equals(m.desc);
        }
        return "sendToAllAround".equals(m.name)
                && "(Lcpw/mods/fml/common/network/simpleimpl/IMessage;Lcpw/mods/fml/common/network/NetworkRegistry$TargetPoint;)V".equals(m.desc);
    }

    private static void rewriteSimpleSend(MethodNode m) {
        rewriteOneArg(m, "sendToServer".equals(m.name) ? "captureClientToServer" : "captureServerToClient",
                "(Lcpw/mods/fml/common/network/simpleimpl/IMessage;)V");
    }

    private static boolean isProxySend(MethodNode m) {
        return DESC_VOID_RETURN.matcher(m.desc).matches()
                && m.desc.startsWith("(Lcpw/mods/fml/common/network/internal/FMLProxyPacket;");
    }

    private static void rewriteProxySend(MethodNode m) {
        rewriteOneArg(m, "sendToServer".equals(m.name) ? "captureClientToServer" : "captureServerToClient",
                "(Lcpw/mods/fml/common/network/internal/FMLProxyPacket;)V");
    }

    private static boolean rewriteCustomSend(MethodNode m) {
        if (!DESC_VOID_RETURN.matcher(m.desc).matches()) return false;
        if ("sendToServer".equals(m.name)
                && m.desc.startsWith("(Lcpw/mods/fml/common/network/simpleimpl/IMessage;")) {
            rewriteOneArg(m, "captureClientToServer",
                    "(Lcpw/mods/fml/common/network/simpleimpl/IMessage;)V");
            return true;
        }
        if (!isServerSendName(m.name)) return false;
        if (m.desc.startsWith("(Lcpw/mods/fml/common/network/simpleimpl/IMessage;")) {
            rewriteOneArg(m, "captureServerToClient",
                    "(Lcpw/mods/fml/common/network/simpleimpl/IMessage;)V");
            return true;
        }
        if (m.desc.startsWith("(Lio/netty/buffer/ByteBuf;")) {
            rewriteByteBufSend(m);
            return true;
        }
        return false;
    }

    private static boolean isServerSendName(String name) {
        return "sendToDimension".equals(name) || "sendToAllAround".equals(name)
                || "sendTo".equals(name) || "sendToAll".equals(name);
    }

    private static void rewriteOneArg(MethodNode m, String method, String desc) {
        reset(m);
        m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        m.instructions.add(call(NETWORK_SHIM, method, desc));
        m.instructions.add(new InsnNode(Opcodes.RETURN));
    }

    /** Preserve the generic wrapper owner so raw ByteBuf sends can re-enter its client channel. */
    private static void rewriteByteBufSend(MethodNode m) {
        reset(m);
        m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        m.instructions.add(call(NETWORK_SHIM, "captureServerToClient",
                "(Lio/netty/buffer/ByteBuf;Ljava/lang/Object;)V"));
        m.instructions.add(new InsnNode(Opcodes.RETURN));
    }

    private static void reset(MethodNode m) {
        m.instructions.clear();
        if (m.tryCatchBlocks != null) {
            m.tryCatchBlocks.clear();
        }
        if (m.localVariables != null) {
            m.localVariables.clear();
        }
        m.visibleLocalVariableAnnotations = null;
        m.invisibleLocalVariableAnnotations = null;
    }
}
