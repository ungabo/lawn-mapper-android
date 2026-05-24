package com.gabecodex.lawnmapper;

import android.graphics.Color;
import android.graphics.PointF;

import com.google.ar.core.Anchor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class ArAnnotation {
    final String id = UUID.randomUUID().toString();
    final String type;
    final int color;
    final Anchor anchor;
    final ArrayList<float[]> localPoints = new ArrayList<>();
    final float[] labelLocalPoint = new float[]{0f, 0f, 0f};
    volatile String label = "";

    ArAnnotation(String type, int color, Anchor anchor, List<float[]> points, float[] labelPoint) {
        this.type = type;
        this.color = color;
        this.anchor = anchor;
        this.localPoints.addAll(points);
        System.arraycopy(labelPoint, 0, labelLocalPoint, 0, 3);
    }

    void detach() {
        anchor.detach();
    }
}

final class ArProjectedAnnotation {
    final ArAnnotation source;
    final String type;
    final String label;
    final int color;
    final ArrayList<PointF> points = new ArrayList<>();
    PointF labelPoint;

    ArProjectedAnnotation(ArAnnotation source) {
        this.source = source;
        this.type = source.type;
        this.label = source.label;
        this.color = source.color == 0 ? Color.rgb(51, 209, 122) : source.color;
    }
}
