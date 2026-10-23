package com.gathin.tvhome;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Optional personal header for this owner's theme. Weather is requested only while Home is visible. */
final class PersonalHeader {
    private final Context context;
    private final TextView weather;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean active;
    private boolean loading;
    private long lastAttempt;

    static boolean enabled(Context context) {
        try {
            File file = new File(context.getExternalFilesDir(null), Config.FILE_NAME);
            return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8))
                    .optBoolean("personal_header", false);
        } catch (Exception ignored) {
            return false;
        }
    }

    PersonalHeader(MainActivity activity, String name) {
        context = activity;
        android.content.SharedPreferences choices = activity.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        TextView profileName = activity.findViewById(R.id.profileName);
        profileName.setText(name);
        profileName.setVisibility(View.VISIBLE);
        activity.findViewById(R.id.date).setVisibility(choices.getBoolean("show_date", true) ? View.VISIBLE : View.GONE);
        weather = activity.findViewById(R.id.weather);
        weather.setContentDescription("Weather data: Open-Meteo");
        weather.setText("Local weather…");
        weather.setVisibility(choices.getBoolean("show_weather", true) ? View.VISIBLE : View.GONE);
    }

    void start() {
        active = true;
        update();
    }

    void stop() { active = false; }

    void close() {
        active = false;
        worker.shutdownNow();
    }

    void update() {
        long now = System.currentTimeMillis();
        if (!active || loading || now - lastAttempt < 30 * 60_000L) return;
        lastAttempt = now;
        loading = true;
        worker.execute(() -> {
            String result = null;
            try {
                Location location = location();
                boolean approximate = location == null;
                double lat = approximate ? 26.28 : location.getLatitude();
                double lon = approximate ? 50.20 : location.getLongitude();
                String city = city(lat, lon);
                // A weather model uses a nearby grid cell, not a thermometer at the TV's coordinates.
                String endpoint = String.format(Locale.US,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&current=temperature_2m&temperature_unit=celsius",
                        lat, lon);
                HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
                try {
                    connection.setConnectTimeout(8000);
                    connection.setReadTimeout(8000);
                    if (connection.getResponseCode() != 200) throw new java.io.IOException("Weather HTTP error");
                    try (InputStream input = connection.getInputStream()) {
                        byte[] bytes = new byte[64_000];
                        int count = 0;
                        while (count < bytes.length) {
                            int read = input.read(bytes, count, bytes.length - count);
                            if (read < 0) break;
                            count += read;
                        }
                        JSONObject json = new JSONObject(new String(bytes, 0, count, StandardCharsets.UTF_8));
                        int degrees = (int) Math.round(json.getJSONObject("current").getDouble("temperature_2m"));
                        result = city + (approximate ? " (approx)" : "") + " · " + degrees + "°C";
                    }
                } finally {
                    connection.disconnect();
                }
            } catch (Exception failure) {
                Log.w("TvHome.Weather", "Weather unavailable", failure);
            }
            String value = result;
            weather.post(() -> {
                loading = false;
                if (active && value != null) weather.setText(value);
            });
        });
    }

    private Location location() {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            return null;
        LocationManager manager = context.getSystemService(LocationManager.class);
        if (manager == null) return null;
        try {
            Location network = manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (network != null && System.currentTimeMillis() - network.getTime() < 6 * 60 * 60_000L)
                return network;
        } catch (SecurityException ignored) { }
        return null;
    }

    private String city(double lat, double lon) {
        // The TV currently sits in the Khobar metro area. Reverse geocoding handles a move elsewhere.
        if (lat > 26.1 && lat < 26.5 && lon > 50.0 && lon < 50.4) return "Al Khobar";
        try {
            if (Geocoder.isPresent()) {
                List<Address> addresses = new Geocoder(context, Locale.ENGLISH).getFromLocation(lat, lon, 1);
                if (addresses != null && !addresses.isEmpty()) {
                    String locality = addresses.get(0).getLocality();
                    if (locality != null && !locality.isEmpty()) return locality;
                }
            }
        } catch (Exception ignored) { }
        return "Local weather";
    }
}
