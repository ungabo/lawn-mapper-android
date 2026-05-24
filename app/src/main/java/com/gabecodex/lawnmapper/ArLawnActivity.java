package com.gabecodex.lawnmapper;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PointF;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.view.Gravity;
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

public final class ArLawnActivity extends Activity implements ArOverlayView.Callback {
    private static final int REQUEST_CAMERA = 77;

    private GLSurfaceView glSurfaceView;
    private ArOverlayView overlayView;
    private ArLawnRenderer renderer;
    private Session session;
    private boolean installRequested;
    private boolean sessionResumed;
    private TextView statusText;
    private final ArrayList<Button> modeButtons = new ArrayList<>();
    private final Handler handler = new Handler();
    private final Runnable statusTicker = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            handler.postDelayed(this, 500L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setupUi();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            resumeArSession();
        }
        glSurfaceView.onResume();
        handler.post(statusTicker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(statusTicker);
        glSurfaceView.onPause();
        if (session != null && sessionResumed) {
            session.pause();
            sessionResumed = false;
        }
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
        if (requestCode == REQUEST_CAMERA
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            resumeArSession();
        } else {
            toast("Camera permission is needed for AR ground locking");
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
        statusText.setPadding(dp(12), dp(8), dp(12), dp(8));
        statusText.setBackgroundColor(Color.argb(185, 0, 0, 0));
        root.addView(statusText, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
        ));

        HorizontalScrollView toolbarScroll = new HorizontalScrollView(this);
        toolbarScroll.setHorizontalScrollBarEnabled(false);
        toolbarScroll.setBackgroundColor(Color.argb(205, 0, 0, 0));
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
        addActionButton(toolbar, "Clear", v -> confirmClear());
        addActionButton(toolbar, "2D", v -> startActivity(new Intent(this, MainActivity.class)));

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
                .setPositiveButton("Save", (dialog, which) -> annotation.label = input.getText().toString().trim())
                .setNeutralButton("Delete", (dialog, which) -> glSurfaceView.queueEvent(() -> renderer.removeAnnotation(annotation)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("Clear AR annotations?")
                .setMessage("This removes the AR anchors in this session.")
                .setPositiveButton("Clear", (dialog, which) -> glSurfaceView.queueEvent(() -> renderer.clearAnnotations()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showArUnavailable(String reason) {
        new AlertDialog.Builder(this)
                .setTitle("AR ground lock unavailable")
                .setMessage("ARCore could not start (" + reason + "). You can still use the older 2D GPS/heading mode.")
                .setPositiveButton("Open 2D Mode", (dialog, which) -> startActivity(new Intent(this, MainActivity.class)))
                .setNegativeButton("Close", null)
                .show();
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
        statusText.setText("AR " + overlayView.getMode().name() + " | " + renderer.getStatus()
                + " | " + renderer.getAnnotationCount() + " anchored");
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
