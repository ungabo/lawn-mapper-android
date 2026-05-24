package com.gabecodex.lawnmapper;

import android.graphics.Color;
import android.graphics.PointF;
import android.location.Location;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class LawnAnnotation {
    static final String TYPE_POINT = "point";
    static final String TYPE_BOX = "box";
    static final String TYPE_CIRCLE = "circle";
    static final String TYPE_FREEHAND = "freehand";

    String id;
    String type;
    String label;
    int color;
    long createdAtMillis;
    double originLatitude;
    double originLongitude;
    double originAltitude;
    float originAccuracyMeters;
    boolean hasOriginLocation;
    final ArrayList<AnchorPoint> anchors = new ArrayList<>();

    LawnAnnotation() {
    }

    static LawnAnnotation create(
            String type,
            String label,
            List<PointF> screenPoints,
            int width,
            int height,
            DevicePose pose,
            float horizontalFovDegrees,
            float verticalFovDegrees,
            Location location,
            int color
    ) {
        LawnAnnotation annotation = new LawnAnnotation();
        annotation.id = UUID.randomUUID().toString();
        annotation.type = type;
        annotation.label = label == null ? "" : label;
        annotation.color = color;
        annotation.createdAtMillis = System.currentTimeMillis();
        if (location != null) {
            annotation.hasOriginLocation = true;
            annotation.originLatitude = location.getLatitude();
            annotation.originLongitude = location.getLongitude();
            annotation.originAltitude = location.hasAltitude() ? location.getAltitude() : 0d;
            annotation.originAccuracyMeters = location.hasAccuracy() ? location.getAccuracy() : 25f;
        }
        for (PointF point : screenPoints) {
            annotation.anchors.add(AnchorPoint.fromScreen(
                    point.x,
                    point.y,
                    width,
                    height,
                    pose,
                    horizontalFovDegrees,
                    verticalFovDegrees
            ));
        }
        return annotation;
    }

    List<PointF> project(DevicePose pose, int width, int height, float horizontalFovDegrees, float verticalFovDegrees) {
        ArrayList<PointF> points = new ArrayList<>();
        for (AnchorPoint anchor : anchors) {
            points.add(anchor.toScreen(pose, width, height, horizontalFovDegrees, verticalFovDegrees));
        }
        return points;
    }

    boolean isNear(Location currentLocation) {
        if (!hasOriginLocation || currentLocation == null) {
            return true;
        }
        float[] result = new float[1];
        Location.distanceBetween(
                currentLocation.getLatitude(),
                currentLocation.getLongitude(),
                originLatitude,
                originLongitude,
                result
        );
        float currentAccuracy = currentLocation.hasAccuracy() ? currentLocation.getAccuracy() : 25f;
        float radius = Math.max(35f, originAccuracyMeters + currentAccuracy + 15f);
        return result[0] <= Math.min(radius, 120f);
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("type", type);
        json.put("label", label);
        json.put("color", color);
        json.put("createdAtMillis", createdAtMillis);
        json.put("hasOriginLocation", hasOriginLocation);
        json.put("originLatitude", originLatitude);
        json.put("originLongitude", originLongitude);
        json.put("originAltitude", originAltitude);
        json.put("originAccuracyMeters", originAccuracyMeters);

        JSONArray anchorJson = new JSONArray();
        for (AnchorPoint anchor : anchors) {
            anchorJson.put(anchor.toJson());
        }
        json.put("anchors", anchorJson);
        return json;
    }

    static LawnAnnotation fromJson(JSONObject json) throws JSONException {
        LawnAnnotation annotation = new LawnAnnotation();
        annotation.id = json.optString("id", UUID.randomUUID().toString());
        annotation.type = json.optString("type", TYPE_POINT);
        annotation.label = json.optString("label", "");
        annotation.color = json.optInt("color", Color.rgb(51, 209, 122));
        annotation.createdAtMillis = json.optLong("createdAtMillis", System.currentTimeMillis());
        annotation.hasOriginLocation = json.optBoolean("hasOriginLocation", false);
        annotation.originLatitude = json.optDouble("originLatitude", 0d);
        annotation.originLongitude = json.optDouble("originLongitude", 0d);
        annotation.originAltitude = json.optDouble("originAltitude", 0d);
        annotation.originAccuracyMeters = (float) json.optDouble("originAccuracyMeters", 25d);

        JSONArray anchorJson = json.optJSONArray("anchors");
        if (anchorJson != null) {
            for (int i = 0; i < anchorJson.length(); i++) {
                annotation.anchors.add(AnchorPoint.fromJson(anchorJson.getJSONObject(i)));
            }
        }
        return annotation;
    }
}
