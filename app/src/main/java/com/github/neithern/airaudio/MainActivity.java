package com.github.neithern.airaudio;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.EditText;
import android.widget.Switch;

public class MainActivity extends Activity {
    private EditText serverName;
    private Switch serverSwitch;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent != null ? intent.getAction() : null;
            if (AirAudioService.BROADCAST_SERVER_STATE.equals(action)) {
                String name = intent.getStringExtra(AirAudioService.EXTRA_NAME);
                if (name != null) {
                    setTitle(name);
                    serverName.setText(name);
                }

                boolean on = intent.getBooleanExtra(AirAudioService.EXTRA_ON, false);
                serverSwitch.setChecked(on);
                serverSwitch.setEnabled(true);
                serverName.setEnabled(!on);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        setContentView(R.layout.activity_main);

        serverName = findViewById(R.id.server_name);
        serverName.setEnabled(false);

        serverSwitch = findViewById(R.id.server_switch);
        serverSwitch.setEnabled(false);
        serverSwitch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String name = serverName.getText().toString();
                boolean on = serverSwitch.isChecked();
                Intent intent = new Intent(MainActivity.this, AirAudioService.class);
                intent.setAction(on ? Intent.ACTION_DEFAULT : Intent.ACTION_SHUTDOWN);
                intent.putExtra(AirAudioService.EXTRA_NAME, name);
                startService(intent);
                serverSwitch.setEnabled(false);
                serverName.setEnabled(!on);
            }
        });

        Intent intent = getIntent();
        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(this);
        String name = AirAudioService.getName(intent.getExtras(), pref);
        setTitle(name);
        serverName.setText(name);

        IntentFilter filter = new IntentFilter();
        filter.addAction(AirAudioService.BROADCAST_SERVER_STATE);
        registerReceiver(receiver, filter);

        AirAudioService.start(this, intent.getExtras());
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(receiver);
        super.onDestroy();
    }
}
