package com.github.neithern.airaudio;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Build;
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
    public static final String EXTRA_OUTPUT_STREAM = "output";

    private static final int RTSP_PORT = 46343;

    public static final int STREAM_TTS = 9; // AudioManager.STREAM_TTS

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
        final SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(this);
        final String action = intent != null ? intent.getAction() : null;
        final Bundle extras = intent != null ? intent.getExtras() : null;
        final String name = getName(extras, pref);
        final int streamType = getStreamType(extras, pref);
        final boolean shutDown = Intent.ACTION_SHUTDOWN.equals(action);
        executor.execute(new Runnable() {
            @Override
            public void run() {
                final AirAudioServer server = AirAudioServer.instance();
                if (server.isRunning()) {
                    server.stop();
                }
                if (!shutDown && !server.isRunning()) {
                    server.start(name, RTSP_PORT, streamType);
                }
                Intent result = new Intent(BROADCAST_SERVER_STATE);
                result.putExtra(EXTRA_NAME, name);
                result.putExtra(EXTRA_ON, server.isRunning());
                sendBroadcast(result);
            }
        });
    }

    public static String getName(Bundle extras, SharedPreferences pref) {
        String name = extras != null ? extras.getString(EXTRA_NAME) : null;
        if (name == null || name.isEmpty()) {
            name = pref.getString(EXTRA_NAME, null);
            if (name == null || name.isEmpty()) {
                name = Build.MODEL;
                if (BuildConfig.DEBUG)
                    name += "(Debug)";
            }
        } else {
            pref.edit().putString(EXTRA_NAME, name).apply();
        }
        return name;
    }

    public static int getStreamType(Bundle extras, SharedPreferences pref) {
        String stream = extras != null ? extras.getString(EXTRA_OUTPUT_STREAM) : null;
        int streamType = -1;
        if ("music".equals(stream))
            streamType = AudioManager.STREAM_MUSIC;
        else if ("ring".equals(stream))
            streamType = AudioManager.STREAM_RING;
        else if ("system".equals(stream))
            streamType = AudioManager.STREAM_SYSTEM;
        else if ("tts".equals(stream))
            streamType = STREAM_TTS;
        if (streamType == -1) {
            streamType = pref.getInt(EXTRA_OUTPUT_STREAM, AudioManager.STREAM_MUSIC);
        } else {
            pref.edit().putInt(EXTRA_OUTPUT_STREAM, streamType).apply();
        }
        return streamType;
    }
}