package org.phlo.AirReceiver;

import android.media.AudioTrack;

public class AudioVolume {
    private static final float AIR_VOLUME_MAX = 0;
	private static final float AIR_VOLUME_MIN = -30;
    private static final float AIR_VOLUME_RANGE = AIR_VOLUME_MAX - AIR_VOLUME_MIN;

	private static final float TRACK_VOLUME_MIN = AudioTrack.getMinVolume();
    private static final float TRACK_VOLUME_MAX = AudioTrack.getMaxVolume();
    private static final float TRACK_VOLUME_RANGE = TRACK_VOLUME_MAX - TRACK_VOLUME_MIN;

    public static float fromAirTunes(float volume) {
        volume = (volume - AIR_VOLUME_MIN) / AIR_VOLUME_RANGE * TRACK_VOLUME_RANGE + TRACK_VOLUME_MIN;
        if (volume < TRACK_VOLUME_MIN) volume = TRACK_VOLUME_MIN;
        if (volume > TRACK_VOLUME_MAX) volume = TRACK_VOLUME_MAX;
        return volume;
    }

    public static float toAirTunes(float volume) {
        volume = (volume - TRACK_VOLUME_MIN) / TRACK_VOLUME_RANGE * AIR_VOLUME_RANGE + AIR_VOLUME_MIN;
        if (volume < AIR_VOLUME_MIN) volume = AIR_VOLUME_MIN;
        if (volume > AIR_VOLUME_MAX) volume = AIR_VOLUME_MAX;
        return volume;
    }
}
