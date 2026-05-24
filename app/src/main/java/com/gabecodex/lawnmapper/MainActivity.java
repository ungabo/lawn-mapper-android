package com.gabecodex.lawnmapper;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity implements SensorEventListener, LocationListener, AnnotationOverlayView.Callback {
    private static final int REQUEST_PERMISSIONS = 42;

    private TextureView cameraView;
    private AnnotationOverlayView overlayView;
    private CameraController cameraController;
    private AnnotationStore annotationStore;
    private SensorManager sensorManager;
    private Sensor rotationVectorSensor;
    private LocationManager locationManager;
    private Location currentLocation;
    private DevicePose currentPose = DevicePose.UNKNOWN;
    private TextView statusText;
    private final ArrayList<Button> modeButtons = new ArrayList<>();
    private final Handler handler = new Handler();
    private final Runnable statusTicker = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            handler.postDelayed(this, 700L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        annotationStore = new AnnotationStore(this);
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        rotationVectorSensor = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        setupUi();
        overlayView.setAnnotations(annotationStore.load());
        cameraController = new CameraController(this, cameraView, new CameraController.Listener() {
            @Override
            public void onCameraReady() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        toast("Camera ready");
                    }
                });
            }

            @Override
            public void onCameraError(final String message) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        toast(message);
                    }
                });
            }
        });
        if (hasRequiredPermissions()) {
            startLiveInputs();
        } else {
            requestPermissions(requiredPermissions(), REQUEST_PERMISSIONS);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasRequiredPermissions()) {
            startLiveInputs();
        }
        handler.post(statusTicker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(statusTicker);
        if (cameraController != null) {
            cameraController.stop();
        }
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        stopLocationUpdates();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            if (hasRequiredPermissions()) {
                startLiveInputs();
            } else {
                toast("Camera and location permissions are needed");
            }
        }
    }

    private void setupUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(0, systemBarHeight("status_bar_height"), 0, systemBarHeight("navigation_bar_height"));
        cameraView = new TextureView(this);
        overlayView = new AnnotationOverlayView(this);
        overlayView.setCallback(this);

        root.addView(cameraView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        root.addView(overlayView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        statusText = new TextView(this);
        statusText.setTextColor(Color.WHITE);
        statusText.setTextSize(13f);
        statusText.setGravity(Gravity.CENTER_VERTICAL);
        statusText.setSingleLine(true);
        statusText.setPadding(dp(12), dp(8), dp(12), dp(8));
        statusText.setBackgroundColor(Color.argb(180, 0, 0, 0));
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
        );
        root.addView(statusText, statusParams);

        HorizontalScrollView toolbarScroll = new HorizontalScrollView(this);
        toolbarScroll.setHorizontalScrollBarEnabled(false);
        toolbarScroll.setBackgroundColor(Color.argb(190, 0, 0, 0));
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(8), dp(7), dp(8), dp(7));
        toolbarScroll.addView(toolbar);

        addModeButton(toolbar, "Point", AnnotationOverlayView.Mode.POINT);
        addModeButton(toolbar, "Box", AnnotationOverlayView.Mode.BOX);
        addModeButton(toolbar, "Circle", AnnotationOverlayView.Mode.CIRCLE);
        addModeButton(toolbar, "Free", AnnotationOverlayView.Mode.FREEHAND);
        addModeButton(toolbar, "Erase", AnnotationOverlayView.Mode.ERASE);
        addModeButton(toolbar, "Edit", AnnotationOverlayView.Mode.EDIT);
        addActionButton(toolbar, "Undo", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                overlayView.undoLast();
            }
        });
        addActionButton(toolbar, "Clear", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmClear();
            }
        });
        addActionButton(toolbar, "Snap", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                takeSnapshot();
            }
        });

        FrameLayout.LayoutParams toolbarParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
        );
        root.addView(toolbarScroll, toolbarParams);
        setContentView(root);
        updateModeButtons();
    }

    private void addModeButton(LinearLayout toolbar, String text, final AnnotationOverlayView.Mode mode) {
        Button button = makeToolbarButton(text);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                overlayView.setMode(mode);
                updateModeButtons();
            }
        });
        modeButtons.add(button);
        toolbar.addView(button);
    }

    private void addActionButton(LinearLayout toolbar, String text, View.OnClickListener listener) {
        Button button = makeToolbarButton(text);
        button.setOnClickListener(listener);
        toolbar.addView(button);
    }

    private Button makeToolbarButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(13f);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(dp(12), dp(7), dp(12), dp(7));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(dp(3), 0, dp(3), 0);
        button.setLayoutParams(params);
        return button;
    }

    private void updateModeButtons() {
        AnnotationOverlayView.Mode mode = overlayView.getMode();
        AnnotationOverlayView.Mode[] modes = new AnnotationOverlayView.Mode[]{
                AnnotationOverlayView.Mode.POINT,
                AnnotationOverlayView.Mode.BOX,
                AnnotationOverlayView.Mode.CIRCLE,
                AnnotationOverlayView.Mode.FREEHAND,
                AnnotationOverlayView.Mode.ERASE,
                AnnotationOverlayView.Mode.EDIT
        };
        for (int i = 0; i < modeButtons.size() && i < modes.length; i++) {
            Button button = modeButtons.get(i);
            if (modes[i] == mode) {
                button.setTextColor(Color.BLACK);
                button.setBackgroundColor(Color.rgb(51, 209, 122));
            } else {
                button.setTextColor(Color.WHITE);
                button.setBackgroundColor(Color.rgb(45, 45, 45));
            }
        }
        updateStatus();
    }

    private void startLiveInputs() {
        if (cameraController != null) {
            cameraController.start();
        }
        if (sensorManager != null && rotationVectorSensor != null) {
            sensorManager.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_UI);
        }
        startLocationUpdates();
    }

    private boolean hasRequiredPermissions() {
        for (String permission : requiredPermissions()) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private String[] requiredPermissions() {
        ArrayList<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.CAMERA);
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        return permissions.toArray(new String[0]);
    }

    private void startLocationUpdates() {
        if (locationManager == null
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            Location gps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            Location network = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            setCurrentLocationIfBetter(gps);
            setCurrentLocationIfBetter(network);
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this);
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1500L, 0f, this);
        } catch (SecurityException ignored) {
        }
    }

    private void stopLocationUpdates() {
        if (locationManager != null) {
            locationManager.removeUpdates(this);
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        setCurrentLocationIfBetter(location);
        overlayView.invalidate();
    }

    private void setCurrentLocationIfBetter(Location candidate) {
        if (candidate == null) {
            return;
        }
        if (currentLocation == null) {
            currentLocation = candidate;
            return;
        }
        boolean newer = candidate.getTime() > currentLocation.getTime() + 5000L;
        boolean moreAccurate = candidate.hasAccuracy() && currentLocation.hasAccuracy()
                && candidate.getAccuracy() < currentLocation.getAccuracy();
        if (newer || moreAccurate) {
            currentLocation = candidate;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) {
            return;
        }
        float[] rotationMatrix = new float[9];
        float[] orientation = new float[3];
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
        SensorManager.getOrientation(rotationMatrix, orientation);
        currentPose = new DevicePose(
                (float) Math.toDegrees(orientation[0]),
                (float) Math.toDegrees(orientation[1]),
                (float) Math.toDegrees(orientation[2]),
                System.currentTimeMillis(),
                true
        );
        overlayView.invalidate();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    public DevicePose getCurrentPose() {
        return currentPose;
    }

    @Override
    public Location getCurrentLocation() {
        return currentLocation;
    }

    @Override
    public float getHorizontalFovDegrees() {
        return cameraController == null ? 60f : cameraController.getHorizontalFovDegrees();
    }

    @Override
    public float getVerticalFovDegrees() {
        return cameraController == null ? 45f : cameraController.getVerticalFovDegrees();
    }

    @Override
    public void onZoomGesture(float scaleFactor) {
        if (cameraController != null) {
            cameraController.multiplyZoom(scaleFactor);
            updateStatus();
            overlayView.invalidate();
        }
    }

    @Override
    public void onAnnotationChanged() {
        annotationStore.save(overlayView.getAnnotations());
        updateStatus();
    }

    @Override
    public void promptLabel(final LawnAnnotation annotation) {
        final EditText input = new EditText(this);
        input.setSingleLine(false);
        input.setMinLines(1);
        input.setMaxLines(4);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setText(annotation.label);
        input.setSelectAllOnFocus(true);
        int padding = dp(18);
        input.setPadding(padding, padding / 2, padding, padding / 2);

        new AlertDialog.Builder(this)
                .setTitle("Label")
                .setView(input)
                .setPositiveButton("Save", (dialog, which) -> {
                    annotation.label = input.getText().toString().trim();
                    onAnnotationChanged();
                    overlayView.invalidate();
                })
                .setNeutralButton("Delete", (dialog, which) -> overlayView.removeAnnotation(annotation))
                .setNegativeButton("Cancel", (dialog, which) -> {
                    onAnnotationChanged();
                    overlayView.invalidate();
                })
                .show();
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("Clear all annotations?")
                .setMessage("This removes every saved lawn label on this phone.")
                .setPositiveButton("Clear", (dialog, which) -> overlayView.clearAll())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void takeSnapshot() {
        if (!cameraView.isAvailable() || cameraView.getWidth() <= 0 || cameraView.getHeight() <= 0) {
            toast("Camera is not ready yet");
            return;
        }
        Bitmap cameraBitmap = cameraView.getBitmap(cameraView.getWidth(), cameraView.getHeight());
        if (cameraBitmap == null) {
            toast("Could not read camera image");
            return;
        }
        Bitmap combined = Bitmap.createBitmap(cameraView.getWidth(), cameraView.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(combined);
        canvas.drawBitmap(cameraBitmap, null, new Rect(0, 0, combined.getWidth(), combined.getHeight()), null);
        overlayView.drawSnapshot(canvas);
        try {
            Uri uri = saveSnapshot(combined);
            toast(uri == null ? "Snapshot saved" : "Snapshot saved to LawnMapper");
        } catch (IOException e) {
            toast("Snapshot failed");
        } finally {
            cameraBitmap.recycle();
            combined.recycle();
        }
    }

    private Uri saveSnapshot(Bitmap bitmap) throws IOException {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String fileName = "lawnmapper_" + timestamp + ".png";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LawnMapper");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IOException("MediaStore insert failed");
            }
            try (OutputStream outputStream = resolver.openOutputStream(uri)) {
                if (outputStream == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)) {
                    throw new IOException("PNG write failed");
                }
            }
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
            return uri;
        }

        File directory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "LawnMapper");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Could not create snapshot directory");
        }
        File output = new File(directory, fileName);
        try (OutputStream outputStream = new FileOutputStream(output)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)) {
                throw new IOException("PNG write failed");
            }
        }
        Uri uri = Uri.fromFile(output);
        sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri));
        return uri;
    }

    private void updateStatus() {
        if (statusText == null || overlayView == null) {
            return;
        }
        String gps = currentLocation == null
                ? "GPS waiting"
                : "GPS " + Math.round(currentLocation.hasAccuracy() ? currentLocation.getAccuracy() : 0f) + "m";
        String pose = currentPose.valid
                ? "H " + Math.round(currentPose.azimuthDegrees) + " P " + Math.round(currentPose.pitchDegrees)
                : "Sensors waiting";
        String zoom = cameraController == null
                ? "1.0x"
                : String.format(Locale.US, "%.1fx", cameraController.getZoomFactor());
        statusText.setText(overlayView.getMode().name() + " | " + gps + " | " + pose + " | "
                + zoom + " | " + overlayView.getAnnotations().size() + " saved");
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int systemBarHeight(String resourceName) {
        int resourceId = getResources().getIdentifier(resourceName, "dimen", "android");
        return resourceId > 0 ? getResources().getDimensionPixelSize(resourceId) : 0;
    }
}
