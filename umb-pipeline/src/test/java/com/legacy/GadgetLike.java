package com.legacy;

/**
 * The legacy-world view of a gadget — the interface a pre-bridge mod would compile
 * against. Bridge v0 pins that calling through this interface lands on a REAL host
 * instance, not a fake: {@code size()} and {@code name()} return host-computed values.
 */
public interface GadgetLike {
    int size();

    String name();
}