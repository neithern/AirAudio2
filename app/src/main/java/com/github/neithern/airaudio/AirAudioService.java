package com.github.neithern.airaudio;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.StrictMode;
import android.preference.PreferenceManager;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class AirAudioService extends IntentService {
    public static final String BROADCAST_SERVER_STATE = "SERVER_STATE";
    public static final String EXTRA_ON = "server_on";
    public static final String EXTRA_NAME = "name";

    public static void start(Context context, Bundle extras) {
        Intent intent = new Intent(context, AirAudioService.class);
        if (extras != null)
            intent.putExtras(extras);
        context.startService(intent);
    }

    final Executor executor = Executors.newSingleThreadExecutor();

    public AirAudioService() {
        super("AirAudioService");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
        StrictMode.setThreadPolicy(policy);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        final String action = intent != null ? intent.getAction() : null;
        final String name = getName(this, intent);
        final boolean shutDown = Intent.ACTION_SHUTDOWN.equals(action);
        executor.execute(new Runnable() {
            @Override
            public void run() {
                final AirAudioServer server = AirAudioServer.instance();
                if (server.isRunning()) {
                    server.stop();
                }
                if (!shutDown && !server.isRunning()) {
                    server.start(name, 0);
                }
                Intent result = new Intent(BROADCAST_SERVER_STATE);
                result.putExtra(EXTRA_NAME, name);
                result.putExtra(EXTRA_ON, server.isRunning());
                sendBroadcast(result);
            }
        });
    }

    public static String getName(Context context, Intent intent) {
        String name = intent != null ? intent.getStringExtra(EXTRA_NAME) : null;
        if (name == null || name.isEmpty()) {
            name = PreferenceManager.getDefaultSharedPreferences(context).getString(EXTRA_NAME, null);
            if (name == null || name.isEmpty())
                name = BuildConfig.DEBUG ? "DebugAudio" : context.getString(R.string.app_name);
        } else {
            PreferenceManager.getDefaultSharedPreferences(context).edit().putString(EXTRA_NAME, name).apply();
        }
        return name;
    }
}