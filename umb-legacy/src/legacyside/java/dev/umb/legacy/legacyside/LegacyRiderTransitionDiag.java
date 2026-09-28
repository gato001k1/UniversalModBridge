package dev.umb.legacy.legacyside;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

import net.minecraft.entity.Entity;

/**
 * One-shot diagnostic for legacy passenger-field transitions.
 *
 * <p>The 1.7.10 Entity API exposes {@code field_70153_n} as a plain field, so a mod can clear a
 * passenger without calling {@code mountEntity(null)}.  UmbShimTransformer instruments writes to
 * that universal field and calls this method after the write.  Identity state is deliberately
 * local to this diagnostic; it never changes the riding graph.</p>
 */
public final class LegacyRiderTransitionDiag {
    private static final Map<Entity, Entity> LAST_PASSENGER =
            Collections.synchronizedMap(new IdentityHashMap<Entity, Entity>());
    private static volatile boolean logged;

    private LegacyRiderTransitionDiag() {
    }

    /** Called after a transformed write to Entity.field_70153_n. */
    public static void afterPassengerWrite(Entity vehicle, Entity passenger) {
        if (vehicle == null) {
            return;
        }
        Entity previous = LAST_PASSENGER.put(vehicle, passenger);
        if (previous == null || passenger != null || logged) {
            return;
        }
        if (!(vehicle.field_70170_p instanceof UmbWorld)) {
            return;
        }
        logged = true;
        StringBuilder line = new StringBuilder("[UMB-ENTITY] legacy passenger transition old=")
                .append(identity(previous)).append(" new=null vehicle=")
                .append(identity(vehicle)).append(" stack=");
        StackTraceElement[] stack = new Throwable().getStackTrace();
        int limit = Math.min(14, stack.length);
        for (int i = 1; i < limit; i++) {
            line.append(stack[i].getClassName()).append('#')
                    .append(stack[i].getMethodName()).append(':').append(stack[i].getLineNumber());
            if (i + 1 < limit) {
                line.append(" <- ");
            }
        }
        try {
            ((UmbWorld) vehicle.field_70170_p).host().log(line.toString());
        } catch (Throwable ignored) {
            // Diagnostics must never alter a legacy field write.
        }
    }

    private static String identity(Entity entity) {
        return entity == null ? "null" : entity.getClass().getName() + '@'
                + System.identityHashCode(entity);
    }
}
