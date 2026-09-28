package dev.umb.guimap;

/**
 * Every identifier this module reads is a VANILLA Forge/Minecraft 1.7.10 API, resolved from
 * {@code research/repos/MinecraftForge/fml/conf/{fields,methods}.csv} and verified with
 * {@code javap} against {@code research/out/1.7.10-client-srg.jar} for methods (that jar's own
 * field names are obfuscated single letters, so field identifiers below were instead cross-checked
 * directly against the SRG names the mod jar's own compiled bytecode references — see
 * {@code umb-guimap/README.md}'s vanilla-member table and the lane's final report for the exact
 * javap transcripts). No HBM class or member name appears anywhere in this file — that is the
 * whole point of this lane (GENERALIZATION-PLAN.md GAP 2): every mod's GUI subclass reads/writes
 * these same vanilla members.
 */
public final class Vanilla {
    private Vanilla() {}

    // ---- classes ----
    public static final String GUI_CONTAINER = "net/minecraft/client/gui/inventory/GuiContainer";
    public static final String GUI_SCREEN = "net/minecraft/client/gui/GuiScreen";
    public static final String CONTAINER = "net/minecraft/inventory/Container";
    public static final String RESOURCE_LOCATION = "net/minecraft/util/ResourceLocation";
    public static final String FONT_RENDERER = "net/minecraft/client/gui/FontRenderer";
    public static final String TEXTURE_MANAGER = "net/minecraft/client/renderer/texture/TextureManager";
    public static final String STAT_COLLECTOR = "net/minecraft/util/StatCollector";
    public static final String I18N = "net/minecraft/client/resources/I18n";
    public static final String IGUI_HANDLER = "cpw/mods/fml/common/network/IGuiHandler";
    /** net.minecraft.client.gui.Gui — the vanilla base class that DECLARES {@link #M_DRAW_RECT}
     *  itself, and the superclass of {@link #GUI_SCREEN} (and so {@link #GUI_CONTAINER}) AND of
     *  every vanilla UI widget (GuiTextField, GuiButton, GuiSlider, ...) — javap-verified: {@code
     *  GuiScreen extends net.minecraft.client.gui.Gui}, {@code GuiTextField extends
     *  net.minecraft.client.gui.Gui} (see the GUARD-EXPRESSIONS lane report). A field/method whose
     *  DECLARING class is this or a subclass of it is GUI/client-rendering machinery — never
     *  tile-entity or world data — regardless of how a receiver chain reaches it; used by
     *  {@code DrawLayerScanner}'s guard-operand classifier to separate "GUI-owned client state"
     *  from a tile-entity field reached the same bytecode-shape way (e.g. a held
     *  {@code GuiTextField} reference vs. a held {@code TileEntity} reference — both are
     *  {@code this.someRef.somethingElse}, structurally identical, semantically opposite). */
    public static final String GUI_BASE = "net/minecraft/client/gui/Gui";
    /** net.minecraft.inventory.ICrafting — the listener interface a {@link #CONTAINER} pushes
     *  progress-bar updates into (SYNC-BINDING lane; methods.csv/{@code func_71112_a}'s own
     *  javadoc: "Sends two ints to the client-side Container... the FIRST int identifies WHICH
     *  VARIABLE to update, and the SECOND contains the NEW VALUE."). */
    public static final String ICRAFTING = "net/minecraft/inventory/ICrafting";

    // ---- GuiContainer fields (fields.csv) ----
    /** field_146999_f — xSize; "The X size of the inventory window in pixels." */
    public static final String F_XSIZE = "field_146999_f";
    /** field_147000_g — ySize; "The Y size of the inventory window in pixels." */
    public static final String F_YSIZE = "field_147000_g";
    /** field_147003_i — guiLeft; "Starting X position for the Gui." */
    public static final String F_GUILEFT = "field_147003_i";
    /** field_147009_r — guiTop; "Starting Y position for the Gui." */
    public static final String F_GUITOP = "field_147009_r";
    /** field_147002_h — inventorySlots; the {@link #CONTAINER} this GuiContainer displays. */
    public static final String F_CONTAINER = "field_147002_h";
    /** field_146289_q — fontRendererObj, declared on GuiScreen. */
    public static final String F_FONTRENDERER = "field_146289_q";

