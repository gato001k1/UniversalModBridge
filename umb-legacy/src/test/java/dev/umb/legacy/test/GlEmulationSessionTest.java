package dev.umb.legacy.test;

import dev.umb.bridge.api.GlEmulationSession;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
}
