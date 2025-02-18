package xyz.theforks.chromatikgst;

import heronarts.lx.model.LXPoint;

import java.util.List;

/**
 * Wrap the LXPoint's with their computed uv coordinates.  The maximum dimension will
 * be 1 and the minimum dimension will be <= 1 for non 1:1 aspect ratios.
 */
public class UVPoint {
    public LXPoint point;
    public float u;
    public float v;
    public UVPoint(LXPoint p, float u, float v) {
        this.point = p;
        this.u = u;
        this.v = v;
    }

    static public void renormalizeUVs(List<UVPoint> uvPoints) {
        float uMin = Float.MAX_VALUE;
        float uMax = Float.MIN_VALUE;
        float vMin = Float.MAX_VALUE;
        float vMax = Float.MIN_VALUE;
        for (UVPoint uv : uvPoints) {
            if (uv.u < uMin) {
                uMin = uv.u;
            }
            if (uv.u > uMax) {
                uMax = uv.u;
            }
            if (uv.v < vMin) {
                vMin = uv.v;
            }
            if (uv.v > vMax) {
                vMax = uv.v;
            }
        }
        float uRange = uMax - uMin;
        float vRange = vMax - vMin;
        float maxRange = Math.max(uRange, vRange);
        for (UVPoint uv : uvPoints) {
            uv.u = (uv.u - uMin) / uRange;
            uv.v = (uv.v - vMin) / vRange;
        }
    }
}