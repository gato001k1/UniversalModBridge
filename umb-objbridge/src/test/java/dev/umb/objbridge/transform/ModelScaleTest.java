package dev.umb.objbridge.transform;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelScaleTest {

    @Test
    void linear3x3ExtractsTheUpperLeftRowMajor() {
        Matrix4f m = new Matrix4f().scale(2, 3, 4);
        float[] lin = ModelScale.linear3x3(m);
        assertEquals(9, lin.length);
        assertEquals(2f, lin[0], 1e-6f);
        assertEquals(3f, lin[4], 1e-6f);
        assertEquals(4f, lin[8], 1e-6f);
        // off-diagonal must be zero for a pure axis scale
        assertEquals(0f, lin[1], 1e-6f);
        assertEquals(0f, lin[2], 1e-6f);
        assertEquals(0f, lin[3], 1e-6f);
    }

    @Test
    void uniformScaleIsTheCubeRootOfTheVolumeFactor() {
        Matrix4f m = new Matrix4f().scale(2, 2, 2);
        assertEquals(2f, ModelScale.uniformScale(m), 1e-5f);
    }

    @Test
    void uniformScaleIsRotationInvariant() {
        Matrix4f m = new Matrix4f().scale(3, 3, 3).rotate((float) Math.toRadians(37), 0, 1, 0);
        assertEquals(3f, ModelScale.uniformScale(m), 1e-4f);
    }

    @Test
    void degenerateMatrixReturnsOneRatherThanZeroOrNaN() {
        Matrix4f zero = new Matrix4f().scale(0, 0, 0);
        assertEquals(1.0f, ModelScale.uniformScale(zero), 0f);
    }
}
