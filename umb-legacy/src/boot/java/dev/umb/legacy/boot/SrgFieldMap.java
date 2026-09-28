package dev.umb.legacy.boot;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads the {@code CL:}/{@code FD:} rows of a joined.srg file into an {@code owner (internal, SRG/real class name) -> {obfFieldShortName -> srgFieldName}} map.
 * <p>See {@code } "F0 - SrgFieldRepair".
 */
final class SrgFieldMap {

    private SrgFieldMap() {
    }

    /** @return owner internal name (e.g. {@code net/minecraft/inventory/Slot}) -&gt; {obfField -&gt; srgField} */
    static Map<String, Map<String, String>> load(Path srgFile) throws IOException {
        Map<String, String> obfToSrgClass = new HashMap<>();
        Map<String, Map<String, String>> byObfOwner = new HashMap<>();
        try (BufferedReader r = Files.newBufferedReader(srgFile, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;
            while ((line = r.readLine()) != null) {
                lineNo++;
                if (lineNo == 1 && line.startsWith("﻿")) {
                    line = line.substring(1);
                }
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("CL:")) {
                    String[] t = line.substring(3).trim().split("\\s+");
                    if (t.length == 2) {
                        obfToSrgClass.put(t[0], t[1]);
                    }
                } else if (line.startsWith("FD:")) {
                    String[] t = line.substring(3).trim().split("\\s+");
                    if (t.length != 2) {
                        continue;
                    }
                    int s1 = t[0].lastIndexOf('/');
                    int s2 = t[1].lastIndexOf('/');
                    if (s1 < 0 || s2 < 0) {
                        continue;
                    }
                    String obfOwner = t[0].substring(0, s1);
                    String obfField = t[0].substring(s1 + 1);
                    String srgField = t[1].substring(s2 + 1);
                    byObfOwner.computeIfAbsent(obfOwner, k -> new HashMap<>()).put(obfField, srgField);
                }
                // MD:/PK: rows carry no field information; ignored - methods are not this map's job.
            }
        }
        Map<String, Map<String, String>> result = new HashMap<>();
        int unresolvedOwners = 0;
        for (Map.Entry<String, Map<String, String>> e : byObfOwner.entrySet()) {
            String srgOwner = obfToSrgClass.get(e.getKey());
            if (srgOwner == null) {
                // The real joined.srg guarantees every FD owner has a CL row; defend anyway rather
                // than throwing, since a stray malformed row must not block the whole repair.
                unresolvedOwners++;
                continue;
            }
            Map<String, String> existing = result.get(srgOwner);
            if (existing == null) {
                result.put(srgOwner, e.getValue());
            } else {
                existing.putAll(e.getValue());
            }
        }
        if (unresolvedOwners > 0) {
            System.err.println("[SrgFieldMap] WARNING: " + unresolvedOwners
                    + " FD owner(s) had no CL row and were skipped");
        }
        return result;
    }
}
