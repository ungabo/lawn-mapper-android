package com.gabecodex.lawnmapper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.UUID;

final class SavedProject {
    String id = UUID.randomUUID().toString();
    String name;
    long createdAtMillis = System.currentTimeMillis();
    long updatedAtMillis = createdAtMillis;
    boolean hasOriginLocation;
    double originLatitude;
    double originLongitude;
    double originAltitude;
    float originAccuracyMeters;
    final ArrayList<SavedProjectAnnotation> annotations = new ArrayList<>();

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("name", name);
        json.put("createdAtMillis", createdAtMillis);
        json.put("updatedAtMillis", updatedAtMillis);
        json.put("hasOriginLocation", hasOriginLocation);
        json.put("originLatitude", originLatitude);
        json.put("originLongitude", originLongitude);
        json.put("originAltitude", originAltitude);
        json.put("originAccuracyMeters", originAccuracyMeters);
        JSONArray array = new JSONArray();
        for (SavedProjectAnnotation annotation : annotations) {
            array.put(annotation.toJson());
        }
        json.put("annotations", array);
        return json;
    }

    static SavedProject fromJson(JSONObject json) throws JSONException {
        SavedProject project = new SavedProject();
        project.id = json.optString("id", UUID.randomUUID().toString());
        project.name = json.optString("name", "Untitled Project");
        project.createdAtMillis = json.optLong("createdAtMillis", System.currentTimeMillis());
        project.updatedAtMillis = json.optLong("updatedAtMillis", project.createdAtMillis);
        project.hasOriginLocation = json.optBoolean("hasOriginLocation", false);
        project.originLatitude = json.optDouble("originLatitude", 0d);
        project.originLongitude = json.optDouble("originLongitude", 0d);
        project.originAltitude = json.optDouble("originAltitude", 0d);
        project.originAccuracyMeters = (float) json.optDouble("originAccuracyMeters", 0d);
        JSONArray array = json.optJSONArray("annotations");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                project.annotations.add(SavedProjectAnnotation.fromJson(array.getJSONObject(i)));
            }
        }
        return project;
    }
}

final class SavedProjectAnnotation {
    String type;
    String label;
    int color;
    final ArrayList<float[]> points = new ArrayList<>();
    float[] labelPoint = new float[]{0f, 0f, 0f};

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("type", type);
        json.put("label", label == null ? "" : label);
        json.put("color", color);
        json.put("labelPoint", pointToJson(labelPoint));
        JSONArray array = new JSONArray();
        for (float[] point : points) {
            array.put(pointToJson(point));
        }
        json.put("points", array);
        return json;
    }

    static SavedProjectAnnotation fromJson(JSONObject json) throws JSONException {
        SavedProjectAnnotation annotation = new SavedProjectAnnotation();
        annotation.type = json.optString("type", LawnAnnotation.TYPE_POINT);
        annotation.label = json.optString("label", "");
        annotation.color = json.optInt("color", 0xFF33D17A);
        annotation.labelPoint = pointFromJson(json.optJSONArray("labelPoint"));
        JSONArray array = json.optJSONArray("points");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                annotation.points.add(pointFromJson(array.getJSONArray(i)));
            }
        }
        return annotation;
    }

    private static JSONArray pointToJson(float[] point) throws JSONException {
        JSONArray array = new JSONArray();
        array.put(point.length > 0 ? point[0] : 0f);
        array.put(point.length > 1 ? point[1] : 0f);
        array.put(point.length > 2 ? point[2] : 0f);
        return array;
    }

    private static float[] pointFromJson(JSONArray array) throws JSONException {
        if (array == null) {
            return new float[]{0f, 0f, 0f};
        }
        return new float[]{
                (float) array.optDouble(0, 0d),
                (float) array.optDouble(1, 0d),
                (float) array.optDouble(2, 0d)
        };
    }
}
