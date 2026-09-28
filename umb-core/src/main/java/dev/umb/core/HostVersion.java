package dev.umb.core;

import java.util.List;

/**
 * First-class host version concept (spec §10). The compatibility system is parameterized
 * by this; never hardcode a specific release.
 */
public record HostVersion(
        String id,
        boolean stable,
        List<String> supportedLegacyEras
) {
    public static final String CURRENT_TARGET = "26.2";

    public static HostVersion current() {
        return new HostVersion(CURRENT_TARGET, true,
                List.of("1.7.10", "1.8.9", "1.12.2", "1.16.5", "1.18.2", "1.20.1", "1.21.1"));
    }
}
