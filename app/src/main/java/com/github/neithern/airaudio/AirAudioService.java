/*
 * This file is part of AirReceiver.
 *
 * AirReceiver is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * AirReceiver is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with AirReceiver.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.github.neithern.airaudio;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.StrictMode;
import android.preference.PreferenceManager;

import org.phlo.AirReceiver.AudioChannel;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class AirAudioService extends Service {
    public static final String BROADCAST_SERVER_STATE = "SERVER_STATE";
    public static final String EXTRA_ON = "server_on";
    public static final String EXTRA_PLAYER_NAME = "name";
    public static final String EXTRA_CHANNEL_MODE = "channel";
    public static final String EXTRA_OUTPUT_STREAM = "output";

    public static final String EXTRA_GROUP_NAME = "group_name";
    public static final String EXTRA_GROUP_ADDRESSES = "group_addresses";

    public static final int STREAM_TTS = 9; // AudioManager.STREAM_TTS

    public static void start(Context context, Bundle extras) {
        Intent intent = new Intent(context, AirAudioService.class);
        if (extras != null)
            intent.putExtras(extras);
        context.startService(intent);
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
    public int onStartCommand(Intent intent, int flags, int startId) {
        final SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(this);
        final String action = intent != null ? intent.getAction() : null;
        final Bundle extras = intent != null ? intent.getExtras() : null;
        final String playerName = getPlayerName(extras, pref);
        final int streamType = getStreamType(extras, pref);
        final AudioChannel channelMode = getChannelMode(extras, pref);
        final String groupName = getGroupName(extras, pref);
        final Set<InetSocketAddress> groupAddresses = getGroupAddresses(extras, pref);
        final boolean shutDown = Intent.ACTION_SHUTDOWN.equals(action);
        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                final AirAudioServer server = AirAudioServer.instance();
                if (server.isRunning()) {
                    server.stop();
                }
                if (!shutDown && !server.isRunning()) {
                    server.start(playerName, streamType, channelMode, groupName, groupAddresses);
                }
                Intent result = new Intent(BROADCAST_SERVER_STATE);
                result.putExtra(EXTRA_PLAYER_NAME, playerName);
                result.putExtra(EXTRA_ON, server.isRunning());
                sendBroadcast(result);
            }
        });
        return START_STICKY;
    }

    public static String getPlayerName(Bundle extras, SharedPreferences pref) {
        String name = extras != null ? extras.getString(EXTRA_PLAYER_NAME) : null;
        if (name == null || name.isEmpty()) {
            name = pref.getString(EXTRA_PLAYER_NAME, null);
            if (name == null)
                name = Build.MODEL;
        } else {
            pref.edit().putString(EXTRA_PLAYER_NAME, name).apply();
        }
        return name;
    }

    public static AudioChannel getChannelMode(Bundle extras, SharedPreferences pref) {
        String channel = extras != null ? extras.getString(EXTRA_CHANNEL_MODE) : null;
        AudioChannel channelMode = null;
        if ("stereo".equals(channel))
            channelMode = AudioChannel.STEREO;
        else if ("left".equals(channel))
            channelMode = AudioChannel.ONLY_LEFT;
        else if ("right".equals(channel))
            channelMode = AudioChannel.ONLY_RIGHT;
        if (channelMode == null) {
            int mode = pref.getInt(EXTRA_CHANNEL_MODE, -1);
            AudioChannel[] values = AudioChannel.values();
            channelMode = mode >= 0 && mode < values.length ? values[mode] : AudioChannel.STEREO;
        } else {
            pref.edit().putInt(EXTRA_CHANNEL_MODE, channelMode.ordinal()).apply();
        }
        return channelMode;
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

    public static String getGroupName(Bundle extras, SharedPreferences pref) {
        String name = extras != null ? extras.getString(EXTRA_GROUP_NAME) : null;
        if (name == null || name.isEmpty()) {
            name = pref.getString(EXTRA_GROUP_NAME, null);
            if (name == null)
                name = "Group";
        } else {
            pref.edit().putString(EXTRA_GROUP_NAME, name).apply();
        }
        return name;
    }

    public static Set<InetSocketAddress> getGroupAddresses(Bundle extras, SharedPreferences pref) {
        final Set<String> servers;
        final String[] list = extras != null ? extras.getStringArray(EXTRA_GROUP_ADDRESSES) : null;
        if (list != null && list.length > 0) {
            servers = new HashSet<>(Arrays.asList(list));
            pref.edit().putStringSet(EXTRA_GROUP_ADDRESSES, servers).apply();
        } else {
            servers = pref.getStringSet(EXTRA_GROUP_ADDRESSES, null);
        }
        if (servers == null || servers.isEmpty())
            return null;

        HashSet<InetSocketAddress> addresses = new HashSet<>(servers.size());
        for (String addr : servers) {
            InetSocketAddress address = AirAudioServer.parseAddress(addr);
            if (address != null)
                addresses.add(address);
        }
        return addresses;
    }
}
