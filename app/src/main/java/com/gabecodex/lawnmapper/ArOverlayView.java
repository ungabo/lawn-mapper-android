package com.gabecodex.lawnmapper;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ArOverlayView extends View {
    interface Callback {
        void requestCreateAnnotation(String type, List<PointF> screenPoints);

        void requestEditAnnotation(ArAnnotation annotation);

        void requestEraseAnnotation(ArAnnotation annotation);

        boolean isProjectPlacementActive();

        void requestPlaceProject(PointF screenPoint);
    }

    private final ArrayList<ArProjectedAnnotation> projected = new ArrayList<>();
    private final ArrayList<PointF> freehandDraft = new ArrayList<>();
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint eraserPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private AnnotationOverlayView.Mode mode = AnnotationOverlayView.Mode.CIRCLE;
    private Callback callback;
    private PointF draftStart;
    private PointF draftCurrent;
    private PointF eraserPoint;
    private boolean drawing;
    private final float touchSlopPx;
    private final float eraserRadiusPx;

    public ArOverlayView(Context context) {
        this(context, null);
    }

    public ArOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        float density = getResources().getDisplayMetrics().density;
        touchSlopPx = 10f * density;
        eraserRadiusPx = 34f * density;

        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(4f * density);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);

        fillPaint.setStyle(Paint.Style.FILL);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(14f * getResources().getDisplayMetrics().scaledDensity);
        textPaint.setFakeBoldText(true);

        labelPaint.setColor(Color.argb(195, 18, 18, 18));
        labelPaint.setStyle(Paint.Style.FILL);

        eraserPaint.setStyle(Paint.Style.STROKE);
        eraserPaint.setStrokeWidth(2f * density);
        eraserPaint.setColor(Color.WHITE);
    }

    void setCallback(Callback callback) {
        this.callback = callback;
    }

    void setMode(AnnotationOverlayView.Mode mode) {
        this.mode = mode;
        clearDraft();
        invalidate();
    }

    AnnotationOverlayView.Mode getMode() {
        return mode;
    }

    void setProjectedAnnotations(List<ArProjectedAnnotation> projectedAnnotations) {
        projected.clear();
        projected.addAll(projectedAnnotations);
        invalidate();
    }

    int getProjectedCount() {
        return projected.size();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (callback == null) {
            return true;
        }
        float x = event.getX();
        float y = event.getY();
        if (callback.isProjectPlacementActive()) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                callback.requestPlaceProject(new PointF(x, y));
            }
            return true;
        }
        switch (mode) {
            case ERASE:
                handleErase(event, x, y);
                return true;
            case EDIT:
                handleEdit(event, x, y);
                return true;
            case POINT:
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    callback.requestCreateAnnotation(LawnAnnotation.TYPE_POINT, Collections.singletonList(new PointF(x, y)));
                }
                return true;
            case BOX:
            case CIRCLE:
                handleBoxOrCircle(event, x, y);
                return true;
            case FREEHAND:
                handleFreehand(event, x, y);
                return true;
            default:
                return true;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawSnapshot(canvas);
        drawDraft(canvas);
        if (mode == AnnotationOverlayView.Mode.ERASE && eraserPoint != null) {
            canvas.drawCircle(eraserPoint.x, eraserPoint.y, eraserRadiusPx, eraserPaint);
        }
    }

    void drawSnapshot(Canvas canvas) {
        for (ArProjectedAnnotation annotation : projected) {
            drawAnnotation(canvas, annotation);
        }
    }

    private void handleBoxOrCircle(MotionEvent event, float x, float y) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            drawing = true;
            draftStart = new PointF(x, y);
            draftCurrent = new PointF(x, y);
            invalidate();
        } else if (action == MotionEvent.ACTION_MOVE && drawing) {
            draftCurrent = new PointF(x, y);
            invalidate();
        } else if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) && drawing) {
            PointF start = draftStart;
            PointF end = new PointF(x, y);
            clearDraft();
            if (start != null && GeoMath.distance(start.x, start.y, end.x, end.y) > touchSlopPx) {
                ArrayList<PointF> points = new ArrayList<>();
                points.add(start);
                points.add(end);
                callback.requestCreateAnnotation(
                        mode == AnnotationOverlayView.Mode.CIRCLE ? LawnAnnotation.TYPE_CIRCLE : LawnAnnotation.TYPE_BOX,
                        points
                );
            }
        }
    }

    private void handleFreehand(MotionEvent event, float x, float y) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            drawing = true;
            freehandDraft.clear();
            freehandDraft.add(new PointF(x, y));
            invalidate();
        } else if (action == MotionEvent.ACTION_MOVE && drawing) {
            PointF last = freehandDraft.get(freehandDraft.size() - 1);
            if (GeoMath.distance(last.x, last.y, x, y) > 8f) {
                freehandDraft.add(new PointF(x, y));
                invalidate();
            }
        } else if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) && drawing) {
            if (freehandDraft.size() > 1) {
                callback.requestCreateAnnotation(LawnAnnotation.TYPE_FREEHAND, new ArrayList<>(freehandDraft));
            }
            clearDraft();
        }
    }

    private void handleEdit(MotionEvent event, float x, float y) {
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            ArProjectedAnnotation hit = findHitAnnotation(x, y, eraserRadiusPx);
            if (hit != null) {
                callback.requestEditAnnotation(hit.source);
            }
        }
    }

    private void handleErase(MotionEvent event, float x, float y) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            eraserPoint = new PointF(x, y);
            ArProjectedAnnotation hit = findHitAnnotation(x, y, eraserRadiusPx);
            if (hit != null) {
                callback.requestEraseAnnotation(hit.source);
            }
            invalidate();
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            eraserPoint = null;
            invalidate();
        }
    }

    private ArProjectedAnnotation findHitAnnotation(float x, float y, float radius) {
        for (int i = projected.size() - 1; i >= 0; i--) {
            ArProjectedAnnotation annotation = projected.get(i);
            if (hitTest(annotation, x, y, radius)) {
                return annotation;
            }
        }
        return null;
    }

    private boolean hitTest(ArProjectedAnnotation annotation, float x, float y, float radius) {
        if (annotation.points.isEmpty()) {
            return false;
        }
        if (LawnAnnotation.TYPE_POINT.equals(annotation.type)) {
            PointF point = annotation.points.get(0);
            return GeoMath.distance(x, y, point.x, point.y) <= radius;
        }
        for (int i = 1; i < annotation.points.size(); i++) {
            PointF a = annotation.points.get(i - 1);
            PointF b = annotation.points.get(i);
            if (GeoMath.distanceToSegment(x, y, a.x, a.y, b.x, b.y) <= radius) {
                return true;
            }
        }
        if (annotation.points.size() > 2) {
            PointF last = annotation.points.get(annotation.points.size() - 1);
            PointF first = annotation.points.get(0);
            return GeoMath.distanceToSegment(x, y, last.x, last.y, first.x, first.y) <= radius;
        }
        return false;
    }

    private void drawAnnotation(Canvas canvas, ArProjectedAnnotation annotation) {
        if (annotation.points.isEmpty()) {
            return;
        }
        int color = annotation.color;
        strokePaint.setColor(color);
        fillPaint.setColor(Color.argb(45, Color.red(color), Color.green(color), Color.blue(color)));

        if (LawnAnnotation.TYPE_POINT.equals(annotation.type)) {
            PointF point = annotation.points.get(0);
            canvas.drawCircle(point.x, point.y, 10f * getResources().getDisplayMetrics().density, fillPaint);
            canvas.drawCircle(point.x, point.y, 10f * getResources().getDisplayMetrics().density, strokePaint);
        } else {
            Path path = new Path();
            PointF first = annotation.points.get(0);
            path.moveTo(first.x, first.y);
            for (int i = 1; i < annotation.points.size(); i++) {
                path.lineTo(annotation.points.get(i).x, annotation.points.get(i).y);
            }
            if (annotation.points.size() > 2) {
                path.close();
                canvas.drawPath(path, fillPaint);
            }
            canvas.drawPath(path, strokePaint);
        }
        if (annotation.labelPoint != null) {
            drawLabel(canvas, annotation.label, annotation.labelPoint.x, annotation.labelPoint.y - 12f);
        }
    }

    private void drawDraft(Canvas canvas) {
        strokePaint.setColor(Color.WHITE);
        fillPaint.setColor(Color.argb(30, 255, 255, 255));
        if ((mode == AnnotationOverlayView.Mode.BOX || mode == AnnotationOverlayView.Mode.CIRCLE)
                && draftStart != null && draftCurrent != null) {
            RectF rect = normalizedRect(draftStart, draftCurrent);
            if (mode == AnnotationOverlayView.Mode.CIRCLE) {
                canvas.drawOval(rect, fillPaint);
                canvas.drawOval(rect, strokePaint);
            } else {
                canvas.drawRect(rect, fillPaint);
                canvas.drawRect(rect, strokePaint);
            }
        } else if (mode == AnnotationOverlayView.Mode.FREEHAND && freehandDraft.size() > 1) {
            Path path = new Path();
            path.moveTo(freehandDraft.get(0).x, freehandDraft.get(0).y);
            for (int i = 1; i < freehandDraft.size(); i++) {
                path.lineTo(freehandDraft.get(i).x, freehandDraft.get(i).y);
            }
            canvas.drawPath(path, strokePaint);
        }
    }

    private void drawLabel(Canvas canvas, String label, float x, float y) {
        if (label == null || label.trim().isEmpty()) {
            return;
        }
        String display = label.trim();
        if (display.length() > 28) {
            display = display.substring(0, 25) + "...";
        }
        float padding = 7f * getResources().getDisplayMetrics().density;
        float textWidth = textPaint.measureText(display);
        float height = textPaint.getTextSize() + padding * 1.7f;
        float left = GeoMath.clamp(x, 4f, getWidth() - textWidth - padding * 2f - 4f);
        float top = GeoMath.clamp(y - height, 4f, getHeight() - height - 4f);
        RectF rect = new RectF(left, top, left + textWidth + padding * 2f, top + height);
        canvas.drawRoundRect(rect, 8f, 8f, labelPaint);
        canvas.drawText(display, rect.left + padding, rect.bottom - padding, textPaint);
    }

    private RectF normalizedRect(PointF a, PointF b) {
        return new RectF(
                Math.min(a.x, b.x),
                Math.min(a.y, b.y),
                Math.max(a.x, b.x),
                Math.max(a.y, b.y)
        );
    }

    private void clearDraft() {
        drawing = false;
        draftStart = null;
        draftCurrent = null;
        eraserPoint = null;
        freehandDraft.clear();
    }
}
