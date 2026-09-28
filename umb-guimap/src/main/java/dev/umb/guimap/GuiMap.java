package dev.umb.guimap;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Entry point: {@code GuiMap <mod.jar> <out.json>}.
 *
 * Static bytecode analysis only — no game is launched, nothing from the mod jar is executed.
 * Finds every concrete {@code GuiContainer}/{@code GuiScreen} subclass in the jar and extracts,
 * from vanilla APIs alone, its panel size, background texture, draw calls and paired Container.
 * See {@code umb-guimap/README.md} for the schema and design notes.
 */
public final class GuiMap {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: GuiMap <mod.jar> <out.json>");
            System.exit(2);
        }
        Path jarPath = Paths.get(args[0]);
        Path outPath = Paths.get(args[1]);
        if (outPath.getParent() != null) Files.createDirectories(outPath.getParent());

        long t0 = System.currentTimeMillis();
        log("reading jar " + jarPath);
        JarIndex jar = JarIndex.open(jarPath);
        log("  " + jar.classes.size() + " classes, " + jar.assets.size() + " assets");

        GuiScanner scanner = new GuiScanner(jar);
        List<GuiScanner.Candidate> candidates = scanner.scan();
        int guiContainerCount = 0, guiScreenOnlyCount = 0;
        for (GuiScanner.Candidate c : candidates) {
            if (c.kind == GuiScanner.Kind.GUI_CONTAINER) guiContainerCount++; else guiScreenOnlyCount++;
        }
        log("  " + guiContainerCount + " GuiContainer subclasses, " + guiScreenOnlyCount + " GuiScreen-only subclasses");

        FieldConstResolver resolver = new FieldConstResolver(jar);
        ContainerPairer pairer = new ContainerPairer(jar);
        GuiClassAnalyzer analyzer = new GuiClassAnalyzer(jar, resolver, pairer);

        JsonArray guis = new JsonArray();
        for (GuiScanner.Candidate c : candidates) guis.add(analyzer.analyze(c));

        GuiHandlerScanner handlerScanner = new GuiHandlerScanner(jar);
        List<GuiHandlerScanner.Pairing> handlerPairings = handlerScanner.scan();
        JsonArray handlerRows = new JsonArray();
        Map<String, GuiHandlerScanner.Pairing> byGuiClass = new HashMap<>();
        for (GuiHandlerScanner.Pairing p : handlerPairings) {
            JsonObject o = new JsonObject();
            o.addProperty("guiId", p.guiId);
            o.addProperty("handlerClass", p.handlerClass);
            o.addProperty("guiClass", p.guiClass);
            o.addProperty("containerClass", p.containerClass);
            handlerRows.add(o);
            if (p.guiClass != null) byGuiClass.put(p.guiClass, p);
        }
        for (var e : guis) {
            JsonObject row = e.getAsJsonObject();
            GuiHandlerScanner.Pairing p = byGuiClass.get(row.get("className").getAsString());
            if (p == null) continue;
            JsonObject container = row.getAsJsonObject("container");
            String ctorContainer = container.has("className") && !container.get("className").isJsonNull()
                    ? container.get("className").getAsString() : null;
            JsonObject cross = new JsonObject();
            cross.addProperty("guiId", p.guiId);
            cross.addProperty("handlerClass", p.handlerClass);
            cross.addProperty("containerClassFromHandler", p.containerClass);
            cross.addProperty("agreesWithConstructorPairing",
                    p.containerClass != null && p.containerClass.equals(ctorContainer));
            row.add("guiHandlerCrossCheck", cross);
        }

        // ---- coverage ----
        int sizeResolved = 0, texResolved = 0, texExistsInJar = 0;
        int rectsStatic = 0, rectsDynamic = 0, rectsConditional = 0;
        int containersExact = 0, containersInferred = 0, containersNone = 0, containersUnresolved = 0;
        // Per-argument classification counts (all six args of every drawTexturedModalRect call,
        // not just u/v/w/h) — see DrawLayerScanner's classifyInt. These are what actually measure
        // progress on the 266-previously-dynamic-rects problem: a rect can carry the row-level
        // "dynamic" flag (still true if ANY u/v/w/h arg is genuinely UNRESOLVED) while most of its
        // arguments have already moved to CONST/PANEL_RELATIVE/STATE_LINEAR.
        int argConst = 0, argPanelRelative = 0, argStateLinear = 0, argUnresolved = 0;
        int originGui = 0, originContainer = 0, originTileViaContainer = 0, originTileViaGui = 0, originOther = 0;
        int rectsWithNoTextureBind = 0;
        int buttonsExtracted = 0, buttonsContainer = 0, buttonsPacket = 0, buttonsPacketRoutable = 0, buttonsUnresolved = 0;
        // SYNC-BINDING lane (schemaVersion 4) — see ContainerSyncScanner. Counted per GUI ROW (not
        // deduplicated by container class), matching every other per-row counter in this loop.
        int syncBindingsTotal = 0, syncBindingsAgree = 0, syncBindingsDisagree = 0,
                syncBindingsServerOnly = 0, syncBindingsClientOnly = 0, guisWithSyncBindings = 0;
        int rectsWithFieldCondition = 0;
        // TILE-FIELD-REQUIREMENTS lane (schemaVersion 5) — see DrawLayerScanner.fieldRequirementJson
        // and research/out/legacy/guimap-notes/TILE-FIELD-REQUIREMENTS.md. Counts how many of the
        // already-existing STATE_LINEAR numerator/divisor sources and guarded-rect fieldConditions
        // additionally resolved a concrete tile-entity field-access chain (never changes whether an
        // arg IS classified STATE_LINEAR, nor its origin bucket - purely a second, independent
        // resolution over the same data). tileEntityFields aggregates the DISTINCT
        // (tileEntityClass, field-access path) set corpus-wide - the per-tile-entity-class summary a
        // future host-side snapshot would implement.
        int fieldReqNumeratorResolved = 0, fieldReqDivisorResolved = 0, fieldReqGuardResolved = 0;
        // GUARD-EXPRESSIONS lane (schemaVersion 6): the four-way partition of every guarded rect
        // (rectsConditional) by the minimal capability a host would need to evaluate its full
        // skipCondition expression tree live — see DrawLayerScanner.classifyGuardNeed and
        // research/out/legacy/guimap-notes/GUARD-EXPRESSIONS.md for the exact denominators and the
        // adversarial check against the prior lane's "361 unresolvable by ANY mechanism" claim.
        int guardNeedTileFieldsOnly = 0, guardNeedTileFieldsAndMouse = 0,
                guardNeedTileFieldsMouseAndGuiState = 0, guardNeedOpaque = 0, guardNeedsMethodCall = 0;
        Map<String, Map<String, JsonObject>> tileEntityFields = new java.util.LinkedHashMap<>();
        Map<String, Set<String>> tileEntityGuiUsers = new java.util.LinkedHashMap<>();
        for (var e : guis) {
            JsonObject row = e.getAsJsonObject();
            String guiClassName = row.get("className").getAsString();
            if (row.has("buttons")) {
                for (var b : row.getAsJsonArray("buttons")) {
                    buttonsExtracted++;
                    String route = b.getAsJsonObject().get("handler").getAsString();
                    if ("container".equals(route)) buttonsContainer++;
                    else if ("packet".equals(route)) {
                        buttonsPacket++;
                        if (b.getAsJsonObject().has("packetRecipe")
                                && b.getAsJsonObject().getAsJsonObject("packetRecipe").has("resolved")
                                && b.getAsJsonObject().getAsJsonObject("packetRecipe").get("resolved").getAsBoolean()) {
                            buttonsPacketRoutable++;
                        }
                    }
                    else buttonsUnresolved++;
                }
            }
            JsonObject size = row.getAsJsonObject("size");
            if (!"unresolved".equals(size.get("confidence").getAsString())) sizeResolved++;

            for (String key : new String[]{"backgroundTextures"}) {
                if (!row.has(key)) continue;
                for (var t : row.getAsJsonArray(key)) {
                    JsonObject to = t.getAsJsonObject();
                    if (to.get("resolved").getAsBoolean()) {
                        texResolved++;
                        if (to.has("existsInJar") && to.get("existsInJar").getAsBoolean()) texExistsInJar++;
                    }
                }
            }
            for (String key : new String[]{"backgroundDrawRects", "foregroundDrawRects"}) {
                if (!row.has(key)) continue;
                for (var r : row.getAsJsonArray(key)) {
                    JsonObject rect = r.getAsJsonObject();
                    if (rect.get("dynamic").getAsBoolean()) rectsDynamic++; else rectsStatic++;
                    if (rect.has("conditional") && rect.getAsJsonObject("conditional").get("guarded").getAsBoolean()) {
                        rectsConditional++;
                        JsonObject cond = rect.getAsJsonObject("conditional");
                        if (cond.has("fieldCondition")) {
                            rectsWithFieldCondition++;
                            JsonObject fcSource = cond.getAsJsonObject("fieldCondition").getAsJsonObject("source");
                            if (fcSource.has("fieldRequirement")) {
                                fieldReqGuardResolved++;
                                recordFieldRequirement(tileEntityFields, tileEntityGuiUsers, guiClassName, fcSource);
                            }
                        }
                        // GUARD-EXPRESSIONS lane (schemaVersion 6) — see DrawLayerScanner.classifyGuardNeed
                        // / research/out/legacy/guimap-notes/GUARD-EXPRESSIONS.md. Every guarded rect now
                        // carries conditional.guardNeeds (computed from the new conditional.skipCondition
                        // expression tree), independent of whether the older single-frame fieldCondition
                        // above happened to resolve - these four buckets partition rectsConditional exactly.
                        if (cond.has("guardNeeds")) {
                            JsonObject need = cond.getAsJsonObject("guardNeeds");
                            switch (need.get("level").getAsString()) {
                                case "tileFieldsOnly" -> guardNeedTileFieldsOnly++;
                                case "tileFieldsAndMouse" -> guardNeedTileFieldsAndMouse++;
                                case "tileFieldsMouseAndGuiState" -> guardNeedTileFieldsMouseAndGuiState++;
                                default -> guardNeedOpaque++;
                            }
                            if (need.get("needsMethodCall").getAsBoolean()) guardNeedsMethodCall++;
                        }
                    }
                    if (rect.has("textureBindIndex") && rect.get("textureBindIndex").getAsInt() < 0) rectsWithNoTextureBind++;
                    for (var a : rect.getAsJsonArray("args")) {
                        JsonObject arg = a.getAsJsonObject();
                        String cls = arg.has("classification") ? arg.get("classification").getAsString() : "UNRESOLVED";
                        switch (cls) {
                            case "CONST" -> argConst++;
                            case "PANEL_RELATIVE" -> argPanelRelative++;
                            case "STATE_LINEAR" -> {
                                argStateLinear++;
                                JsonObject binding = arg.getAsJsonObject("stateBinding");
                                JsonObject source = binding.getAsJsonObject("source");
                                String origin = source.get("origin").getAsString();
                                switch (origin) {
                                    case "gui" -> originGui++;
                                    case "container" -> originContainer++;
                                    case "tileEntityViaContainer" -> originTileViaContainer++;
                                    case "tileEntityViaGui" -> originTileViaGui++;
                                    default -> originOther++;
                                }
                                if (source.has("fieldRequirement")) {
                                    fieldReqNumeratorResolved++;
                                    recordFieldRequirement(tileEntityFields, tileEntityGuiUsers, guiClassName, source);
                                }
                                JsonObject divisor = binding.getAsJsonObject("divisor");
                                if ("field".equals(divisor.get("kind").getAsString()) && divisor.has("fieldRequirement")) {
                                    fieldReqDivisorResolved++;
                                    recordFieldRequirement(tileEntityFields, tileEntityGuiUsers, guiClassName, divisor);
                                }
                            }
                            default -> argUnresolved++;
                        }
                    }
                }
            }
            JsonObject containerObj = row.getAsJsonObject("container");
            String conf = containerObj.get("confidence").getAsString();
            switch (conf) {
                case "exact" -> containersExact++;
                case "inferred" -> containersInferred++;
                case "none" -> containersNone++;
                default -> containersUnresolved++;
            }
            if (containerObj.has("syncBindings")) {
                JsonArray bindings = containerObj.getAsJsonArray("syncBindings");
                if (bindings.size() > 0) guisWithSyncBindings++;
                for (var b : bindings) {
                    JsonObject bo = b.getAsJsonObject();
                    syncBindingsTotal++;
                    boolean server = bo.get("serverRoute").getAsBoolean();
                    boolean client = bo.get("clientRoute").getAsBoolean();
                    if (server && client) {
                        if (bo.get("agree").getAsBoolean()) syncBindingsAgree++; else syncBindingsDisagree++;
                    } else if (server) {
                        syncBindingsServerOnly++;
                    } else {
                        syncBindingsClientOnly++;
                    }
                }
            }
        }

        // TILE-FIELD-REQUIREMENTS lane (schemaVersion 5): the per-tile-entity-class field summary a
        // future host-side snapshot would implement — the DISTINCT set of {tileEntityClass, field
        // path} pairs required across the whole corpus, each with its full hop chain and every GUI
        // class that needs it. Built from tileEntityFields/tileEntityGuiUsers, collected additively
        // in the loop above (never fed back into the per-arg "origin"/coverage counts above it).
        JsonArray tileEntityFieldRequirements = new JsonArray();
        int distinctTileEntityFieldsRequired = 0;
        int distinctFieldsViaPlainField = 0, distinctFieldsViaAccessor = 0, distinctFieldsDeepChain = 0;
        for (var entry : tileEntityFields.entrySet()) {
            String teClass = entry.getKey();
            Map<String, JsonObject> fields = entry.getValue();
            JsonObject cls = new JsonObject();
            cls.addProperty("tileEntityClass", teClass);
            JsonArray fieldsArr = new JsonArray();
            for (JsonObject f : fields.values()) fieldsArr.add(f);
            cls.add("fields", fieldsArr);
            cls.addProperty("fieldCount", fields.size());
            Set<String> guiSet = tileEntityGuiUsers.getOrDefault(teClass, java.util.Collections.emptySet());
            JsonArray guiArr = new JsonArray();
            for (String g : guiSet) guiArr.add(g);
            cls.add("guiClasses", guiArr);
            cls.addProperty("guiClassCount", guiSet.size());
            tileEntityFieldRequirements.add(cls);
            distinctTileEntityFieldsRequired += fields.size();
            for (JsonObject f : fields.values()) {
                if ("accessor".equals(f.get("leafKind").getAsString())) distinctFieldsViaAccessor++; else distinctFieldsViaPlainField++;
                if (f.getAsJsonArray("hops").size() > 1) distinctFieldsDeepChain++;
            }
        }

        JsonObject root = new JsonObject();
        JsonObject meta = new JsonObject();
        meta.addProperty("schemaVersion", 7); // v7: +per-GUI GuiButton/raw-mouse control records
                // per-arg classification ("classification"/"stateBinding" on every
                // drawTexturedModalRect arg) and per-rect "conditional" guard capture, see
                // research/out/legacy/guimap-notes/DYNAMIC-RECTS.md. v3: +"textureBindIndex" on
                // every drawTexturedModalRect row (index into this GUI's "backgroundTextures" list
                // of the most recently bound texture at the point the rect is drawn; -1 if the
                // method never bound one of its own) — strictly additive, see
                // research/out/legacy/guimap-notes/SCREEN-RENDER.md. v4: +"container.syncBindings"
                // (the field <-> ICrafting sync-register-id map for an exactly-paired Container
                // class, cross-checked between the server func_71112_a and client func_75137_b
                // routes) — strictly additive, see research/out/legacy/guimap-notes/SYNC-BINDING.md.
                // v5: +"fieldDesc"/"fieldRequirement" on every STATE_LINEAR source/divisor and every
                // guarded rect's conditional.fieldCondition.source (the concrete tile-entity class
                // and bounded field-access chain a host snapshot would need), plus the top-level
                // "tileEntityFieldRequirements" per-tile-entity-class summary array — strictly
                // additive, see research/out/legacy/guimap-notes/TILE-FIELD-REQUIREMENTS.md
                // v6: +"conditional.skipCondition" (a general AND/OR/COMPARE boolean expression tree
                // over EVERY guard frame, both real operands recorded for each comparison — not just
                // the pre-existing single-frame getfield-vs-0 "fieldCondition" shape) and
                // "conditional.guardNeeds" (the minimal capability tier — tileFieldsOnly /
                // tileFieldsAndMouse / tileFieldsMouseAndGuiState / opaque, plus a needsMethodCall
                // flag and, when opaque, an opaqueCauses breakdown — derived from skipCondition) on
                // every guarded drawTexturedModalRect row; the MethodSim LCMP/FCMPx/DCMPx fix that
                // recovers a bare "compare" guard's real two operands — strictly additive, see
                // research/out/legacy/guimap-notes/GUARD-EXPRESSIONS.md
        meta.addProperty("generatedAt", Instant.now().toString());
        meta.addProperty("sourceJar", jarPath.getFileName().toString());
        meta.addProperty("tool", "dev.umb.guimap.GuiMap");
        meta.addProperty("note", "Static bytecode analysis only; no game launched. Reads only vanilla"
                + " GuiContainer/GuiScreen/Gui/FontRenderer/TextureManager/ResourceLocation/StatCollector/I18n"
                + " APIs (see Vanilla.java) — generic for any 1.7.10 Forge mod.");
        root.add("meta", meta);

        JsonObject coverage = new JsonObject();
        coverage.addProperty("guiContainerSubclasses", guiContainerCount);
        coverage.addProperty("guiScreenOnlySubclasses", guiScreenOnlyCount);
        coverage.addProperty("totalGuiClasses", candidates.size());
        coverage.addProperty("sizeResolved", sizeResolved);
        coverage.addProperty("backgroundTextureResolved", texResolved);
        coverage.addProperty("backgroundTextureExistsInJar", texExistsInJar);
        coverage.addProperty("drawTexturedModalRectStatic", rectsStatic);
        coverage.addProperty("drawTexturedModalRectDynamic", rectsDynamic);
        coverage.addProperty("drawTexturedModalRectConditional", rectsConditional);
        coverage.addProperty("drawTexturedModalRectNoTextureBind", rectsWithNoTextureBind);
        // Per-argument classification (all 6 args of every rect) — see DrawLayerScanner.classifyInt
        // and research/out/legacy/guimap-notes/DYNAMIC-RECTS.md for the schema and worked evidence.
        coverage.addProperty("drawArgsConst", argConst);
        coverage.addProperty("drawArgsPanelRelative", argPanelRelative);
        coverage.addProperty("drawArgsStateLinear", argStateLinear);
        coverage.addProperty("drawArgsUnresolved", argUnresolved);
        coverage.addProperty("stateLinearOriginGui", originGui);
        coverage.addProperty("stateLinearOriginContainer", originContainer);
        coverage.addProperty("stateLinearOriginTileEntityViaContainer", originTileViaContainer);
        coverage.addProperty("stateLinearOriginTileEntityViaGui", originTileViaGui);
        coverage.addProperty("stateLinearOriginOther", originOther);
        coverage.addProperty("containersPairedExact", containersExact);
        coverage.addProperty("containersPairedInferred", containersInferred);
        coverage.addProperty("containersNone", containersNone);
        coverage.addProperty("containersUnresolved", containersUnresolved);
        coverage.addProperty("guiHandlerCrossCheckPairings", handlerPairings.size());
        // SYNC-BINDING lane (schemaVersion 4) — see ContainerSyncScanner / SYNC-BINDING.md.
        coverage.addProperty("guisWithSyncBindings", guisWithSyncBindings);
        coverage.addProperty("containerSyncBindingsTotal", syncBindingsTotal);
        coverage.addProperty("containerSyncBindingsAgree", syncBindingsAgree);
        coverage.addProperty("containerSyncBindingsDisagree", syncBindingsDisagree);
        coverage.addProperty("containerSyncBindingsServerOnly", syncBindingsServerOnly);
        coverage.addProperty("containerSyncBindingsClientOnly", syncBindingsClientOnly);
        coverage.addProperty("drawTexturedModalRectGuardedWithFieldCondition", rectsWithFieldCondition);
        // TILE-FIELD-REQUIREMENTS lane (schemaVersion 5) — see DrawLayerScanner.fieldRequirementJson
        // / research/out/legacy/guimap-notes/TILE-FIELD-REQUIREMENTS.md.
        coverage.addProperty("stateLinearFieldRequirementNumeratorResolved", fieldReqNumeratorResolved);
        coverage.addProperty("stateLinearFieldRequirementDivisorResolved", fieldReqDivisorResolved);
        coverage.addProperty("guardFieldConditionFieldRequirementResolved", fieldReqGuardResolved);
        coverage.addProperty("distinctTileEntityClassesRequired", tileEntityFields.size());
        coverage.addProperty("distinctTileEntityFieldsRequired", distinctTileEntityFieldsRequired);
        coverage.addProperty("distinctTileEntityFieldsViaPlainField", distinctFieldsViaPlainField);
        coverage.addProperty("distinctTileEntityFieldsViaAccessor", distinctFieldsViaAccessor);
        coverage.addProperty("distinctTileEntityFieldsViaDeepChain", distinctFieldsDeepChain);
        // GUARD-EXPRESSIONS lane (schemaVersion 6) — see DrawLayerScanner.classifyGuardNeed /
        // research/out/legacy/guimap-notes/GUARD-EXPRESSIONS.md. The four guardNeed* counters
        // partition drawTexturedModalRectConditional exactly (sum equals it); guardNeedsMethodCall
        // is cross-cutting (a tileFieldsOnly/tileFieldsMouseAndGuiState guard can still need one).
        coverage.addProperty("guardNeedTileFieldsOnly", guardNeedTileFieldsOnly);
        coverage.addProperty("guardNeedTileFieldsAndMouse", guardNeedTileFieldsAndMouse);
        coverage.addProperty("guardNeedTileFieldsMouseAndGuiState", guardNeedTileFieldsMouseAndGuiState);
        coverage.addProperty("guardNeedOpaque", guardNeedOpaque);
        coverage.addProperty("guardNeedsMethodCall", guardNeedsMethodCall);
        coverage.addProperty("buttonsExtracted", buttonsExtracted);
        coverage.addProperty("buttonsRoutableToContainer", buttonsContainer);
        coverage.addProperty("buttonsRoutableToPacket", buttonsPacket);
        coverage.addProperty("buttonsPacketRecipeRoutable", buttonsPacketRoutable);
        coverage.addProperty("buttonsUnresolved", buttonsUnresolved);
        root.add("coverage", coverage);

        root.add("guis", guis);
        root.add("guiHandlerMappings", handlerRows);
        root.add("tileEntityFieldRequirements", tileEntityFieldRequirements);

        try (Writer w = Files.newBufferedWriter(outPath, StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root, w);
        }
        log("wrote " + outPath);
        // No separate REPORT.md: umb-guimap owns only gui-profile.json and laneGui-progress.md in
        // research/out/legacy/ (a directory shared with other lanes) - the "coverage" block above
        // carries every number a report would; print it here too for a quick console readout.
        log("coverage: " + root.getAsJsonObject("coverage"));
        log("done in " + (System.currentTimeMillis() - t0) + " ms");
    }

    private static void log(String s) { System.out.println("[guimap] " + s); }

    /**
     * TILE-FIELD-REQUIREMENTS lane: folds one resolved {@code fieldRequirement} (from a
     * STATE_LINEAR source/divisor, or a guard's fieldCondition source) into the corpus-wide
     * per-tile-entity-class summary, deduplicated by its full hop-name path (e.g. {@code
     * "feed.fluid"}) so the same tank read via several rects/GUIs is counted exactly once.
     */
    private static void recordFieldRequirement(Map<String, Map<String, JsonObject>> byClass,
                                                 Map<String, Set<String>> guiUsers,
                                                 String guiClassName, JsonObject sourceOrDivisor) {
        JsonObject req = sourceOrDivisor.getAsJsonObject("fieldRequirement");
        JsonArray hops = req.getAsJsonArray("hops");
        String teClass = req.has("tileEntityClass") && !req.get("tileEntityClass").isJsonNull()
                ? req.get("tileEntityClass").getAsString()
                // real HBM corpus never hits this (0 fields live directly on a Container) - kept for
                // completeness/symmetry with describeSource's own "container" origin bucket.
                : "<container:" + hops.get(0).getAsJsonObject().get("ownerClass").getAsString() + ">";
        StringBuilder path = new StringBuilder();
        for (var h : hops) {
            if (path.length() > 0) path.append('.');
            path.append(h.getAsJsonObject().get("fieldName").getAsString());
        }
        String pathKey = path.toString();
        Map<String, JsonObject> fields = byClass.computeIfAbsent(teClass, k -> new java.util.LinkedHashMap<>());
        if (!fields.containsKey(pathKey)) {
            JsonObject fieldEntry = new JsonObject();
            fieldEntry.addProperty("path", pathKey);
            fieldEntry.add("hops", hops);
            JsonObject lastHop = hops.get(hops.size() - 1).getAsJsonObject();
            fieldEntry.addProperty("leafDesc", lastHop.get("desc").getAsString());
            fieldEntry.addProperty("leafKind", lastHop.get("kind").getAsString());
            fieldEntry.addProperty("reachedVia", req.get("reachedVia").getAsString());
            fields.put(pathKey, fieldEntry);
        }
        guiUsers.computeIfAbsent(teClass, k -> new java.util.LinkedHashSet<>()).add(guiClassName);
    }
}
