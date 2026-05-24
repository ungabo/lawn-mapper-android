package com.gabecodex.lawnmapper;

import android.graphics.PointF;

import org.json.JSONException;
import org.json.JSONObject;

final class AnchorPoint {
    final float bearingDegrees;
    final float elevationDegrees;
    final float screenYRatio;

    AnchorPoint(float bearingDegrees, float elevationDegrees) {
        this(bearingDegrees, elevationDegrees, Float.NaN);
    }

    AnchorPoint(float bearingDegrees, float elevationDegrees, float screenYRatio) {
        this.bearingDegrees = GeoMath.normalizeCompass(bearingDegrees);
        this.elevationDegrees = elevationDegrees;
        this.screenYRatio = screenYRatio;
    }

    static AnchorPoint fromScreen(
            float x,
            float y,
            int width,
            int height,
            DevicePose pose,
            float horizontalFovDegrees,
            float verticalFovDegrees
    ) {
        float xNorm = width <= 0 ? 0.5f : x / (float) width;
        float yNorm = height <= 0 ? 0.5f : y / (float) height;
        float bearing = pose.azimuthDegrees + (xNorm - 0.5f) * horizontalFovDegrees;
        float elevation = pose.pitchDegrees + (0.5f - yNorm) * verticalFovDegrees;
        return new AnchorPoint(bearing, elevation, yNorm);
    }

    PointF toScreen(DevicePose pose, int width, int height, float horizontalFovDegrees, float verticalFovDegrees) {
        float bearingDelta = GeoMath.normalizeSignedDegrees(bearingDegrees - pose.azimuthDegrees);
        float xNorm = 0.5f + bearingDelta / Math.max(1f, horizontalFovDegrees);
        float yNorm;
        if (Float.isNaN(screenYRatio)) {
            float elevationDelta = elevationDegrees - pose.pitchDegrees;
            yNorm = 0.5f - elevationDelta / Math.max(1f, verticalFovDegrees);
        } else {
            yNorm = screenYRatio;
        }
        return new PointF(xNorm * width, yNorm * height);
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("bearingDegrees", bearingDegrees);
        json.put("elevationDegrees", elevationDegrees);
        if (!Float.isNaN(screenYRatio)) {
            json.put("screenYRatio", screenYRatio);
        }
        return json;
    }

    static AnchorPoint fromJson(JSONObject json) throws JSONException {
        return new AnchorPoint(
                (float) json.optDouble("bearingDegrees", 0d),
                (float) json.optDouble("elevationDegrees", 0d),
                json.has("screenYRatio") ? (float) json.optDouble("screenYRatio", 0.5d) : Float.NaN
        );
    }
}
