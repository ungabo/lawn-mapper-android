package com.gabecodex.lawnmapper;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.location.Location;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public final class AnnotationOverlayView extends View {
    enum Mode {
        POINT,
        BOX,
        CIRCLE,
        FREEHAND,
        ERASE,
        EDIT
    }

    interface Callback {
        DevicePose getCurrentPose();

        Location getCurrentLocation();

        float getHorizontalFovDegrees();

        float getVerticalFovDegrees();

        void onZoomGesture(float scaleFactor);

        void onAnnotationChanged();

        void promptLabel(LawnAnnotation annotation);
    }

    private static final int[] PALETTE = new int[]{
            Color.rgb(51, 209, 122),
            Color.rgb(98, 160, 234),
            Color.rgb(246, 211, 45),
            Color.rgb(255, 120, 82),
            Color.rgb(220, 138, 221),
            Color.rgb(138, 226, 216)
    };

    private final ArrayList<LawnAnnotation> annotations = new ArrayList<>();
    private final ArrayList<PointF> freehandDraft = new ArrayList<>();
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint eraserPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ScaleGestureDetector scaleGestureDetector;

    private Callback callback;
    private Mode mode = Mode.POINT;
    private PointF draftStart;
    private PointF draftCurrent;
    private PointF eraserPoint;
    private boolean drawing;
    private float touchSlopPx;
    private float eraserRadiusPx;

    public AnnotationOverlayView(Context context) {
        this(context, null);
    }

    public AnnotationOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        float density = getResources().getDisplayMetrics().density;
        touchSlopPx = 10f * density;
        eraserRadiusPx = 34f * density;

        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(3.5f * density);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);

        fillPaint.setStyle(Paint.Style.FILL);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(14f * getResources().getDisplayMetrics().scaledDensity);
        textPaint.setFakeBoldText(true);

        labelPaint.setColor(Color.argb(190, 18, 18, 18));
        labelPaint.setStyle(Paint.Style.FILL);

        eraserPaint.setStyle(Paint.Style.STROKE);
        eraserPaint.setStrokeWidth(2f * density);
        eraserPaint.setColor(Color.WHITE);

        scaleGestureDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (callback != null) {
                    callback.onZoomGesture(detector.getScaleFactor());
                }
                return true;
            }
        });
    }

    void setCallback(Callback callback) {
        this.callback = callback;
    }

    void setMode(Mode mode) {
        this.mode = mode;
        clearDraft();
        invalidate();
    }

    Mode getMode() {
        return mode;
    }

    void setAnnotations(List<LawnAnnotation> loadedAnnotations) {
        annotations.clear();
        annotations.addAll(loadedAnnotations);
        invalidate();
    }

    List<LawnAnnotation> getAnnotations() {
        return Collections.unmodifiableList(annotations);
    }

    void removeAnnotation(LawnAnnotation annotation) {
        if (annotations.remove(annotation)) {
            notifyChanged();
        }
    }

    void undoLast() {
        if (!annotations.isEmpty()) {
            annotations.remove(annotations.size() - 1);
            notifyChanged();
        }
    }

    void clearAll() {
        if (!annotations.isEmpty()) {
            annotations.clear();
            notifyChanged();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleGestureDetector.onTouchEvent(event);
        if (event.getPointerCount() > 1 || scaleGestureDetector.isInProgress()) {
            clearDraft();
            return true;
        }

        if (callback == null) {
            return true;
        }

        float x = event.getX();
        float y = event.getY();
        switch (mode) {
            case ERASE:
                handleErase(event, x, y);
                return true;
            case EDIT:
                handleEdit(event, x, y);
                return true;
            case POINT:
                handlePoint(event, x, y);
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
        drawAnnotations(canvas);
        drawDraft(canvas);
        if (mode == Mode.ERASE && eraserPoint != null) {
            canvas.drawCircle(eraserPoint.x, eraserPoint.y, eraserRadiusPx, eraserPaint);
        }
    }

    void drawSnapshot(Canvas canvas) {
        drawAnnotations(canvas);
    }

    private void handlePoint(MotionEvent event, float x, float y) {
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            ArrayList<PointF> points = new ArrayList<>();
            points.add(new PointF(x, y));
            createAnnotation(LawnAnnotation.TYPE_POINT, points);
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
                createAnnotation(mode == Mode.CIRCLE ? LawnAnnotation.TYPE_CIRCLE : LawnAnnotation.TYPE_BOX, points);
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
            if (GeoMath.distance(last.x, last.y, x, y) > 5f) {
                freehandDraft.add(new PointF(x, y));
                invalidate();
            }
        } else if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) && drawing) {
            if (freehandDraft.size() > 1) {
                createAnnotation(LawnAnnotation.TYPE_FREEHAND, new ArrayList<>(freehandDraft));
            }
            clearDraft();
        }
    }

    private void handleErase(MotionEvent event, float x, float y) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            eraserPoint = new PointF(x, y);
            if (eraseAt(x, y)) {
                notifyChanged();
            } else {
                invalidate();
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            eraserPoint = null;
            invalidate();
        }
    }

    private void handleEdit(MotionEvent event, float x, float y) {
        if (event.getActionMasked() != MotionEvent.ACTION_UP) {
            return;
        }
        LawnAnnotation hit = findHitAnnotation(x, y, eraserRadiusPx * 0.8f);
        if (hit != null && callback != null) {
            callback.promptLabel(hit);
        }
    }

    private void createAnnotation(String type, List<PointF> points) {
        DevicePose pose = callback.getCurrentPose();
        if (!pose.valid || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        int color = PALETTE[annotations.size() % PALETTE.length];
        LawnAnnotation annotation = LawnAnnotation.create(
                type,
                "",
                points,
                getWidth(),
                getHeight(),
                pose,
                callback.getHorizontalFovDegrees(),
                callback.getVerticalFovDegrees(),
                callback.getCurrentLocation(),
                color
        );
        annotations.add(annotation);
        notifyChanged();
        if (callback != null) {
            callback.promptLabel(annotation);
        }
    }

    private boolean eraseAt(float x, float y) {
        for (int i = annotations.size() - 1; i >= 0; i--) {
            LawnAnnotation annotation = annotations.get(i);
            if (hitTest(annotation, x, y, eraserRadiusPx)) {
                annotations.remove(i);
                return true;
            }
        }
        return false;
    }

    private LawnAnnotation findHitAnnotation(float x, float y, float radius) {
        for (int i = annotations.size() - 1; i >= 0; i--) {
            LawnAnnotation annotation = annotations.get(i);
            if (hitTest(annotation, x, y, radius)) {
                return annotation;
            }
        }
        return null;
    }

    private boolean hitTest(LawnAnnotation annotation, float x, float y, float radius) {
        if (callback == null || !annotation.isNear(callback.getCurrentLocation())) {
            return false;
        }
        List<PointF> points = annotation.project(
                callback.getCurrentPose(),
                getWidth(),
                getHeight(),
                callback.getHorizontalFovDegrees(),
                callback.getVerticalFovDegrees()
        );
        if (points.isEmpty()) {
            return false;
        }
        if (LawnAnnotation.TYPE_POINT.equals(annotation.type)) {
            PointF point = points.get(0);
            return GeoMath.distance(x, y, point.x, point.y) <= radius;
        }
        if (LawnAnnotation.TYPE_BOX.equals(annotation.type) || LawnAnnotation.TYPE_CIRCLE.equals(annotation.type)) {
            if (points.size() < 2) {
                return false;
            }
            RectF rect = normalizedRect(points.get(0), points.get(1));
            rect.inset(-radius, -radius);
            return rect.contains(x, y);
        }
        for (int i = 1; i < points.size(); i++) {
            PointF a = points.get(i - 1);
            PointF b = points.get(i);
            if (GeoMath.distanceToSegment(x, y, a.x, a.y, b.x, b.y) <= radius) {
                return true;
            }
        }
        return false;
    }

    private void drawAnnotations(Canvas canvas) {
        if (callback == null) {
            return;
        }
        DevicePose pose = callback.getCurrentPose();
        if (!pose.valid) {
            return;
        }
        for (LawnAnnotation annotation : annotations) {
            if (!annotation.isNear(callback.getCurrentLocation())) {
                continue;
            }
            List<PointF> points = annotation.project(
                    pose,
                    getWidth(),
                    getHeight(),
                    callback.getHorizontalFovDegrees(),
                    callback.getVerticalFovDegrees()
            );
            if (points.isEmpty() || !hasPointNearScreen(points)) {
                continue;
            }
            drawAnnotation(canvas, annotation, points);
        }
    }

    private void drawAnnotation(Canvas canvas, LawnAnnotation annotation, List<PointF> points) {
        int color = annotation.color;
        strokePaint.setColor(color);
        fillPaint.setColor(Color.argb(45, Color.red(color), Color.green(color), Color.blue(color)));

        if (LawnAnnotation.TYPE_POINT.equals(annotation.type)) {
            PointF point = points.get(0);
            canvas.drawCircle(point.x, point.y, 8f * getResources().getDisplayMetrics().density, fillPaint);
            canvas.drawCircle(point.x, point.y, 8f * getResources().getDisplayMetrics().density, strokePaint);
            drawLabel(canvas, annotation.label, point.x + 12f, point.y - 12f);
            return;
        }

        if ((LawnAnnotation.TYPE_BOX.equals(annotation.type) || LawnAnnotation.TYPE_CIRCLE.equals(annotation.type))
                && points.size() >= 2) {
            RectF rect = normalizedRect(points.get(0), points.get(1));
            if (LawnAnnotation.TYPE_CIRCLE.equals(annotation.type)) {
                canvas.drawOval(rect, fillPaint);
                canvas.drawOval(rect, strokePaint);
            } else {
                canvas.drawRect(rect, fillPaint);
                canvas.drawRect(rect, strokePaint);
            }
            drawLabel(canvas, annotation.label, rect.centerX(), rect.top - 10f);
            return;
        }

        Path path = new Path();
        PointF first = points.get(0);
        path.moveTo(first.x, first.y);
        for (int i = 1; i < points.size(); i++) {
            path.lineTo(points.get(i).x, points.get(i).y);
        }
        if (points.size() > 2) {
            path.close();
            canvas.drawPath(path, fillPaint);
        }
        canvas.drawPath(path, strokePaint);
        drawLabel(canvas, annotation.label, first.x, first.y - 10f);
    }

    private void drawDraft(Canvas canvas) {
        int draftColor = Color.WHITE;
        strokePaint.setColor(draftColor);
        fillPaint.setColor(Color.argb(30, 255, 255, 255));

        if ((mode == Mode.BOX || mode == Mode.CIRCLE) && draftStart != null && draftCurrent != null) {
            RectF rect = normalizedRect(draftStart, draftCurrent);
            if (mode == Mode.CIRCLE) {
                canvas.drawOval(rect, fillPaint);
                canvas.drawOval(rect, strokePaint);
            } else {
                canvas.drawRect(rect, fillPaint);
                canvas.drawRect(rect, strokePaint);
            }
        } else if (mode == Mode.FREEHAND && freehandDraft.size() > 1) {
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

    private boolean hasPointNearScreen(List<PointF> points) {
        float margin = Math.max(getWidth(), getHeight()) * 0.5f;
        for (PointF point : points) {
            if (point.x >= -margin && point.x <= getWidth() + margin
                    && point.y >= -margin && point.y <= getHeight() + margin) {
                return true;
            }
        }
        return false;
    }

    private RectF normalizedRect(PointF a, PointF b) {
        return new RectF(
                Math.min(a.x, b.x),
                Math.min(a.y, b.y),
                Math.max(a.x, b.x),
                Math.max(a.y, b.y)
        );
    }

    private void notifyChanged() {
        if (callback != null) {
            callback.onAnnotationChanged();
        }
        invalidate();
    }

    private void clearDraft() {
        drawing = false;
        draftStart = null;
        draftCurrent = null;
        freehandDraft.clear();
        eraserPoint = null;
    }
}
