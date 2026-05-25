package com.gabecodex.lawnmapper;

import android.app.Activity;
import android.graphics.PointF;
import android.location.Location;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import com.google.ar.core.Anchor;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.CameraNotAvailableException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

final class ArLawnRenderer implements GLSurfaceView.Renderer {
    interface CreateCallback {
        void onCreated(ArAnnotation annotation);

        void onFailed(String message);
    }

    interface ProjectBuildCallback {
        void onBuilt(SavedProject project);

        void onFailed(String message);
    }

    interface ProjectLoadCallback {
        void onLoaded(int annotationCount);

        void onFailed(String message);
    }

    private static final int[] PALETTE = new int[]{
            0xFF33D17A,
            0xFF62A0EA,
            0xFFF6D32D,
            0xFFFF7852,
            0xFFDC8ADD,
            0xFF8AE2D8
    };
    private static final float DEFAULT_PHONE_HEIGHT_METERS = 1.4f;
    private static final float HEADING_VECTOR_MIN_HORIZONTAL = 0.35f;

    private final Activity activity;
    private final ArOverlayView overlayView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ArrayList<ArAnnotation> annotations = new ArrayList<>();
    private final Object annotationLock = new Object();
    private final BackgroundRenderer backgroundRenderer = new BackgroundRenderer();
    private final float[] viewMatrix = new float[16];
    private final float[] projectionMatrix = new float[16];
    private final float[] viewProjectionMatrix = new float[16];

    private Session session;
    private Frame latestFrame;
    private int width;
    private int height;
    private int pendingRotation = Surface.ROTATION_0;
    private volatile String status = "AR starting";
    private boolean cameraTextureSet;
    private boolean hasEstimatedGroundY;
    private float estimatedGroundY;

    ArLawnRenderer(Activity activity, ArOverlayView overlayView) {
        this.activity = activity;
        this.overlayView = overlayView;
    }

    void setSession(Session session) {
        this.session = session;
        cameraTextureSet = false;
    }

    String getStatus() {
        return status;
    }

    int getAnnotationCount() {
        synchronized (annotationLock) {
            return annotations.size();
        }
    }

    void clearAnnotations() {
        synchronized (annotationLock) {
            for (ArAnnotation annotation : annotations) {
                annotation.detach();
            }
            annotations.clear();
        }
    }

    void removeAnnotation(ArAnnotation target) {
        synchronized (annotationLock) {
            if (annotations.remove(target)) {
                target.detach();
            }
        }
    }

    void createAnnotationOnGlThread(String type, List<PointF> screenPoints, CreateCallback callback) {
        if (latestFrame == null || session == null) {
            postFailed(callback, "AR is not tracking yet");
            return;
        }
        Camera camera = latestFrame.getCamera();
        if (camera.getTrackingState() != TrackingState.TRACKING) {
            postFailed(callback, "Move slowly until AR tracking is ready");
            return;
        }

        ArrayList<float[]> worldPoints = new ArrayList<>();
        for (PointF point : simplifyForHitTesting(type, screenPoints)) {
            float[] worldPoint = hitGroundPoint(latestFrame, point.x, point.y);
            if (worldPoint == null) {
                postFailed(callback, "Move slowly until AR tracking can estimate the lawn plane");
                return;
            }
            worldPoints.add(worldPoint);
        }
        if (worldPoints.isEmpty()) {
            postFailed(callback, "No ground point found");
            return;
        }

        Anchor anchor = session.createAnchor(Pose.makeTranslation(worldPoints.get(0)));
        Pose inverseAnchorPose = anchor.getPose().inverse();
        ArrayList<float[]> localHitPoints = new ArrayList<>();
        for (float[] worldPoint : worldPoints) {
            localHitPoints.add(inverseAnchorPose.transformPoint(worldPoint));
        }

        ArrayList<float[]> shapePoints = buildShapePoints(type, localHitPoints);
        float[] labelPoint = computeLabelPoint(type, shapePoints);
        ArAnnotation annotation = new ArAnnotation(
                type,
                PALETTE[getAnnotationCount() % PALETTE.length],
                anchor,
                shapePoints,
                labelPoint
        );
        synchronized (annotationLock) {
            annotations.add(annotation);
        }
        mainHandler.post(() -> callback.onCreated(annotation));
    }