    // ---- GuiContainer/Gui methods (methods.csv) ----
    /** func_146976_a(float,int,int)V — drawGuiContainerBackgroundLayer (abstract on GuiContainer). */
    public static final String M_DRAW_BG = "func_146976_a";
    public static final String M_DRAW_BG_DESC = "(FII)V";
    /** func_146979_b(int,int)V — drawGuiContainerForegroundLayer. */
    public static final String M_DRAW_FG = "func_146979_b";
    public static final String M_DRAW_FG_DESC = "(II)V";
    /** func_73729_b(int,int,int,int,int,int)V — drawTexturedModalRect(x,y,u,v,width,height), on Gui. */
    public static final String M_DRAW_RECT = "func_73729_b";
    public static final String M_DRAW_RECT_DESC = "(IIIIII)V";
    /** func_73863_a(int,int,float)V — drawScreen, the sole GuiScreen render entrypoint. javap
     *  against {@code GuiScreen.class}: {@code public void func_73863_a(int, int, float)} — arg0
     *  mouseX, arg1 mouseY, arg2 partialTicks (GUARD-EXPRESSIONS lane: used to recognise a mouse
     *  coordinate operand in a GuiScreen-only class's own draw method). */
    public static final String M_DRAW_SCREEN = "func_73863_a";
    public static final String M_DRAW_SCREEN_DESC = "(IIF)V";
    /** func_110577_a(ResourceLocation)V — TextureManager.bindTexture. */
    public static final String M_BIND_TEXTURE_MANAGER = "func_110577_a";
    /** func_147499_a(ResourceLocation)V — Render/RenderLivingBase.bindTexture (alt overload). */
    public static final String M_BIND_TEXTURE_RENDER = "func_147499_a";
    /** func_110776_a(ResourceLocation)V — another bindTexture-shaped overload. */
    public static final String M_BIND_TEXTURE_ALT = "func_110776_a";
    /** func_78276_b(String,int,int,int)I — FontRenderer.drawString. */
    public static final String M_DRAW_STRING = "func_78276_b";
    /** func_78261_a(String,int,int,int)I — FontRenderer.drawStringWithShadow. */
    public static final String M_DRAW_STRING_SHADOW = "func_78261_a";
    /** func_74838_a(String)String — StatCollector.translateToLocal. */
    public static final String M_TRANSLATE = "func_74838_a";
    /** func_135052_a(String,Object...)String — I18n.format (wraps StatCollector). */
    public static final String M_I18N_FORMAT = "func_135052_a";
    /** func_71112_a(Container,int,int)V — ICrafting.sendProgressBarUpdate; methods.csv: "Sends two
     *  ints to the client-side Container... Normally the FIRST int identifies WHICH VARIABLE to
     *  update, and the SECOND contains the NEW VALUE." (SYNC-BINDING lane, server route.) */
    public static final String M_SEND_PROGRESS_BAR_UPDATE = "func_71112_a";
    public static final String M_SEND_PROGRESS_BAR_UPDATE_DESC = "(Lnet/minecraft/inventory/Container;II)V";
    /** func_75137_b(int,int)V — Container.updateProgressBar; the client-side "id -> field" half of
     *  the same mechanism, normally overridden as a plain {@code switch}/{@code if}-chain on the
     *  first int (SYNC-BINDING lane, client route). */
    public static final String M_UPDATE_PROGRESS_BAR = "func_75137_b";
    public static final String M_UPDATE_PROGRESS_BAR_DESC = "(II)V";
    /** func_75142_b()V — Container.detectAndSendChanges; not matched by name/desc alone (a mod
     *  could theoretically call {@link #M_SEND_PROGRESS_BAR_UPDATE} from elsewhere), but this is
     *  where the vanilla convention puts the server route — kept here for documentation/lookup. */
    public static final String M_DETECT_AND_SEND_CHANGES = "func_75142_b";
    public static final String M_DETECT_AND_SEND_CHANGES_DESC = "()V";

    public static boolean isBindTextureCall(String owner, String name) {
        return (TEXTURE_MANAGER.equals(owner) && M_BIND_TEXTURE_MANAGER.equals(name))
                || M_BIND_TEXTURE_RENDER.equals(name) || M_BIND_TEXTURE_ALT.equals(name);
    }

    public static boolean isTranslateCall(String owner, String name) {
        return (STAT_COLLECTOR.equals(owner) && M_TRANSLATE.equals(name))
                || (I18N.equals(owner) && M_I18N_FORMAT.equals(name));
    }

    public static boolean isDrawStringCall(String owner, String name) {
        return FONT_RENDERER.equals(owner) && (M_DRAW_STRING.equals(name) || M_DRAW_STRING_SHADOW.equals(name));
    }
}
