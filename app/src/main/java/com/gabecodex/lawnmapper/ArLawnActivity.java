package com.gabecodex.lawnmapper;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PointF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.view.Gravity;
import android.view.PixelCopy;
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

import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Config;
import com.google.ar.core.Session;
import com.google.ar.core.exceptions.CameraNotAvailableException;

import java.util.ArrayList;
import java.util.List;

public final class ArLawnActivity extends Activity implements ArOverlayView.Callback, SensorEventListener, LocationListener {
    private static final int REQUEST_CAMERA = 77;

    private GLSurfaceView glSurfaceView;
    private ArOverlayView overlayView;
    private ArLawnRenderer renderer;
    private SavedProjectStore projectStore;
    private SavedProject currentProject;
    private SensorManager sensorManager;
    private Sensor rotationVectorSensor;
    private LocationManager locationManager;
    private Location currentLocation;
    private float currentAzimuthDegrees;
    private boolean hasPose;
    private Session session;
    private boolean installRequested;
    private boolean sessionResumed;
    private boolean projectDirty;
    private boolean autosaveScheduled;
    private TextView statusText;
    private final ArrayList<Button> modeButtons = new ArrayList<>();
    private final Handler handler = new Handler();
    private final Runnable statusTicker = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            if (projectDirty && currentLocation != null && hasPose) {
                autoSaveProjectSoon();
            }
            handler.postDelayed(this, 500L);
        }
    };
    private final Runnable autosaveRunnable = new Runnable() {
        @Override
        public void run() {
            autosaveScheduled = false;
            autoSaveProjectQuiet();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        projectStore = new SavedProjectStore(this);
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        rotationVectorSensor = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        setupUi();
        if (!hasRequiredPermissions()) {
            requestPermissions(requiredPermissions(), REQUEST_CAMERA);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasRequiredPermissions()) {
            resumeArSession();
            startLocationAndSensors();
        }
        glSurfaceView.onResume();
        handler.post(statusTicker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(statusTicker);
        handler.removeCallbacks(autosaveRunnable);
        autosaveScheduled = false;
        glSurfaceView.onPause();
        if (session != null && sessionResumed) {
            session.pause();
            sessionResumed = false;
        }
        stopLocationAndSensors();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (session != null) {
            session.close();
            session = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA && hasRequiredPermissions()) {
            resumeArSession();
            startLocationAndSensors();
        } else {
            toast("Camera and location permissions are needed");
        }
    }

    private void setupUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(0, systemBarHeight("status_bar_height"), 0, systemBarHeight("navigation_bar_height"));

        glSurfaceView = new GLSurfaceView(this);
        glSurfaceView.setPreserveEGLContextOnPause(true);
        glSurfaceView.setEGLContextClientVersion(2);
        overlayView = new ArOverlayView(this);
        overlayView.setCallback(this);
        renderer = new ArLawnRenderer(this, overlayView);
        glSurfaceView.setRenderer(renderer);
        glSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        root.addView(glSurfaceView, new FrameLayout.LayoutParams(
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
        statusText.setSingleLine(true);
        statusText.setGravity(Gravity.CENTER_VERTICAL);
        statusText.setPadding(dp(12), dp(8), dp(92), dp(8));
        statusText.setBackgroundColor(Color.argb(185, 0, 0, 0));
        root.addView(statusText, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
        ));
        Button menuButton = makeToolbarButton("Menu");
        menuButton.setOnClickListener(v -> showMenu());
        FrameLayout.LayoutParams menuParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.RIGHT
        );
        menuParams.setMargins(0, dp(3), dp(6), 0);
        root.addView(menuButton, menuParams);

        HorizontalScrollView toolbarScroll = new HorizontalScrollView(this);
        toolbarScroll.setHorizontalScrollBarEnabled(false);
        toolbarScroll.setBackgroundColor(Color.argb(205, 0, 0, 0));
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(8), dp(7), dp(8), dp(7));
        toolbarScroll.addView(toolbar);

        addActionButton(toolbar, "Snap", v -> takeSnapshot());
        addModeButton(toolbar, "Point", AnnotationOverlayView.Mode.POINT);
        addModeButton(toolbar, "Box", AnnotationOverlayView.Mode.BOX);
        addModeButton(toolbar, "Circle", AnnotationOverlayView.Mode.CIRCLE);
        addModeButton(toolbar, "Free", AnnotationOverlayView.Mode.FREEHAND);
        addModeButton(toolbar, "Erase", AnnotationOverlayView.Mode.ERASE);
        addModeButton(toolbar, "Edit", AnnotationOverlayView.Mode.EDIT);

        root.addView(toolbarScroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
        ));
        setContentView(root);
        updateModeButtons();
        updateStatus();
    }

    private void resumeArSession() {
        try {
            if (session == null) {
                ArCoreApk.InstallStatus installStatus = ArCoreApk.getInstance().requestInstall(this, !installRequested);
                if (installStatus == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                    installRequested = true;
                    return;
                }
                session = new Session(this);
                Config config = new Config(session);
                config.setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL);
                config.setFocusMode(Config.FocusMode.AUTO);
                config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);
                config.setInstantPlacementMode(Config.InstantPlacementMode.DISABLED);
                if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                    config.setDepthMode(Config.DepthMode.AUTOMATIC);
                }
                session.configure(config);
                renderer.setSession(session);
            }
            session.resume();
            sessionResumed = true;
        } catch (CameraNotAvailableException e) {
            toast("Camera is not available for AR");
        } catch (Exception e) {
            showArUnavailable(e.getClass().getSimpleName());
        }
    }

    @Override
    public void requestCreateAnnotation(String type, List<PointF> screenPoints) {
        glSurfaceView.queueEvent(() -> renderer.createAnnotationOnGlThread(type, screenPoints, new ArLawnRenderer.CreateCallback() {
            @Override
            public void onCreated(ArAnnotation annotation) {
                ensureCurrentProject();
                markProjectDirtyAndAutoSave();
                promptLabel(annotation);
            }

            @Override
            public void onFailed(String message) {
                toast(message);
            }
        }));
    }

    @Override
    public void requestEditAnnotation(ArAnnotation annotation) {
        promptLabel(annotation);
    }

    @Override
    public void requestEraseAnnotation(ArAnnotation annotation) {
        glSurfaceView.queueEvent(() -> renderer.removeAnnotation(annotation));
        markProjectDirtyAndAutoSave();
    }

    private void promptLabel(final ArAnnotation annotation) {
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
                    ensureCurrentProject();
                    markProjectDirtyAndAutoSave();
                })
                .setNeutralButton("Delete", (dialog, which) -> {
                    glSurfaceView.queueEvent(() -> renderer.removeAnnotation(annotation));
                    markProjectDirtyAndAutoSave();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("Clear AR annotations?")
                .setMessage("This removes the AR anchors in this session.")
                .setPositiveButton("Clear", (dialog, which) -> {
                    glSurfaceView.queueEvent(() -> renderer.clearAnnotations());
                    if (currentProject != null) {
                        currentProject.annotations.clear();
                        currentProject.hasOriginLocation = false;
                        projectDirty = false;
                        projectStore.save(currentProject);
                    }
                    updateStatus();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showArUnavailable(String reason) {
        new AlertDialog.Builder(this)
                .setTitle("AR ground lock unavailable")
                .setMessage("ARCore could not start (" + reason + "). Install or update Google Play Services for AR, then reopen Lawn Mapper.")
                .setPositiveButton("Close", null)
                .show();
    }

    private void showMenu() {
        String[] items = new String[]{
                "New project",
                "Save project",
                "Load project",
                "Rename project",
                "Delete project",
                "View snapshots",
                "Help",
                "Clear anchors"
        };
        new AlertDialog.Builder(this)
                .setTitle("Menu")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        confirmNewProject();
                    } else if (which == 1) {
                        saveProject();
                    } else if (which == 2) {
                        chooseProjectToLoad();
                    } else if (which == 3) {
                        chooseProjectToRename();
                    } else if (which == 4) {
                        chooseProjectToDelete();
                    } else if (which == 5) {
                        startActivity(new Intent(this, SnapshotGalleryActivity.class));
                    } else if (which == 6) {
                        showHelp();
                    } else if (which == 7) {
                        confirmClear();
                    }
                })
                .show();
    }

    private void confirmNewProject() {
        if (renderer.getAnnotationCount() == 0) {
            newProject();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Start new project?")
                .setMessage("This clears the current on-screen anchors. Saved projects stay in the project list.")
                .setPositiveButton("New", (dialog, which) -> newProject())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void newProject() {
        currentProject = new SavedProject();
        currentProject.name = projectStore.defaultProjectName();
        projectStore.save(currentProject);
        projectDirty = false;
        glSurfaceView.queueEvent(() -> renderer.clearAnnotations());
        toast("New project: " + currentProject.name);
        updateStatus();
    }

    private void saveProject() {
        final SavedProject baseProject = ensureCurrentProject();
        final String projectName = baseProject == null ? projectStore.defaultProjectName() : baseProject.name;
        if (renderer.getAnnotationCount() == 0 && baseProject != null) {
            projectStore.save(baseProject);
            toast("Saved " + baseProject.name);
            return;
        }
        if (currentLocation == null) {
            toast("Waiting for GPS before saving");
            return;
        }
        if (!hasPose) {
            toast("Waiting for compass before saving");
            return;
        }
        glSurfaceView.queueEvent(() -> renderer.buildProjectOnGlThread(projectName, baseProject, currentLocation, currentAzimuthDegrees, new ArLawnRenderer.ProjectBuildCallback() {
            @Override
            public void onBuilt(SavedProject project) {
                if (projectStore.save(project)) {
                    currentProject = project;
                    projectDirty = false;
                    toast("Saved " + project.name);
                    updateStatus();
                } else {
                    toast("Project save failed");
                }
            }

            @Override
            public void onFailed(String message) {
                toast(message);
            }
        }));
    }

    private void chooseProjectToLoad() {
        ArrayList<SavedProject> projects = projectStore.listProjects();
        if (projects.isEmpty()) {
            toast("No saved projects yet");
            return;
        }
        String[] names = projectNames(projects);
        new AlertDialog.Builder(this)
                .setTitle("Load Project")
                .setItems(names, (dialog, which) -> {
                    SavedProject loaded = projectStore.load(projects.get(which).id);
                    if (loaded == null) {
                        toast("Could not load project");
                        return;
                    }
                    loadProjectUsingGps(loaded);
                })
                .show();
    }

    private void loadProjectUsingGps(final SavedProject project) {
        if (currentLocation == null) {
            toast("Waiting for GPS before loading");
            return;
        }
        if (!hasPose) {
            toast("Waiting for compass before loading");
            return;
        }
        glSurfaceView.queueEvent(() -> renderer.placeProjectUsingGpsOnGlThread(project, currentLocation, currentAzimuthDegrees, new ArLawnRenderer.ProjectLoadCallback() {
            @Override
            public void onLoaded(int annotationCount) {
                currentProject = project;
                projectDirty = false;
                toast("Loaded " + project.name);
                updateStatus();
            }

            @Override
            public void onFailed(String message) {
                toast(message);
            }
        }));
    }

    private void chooseProjectToRename() {
        ArrayList<SavedProject> projects = projectStore.listProjects();
        if (projects.isEmpty()) {
            toast("No saved projects yet");
            return;
        }
        String[] names = projectNames(projects);
        new AlertDialog.Builder(this)
                .setTitle("Rename Project")
                .setItems(names, (dialog, which) -> promptProjectRename(projects.get(which)))
                .show();
    }

    private void chooseProjectToDelete() {
        ArrayList<SavedProject> projects = projectStore.listProjects();
        if (projects.isEmpty()) {
            toast("No saved projects yet");
            return;
        }
        String[] names = projectNames(projects);
        new AlertDialog.Builder(this)
                .setTitle("Delete Project")
                .setItems(names, (dialog, which) -> confirmDeleteProject(projects.get(which)))
                .show();
    }

    private void promptProjectRename(final SavedProject project) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setText(project.name);
        input.setSelectAllOnFocus(true);
        input.setPadding(dp(18), dp(8), dp(18), dp(8));
        new AlertDialog.Builder(this)
                .setTitle("Rename Project")
                .setView(input)
                .setPositiveButton("Save", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (projectStore.rename(project.id, name)) {
                        if (currentProject != null && currentProject.id.equals(project.id)) {
                            currentProject.name = name.isEmpty() ? projectStore.defaultProjectName() : name;
                        }
                        toast("Renamed project");
                        updateStatus();
                    } else {
                        toast("Rename failed");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDeleteProject(final SavedProject project) {
        new AlertDialog.Builder(this)
                .setTitle("Delete " + project.name + "?")
                .setMessage("This deletes the saved project file. Current on-screen anchors are not changed.")
                .setPositiveButton("Delete", (dialog, which) -> {
                    if (projectStore.delete(project.id)) {
                        if (currentProject != null && currentProject.id.equals(project.id)) {
                            currentProject = null;
                            projectDirty = false;
                        }
                        toast("Deleted project");
                        updateStatus();
                    } else {
                        toast("Delete failed");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showHelp() {
        new AlertDialog.Builder(this)
                .setTitle("How Lawn Mapper Works")
                .setMessage(
                        "Scan: Move slowly until the status says AR locked to ground planes.\n\n"
                                + "Snap: Saves the live camera image with the AR shapes and labels.\n\n"
                                + "Point: Tap the lawn to add a labeled dot.\n\n"
                                + "Box: Drag from one corner of a lawn area to the opposite corner.\n\n"
                                + "Circle: Drag across the area where the circle or oval should sit.\n\n"
                                + "Free: Draw an irregular outline on the visible lawn.\n\n"
                                + "Erase: Tap or drag through a shape to delete it.\n\n"
                                + "Edit: Tap a shape or label to rename or delete it.\n\n"
                                + "New project: Starts a blank project named by date and time.\n\n"
                                + "Save project: Saves the current shapes and labels. Projects also autosave after edits.\n\n"
                                + "Load project: Pick a saved project. It loads by GPS position when AR tracking, GPS, and compass are ready.\n\n"
                                + "Rename project: Changes a saved project's name.\n\n"
                                + "Delete project: Removes a saved project file.\n\n"
                                + "View snapshots: Opens saved photos so you can preview or share them.\n\n"
                                + "GPS loading uses phone GPS accuracy. For tighter automatic return-to-yard placement, the future Geospatial/VPS setup is still needed."
                )
                .setPositiveButton("OK", null)
                .show();
    }

    private void takeSnapshot() {
        int width = glSurfaceView.getWidth();
        int height = glSurfaceView.getHeight();
        if (width <= 0 || height <= 0) {
            toast("Camera is not ready yet");
            return;
        }
        Bitmap surfaceBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        PixelCopy.request(glSurfaceView, surfaceBitmap, result -> {
            if (result != PixelCopy.SUCCESS) {
                surfaceBitmap.recycle();
                toast("Snapshot failed");
                return;
            }
            saveSnapshot(surfaceBitmap);
        }, handler);
    }

    private void saveSnapshot(Bitmap surfaceBitmap) {
        Bitmap combined = Bitmap.createBitmap(surfaceBitmap.getWidth(), surfaceBitmap.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(combined);
        canvas.drawBitmap(surfaceBitmap, 0f, 0f, null);
        overlayView.drawSnapshot(canvas);
        try {
            SnapshotStore.saveSnapshot(this, combined);
            toast("Snapshot saved");
        } catch (Exception e) {
            toast("Snapshot failed");
        } finally {
            surfaceBitmap.recycle();
            combined.recycle();
        }
    }

    private void addModeButton(LinearLayout toolbar, String text, final AnnotationOverlayView.Mode mode) {
        Button button = makeToolbarButton(text);
        button.setOnClickListener(v -> {
            overlayView.setMode(mode);
            updateModeButtons();
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

    private void updateStatus() {
        if (statusText == null || renderer == null) {
            return;
        }
        String project = currentProject == null ? "Unsaved" : currentProject.name;
        String gps = currentLocation == null ? "GPS waiting" : "GPS " + Math.round(currentLocation.hasAccuracy() ? currentLocation.getAccuracy() : 0f) + "m";
        String compass = hasPose ? "Compass ready" : "Compass waiting";
        statusText.setText("AR " + overlayView.getMode().name() + " | " + project + " | " + gps + " | " + compass
                + " | " + renderer.getAnnotationCount() + " anchored");
    }

    private SavedProject ensureCurrentProject() {
        if (currentProject == null) {
            currentProject = new SavedProject();
            currentProject.name = projectStore.defaultProjectName();
            projectStore.save(currentProject);
            updateStatus();
        }
        return currentProject;
    }

    private void autoSaveProjectSoon() {
        if (!projectDirty || autosaveScheduled) {
            return;
        }
        autosaveScheduled = true;
        handler.postDelayed(autosaveRunnable, 350L);
    }

    private void autoSaveProjectQuiet() {
        final SavedProject baseProject = ensureCurrentProject();
        if (renderer.getAnnotationCount() == 0) {
            baseProject.annotations.clear();
            baseProject.hasOriginLocation = false;
            projectStore.save(baseProject);
            projectDirty = false;
            updateStatus();
            return;
        }
        if (currentLocation == null || !hasPose) {
            updateStatus();
            return;
        }
        glSurfaceView.queueEvent(() -> renderer.buildProjectOnGlThread(baseProject.name, baseProject, currentLocation, currentAzimuthDegrees, new ArLawnRenderer.ProjectBuildCallback() {
            @Override
            public void onBuilt(SavedProject project) {
                if (projectStore.save(project)) {
                    currentProject = project;
                    projectDirty = false;
                    updateStatus();
                }
            }

            @Override
            public void onFailed(String message) {
            }
        }));
    }

    private String[] projectNames(ArrayList<SavedProject> projects) {
        String[] names = new String[projects.size()];
        for (int i = 0; i < projects.size(); i++) {
            SavedProject project = projects.get(i);
            names[i] = project.name + " (" + project.annotations.size() + ")";
        }
        return names;
    }

    private void markProjectDirtyAndAutoSave() {
        projectDirty = true;
        autoSaveProjectSoon();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
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
        return new String[]{
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        };
    }

    private void startLocationAndSensors() {
        if (sensorManager != null && rotationVectorSensor != null) {
            sensorManager.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_UI);
        }
        if (locationManager == null || !hasRequiredPermissions()) {
            return;
        }
        try {
            setCurrentLocationIfBetter(locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER));
            setCurrentLocationIfBetter(locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER));
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this);
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1500L, 0f, this);
        } catch (SecurityException ignored) {
        }
    }

    private void stopLocationAndSensors() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(this);
            } catch (SecurityException ignored) {
            }
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        setCurrentLocationIfBetter(location);
        updateStatus();
        if (projectDirty && hasPose) {
            autoSaveProjectSoon();
        }
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
        currentAzimuthDegrees = GeoMath.normalizeCompass((float) Math.toDegrees(orientation[0]));
        hasPose = true;
        if (projectDirty && currentLocation != null) {
            autoSaveProjectSoon();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int systemBarHeight(String resourceName) {
        int resourceId = getResources().getIdentifier(resourceName, "dimen", "android");
        return resourceId > 0 ? getResources().getDimensionPixelSize(resourceId) : 0;
    }
}
