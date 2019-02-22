package com.github.neithern.airaudio;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class AirAudioReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        Intent intent2 = new Intent(context, AirAudioService.class);
        intent2.setAction(action);
        context.startService(intent2);
    }
}
