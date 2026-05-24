package com.gabecodex.lawnmapper;

final class DevicePose {
    static final DevicePose UNKNOWN = new DevicePose(0f, 0f, 0f, 0L, false);

    final float azimuthDegrees;
    final float pitchDegrees;
    final float rollDegrees;
    final long timestampMillis;
    final boolean valid;

    DevicePose(float azimuthDegrees, float pitchDegrees, float rollDegrees, long timestampMillis, boolean valid) {
        this.azimuthDegrees = GeoMath.normalizeCompass(azimuthDegrees);
        this.pitchDegrees = pitchDegrees;
        this.rollDegrees = rollDegrees;
        this.timestampMillis = timestampMillis;
        this.valid = valid;
    }
}
