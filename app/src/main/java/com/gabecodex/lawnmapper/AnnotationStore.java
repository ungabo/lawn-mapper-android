package com.gabecodex.lawnmapper;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class AnnotationStore {
    private static final String FILE_NAME = "annotations.json";
    private final Context context;

    AnnotationStore(Context context) {
        this.context = context.getApplicationContext();
    }

    List<LawnAnnotation> load() {
        ArrayList<LawnAnnotation> annotations = new ArrayList<>();
        try (InputStream inputStream = context.openFileInput(FILE_NAME);
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
            JSONArray array = new JSONArray(builder.toString());
            for (int i = 0; i < array.length(); i++) {
                LawnAnnotation annotation = LawnAnnotation.fromJson(array.getJSONObject(i));
                if (!annotation.anchors.isEmpty()) {
                    annotations.add(annotation);
                }
            }
        } catch (FileNotFoundException ignored) {
            return annotations;
        } catch (IOException | JSONException ignored) {
            return annotations;
        }
        return annotations;
    }

    boolean save(List<LawnAnnotation> annotations) {
        JSONArray array = new JSONArray();
        try {
            for (LawnAnnotation annotation : annotations) {
                array.put(annotation.toJson());
            }
            try (OutputStream outputStream = context.openFileOutput(FILE_NAME, Context.MODE_PRIVATE)) {
                outputStream.write(array.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            return true;
        } catch (IOException | JSONException ignored) {
            return false;
        }
    }
}
