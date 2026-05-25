package com.gabecodex.lawnmapper;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

final class SavedProjectStore {
    private static final String PROJECTS_DIR = "projects";
    private final Context context;

    SavedProjectStore(Context context) {
        this.context = context.getApplicationContext();
    }

    String defaultProjectName() {
        return new SimpleDateFormat("MMMM d, yyyy h:mm a", Locale.getDefault()).format(new Date());
    }

    ArrayList<SavedProject> listProjects() {
        ArrayList<SavedProject> projects = new ArrayList<>();
        File[] files = projectDir().listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) {
            return projects;
        }
        for (File file : files) {
            SavedProject project = loadFromFile(file);
            if (project != null) {
                projects.add(project);
            }
        }
        Collections.sort(projects, new Comparator<SavedProject>() {
            @Override
            public int compare(SavedProject a, SavedProject b) {
                return Long.compare(b.updatedAtMillis, a.updatedAtMillis);
            }
        });
        return projects;
    }

    SavedProject load(String id) {
        return loadFromFile(projectFile(id));
    }

    boolean save(SavedProject project) {
        if (project.id == null || project.id.trim().isEmpty()) {
            project.id = UUID.randomUUID().toString();
        }
        if (project.name == null || project.name.trim().isEmpty()) {
            project.name = defaultProjectName();
        }
        project.updatedAtMillis = System.currentTimeMillis();
        File output = projectFile(project.id);
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(output), StandardCharsets.UTF_8)) {
            writer.write(project.toJson().toString(2));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    boolean rename(String id, String newName) {
        SavedProject project = load(id);
        if (project == null) {
            return false;
        }
        project.name = newName == null || newName.trim().isEmpty() ? defaultProjectName() : newName.trim();
        return save(project);
    }

    boolean delete(String id) {
        File file = projectFile(id);
        return file.exists() && file.delete();
    }

    private SavedProject loadFromFile(File file) {
        if (file == null || !file.exists()) {
            return null;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
            return SavedProject.fromJson(new JSONObject(builder.toString()));
        } catch (Exception ignored) {
            return null;
        }
    }

    private File projectFile(String id) {
        String safeId = id == null ? UUID.randomUUID().toString() : id.replaceAll("[^A-Za-z0-9_-]", "_");
        return new File(projectDir(), safeId + ".json");
    }

    private File projectDir() {
        File dir = new File(context.getFilesDir(), PROJECTS_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }
}
