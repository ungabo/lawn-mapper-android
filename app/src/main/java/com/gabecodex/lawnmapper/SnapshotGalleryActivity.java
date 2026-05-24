package com.gabecodex.lawnmapper;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

public final class SnapshotGalleryActivity extends Activity {
    private static final int REQUEST_IMAGES = 91;

    private final ArrayList<SnapshotStore.SnapshotItem> snapshots = new ArrayList<>();
    private SnapshotAdapter adapter;
    private TextView emptyText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setupUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (needsImagePermission()) {
            requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES}, REQUEST_IMAGES);
        } else {
            loadSnapshots();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_IMAGES
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            loadSnapshots();
        } else {
            loadSnapshots();
        }
    }

    private void setupUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(0, systemBarHeight("status_bar_height"), 0, systemBarHeight("navigation_bar_height"));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(8), dp(12), dp(8));
        header.setBackgroundColor(Color.rgb(24, 24, 24));
        TextView title = new TextView(this);
        title.setText("Snapshots");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18f);
        title.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button close = new Button(this);
        close.setText("Close");
        close.setAllCaps(false);
        close.setOnClickListener(v -> finish());
        header.addView(close);
        content.addView(header);

        GridView grid = new GridView(this);
        grid.setNumColumns(3);
        grid.setVerticalSpacing(dp(6));
        grid.setHorizontalSpacing(dp(6));
        grid.setPadding(dp(6), dp(6), dp(6), dp(6));
        grid.setClipToPadding(false);
        grid.setBackgroundColor(Color.BLACK);
        adapter = new SnapshotAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> showSnapshot(snapshots.get(position)));
        content.addView(grid, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        emptyText = new TextView(this);
        emptyText.setText("No snapshots yet");
        emptyText.setTextColor(Color.WHITE);
        emptyText.setTextSize(16f);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setVisibility(View.GONE);

        root.addView(content);
        root.addView(emptyText, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        setContentView(root);
    }

    private void loadSnapshots() {
        snapshots.clear();
        snapshots.addAll(SnapshotStore.loadSnapshots(this));
        adapter.notifyDataSetChanged();
        emptyText.setVisibility(snapshots.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void showSnapshot(SnapshotStore.SnapshotItem item) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(8), dp(8), dp(8), 0);

        ImageView imageView = new ImageView(this);
        imageView.setAdjustViewBounds(true);
        imageView.setMaxHeight(dp(420));
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setImageURI(item.uri);
        panel.addView(imageView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        new AlertDialog.Builder(this)
                .setTitle(item.name == null ? "Snapshot" : item.name)
                .setView(panel)
                .setPositiveButton("Share", (dialog, which) -> shareSnapshot(item.uri))
                .setNegativeButton("Close", null)
                .show();
    }

    private void shareSnapshot(Uri uri) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("image/png");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent, "Share snapshot"));
    }

    private boolean needsImagePermission() {
        return Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int systemBarHeight(String resourceName) {
        int resourceId = getResources().getIdentifier(resourceName, "dimen", "android");
        return resourceId > 0 ? getResources().getDimensionPixelSize(resourceId) : 0;
    }

    private final class SnapshotAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return snapshots.size();
        }

        @Override
        public Object getItem(int position) {
            return snapshots.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ImageView imageView = convertView instanceof ImageView ? (ImageView) convertView : new ImageView(SnapshotGalleryActivity.this);
            imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            imageView.setBackgroundColor(Color.rgb(32, 32, 32));
            imageView.setLayoutParams(new GridView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(132)
            ));
            Uri uri = snapshots.get(position).uri;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    imageView.setImageBitmap(getContentResolver().loadThumbnail(uri, new Size(260, 260), null));
                } else {
                    imageView.setImageURI(uri);
                }
            } catch (Exception e) {
                imageView.setImageURI(uri);
            }
            return imageView;
        }
    }
}
