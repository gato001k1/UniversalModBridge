package dev.umb.legacy.test;

import dev.umb.bridge.api.GlEmulationSession;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P4's native-free IR gate: matrix/pivot, state, display-list and VBO paths. */
public class GlEmulationSessionTest {
    @Test
    void matrixIsAppliedAtVertexEmission() {
        GlEmulationSession session = new GlEmulationSession(true);
        session.begin(GlEmulationSession.GL_QUADS);
        session.translate(1, 2, 3);
        session.rotate(90, 0, 0, 1);
        session.vertex(1, 0, 0, 0, 0);
        GlEmulationSession.Mesh mesh = session.seal();
        assertEquals(1, mesh.vertexCount());
        GlEmulationSession.Vertex vertex = mesh.draws.get(0).vertices.get(0);
        assertEquals(1.0f, vertex.x, 0.0001f);
        assertEquals(3.0f, vertex.y, 0.0001f);
        assertEquals(3.0f, vertex.z, 0.0001f);
    }

    @Test
    void displayListsAndStateRemainDataOnly() {
        GlEmulationSession session = new GlEmulationSession(true);
        session.bindTexture("fixture:textures/door.png");
        session.enable(3042);
        session.blendFunc(770, 771);
        int list = session.genLists(1);
        session.newList(list);
        session.begin(GlEmulationSession.GL_TRIANGLES);
        session.vertex(0, 0, 0);
        session.vertex(1, 0, 0);
        session.vertex(0, 1, 0);
        session.end();
        session.endList();
        session.callList(list);
        GlEmulationSession.Mesh mesh = session.seal();
        assertEquals(2, mesh.draws.size());
        assertEquals("fixture:textures/door.png", mesh.draws.get(0).state.texture);
        assertNotNull(mesh.draws.get(1).state);
        assertEquals(770, mesh.draws.get(1).state.blendSource);
        assertEquals(771, mesh.draws.get(1).state.blendDestination);
    }

    @Test
    void displayListSurvivesTheModelLoadToRenderSessionBoundary() {
        GlEmulationSession modelLoad = new GlEmulationSession(true);
        int list = modelLoad.genLists(1);
        modelLoad.newList(list);
        modelLoad.begin(GlEmulationSession.GL_TRIANGLES);
        modelLoad.vertex(0, 0, 0, 0, 0);
        modelLoad.vertex(1, 0, 0, 1, 0);
        modelLoad.vertex(0, 1, 0, 0, 1);
        modelLoad.end();
        modelLoad.endList();

        GlEmulationSession render = new GlEmulationSession(true);
        render.translate(4, 0, 0);
        render.callList(list);
        GlEmulationSession.Vertex first = render.seal().draws.get(0).vertices.get(0);
        assertEquals(4.0f, first.x, 0.0001f);
    }

    @Test
    void floatVboStreamDoesNotNeedNativeBuffers() {
        GlEmulationSession session = new GlEmulationSession(true);
        session.drawFloatStream(GlEmulationSession.GL_TRIANGLES,
                new float[] {0,0,0, 0,0, 1,0,0, 1,0, 0,1,0}, 5);
        assertEquals(3, session.seal().vertexCount());
    }

    /**
     * Bug finding #3 (HBM-guns static analysis, double-crosshair class of bug): a legacy mod
     * cancelling {@code RenderGameOverlayEvent.Pre} for one element must reach the host so it can
     * suppress its own matching vanilla draw. {@link GlEmulationSession.Mesh#canceledElements}
     * is the carrier; a fresh mesh (nothing cancelled, the overwhelming common case) must expose
     * the SAME shared empty set instance every time - no allocation on that path.
     */
    @Test
    void freshMeshHasTheSharedEmptyCanceledElementsSet() {
        GlEmulationSession.Mesh mesh = new GlEmulationSession(true).seal();
        assertSame(Collections.emptySet(), mesh.canceledElements);
    }

    @Test
    void withCanceledElementsIsANoOpForNullOrEmpty() {
        GlEmulationSession.Mesh mesh = new GlEmulationSession(true).seal();
        assertSame(mesh, GlEmulationSession.withCanceledElements(mesh, null));
        assertSame(mesh, GlEmulationSession.withCanceledElements(mesh, Collections.<String>emptySet()));
    }

    @Test
    void withCanceledElementsAttachesTheNamesWithoutTouchingDraws() {
        GlEmulationSession session = new GlEmulationSession(true);
        session.begin(GlEmulationSession.GL_QUADS);
        session.vertex(0, 0, 0);
        GlEmulationSession.Mesh mesh = session.seal();

        Set<String> canceled = new LinkedHashSet<String>();
        canceled.add("CROSSHAIRS");
        GlEmulationSession.Mesh withCancel = GlEmulationSession.withCanceledElements(mesh, canceled);

        assertTrue(withCancel.canceledElements.contains("CROSSHAIRS"));
        assertEquals(mesh.draws.size(), withCancel.draws.size());
        assertEquals(mesh.vertexCount(), withCancel.vertexCount());
        // The set passed in is defensively copied - mutating it afterward must not leak through.
        canceled.add("HOTBAR");
        assertEquals(1, withCancel.canceledElements.size());
    }

    @Test
    void concatUnionsCanceledElementsButStaysAllocationFreeWhenBothAreEmpty() {
        GlEmulationSession.Mesh a = new GlEmulationSession(true).seal();
        GlEmulationSession.Mesh b = new GlEmulationSession(true).seal();
        GlEmulationSession.Mesh joined = GlEmulationSession.concat(a, b);
        assertSame(Collections.emptySet(), joined.canceledElements);

        Set<String> onlyCrosshairs = new LinkedHashSet<String>();
        onlyCrosshairs.add("CROSSHAIRS");
        GlEmulationSession.Mesh withA = GlEmulationSession.withCanceledElements(a, onlyCrosshairs);
        Set<String> onlyHotbar = new LinkedHashSet<String>();
        onlyHotbar.add("HOTBAR");
        GlEmulationSession.Mesh withB = GlEmulationSession.withCanceledElements(b, onlyHotbar);

        GlEmulationSession.Mesh union = GlEmulationSession.concat(withA, withB);
        assertEquals(2, union.canceledElements.size());
        assertTrue(union.canceledElements.contains("CROSSHAIRS"));
        assertTrue(union.canceledElements.contains("HOTBAR"));
    }
}
