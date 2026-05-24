package com.gabecodex.lawnmapper;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.util.SizeF;
import android.view.Surface;
import android.view.TextureView;

import java.util.Arrays;
import java.util.Collections;

final class CameraController {
    interface Listener {
        void onCameraReady();

        void onCameraError(String message);
    }

    private final Activity activity;
    private final TextureView textureView;
    private final Listener listener;

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder previewRequestBuilder;
    private CameraCharacteristics cameraCharacteristics;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private Size previewSize;
    private String cameraId;
    private float baseHorizontalFovDegrees = 60f;
    private float baseVerticalFovDegrees = 45f;
    private float zoomFactor = 1f;
    private float maxZoomFactor = 4f;

    private final TextureView.SurfaceTextureListener surfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            openCamera(width, height);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            configureTransform(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        }
    };

    CameraController(Activity activity, TextureView textureView, Listener listener) {
        this.activity = activity;
        this.textureView = textureView;
        this.listener = listener;
    }

    void start() {
        startBackgroundThread();
        textureView.setSurfaceTextureListener(surfaceTextureListener);
        if (textureView.isAvailable()) {
            openCamera(textureView.getWidth(), textureView.getHeight());
        }
    }

    void stop() {
        closeCamera();
        stopBackgroundThread();
    }

    float getZoomFactor() {
        return zoomFactor;
    }

    float getHorizontalFovDegrees() {
        return Math.max(8f, baseHorizontalFovDegrees / zoomFactor);
    }

    float getVerticalFovDegrees() {
        return Math.max(6f, baseVerticalFovDegrees / zoomFactor);
    }

    void multiplyZoom(float scaleFactor) {
        setZoomFactor(zoomFactor * scaleFactor);
    }

    private void setZoomFactor(float newZoomFactor) {
        zoomFactor = GeoMath.clamp(newZoomFactor, 1f, maxZoomFactor);
        if (previewRequestBuilder != null && captureSession != null) {
            applyZoom(previewRequestBuilder);
            try {
                captureSession.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler);
            } catch (CameraAccessException e) {
                notifyError("Camera zoom failed");
            }
        }
    }

    private void openCamera(int width, int height) {
        if (activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            cameraId = chooseBackCamera(manager);
            if (cameraId == null) {
                notifyError("No back camera found");
                return;
            }
            cameraCharacteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                notifyError("Camera preview sizes unavailable");
                return;
            }
            previewSize = choosePreviewSize(map.getOutputSizes(SurfaceTexture.class));
            updateFov(cameraCharacteristics);
            configureTransform(width, height);
            Float maxZoom = cameraCharacteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            maxZoomFactor = maxZoom == null ? 4f : Math.min(8f, Math.max(1f, maxZoom));
            manager.openCamera(cameraId, stateCallback, backgroundHandler);
        } catch (CameraAccessException | SecurityException e) {
            notifyError("Could not open camera");
        }
    }

    private String chooseBackCamera(CameraManager manager) throws CameraAccessException {
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                return id;
            }
        }
        String[] ids = manager.getCameraIdList();
        return ids.length > 0 ? ids[0] : null;
    }

    private Size choosePreviewSize(Size[] choices) {
        if (choices == null || choices.length == 0) {
            return new Size(1280, 720);
        }
        return Arrays.stream(choices)
                .filter(size -> size.getWidth() <= 1920 && size.getHeight() <= 1080)
                .max((a, b) -> Long.compare((long) a.getWidth() * a.getHeight(), (long) b.getWidth() * b.getHeight()))
                .orElse(choices[0]);
    }

    private void updateFov(CameraCharacteristics characteristics) {
        SizeF physicalSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        float[] focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        if (physicalSize == null || focalLengths == null || focalLengths.length == 0) {
            return;
        }
        float focalLength = focalLengths[0];
        float landscapeHorizontal = (float) Math.toDegrees(2d * Math.atan(physicalSize.getWidth() / (2d * focalLength)));
        float landscapeVertical = (float) Math.toDegrees(2d * Math.atan(physicalSize.getHeight() / (2d * focalLength)));
        if (activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT) {
            baseHorizontalFovDegrees = landscapeVertical;
            baseVerticalFovDegrees = landscapeHorizontal;
        } else {
            baseHorizontalFovDegrees = landscapeHorizontal;
            baseVerticalFovDegrees = landscapeVertical;
        }
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            cameraDevice = camera;
            createPreviewSession();
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            camera.close();
            cameraDevice = null;
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            camera.close();
            cameraDevice = null;
            notifyError("Camera error " + error);
        }
    };

    private void createPreviewSession() {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            if (texture == null || cameraDevice == null || previewSize == null) {
                return;
            }
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface surface = new Surface(texture);
            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(surface);
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            applyZoom(previewRequestBuilder);
            cameraDevice.createCaptureSession(Collections.singletonList(surface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    if (cameraDevice == null) {
                        return;
                    }
                    captureSession = session;
                    try {
                        captureSession.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler);
                        if (listener != null) {
                            listener.onCameraReady();
                        }
                    } catch (CameraAccessException e) {
                        notifyError("Could not start camera preview");
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                    notifyError("Camera preview configuration failed");
                }
            }, backgroundHandler);
        } catch (CameraAccessException e) {
            notifyError("Could not create camera preview");
        }
    }

    private void applyZoom(CaptureRequest.Builder builder) {
        if (cameraCharacteristics == null || builder == null) {
            return;
        }
        Rect activeArray = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (activeArray == null || zoomFactor <= 1f) {
            builder.set(CaptureRequest.SCALER_CROP_REGION, activeArray);
            return;
        }
        int cropWidth = (int) (activeArray.width() / zoomFactor);
        int cropHeight = (int) (activeArray.height() / zoomFactor);
        int left = activeArray.left + (activeArray.width() - cropWidth) / 2;
        int top = activeArray.top + (activeArray.height() - cropHeight) / 2;
        builder.set(CaptureRequest.SCALER_CROP_REGION, new Rect(left, top, left + cropWidth, top + cropHeight));
    }

    private void configureTransform(int viewWidth, int viewHeight) {
        if (previewSize == null || viewWidth == 0 || viewHeight == 0) {
            return;
        }
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / previewSize.getHeight(),
                    (float) viewWidth / previewSize.getWidth()
            );
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90f * (rotation - 2), centerX, centerY);
        } else if (rotation == Surface.ROTATION_180) {
            matrix.postRotate(180f, centerX, centerY);
        }
        textureView.setTransform(matrix);
    }

    private void closeCamera() {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
    }

    private void startBackgroundThread() {
        if (backgroundThread != null) {
            return;
        }
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread == null) {
            return;
        }
        backgroundThread.quitSafely();
        try {
            backgroundThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        backgroundThread = null;
        backgroundHandler = null;
    }

    private void notifyError(String message) {
        if (listener != null) {
            listener.onCameraError(message);
        }
    }
}
