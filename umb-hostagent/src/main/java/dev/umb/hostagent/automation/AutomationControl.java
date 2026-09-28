package dev.umb.hostagent.automation;

import com.google.gson.*;
import dev.umb.hostagent.AgentLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.io.*;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.UUID;

/** Local in-process control plane. It never uses AWT, GLFW, or OS input. */
public final class AutomationControl {
    private static final Gson GSON = new Gson();
    private static final SecureRandom RNG = new SecureRandom();
    private static volatile AutomationControl INSTANCE;
    private final int configuredPort;
    private final String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes());
    private ServerSocket socket;

    private AutomationControl(int port) { configuredPort = port; }

    public static void startIfConfigured(java.util.Map<String,String> args) {
        String raw = args.get("automation");
        if (raw == null || raw.isBlank()) raw = System.getProperty("umb.automation.port");
        if (raw == null || raw.isBlank()) return;
        try { int p = Integer.parseInt(raw); if (p < 0 || p > 65535) throw new NumberFormatException();
            AutomationControl c = new AutomationControl(p); INSTANCE = c; c.start();
        } catch (Exception e) { AgentLog.error("automation.start", e, 5); }
    }
    private static byte[] randomBytes() { byte[] b = new byte[32]; RNG.nextBytes(b); return b; }
    private void start() throws IOException {
        socket = new ServerSocket(configuredPort, 16, InetAddress.getLoopbackAddress());
        Path gameDir = gameDirectory();
        Files.createDirectories(gameDir.resolve("logs"));
        JsonObject info = new JsonObject(); info.addProperty("port", socket.getLocalPort()); info.addProperty("token", token);
        Files.writeString(gameDir.resolve("logs/automation.json"), GSON.toJson(info), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Thread t = new Thread(this::acceptLoop, "umb-automation"); t.setDaemon(true); t.start();
        AgentLog.line("automation listening on 127.0.0.1:" + socket.getLocalPort());
    }
    private static Path gameDirectory() {
        try { return Minecraft.getInstance().gameDirectory.toPath(); } catch (Throwable ignored) { return Paths.get("."); }
    }
    private void acceptLoop() { while (true) { try { Socket s = socket.accept(); Thread t = new Thread(() -> serve(s), "umb-automation-client"); t.setDaemon(true); t.start(); } catch (IOException e) { return; } } }
    private void serve(Socket s) {
        try (s; BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8))) {
            String line; while ((line = in.readLine()) != null) {
                JsonObject req; Object id = "?";
                try { req = AutomationProtocol.parse(line); if (req.has("id")) id = req.get("id").getAsString();
                    if (!token.equals(req.has("token") ? req.get("token").getAsString() : "")) throw new SecurityException("bad token");
                    JsonElement result = dispatch(req); out.write(GSON.toJson(AutomationProtocol.ok(id, result)));
                } catch (Throwable e) { out.write(GSON.toJson(AutomationProtocol.error(id, e.toString()))); }
                out.write('\n'); out.flush();
            }
        } catch (IOException ignored) {}
    }
    private JsonElement dispatch(JsonObject r) throws Exception {
        String cmd = r.get("command").getAsString();
        if (cmd.equals("wait_ticks")) { int n=r.get("n").getAsInt(); Thread.sleep(Math.max(0,n)*50L); return new JsonPrimitive(true); }
        if (cmd.equals("read_log_tail")) {
            Path p=gameDirectory().resolve("logs/hostagent.log"); int n=r.get("n").getAsInt();
            if(!Files.exists(p)) return new JsonPrimitive("");
            java.util.List<String> lines=Files.readAllLines(p, StandardCharsets.UTF_8);
            return new JsonPrimitive(String.join("\n", lines.subList(Math.max(0, lines.size()-Math.max(0,n)), lines.size())));
        }
        if (cmd.equals("client_block_entity")) return onClient(() -> { JsonObject o=new JsonObject(); Minecraft mc=Minecraft.getInstance(); JsonObject pj=r.getAsJsonObject("pos"); net.minecraft.core.BlockPos b=new net.minecraft.core.BlockPos(pj.get("x").getAsInt(),pj.get("y").getAsInt(),pj.get("z").getAsInt()); if(mc.level==null){o.addProperty("error","no client level");return o;} o.addProperty("block",String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(b).getBlock()))); var be=mc.level.getBlockEntity(b); o.addProperty("clientBlockEntity",be==null?"none":be.getClass().getName()); if(be!=null){o.addProperty("type",String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()))); if(be instanceof dev.umb.hostagent.content.UmbLegacyBlockEntity l){o.addProperty("isLegacyCore",l.isLegacyCore());o.addProperty("legacyMetaForRender",l.legacyMetaForRender());} var rr=mc.getBlockEntityRenderDispatcher().getRenderer(be); o.addProperty("renderer",rr==null?"none":rr.getClass().getName()); o.addProperty("hasLevel",be.hasLevel()); o.addProperty("typeValid",be.getType().isValid(be.getBlockState())); try{Object st=mc.getBlockEntityRenderDispatcher().tryExtractRenderState(be,0f,null,false);o.addProperty("extractSection",String.valueOf(st)); Object st2=mc.getBlockEntityRenderDispatcher().tryExtractRenderState(be,0f,null,true);o.addProperty("extractGlobal",String.valueOf(st2));}catch(Throwable t){o.addProperty("extractError",String.valueOf(t));}} return o; });
        if (cmd.equals("screenshot")) return onClient(() -> {
            File target = resolveScreenshotTarget(r.get("path").getAsString());
            File parent = target.getAbsoluteFile().getParentFile();
            if (parent != null) parent.mkdirs();
            // Uniform exact-path write (NOT Screenshot.grab: grab always inserts a
            // "screenshots/" segment and timestamp-names, so it can never hit an exact .png
            // path - and its async name can't be echoed truthfully). takeScreenshot hands us
            Minecraft mc = Minecraft.getInstance();
            Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> {
                try { image.writeToFile(target); }
                catch (IOException e) { throw new RuntimeException(e); }
            });
            boolean exists = target.exists();
            long deadline = System.currentTimeMillis() + 5000L;
            while (!exists && System.currentTimeMillis() < deadline) {
                try { Thread.sleep(50L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                exists = target.exists();
            }
            JsonObject o = new JsonObject();
            o.addProperty("path", target.getAbsolutePath());
            o.addProperty("exists", exists);
            return o;
        });
        if (cmd.equals("client_screen")) return onClient(() -> {
            JsonObject o = new JsonObject();
            Minecraft mc = Minecraft.getInstance();
            Screen s = (mc == null || mc.gui == null) ? null : mc.gui.screen();
            if (s == null) { o.addProperty("screen", "none"); return o; }
            o.addProperty("screen", s.getClass().getName());
            o.addProperty("title", s.getTitle() == null ? "" : s.getTitle().getString());
            if (s instanceof AbstractContainerScreen<?> acs) {
                // imageWidth/imageHeight/leftPos/topPos are protected with no public getters
                o.add("imageWidth", box(containerInt(acs, "imageWidth")));
                o.add("imageHeight", box(containerInt(acs, "imageHeight")));
                o.add("leftPos", box(containerInt(acs, "leftPos")));
                o.add("topPos", box(containerInt(acs, "topPos")));
                JsonArray slots = new JsonArray();
                for (Slot slot : acs.getMenu().slots) {
                    JsonObject x = new JsonObject();
                    x.addProperty("index", slot.index);
                    x.addProperty("x", slot.x);
                    x.addProperty("y", slot.y);
                    x.addProperty("item", BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).toString());
                    slots.add(x);
                }
                o.add("slots", slots);
            }
            return o;
        });
        if (cmd.equals("gui_type")) {
            // Types through the open screen's own charTyped/keyPressed (the path real keystrokes
            // take), so text entry is testable without OS-level input. args: text, keys (GLFW).
            JsonObject args = r.has("args") && r.get("args").isJsonObject() ? r.getAsJsonObject("args") : r;
            String text = args.has("text") ? args.get("text").getAsString() : "";
            com.google.gson.JsonArray keys = args.has("keys") && args.get("keys").isJsonArray()
                    ? args.getAsJsonArray("keys") : new com.google.gson.JsonArray();
            return onClient(() -> {
                Minecraft mc = Minecraft.getInstance();
                Screen screen = (mc == null || mc.gui == null) ? null : mc.gui.screen();
                if (screen == null) throw new IllegalStateException("gui_type requires an open screen");
                JsonObject o = new JsonObject();
                o.addProperty("screen", screen.getClass().getName());
                o.addProperty("legacyTextFocused",
                        dev.umb.hostagent.content.UmbLegacyScreen.legacyTextFocused());
                int typed = 0, consumed = 0;
                for (int i = 0; i < text.length(); i++) {
                    typed++;
                    if (screen.charTyped(new net.minecraft.client.input.CharacterEvent(text.charAt(i)))) consumed++;
                }
                for (com.google.gson.JsonElement k : keys) {
                    typed++;
                    if (screen.keyPressed(new net.minecraft.client.input.KeyEvent(k.getAsInt(), 0, 0))) consumed++;
                }
                o.addProperty("typed", typed);
                o.addProperty("consumed", consumed);
                return o;
            });
        }
        if (cmd.equals("gui_click")) {
            GuiClickRequest click = parseGuiClick(r);
            return onClient(() -> {
                Minecraft mc = Minecraft.getInstance();
                Screen screen = (mc == null || mc.gui == null) ? null : mc.gui.screen();
                if (!(screen instanceof AbstractContainerScreen<?> acs)) {
                    throw new IllegalStateException("gui_click requires an open AbstractContainerScreen");
                }
                Integer left = containerInt(acs, "leftPos"), top = containerInt(acs, "topPos");
                if (left == null || top == null) {
                    throw new IllegalStateException("gui_click could not resolve GUI origin");
                }
                double screenX = left.doubleValue() + click.x();
                double screenY = top.doubleValue() + click.y();
                MouseButtonEvent event = new MouseButtonEvent(screenX, screenY,
                        new MouseButtonInfo(click.button(), 0));
                boolean pressed = screen.mouseClicked(event, false);
                boolean released = screen.mouseReleased(event);
                // Native slots and proven 26.2 widgets own their event.  Screen.mouseClicked itself
                // returns true for the parent screen over empty panel space, so the geometric
                // nativeRegion check below—not that boolean—is the double-apply guard.
                boolean legacyQueued = false;
                boolean nativeRegion = true;
                if (screen instanceof dev.umb.hostagent.content.UmbLegacyScreen legacy) {
                    nativeRegion = legacy.isNativeRegion(click.x(), click.y());
                }
                if (!nativeRegion && screen instanceof dev.umb.hostagent.content.UmbLegacyScreen legacy) {
                    legacyQueued = legacy.queueLegacyMouseClick(click.x(), click.y(), click.button());
                }
                JsonObject o = new JsonObject();
                o.addProperty("screen", screen.getClass().getName());
                o.addProperty("guiX", click.x());
                o.addProperty("guiY", click.y());
                o.addProperty("screenX", screenX);
                o.addProperty("screenY", screenY);
                o.addProperty("button", click.button());
                o.addProperty("nativePressed", pressed);
                o.addProperty("nativeReleased", released);
                o.addProperty("nativeRegion", nativeRegion);
                o.addProperty("legacyQueued", legacyQueued);
                // Screen.mouseReleased is false for an unowned 26.2 child even when the
                // legacy click packet was accepted.  Keep the public result useful while
                // retaining the raw Screen values above for diagnostics.
                o.addProperty("pressed", pressed || legacyQueued);
                o.addProperty("released", released || legacyQueued);
                return o;
            });
        }
        if (cmd.equals("open_create_world")) return onClient(() -> {
            // Title-screen button focus order varies (toasts, accessibility buttons), so blind
            // Tab/Enter navigation sometimes opened Options instead. Open the Create New World
            // "back" returns to the title screen.
            Minecraft mc = Minecraft.getInstance();
            net.minecraft.client.gui.screens.worldselection.CreateWorldScreen.openFresh(mc,
                    () -> mc.gui.setScreen(new net.minecraft.client.gui.screens.TitleScreen()));
            return new JsonPrimitive(true);
        });
        if (cmd.equals("close_menu")) return onClient(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.gui == null || mc.gui.screen() == null) return new JsonPrimitive(false);
            // (TitleScreen fallback only applies with no level loaded, which can't happen here).
            mc.gui.setScreen(null);
            return new JsonPrimitive(true);
        });
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) throw new IllegalStateException("integrated server is not loaded");
        return onServer(server, () -> dispatchServer(server, r));
    }
    private static <T> T onServer(MinecraftServer s, Callable<T> c) throws Exception { if (Thread.currentThread()==s.getRunningThread()) return c.call(); CompletableFuture<T> f=new CompletableFuture<>(); s.execute(() -> { try { f.complete(c.call()); } catch(Throwable e){f.completeExceptionally(e);} }); return f.get(30,TimeUnit.SECONDS); }
    private static <T> T onClient(Callable<T> c) throws Exception { Minecraft m=Minecraft.getInstance(); if(Thread.currentThread()==m.getRunningThread()) return c.call(); CompletableFuture<T> f=new CompletableFuture<>(); m.execute(() -> {try{f.complete(c.call());}catch(Throwable e){f.completeExceptionally(e);}}); return f.get(30,TimeUnit.SECONDS); }
    /**
     * Maps a screenshot {path} to the exact file that will be written. A directory (or any
     * non-.png path) means "<dir>/screenshots/umb-<vanilla timestamp>[-n].png" - vanilla's own
     * umb- prefix so automation shots never collide with manual F2 shots; an exact .png path
     * is honored verbatim. Pure path computation (reads only), so unit-testable headlessly.
     */
    static File resolveScreenshotTarget(String raw) {
        File given = new File(raw);
        if (!raw.toLowerCase(Locale.ROOT).endsWith(".png") || given.isDirectory()) {
            File shots = new File(given, "screenshots");
            String base = "umb-" + Util.getFilenameFormattedDateTime();
            File f = new File(shots, base + ".png");
            int n = 2;
            while (f.exists()) f = new File(shots, base + "-" + (n++) + ".png");
            return f;
        }
        return given.getAbsoluteFile();
    }
    /** Protected AbstractContainerScreen geometry (no public getters, javap-verified) - null on failure, never a guess. */
    private static Integer containerInt(AbstractContainerScreen<?> screen, String field) {
        try {
            Field f = AbstractContainerScreen.class.getDeclaredField(field);
            f.setAccessible(true);
            return (Integer) f.get(screen);
        } catch (Throwable t) { return null; }
    }
    private static JsonElement box(Integer v) { return v == null ? JsonNull.INSTANCE : new JsonPrimitive(v.intValue()); }

    static GuiClickRequest parseGuiClick(JsonObject r) {
        if (!r.has("x") || !r.has("y") || !r.has("button")) {
            throw new IllegalArgumentException("gui_click needs x, y, and button (GUI-relative coordinates)");
        }
        double x = r.get("x").getAsDouble(), y = r.get("y").getAsDouble();
        int button = r.get("button").getAsInt();
        if (!Double.isFinite(x) || !Double.isFinite(y) || x < 0 || y < 0) {
            throw new IllegalArgumentException("gui_click x/y must be finite and non-negative");
        }
        if (button < 0 || button > 2) throw new IllegalArgumentException("gui_click button must be 0, 1, or 2");
        return new GuiClickRequest(x, y, button);
    }

    record GuiClickRequest(double x, double y, int button) {}

    private JsonElement dispatchServer(MinecraftServer s, JsonObject r) throws Exception {
        ServerPlayer p=s.getPlayerList().getPlayers().stream().findFirst().orElseThrow(() -> new IllegalStateException("no player"));
        String c=r.get("command").getAsString(); JsonObject o=new JsonObject();
        if(c.equals("status")){o.addProperty("worldLoaded",p.level()!=null);o.addProperty("x",p.getX());o.addProperty("y",p.getY());o.addProperty("z",p.getZ());o.addProperty("yaw",p.getYRot());o.addProperty("pitch",p.getXRot());o.addProperty("held",BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem()).toString());o.addProperty("gamemode",p.gameMode().getSerializedName());return o;}
        if(c.equals("select_hotbar")){int slot=r.get("slot").getAsInt();if(slot<0||slot>8)throw new IllegalArgumentException("slot must be hotbar 0-8");p.getInventory().setSelectedSlot(slot);p.inventoryMenu.sendAllDataToRemote();p.connection.send(new ClientboundSetHeldSlotPacket(slot));return new JsonPrimitive(true);}
        if(c.equals("teleport")){p.teleportTo(p.level(),r.get("x").getAsDouble(),r.get("y").getAsDouble(),r.get("z").getAsDouble(),java.util.Set.of(),r.has("yaw")?r.get("yaw").getAsFloat():p.getYRot(),r.has("pitch")?r.get("pitch").getAsFloat():p.getXRot(),true);if(r.has("fly")&&r.get("fly").getAsBoolean()){p.getAbilities().flying=true;p.onUpdateAbilities();JsonObject t=new JsonObject();t.addProperty("teleported",true);t.addProperty("flying",p.getAbilities().flying);t.addProperty("creative",p.gameMode().isCreative());return t;}return new JsonPrimitive(true);}
        if(c.equals("look")){p.setYRot(r.get("yaw").getAsFloat());p.setXRot(r.get("pitch").getAsFloat());return new JsonPrimitive(true);}
        if(c.equals("give")){var item=BuiltInRegistries.ITEM.get(net.minecraft.resources.Identifier.parse(r.get("item").getAsString())).orElseThrow(() -> new IllegalArgumentException("unknown item"));int count=r.get("count").getAsInt();if(count<1)throw new IllegalArgumentException("count must be >= 1");ItemStack stack=new ItemStack(item,count);if(r.has("slot")){int slot=r.get("slot").getAsInt();if(slot<0||slot>8)throw new IllegalArgumentException("slot must be hotbar 0-8");var inv=p.getInventory();String prev=BuiltInRegistries.ITEM.getKey(inv.getItem(slot).getItem()).toString();inv.setItem(slot,stack);inv.setSelectedSlot(slot);p.inventoryMenu.sendAllDataToRemote();p.connection.send(new ClientboundSetHeldSlotPacket(slot));JsonObject g=new JsonObject();g.addProperty("slot",slot);g.addProperty("previous",prev);g.addProperty("selected",true);return g;}p.getInventory().placeItemBackInInventory(stack);return new JsonPrimitive(true);}
        if(c.equals("use_item_on")||c.equals("use_item")){InteractionHand hand=InteractionHand.MAIN_HAND; if(c.equals("use_item")){return new JsonPrimitive(p.gameMode.useItem(p,p.level(),p.getItemInHand(hand),hand).toString());} BlockPos b=pos(r.getAsJsonObject("pos"));Direction d=Direction.byName(r.get("face").getAsString());JsonObject hitJson=r.getAsJsonObject("hit");BlockHitResult hit=new BlockHitResult(new Vec3(hitJson.get("x").getAsDouble(),hitJson.get("y").getAsDouble(),hitJson.get("z").getAsDouble()),d,b,false);return new JsonPrimitive(p.gameMode.useItemOn(p,p.level(),p.getItemInHand(hand),hand,hit).toString());}
        if(c.equals("interact_entity")){Entity target=findEntity(s, UUID.fromString(r.get("uuid").getAsString()));InteractionHand hand=parseHand(r);var result=p.interactOn(target,hand,target.position());JsonObject reply=vehicleReply(p);reply.addProperty("interaction",result.toString());return reply;}
        if(c.equals("attack_entity")){Entity target=findEntity(s, UUID.fromString(r.get("uuid").getAsString()));p.attack(target);JsonObject reply=vehicleReply(p);reply.addProperty("attacked",true);return reply;}
        if(c.equals("attack")||c.equals("break")){return new JsonPrimitive(p.gameMode.destroyBlock(pos(r.getAsJsonObject("pos"))));}
        if(c.equals("block_info")){BlockPos b=pos(r.getAsJsonObject("pos"));var st=p.level().getBlockState(b);o.addProperty("id",BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString());o.addProperty("state",st.toString());BlockEntity be=p.level().getBlockEntity(b);if(be!=null)o.addProperty("blockEntity",be.getClass().getName());if(be instanceof dev.umb.hostagent.content.UmbLegacyBlockEntity lbe)o.addProperty("legacyTile",lbe.legacyTileStatus());if(p.level() instanceof net.minecraft.server.level.ServerLevel sl&&st.getBlock() instanceof dev.umb.hostagent.content.UmbLegacyBlock)o.addProperty("legacyMeta",dev.umb.hostagent.content.UmbMetadataSavedData.get(sl).getMeta(b.getX(),b.getY(),b.getZ()));return o;}
        if(c.equals("legacy_entities")){return describeLegacyEntities(p);}
        if(c.equals("legacy_tile")){return describeLegacyTile(p, pos(r.getAsJsonObject("pos")));}
        if(c.equals("click_slot")){AbstractContainerMenu m=p.containerMenu;ContainerInput in=ContainerInput.valueOf(r.get("clickType").getAsString().toUpperCase());m.clicked(r.get("index").getAsInt(),r.get("button").getAsInt(),in,p);return new JsonPrimitive(true);}
        if(c.equals("open_menu")){AbstractContainerMenu m=p.containerMenu;o.addProperty("type",String.valueOf(m.getType()));o.addProperty("slots",m.slots.size());o.addProperty("panelWidth",176);o.addProperty("panelHeight",166);JsonArray slots=new JsonArray();for(var slot:m.slots){JsonObject x=new JsonObject();x.addProperty("index",m.slots.indexOf(slot));x.addProperty("x",slot.x);x.addProperty("y",slot.y);x.addProperty("item",BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).toString());slots.add(x);}o.add("slotInfo",slots);return o;}
        if(c.equals("press_legacy_key")){String stableId=r.has("stableId")?r.get("stableId").getAsString():null;if(stableId==null||stableId.isBlank())throw new IllegalArgumentException("press_legacy_key needs stableId (a legacy:key:... id from the input plans)");dev.umb.hostagent.input.LegacyAutomationKeys.press(stableId);dev.umb.hostagent.input.LegacyInputDiag.loud("automation press: " + stableId);JsonObject key=new JsonObject();key.addProperty("pressed",true);key.addProperty("stableId",stableId);return key;}
        if(c.equals("run_command")){s.getCommands().performPrefixedCommand(p.createCommandSourceStack().withPermission(net.minecraft.server.permissions.PermissionSet.ALL_PERMISSIONS)/* javap 26.2: CommandSourceStack.withPermission(PermissionSet); works in worlds without cheats */,r.get("text").getAsString());return new JsonPrimitive(true);}
        throw new IllegalArgumentException("unknown command: "+c);
    }
    private static BlockPos pos(JsonObject o){return new BlockPos(o.get("x").getAsInt(),o.get("y").getAsInt(),o.get("z").getAsInt());}
    /** 26.2 javap: ServerPlayer.interactOn(Entity, InteractionHand, Vec3) and attack(Entity). */
    private static Entity findEntity(MinecraftServer server, UUID uuid) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity e = level.getEntity(uuid);
            if (e != null && !e.isRemoved()) return e;
        }
        throw new IllegalArgumentException("entity not found: " + uuid);
    }
    static InteractionHand parseHand(JsonObject r) {
        String raw = r.has("hand") ? r.get("hand").getAsString().toLowerCase(Locale.ROOT) : "main";
        return switch (raw) {
            case "main", "main_hand" -> InteractionHand.MAIN_HAND;
            case "off", "off_hand" -> InteractionHand.OFF_HAND;
            default -> throw new IllegalArgumentException("hand must be main or off");
        };
    }
    private static JsonObject vehicleReply(ServerPlayer p) {
        JsonObject o = new JsonObject(); Entity v = p.getVehicle();
        o.addProperty("riding", v != null);
        if (v != null) {
            o.addProperty("vehicleUuid", v.getUUID().toString());
            o.addProperty("vehicleClass", v.getClass().getName());
            o.addProperty("vehicleType", String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(v.getType())));
        }
        return o;
    }

    /** Lists the live generic twins; all values are read from the current server-side entities. */
    private static JsonObject describeLegacyEntities(ServerPlayer p) {
        JsonObject out = new JsonObject();
        JsonArray rows = new JsonArray();
        for (net.minecraft.world.entity.Entity raw : p.level().getAllEntities()) {
            if (!(raw instanceof dev.umb.hostagent.content.UmbLegacyEntity entity) || entity.isRemoved()) {
                continue;
            }
            JsonObject row = new JsonObject();
            row.addProperty("class", entity.legacyClassName());
            row.addProperty("id", entity.legacyClassId());
            row.addProperty("x", entity.getX());
            row.addProperty("y", entity.getY());
            row.addProperty("z", entity.getZ());
            row.addProperty("twinUuid", entity.getUUID().toString());
            row.addProperty("riders", entity.getPassengers().size());
            rows.add(row);
        }
        out.add("entities", rows);
        out.addProperty("count", rows.size());
        return out;
    }

    /**
     * plus every declared instance field (name, type, display value) through the live
     * {@code TileHandle} on the SERVER thread. Bounded output (the handle caps field count
     * and value length); unreadable fields report present=false. A missing block entity, a
     * non-legacy block entity, or a tile with no live handle yields an explicit status
     * object, never an exception to the caller.
     */
    private static JsonObject describeLegacyTile(ServerPlayer p, BlockPos b) {
        JsonObject o = new JsonObject();
        o.addProperty("id", BuiltInRegistries.BLOCK.getKey(p.level().getBlockState(b).getBlock()).toString());
        BlockEntity be = p.level().getBlockEntity(b);
        if (be == null) {
            o.addProperty("status", "no-block-entity");
            return o;
        }
        o.addProperty("blockEntity", be.getClass().getName());
        if (!(be instanceof dev.umb.hostagent.content.UmbLegacyBlockEntity lbe)) {
            o.addProperty("status", "not-legacy");
            return o;
        }
        o.addProperty("legacyTile", lbe.legacyTileStatus());
        dev.umb.bridge.api.TileHandle h = lbe.legacyHandle();
        if (h == null || !h.isValid()) {
            o.addProperty("status", h == null ? "no-handle" : "invalid-handle");
            return o;
        }
        java.util.List<dev.umb.bridge.api.TileFieldDatum> fields;
        try {
            fields = h.describeFields();
        } catch (Throwable t) {
            o.addProperty("status", "describe-threw");
            o.addProperty("error", String.valueOf(t));
            return o;
        }
        // Each row carries its declaring owner, so the response names the live TE class
        // (first row's owner chain root is the runtime class) without a second round trip.
        o.addProperty("status", "ok");
        o.addProperty("handleClass", h.getClass().getName());
        JsonArray rows = new JsonArray();
        if (fields != null) {
            for (dev.umb.bridge.api.TileFieldDatum d : fields) {
                if (d == null) continue;
                JsonObject row = new JsonObject();
                row.addProperty("owner", String.valueOf(d.owner));
                row.addProperty("name", String.valueOf(d.name));
                row.addProperty("type", String.valueOf(d.type));
                if (d.present && d.value != null) row.addProperty("value", d.value);
                else row.addProperty("present", false);
                rows.add(row);
            }
        }
        o.add("fields", rows);
        o.addProperty("fieldCount", rows.size());
        if (rows.size() > 0 && rows.get(0).isJsonObject()) {
            JsonObject first = rows.get(0).getAsJsonObject();
            if (first.has("owner")) o.addProperty("teClass", first.get("owner").getAsString());
        }
        return o;
    }
}
