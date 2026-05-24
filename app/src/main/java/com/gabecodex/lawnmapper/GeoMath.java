package com.gabecodex.lawnmapper;

final class GeoMath {
    private GeoMath() {
    }

    static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    static float normalizeCompass(float degrees) {
        float value = degrees % 360f;
        if (value < 0f) {
            value += 360f;
        }
        return value;
    }

    static float normalizeSignedDegrees(float degrees) {
        float value = normalizeCompass(degrees);
        if (value > 180f) {
            value -= 360f;
        }
        return value;
    }

    static float distance(float x1, float y1, float x2, float y2) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    static float distanceToSegment(float px, float py, float ax, float ay, float bx, float by) {
        float dx = bx - ax;
        float dy = by - ay;
        float lengthSquared = dx * dx + dy * dy;
        if (lengthSquared <= 0.0001f) {
            return distance(px, py, ax, ay);
        }
        float t = ((px - ax) * dx + (py - ay) * dy) / lengthSquared;
        t = clamp(t, 0f, 1f);
        return distance(px, py, ax + t * dx, ay + t * dy);
    }
}