    void buildProjectOnGlThread(String projectName, SavedProject baseProject, Location currentLocation, float azimuthDegrees, ProjectBuildCallback callback) {
        if (currentLocation == null) {
            postFailed(callback, "Waiting for GPS before saving project");
            return;
        }
        CameraBasis basis = getCameraBasis();
        if (basis == null) {
            postFailed(callback, "Scan until AR tracking is ready before saving");
            return;
        }
        SavedProject project = new SavedProject();
        if (baseProject != null) {
            project.id = baseProject.id;
            project.createdAtMillis = baseProject.createdAtMillis;
        }
        project.name = projectName;
        project.hasSavePose = true;
        project.savePhoneLatitude = currentLocation.getLatitude();
        project.savePhoneLongitude = currentLocation.getLongitude();
        project.savePhoneAltitude = currentLocation.hasAltitude() ? currentLocation.getAltitude() : 0d;
        project.savePhoneAccuracyMeters = currentLocation.hasAccuracy() ? currentLocation.getAccuracy() : 0f;
        project.saveAzimuthDegrees = azimuthDegrees;

        float[] origin = null;
        synchronized (annotationLock) {
            for (ArAnnotation annotation : annotations) {
                if (!annotation.localPoints.isEmpty()) {
                    origin = annotation.anchor.getPose().transformPoint(annotation.localPoints.get(0));
                    break;
                }
            }
            if (origin == null) {
                postFailed(callback, "Draw something before saving a project");
                return;
            }

            float[] originFromPhoneEnu = basis.worldDeltaToEnu(subtract(origin, basis.cameraGroundWorld), azimuthDegrees);
            Location originLocation = offsetLocation(currentLocation, originFromPhoneEnu[0], originFromPhoneEnu[1], originFromPhoneEnu[2]);
            project.hasOriginLocation = true;
            project.originLatitude = originLocation.getLatitude();
            project.originLongitude = originLocation.getLongitude();
            project.originAltitude = originLocation.hasAltitude() ? originLocation.getAltitude() : 0d;
            project.originAccuracyMeters = currentLocation.hasAccuracy() ? currentLocation.getAccuracy() : 0f;

            for (ArAnnotation annotation : annotations) {
                SavedProjectAnnotation saved = new SavedProjectAnnotation();
                saved.id = annotation.id;
                saved.type = annotation.type;
                saved.label = annotation.label;
                saved.color = annotation.color;
                saved.hasObserverPose = true;
                saved.observerLatitude = currentLocation.getLatitude();
                saved.observerLongitude = currentLocation.getLongitude();
                saved.observerAltitude = currentLocation.hasAltitude() ? currentLocation.getAltitude() : 0d;
                saved.observerAccuracyMeters = currentLocation.hasAccuracy() ? currentLocation.getAccuracy() : 0f;
                saved.observerAzimuthDegrees = azimuthDegrees;
                Pose anchorPose = annotation.anchor.getPose();
                for (float[] localPoint : annotation.localPoints) {
                    float[] pointFromOrigin = basis.worldDeltaToEnu(subtract(anchorPose.transformPoint(localPoint), origin), azimuthDegrees);
                    saved.points.add(pointFromOrigin);
                    saved.geoPoints.add(SavedGeoPoint.fromLocation(offsetLocation(originLocation, pointFromOrigin[0], pointFromOrigin[1], pointFromOrigin[2])));
                }
                saved.labelPoint = basis.worldDeltaToEnu(subtract(anchorPose.transformPoint(annotation.labelLocalPoint), origin), azimuthDegrees);
                saved.labelGeoPoint = SavedGeoPoint.fromLocation(offsetLocation(originLocation, saved.labelPoint[0], saved.labelPoint[1], saved.labelPoint[2]));
                project.annotations.add(saved);
            }
        }
        mainHandler.post(() -> callback.onBuilt(project));
    }

