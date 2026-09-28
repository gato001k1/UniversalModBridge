package dev.umb.legacy.boot;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Reads the MD rows needed to link raw member calls to the SRG runtime names. */
final class SrgMethodMap {

    static final class Target {
        final String name;

        Target(String name) {
            this.name = name;
        }
    }

    static final class Key {
        final String name;
        final String descriptor;

        Key(String name, String descriptor) {
            this.name = name;
            this.descriptor = descriptor;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key k = (Key) other;
            return name.equals(k.name) && descriptor.equals(k.descriptor);
        }

        @Override
        public int hashCode() {
            return 31 * name.hashCode() + descriptor.hashCode();
        }
    }

    private SrgMethodMap() {
    }

    /** @return SRG owner -> {(raw method name, SRG descriptor) -> SRG method name}. */
    static Map<String, Map<Key, Target>> load(Path srgFile) throws IOException {
        Map<String, String> obfToSrgClass = new HashMap<>();
        java.util.List<String[]> methods = new java.util.ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(srgFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("CL:")) {
                    String[] t = line.substring(3).trim().split("\\s+");
                    if (t.length == 2) obfToSrgClass.put(t[0], t[1]);
                } else if (line.startsWith("MD:")) {
                    String[] t = line.substring(3).trim().split("\\s+");
                    if (t.length == 4) methods.add(t);
                }
            }
        }

        Map<String, Map<Key, Target>> result = new HashMap<>();
        for (String[] t : methods) {
            int rawSlash = t[0].lastIndexOf('/');
            int srgSlash = t[2].lastIndexOf('/');
            if (rawSlash < 0 || srgSlash < 0) continue;
            String rawOwner = t[0].substring(0, rawSlash);
            String srgOwner = t[2].substring(0, srgSlash);
            String resolvedOwner = obfToSrgClass.get(rawOwner);
            if (resolvedOwner == null) resolvedOwner = srgOwner;
            String rawName = t[0].substring(rawSlash + 1);
            String srgName = t[2].substring(srgSlash + 1);
            String srgDescriptor = remapDescriptor(t[1], obfToSrgClass);
            result.computeIfAbsent(resolvedOwner, k -> new HashMap<>())
                    .put(new Key(rawName, srgDescriptor), new Target(srgName));
        }
        return result;
    }

    private static String remapDescriptor(String descriptor, Map<String, String> obfToSrgClass) {
        StringBuilder out = new StringBuilder(descriptor.length());
        for (int i = 0; i < descriptor.length();) {
            char c = descriptor.charAt(i++);
            out.append(c);
            if (c != 'L') continue;
            int end = descriptor.indexOf(';', i);
            if (end < 0) return descriptor;
            String rawClass = descriptor.substring(i, end);
            out.append(obfToSrgClass.getOrDefault(rawClass, rawClass));
            out.append(';');
            i = end + 1;
        }
        return out.toString();
    }
}
