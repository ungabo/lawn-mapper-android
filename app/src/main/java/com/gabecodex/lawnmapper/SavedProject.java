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
    boolean hasSavePose;
    double savePhoneLatitude;
    double savePhoneLongitude;
    double savePhoneAltitude;
    float savePhoneAccuracyMeters;
    float saveAzimuthDegrees;
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
        json.put("hasSavePose", hasSavePose);
        json.put("savePhoneLatitude", savePhoneLatitude);
        json.put("savePhoneLongitude", savePhoneLongitude);
        json.put("savePhoneAltitude", savePhoneAltitude);
        json.put("savePhoneAccuracyMeters", savePhoneAccuracyMeters);
        json.put("saveAzimuthDegrees", saveAzimuthDegrees);
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
        project.hasSavePose = json.optBoolean("hasSavePose", false);
        project.savePhoneLatitude = json.optDouble("savePhoneLatitude", 0d);
        project.savePhoneLongitude = json.optDouble("savePhoneLongitude", 0d);
        project.savePhoneAltitude = json.optDouble("savePhoneAltitude", 0d);
        project.savePhoneAccuracyMeters = (float) json.optDouble("savePhoneAccuracyMeters", 0d);
        project.saveAzimuthDegrees = (float) json.optDouble("saveAzimuthDegrees", 0d);
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
    String id = UUID.randomUUID().toString();
    String type;
    String label;
    int color;
    boolean hasObserverPose;
    double observerLatitude;
    double observerLongitude;
    double observerAltitude;
    float observerAccuracyMeters;
    float observerAzimuthDegrees;
    final ArrayList<float[]> points = new ArrayList<>();
    final ArrayList<SavedGeoPoint> geoPoints = new ArrayList<>();
    float[] labelPoint = new float[]{0f, 0f, 0f};
    SavedGeoPoint labelGeoPoint;

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("type", type);
        json.put("label", label == null ? "" : label);
        json.put("color", color);
        json.put("hasObserverPose", hasObserverPose);
        json.put("observerLatitude", observerLatitude);
        json.put("observerLongitude", observerLongitude);
        json.put("observerAltitude", observerAltitude);
        json.put("observerAccuracyMeters", observerAccuracyMeters);
        json.put("observerAzimuthDegrees", observerAzimuthDegrees);
        json.put("labelPoint", pointToJson(labelPoint));
        if (labelGeoPoint != null) {
            json.put("labelGeoPoint", labelGeoPoint.toJson());
        }
        JSONArray array = new JSONArray();
        for (float[] point : points) {
            array.put(pointToJson(point));
        }
        json.put("points", array);
        JSONArray geoArray = new JSONArray();
        for (SavedGeoPoint point : geoPoints) {
            geoArray.put(point.toJson());
        }
        json.put("geoPoints", geoArray);
        return json;
    }

    static SavedProjectAnnotation fromJson(JSONObject json) throws JSONException {
        SavedProjectAnnotation annotation = new SavedProjectAnnotation();
        annotation.id = json.optString("id", UUID.randomUUID().toString());
        annotation.type = json.optString("type", LawnAnnotation.TYPE_POINT);
        annotation.label = json.optString("label", "");
        annotation.color = json.optInt("color", 0xFF33D17A);
        annotation.hasObserverPose = json.optBoolean("hasObserverPose", false);
        annotation.observerLatitude = json.optDouble("observerLatitude", 0d);
        annotation.observerLongitude = json.optDouble("observerLongitude", 0d);
        annotation.observerAltitude = json.optDouble("observerAltitude", 0d);
        annotation.observerAccuracyMeters = (float) json.optDouble("observerAccuracyMeters", 0d);
        annotation.observerAzimuthDegrees = (float) json.optDouble("observerAzimuthDegrees", 0d);
        annotation.labelPoint = pointFromJson(json.optJSONArray("labelPoint"));
        JSONObject labelGeo = json.optJSONObject("labelGeoPoint");
        if (labelGeo != null) {
            annotation.labelGeoPoint = SavedGeoPoint.fromJson(labelGeo);
        }
        JSONArray array = json.optJSONArray("points");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                annotation.points.add(pointFromJson(array.getJSONArray(i)));
            }
        }
        JSONArray geoArray = json.optJSONArray("geoPoints");
        if (geoArray != null) {
            for (int i = 0; i < geoArray.length(); i++) {
                annotation.geoPoints.add(SavedGeoPoint.fromJson(geoArray.getJSONObject(i)));
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

final class SavedGeoPoint {
    double latitude;
    double longitude;
    double altitude;

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("latitude", latitude);
        json.put("longitude", longitude);
        json.put("altitude", altitude);
        return json;
    }

    static SavedGeoPoint fromLocation(android.location.Location location) {
        SavedGeoPoint point = new SavedGeoPoint();
        point.latitude = location.getLatitude();
        point.longitude = location.getLongitude();
        point.altitude = location.hasAltitude() ? location.getAltitude() : 0d;
        return point;
    }

    static SavedGeoPoint fromJson(JSONObject json) {
        SavedGeoPoint point = new SavedGeoPoint();
        point.latitude = json.optDouble("latitude", 0d);
        point.longitude = json.optDouble("longitude", 0d);
        point.altitude = json.optDouble("altitude", 0d);
        return point;
    }
}
