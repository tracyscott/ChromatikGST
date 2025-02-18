package xyz.theforks.chromatikgst;

import heronarts.lx.model.LXModel;
import heronarts.lx.model.LXPoint;
import heronarts.lx.transform.LXMatrix;

public class UVUtil {
    static public float vectorLength(float[] v) {
        return (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }

    static public void normalizePlaneNormal(float[] planeNormal) {
        // Normalize the plane normal
        float normalLength = (float)Math.sqrt(
                planeNormal[0] * planeNormal[0] +
                        planeNormal[1] * planeNormal[1] +
                        planeNormal[2] * planeNormal[2]
        );

        planeNormal[0] = planeNormal[0] / normalLength;
        planeNormal[1] = planeNormal[1] / normalLength;
        planeNormal[2] = planeNormal[2] / normalLength;
    }

    static public float[] computeAxesRotates(float[] planeNormal) {
        // Given a plane normal, compute the series of rotations to return the plane to the XY plane.
        // Compute the rotation matrix to rotate the plane normal to the XY plane.
        float[] zAxis = {0, 0, 1};
        float[] cross = new float[3];
        cross[0] = zAxis[1] * planeNormal[2] - zAxis[2] * planeNormal[1];
        cross[1] = zAxis[2] * planeNormal[0] - zAxis[0] * planeNormal[2];
        cross[2] = zAxis[0] * planeNormal[1] - zAxis[1] * planeNormal[0];
        float dot = zAxis[0] * planeNormal[0] + zAxis[1] * planeNormal[1] + zAxis[2] * planeNormal[2];
        float[] axis = new float[3];
        axis[0] = cross[0];
        axis[1] = cross[1];
        axis[2] = cross[2];
        float angle = (float) Math.acos(dot);
        return new float[] {axis[0], axis[1], axis[2], angle};
    }

    static public float[] computePlaneNormal(LXModel model) {
        // Compute the plane normal for the model
        // Compute the plane normal for the model
        LXPoint p0 = model.points[0];
        LXPoint p1 = model.points[model.points.length/2];
        LXPoint p2 = model.points[model.points.length-1];
        float[] v1 = {p1.x - p0.x, p1.y - p0.y, p1.z - p0.z};
        float[] v2 = {p2.x - p0.x, p2.y - p0.y, p2.z - p0.z};
        float[] normal = new float[3];
        normal[0] = v1[1] * v2[2] - v1[2] * v2[1];
        normal[1] = v1[2] * v2[0] - v1[0] * v2[2];
        normal[2] = v1[0] * v2[1] - v1[1] * v2[0];
        return normal;
    }

    static public void rotatePointAroundAxis(float[] point, float[] axis, float angle, float[] rotatedPoint) {
        float[] axisUnit = new float[3];
        float axisLength = (float) Math.sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2]);
        axisUnit[0] = axis[0] / axisLength;
        axisUnit[1] = axis[1] / axisLength;
        axisUnit[2] = axis[2] / axisLength;
        float dot = point[0] * axisUnit[0] + point[1] * axisUnit[1] + point[2] * axisUnit[2];
        float[] cross = new float[3];
        cross[0] = axisUnit[1] * point[2] - axisUnit[2] * point[1];
        cross[1] = axisUnit[2] * point[0] - axisUnit[0] * point[2];
        cross[2] = axisUnit[0] * point[1] - axisUnit[1] * point[0];
        rotatedPoint[0] = (float) (point[0] * Math.cos(angle) + cross[0] * Math.sin(angle) + dot * (1 - Math.cos(angle)) * axisUnit[0]);
        rotatedPoint[1] = (float) (point[1] * Math.cos(angle) + cross[1] * Math.sin(angle) + dot * (1 - Math.cos(angle)) * axisUnit[1]);
        rotatedPoint[2] = (float) (point[2] * Math.cos(angle) + cross[2] * Math.sin(angle) + dot * (1 - Math.cos(angle)) * axisUnit[2]);
    }

    static public LXMatrix inverseLXMatrix(LXMatrix matrix) {
        LXMatrix result = new LXMatrix();

        // First compute inverse of rotation part (transpose)
        result.m11 = matrix.m11;  result.m12 = matrix.m21;  result.m13 = matrix.m31;
        result.m21 = matrix.m12;  result.m22 = matrix.m22;  result.m23 = matrix.m32;
        result.m31 = matrix.m13;  result.m32 = matrix.m23;  result.m33 = matrix.m33;

        // Now compute -R^T * T for the translation part
        result.m14 = -(result.m11 * matrix.m14 + result.m12 * matrix.m24 + result.m13 * matrix.m34);
        result.m24 = -(result.m21 * matrix.m14 + result.m22 * matrix.m24 + result.m23 * matrix.m34);
        result.m34 = -(result.m31 * matrix.m14 + result.m32 * matrix.m24 + result.m33 * matrix.m34);

        // Set bottom row
        result.m41 = 0;
        result.m42 = 0;
        result.m43 = 0;
        result.m44 = 1;

        return result;
    }
}