    void placeProjectUsingGpsOnGlThread(SavedProject project, Location currentLocation, float azimuthDegrees, ProjectLoadCallback callback) {
        if (project == null || project.annotations.isEmpty()) {
            postFailed(callback, "Project has no saved shapes");
            return;
        }
        if (!project.hasOriginLocation) {
            postFailed(callback, "Project does not have GPS origin data");
            return;
        }
        if (currentLocation == null) {
            postFailed(callback, "Waiting for GPS before loading project");
            return;
        }
        if (latestFrame == null || session == null) {
            postFailed(callback, "AR is not tracking yet");
            return;
        }
        Camera camera = latestFrame.getCamera();
        if (camera.getTrackingState() != TrackingState.TRACKING) {
            postFailed(callback, "Move slowly until AR tracking is ready");
            return;
        }
        CameraBasis basis = getCameraBasis();
        if (basis == null) {
            postFailed(callback, "Scan the lawn until a ground plane is found");
            return;
        }
        float[] currentFromProject = enuBetween(project.originLatitude, project.originLongitude, currentLocation);

        synchronized (annotationLock) {
            for (ArAnnotation annotation : annotations) {
                annotation.detach();
            }
            annotations.clear();

            for (SavedProjectAnnotation saved : project.annotations) {
                ArrayList<float[]> worldPoints = new ArrayList<>();
                for (int i = 0; i < saved.points.size(); i++) {
                    float[] fromCurrent = pointFromCurrent(saved, i, currentLocation, currentFromProject);
                    worldPoints.add(basis.enuFromCurrentToWorld(fromCurrent, azimuthDegrees));
                }
                if (worldPoints.isEmpty()) {
                    continue;
                }
                Anchor anchor = session.createAnchor(Pose.makeTranslation(worldPoints.get(0)));
                Pose inverseAnchorPose = anchor.getPose().inverse();
                ArrayList<float[]> localPoints = new ArrayList<>();
                for (float[] worldPoint : worldPoints) {
                    localPoints.add(inverseAnchorPose.transformPoint(worldPoint));
                }
                float[] labelFromCurrent = saved.labelGeoPoint == null
                        ? subtract(saved.labelPoint, currentFromProject)
                        : enuFromCurrentToGeo(currentLocation, saved.labelGeoPoint);
                float[] labelPoint = inverseAnchorPose.transformPoint(basis.enuFromCurrentToWorld(labelFromCurrent, azimuthDegrees));
                ArAnnotation annotation = new ArAnnotation(
                        saved.id,
                        saved.type,
                        saved.color,
                        anchor,
                        localPoints,
                        labelPoint
                );
                annotation.label = saved.label == null ? "" : saved.label;
                annotations.add(annotation);
            }
        }
        mainHandler.post(() -> callback.onLoaded(project.annotations.size()));
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        backgroundRenderer.createOnGlThread();
        cameraTextureSet = false;
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        this.width = width;
        this.height = height;
        GLES20.glViewport(0, 0, width, height);
        Session current = session;
        if (current != null) {
            current.setDisplayGeometry(getRotation(), width, height);
        }
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        Session current = session;
        if (current == null) {
            status = "AR session unavailable";
            return;
        }
        try {
            if (!cameraTextureSet) {
                current.setCameraTextureName(backgroundRenderer.getTextureId());
                cameraTextureSet = true;
            }
            int rotation = getRotation();
            if (rotation != pendingRotation && width > 0 && height > 0) {
                pendingRotation = rotation;
                current.setDisplayGeometry(rotation, width, height);
            }
            Frame frame = current.update();
            latestFrame = frame;
            backgroundRenderer.draw(frame);

            Camera camera = frame.getCamera();
            if (camera.getTrackingState() != TrackingState.TRACKING) {
                status = "Scan slowly until AR tracking starts";
                postProjection(new ArrayList<>());
                return;
            }

            camera.getViewMatrix(viewMatrix, 0);
            camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100f);
            multiplyMM(viewProjectionMatrix, projectionMatrix, viewMatrix);
            updateStatus(current);
            postProjection(projectAnnotations());
        } catch (CameraNotAvailableException e) {
            status = "Camera unavailable";
        } catch (RuntimeException e) {
            status = "AR frame error: " + e.getClass().getSimpleName();
        }
    }

    private void updateStatus(Session current) {
        int trackingPlanes = 0;
        for (Plane plane : current.getAllTrackables(Plane.class)) {
            if (plane.getTrackingState() == TrackingState.TRACKING
                    && plane.getType() == Plane.Type.HORIZONTAL_UPWARD_FACING) {
                trackingPlanes++;
                estimatedGroundY = plane.getCenterPose().ty();
                hasEstimatedGroundY = true;
            }
        }
        if (trackingPlanes > 0) {
            status = "AR locked to ground planes";
        } else if (hasEstimatedGroundY) {
            status = "AR using estimated ground";
        } else {
            status = "AR using rough ground estimate";
        }
    }

    private ArrayList<ArProjectedAnnotation> projectAnnotations() {
        ArrayList<ArProjectedAnnotation> projected = new ArrayList<>();
        synchronized (annotationLock) {
            for (ArAnnotation annotation : annotations) {
                if (annotation.anchor.getTrackingState() != TrackingState.TRACKING) {
                    continue;
                }
                Pose anchorPose = annotation.anchor.getPose();
                ArProjectedAnnotation item = new ArProjectedAnnotation(annotation);
                for (float[] localPoint : annotation.localPoints) {
                    PointF screen = projectWorldPoint(anchorPose.transformPoint(localPoint));
                    if (screen != null) {
                        item.points.add(screen);
                    }
                }
                item.labelPoint = projectWorldPoint(anchorPose.transformPoint(annotation.labelLocalPoint));
                if (!item.points.isEmpty()) {
                    projected.add(item);
                }
            }
        }
        return projected;
    }

    private PointF projectWorldPoint(float[] worldPoint) {
        float[] clip = multiplyMV(viewProjectionMatrix, new float[]{worldPoint[0], worldPoint[1], worldPoint[2], 1f});
        if (clip[3] <= 0.0001f) {
            return null;
        }
        float ndcX = clip[0] / clip[3];
        float ndcY = clip[1] / clip[3];
        if (ndcX < -3f || ndcX > 3f || ndcY < -3f || ndcY > 3f) {
            return null;
        }
        return new PointF((ndcX + 1f) * 0.5f * width, (1f - ndcY) * 0.5f * height);
    }

    private void postProjection(ArrayList<ArProjectedAnnotation> projected) {
        mainHandler.post(() -> overlayView.setProjectedAnnotations(projected));
    }

    private void postFailed(CreateCallback callback, String message) {
        mainHandler.post(() -> callback.onFailed(message));
    }

    private void postFailed(ProjectBuildCallback callback, String message) {
        mainHandler.post(() -> callback.onFailed(message));
    }

    private void postFailed(ProjectLoadCallback callback, String message) {
        mainHandler.post(() -> callback.onFailed(message));
    }

    private float[] pointFromCurrent(SavedProjectAnnotation saved, int index, Location currentLocation, float[] currentFromProject) {
        if (index < saved.geoPoints.size()) {
            return enuFromCurrentToGeo(currentLocation, saved.geoPoints.get(index));
        }
        return subtract(saved.points.get(index), currentFromProject);
    }

    private float[] hitGroundPoint(Frame frame, float x, float y) {
        for (HitResult hit : frame.hitTest(x, y)) {
            Trackable trackable = hit.getTrackable();
            if (trackable instanceof Plane) {
                Plane plane = (Plane) trackable;
                if (plane.getTrackingState() == TrackingState.TRACKING
                        && plane.getType() == Plane.Type.HORIZONTAL_UPWARD_FACING
                        && plane.isPoseInPolygon(hit.getHitPose())) {
                    estimatedGroundY = hit.getHitPose().ty();
                    hasEstimatedGroundY = true;
                    return hit.getHitPose().getTranslation();
                }
            }
        }
        return hitEstimatedGroundPoint(frame, x, y);
    }

    private CameraBasis getCameraBasis() {
        if (latestFrame == null || latestFrame.getCamera().getTrackingState() != TrackingState.TRACKING) {
            return null;
        }
        Pose cameraPose = latestFrame.getCamera().getPose();
        float groundY = estimateGroundY(cameraPose);
        float[] camera = cameraPose.getTranslation();
        float[] heading = chooseHorizontalHeadingVector(cameraPose);
        float[] right = new float[]{-heading[2], 0f, heading[0]};
        return new CameraBasis(new float[]{camera[0], groundY, camera[2]}, heading, right);
    }

    private float[] hitEstimatedGroundPoint(Frame frame, float x, float y) {
        if (width <= 0 || height <= 0) {
            return null;
        }
        Camera camera = frame.getCamera();
        if (camera.getTrackingState() != TrackingState.TRACKING) {
            return null;
        }
        Pose cameraPose = camera.getPose();
        float groundY = estimateGroundY(cameraPose);
        float[] localViewMatrix = new float[16];
        float[] localProjectionMatrix = new float[16];
        float[] localViewProjectionMatrix = new float[16];
        float[] inverseViewProjectionMatrix = new float[16];
        camera.getViewMatrix(localViewMatrix, 0);
        camera.getProjectionMatrix(localProjectionMatrix, 0, 0.1f, 100f);
        multiplyMM(localViewProjectionMatrix, localProjectionMatrix, localViewMatrix);
        if (!Matrix.invertM(inverseViewProjectionMatrix, 0, localViewProjectionMatrix, 0)) {
            return null;
        }

        float ndcX = (x / width) * 2f - 1f;
        float ndcY = 1f - (y / height) * 2f;
        float[] near = multiplyMV(inverseViewProjectionMatrix, new float[]{ndcX, ndcY, -1f, 1f});
        float[] far = multiplyMV(inverseViewProjectionMatrix, new float[]{ndcX, ndcY, 1f, 1f});
        divideByW(near);
        divideByW(far);

        float[] direction = subtract(far, near);
        if (Math.abs(direction[1]) < 0.0001f) {
            return null;
        }
        float t = (groundY - near[1]) / direction[1];
        if (t <= 0f || t > 200f) {
            return null;
        }
        return new float[]{
                near[0] + direction[0] * t,
                groundY,
                near[2] + direction[2] * t
        };
    }

    private float estimateGroundY(Pose cameraPose) {
        Float trackedGroundY = findTrackedGroundY();
        if (trackedGroundY != null) {
            estimatedGroundY = trackedGroundY;
            hasEstimatedGroundY = true;
            return trackedGroundY;
        }
        if (!hasEstimatedGroundY) {
            estimatedGroundY = cameraPose.ty() - DEFAULT_PHONE_HEIGHT_METERS;
            hasEstimatedGroundY = true;
        }
        return estimatedGroundY;
    }

    private Float findTrackedGroundY() {
        Session current = session;
        if (current == null) {
            return null;
        }
        for (Plane plane : current.getAllTrackables(Plane.class)) {
            if (plane.getTrackingState() == TrackingState.TRACKING
                    && plane.getType() == Plane.Type.HORIZONTAL_UPWARD_FACING) {
                return plane.getCenterPose().ty();
            }
        }
        return null;
    }

    private static float[] chooseHorizontalHeadingVector(Pose cameraPose) {
        float[] forward = cameraPose.rotateVector(new float[]{0f, 0f, -1f});
        if (horizontalLength(forward) < HEADING_VECTOR_MIN_HORIZONTAL) {
            forward = cameraPose.rotateVector(new float[]{0f, 1f, 0f});
        }
        forward[1] = 0f;
        normalizeHorizontal(forward);
        return forward;
    }

    private List<PointF> simplifyForHitTesting(String type, List<PointF> screenPoints) {
        if (!LawnAnnotation.TYPE_FREEHAND.equals(type) || screenPoints.size() <= 80) {
            return screenPoints;
        }
        ArrayList<PointF> simplified = new ArrayList<>();
        float step = screenPoints.size() / 80f;
        for (float index = 0f; index < screenPoints.size(); index += step) {
            simplified.add(screenPoints.get(Math.min(screenPoints.size() - 1, Math.round(index))));
        }
        return simplified;
    }

    private ArrayList<float[]> buildShapePoints(String type, ArrayList<float[]> localHits) {
        if (LawnAnnotation.TYPE_POINT.equals(type) || LawnAnnotation.TYPE_FREEHAND.equals(type) || localHits.size() < 2) {
            return new ArrayList<>(localHits);
        }

        float[] a = localHits.get(0);
        float[] b = localHits.get(1);
        float y = (a[1] + b[1]) * 0.5f;
        float minX = Math.min(a[0], b[0]);
        float maxX = Math.max(a[0], b[0]);
        float minZ = Math.min(a[2], b[2]);
        float maxZ = Math.max(a[2], b[2]);
        ArrayList<float[]> points = new ArrayList<>();
        if (LawnAnnotation.TYPE_BOX.equals(type)) {
            points.add(new float[]{minX, y, minZ});
            points.add(new float[]{maxX, y, minZ});
            points.add(new float[]{maxX, y, maxZ});
            points.add(new float[]{minX, y, maxZ});
            return points;
        }

        float centerX = (minX + maxX) * 0.5f;
        float centerZ = (minZ + maxZ) * 0.5f;
        float radiusX = Math.max(0.05f, Math.abs(maxX - minX) * 0.5f);
        float radiusZ = Math.max(0.05f, Math.abs(maxZ - minZ) * 0.5f);
        for (int i = 0; i < 64; i++) {
            double angle = (Math.PI * 2d * i) / 64d;
            points.add(new float[]{
                    centerX + (float) Math.cos(angle) * radiusX,
                    y,
                    centerZ + (float) Math.sin(angle) * radiusZ
            });
        }
        return points;
    }

    private float[] computeLabelPoint(String type, ArrayList<float[]> localPoints) {
        if (localPoints.isEmpty()) {
            return new float[]{0f, 0f, 0f};
        }
        if (LawnAnnotation.TYPE_POINT.equals(type)) {
            return localPoints.get(0);
        }
        float x = 0f;
        float y = 0f;
        float z = 0f;
        for (float[] point : localPoints) {
            x += point[0];
            y += point[1];
            z += point[2];
        }
        float count = localPoints.size();
        return new float[]{x / count, y / count, z / count};
    }

    private int getRotation() {
        return activity.getWindowManager().getDefaultDisplay().getRotation();
    }

    private static void multiplyMM(float[] out, float[] a, float[] b) {
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                out[col * 4 + row] =
                        a[0 * 4 + row] * b[col * 4 + 0]
                                + a[1 * 4 + row] * b[col * 4 + 1]
                                + a[2 * 4 + row] * b[col * 4 + 2]
                                + a[3 * 4 + row] * b[col * 4 + 3];
            }
        }
    }

    private static float[] multiplyMV(float[] matrix, float[] vector) {
        return new float[]{
                matrix[0] * vector[0] + matrix[4] * vector[1] + matrix[8] * vector[2] + matrix[12] * vector[3],
                matrix[1] * vector[0] + matrix[5] * vector[1] + matrix[9] * vector[2] + matrix[13] * vector[3],
                matrix[2] * vector[0] + matrix[6] * vector[1] + matrix[10] * vector[2] + matrix[14] * vector[3],
                matrix[3] * vector[0] + matrix[7] * vector[1] + matrix[11] * vector[2] + matrix[15] * vector[3]
        };
    }

    private static void divideByW(float[] vector) {
        if (vector.length < 4 || Math.abs(vector[3]) < 0.0001f) {
            return;
        }
        vector[0] /= vector[3];
        vector[1] /= vector[3];
        vector[2] /= vector[3];
        vector[3] = 1f;
    }

    private static float[] add(float[] a, float[] b) {
        return new float[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    private static float[] subtract(float[] a, float[] b) {
        return new float[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static void normalizeHorizontal(float[] vector) {
        float length = horizontalLength(vector);
        if (length < 0.0001f) {
            vector[0] = 0f;
            vector[2] = -1f;
            return;
        }
        vector[0] /= length;
        vector[2] /= length;
    }

    private static float horizontalLength(float[] vector) {
        return (float) Math.sqrt(vector[0] * vector[0] + vector[2] * vector[2]);
    }

    private static Location offsetLocation(Location origin, float eastMeters, float northMeters, float upMeters) {
        double latRadians = Math.toRadians(origin.getLatitude());
        double earthRadius = 6378137d;
        double newLat = origin.getLatitude() + Math.toDegrees(northMeters / earthRadius);
        double newLon = origin.getLongitude() + Math.toDegrees(eastMeters / (earthRadius * Math.cos(latRadians)));
        Location location = new Location(origin);
        location.setLatitude(newLat);
        location.setLongitude(newLon);
        if (origin.hasAltitude()) {
            location.setAltitude(origin.getAltitude() + upMeters);
        }
        return location;
    }

    private static float[] enuBetween(double originLat, double originLon, Location currentLocation) {
        return enuBetween(originLat, originLon, currentLocation.getLatitude(), currentLocation.getLongitude());
    }

    private static float[] enuFromCurrentToGeo(Location currentLocation, SavedGeoPoint point) {
        return enuBetween(currentLocation.getLatitude(), currentLocation.getLongitude(), point.latitude, point.longitude);
    }

    private static float[] enuBetween(double originLat, double originLon, double targetLat, double targetLon) {
        float[] distance = new float[1];
        Location.distanceBetween(originLat, originLon, targetLat, originLon, distance);
        float north = targetLat >= originLat ? distance[0] : -distance[0];
        Location.distanceBetween(originLat, originLon, originLat, targetLon, distance);
        float east = targetLon >= originLon ? distance[0] : -distance[0];
        return new float[]{east, north, 0f};
    }

    private static final class CameraBasis {
        final float[] cameraGroundWorld;
        final float[] forwardWorld;
        final float[] rightWorld;

        CameraBasis(float[] cameraGroundWorld, float[] forwardWorld, float[] rightWorld) {
            this.cameraGroundWorld = cameraGroundWorld;
            this.forwardWorld = forwardWorld;
            this.rightWorld = rightWorld;
        }

        float[] worldDeltaToEnu(float[] worldDelta, float azimuthDegrees) {
            float forward = worldDelta[0] * forwardWorld[0] + worldDelta[2] * forwardWorld[2];
            float right = worldDelta[0] * rightWorld[0] + worldDelta[2] * rightWorld[2];
            double az = Math.toRadians(azimuthDegrees);
            float east = (float) (forward * Math.sin(az) + right * Math.cos(az));
            float north = (float) (forward * Math.cos(az) - right * Math.sin(az));
            return new float[]{east, north, worldDelta[1]};
        }

        float[] enuFromCurrentToWorld(float[] enu, float azimuthDegrees) {
            double az = Math.toRadians(azimuthDegrees);
            float forward = (float) (enu[1] * Math.cos(az) + enu[0] * Math.sin(az));
            float right = (float) (enu[0] * Math.cos(az) - enu[1] * Math.sin(az));
            return new float[]{
                    cameraGroundWorld[0] + forwardWorld[0] * forward + rightWorld[0] * right,
                    cameraGroundWorld[1] + enu[2],
                    cameraGroundWorld[2] + forwardWorld[2] * forward + rightWorld[2] * right
            };
        }
    }

    private static FloatBuffer directFloatBuffer(float[] values) {
        FloatBuffer buffer = ByteBuffer.allocateDirect(values.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        buffer.put(values);
        buffer.position(0);
        return buffer;
    }

    private static final class BackgroundRenderer {
        private final FloatBuffer quadCoords = directFloatBuffer(new float[]{
                -1f, -1f,
                1f, -1f,
                -1f, 1f,
                1f, 1f
        });
        private final FloatBuffer textureCoords = directFloatBuffer(new float[]{
                0f, 1f,
                1f, 1f,
                0f, 0f,
                1f, 0f
        });
        private final FloatBuffer transformedTextureCoords = directFloatBuffer(new float[8]);

        private int textureId = -1;
        private int program;
        private int positionAttribute;
        private int texCoordAttribute;
        private int textureUniform;

        void createOnGlThread() {
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            textureId = textures[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
            positionAttribute = GLES20.glGetAttribLocation(program, "a_Position");
            texCoordAttribute = GLES20.glGetAttribLocation(program, "a_TexCoord");
            textureUniform = GLES20.glGetUniformLocation(program, "sTexture");
        }

        int getTextureId() {
            return textureId;
        }

        void draw(Frame frame) {
            if (frame.getTimestamp() == 0L) {
                return;
            }
            quadCoords.position(0);
            transformedTextureCoords.position(0);
            frame.transformCoordinates2d(
                    com.google.ar.core.Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                    quadCoords,
                    com.google.ar.core.Coordinates2d.TEXTURE_NORMALIZED,
                    transformedTextureCoords
            );

            GLES20.glDisable(GLES20.GL_DEPTH_TEST);
            GLES20.glDepthMask(false);
            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glUniform1i(textureUniform, 0);

            quadCoords.position(0);
            GLES20.glVertexAttribPointer(positionAttribute, 2, GLES20.GL_FLOAT, false, 0, quadCoords);
            GLES20.glEnableVertexAttribArray(positionAttribute);
            transformedTextureCoords.position(0);
            GLES20.glVertexAttribPointer(texCoordAttribute, 2, GLES20.GL_FLOAT, false, 0, transformedTextureCoords);
            GLES20.glEnableVertexAttribArray(texCoordAttribute);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(positionAttribute);
            GLES20.glDisableVertexAttribArray(texCoordAttribute);
            GLES20.glDepthMask(true);
            GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        }

        private static int createProgram(String vertexShaderSource, String fragmentShaderSource) {
            int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderSource);
            int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderSource);
            int program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, vertexShader);
            GLES20.glAttachShader(program, fragmentShader);
            GLES20.glLinkProgram(program);
            return program;
        }

        private static int loadShader(int type, String source) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            return shader;
        }

        private static final String VERTEX_SHADER =
                "attribute vec4 a_Position;\n"
                        + "attribute vec2 a_TexCoord;\n"
                        + "varying vec2 v_TexCoord;\n"
                        + "void main() {\n"
                        + "  gl_Position = a_Position;\n"
                        + "  v_TexCoord = a_TexCoord;\n"
                        + "}\n";

        private static final String FRAGMENT_SHADER =
                "#extension GL_OES_EGL_image_external : require\n"
                        + "precision mediump float;\n"
                        + "varying vec2 v_TexCoord;\n"
                        + "uniform samplerExternalOES sTexture;\n"
                        + "void main() {\n"
                        + "  gl_FragColor = texture2D(sTexture, v_TexCoord);\n"
                        + "}\n";
    }
}
