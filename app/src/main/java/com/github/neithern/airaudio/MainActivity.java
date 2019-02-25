package com.github.neithern.airaudio;

import android.app.ActionBar;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.view.Gravity;
import android.view.View;
import android.widget.Switch;

import static com.github.neithern.airaudio.AirAudioService.EXTRA_NAME;

public class MainActivity extends PreferenceActivity {
    private Switch serverSwitch;
    private Preference prefName;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent != null ? intent.getAction() : null;
            if (AirAudioService.BROADCAST_SERVER_STATE.equals(action)) {
                String name = intent.getStringExtra(EXTRA_NAME);
                if (name != null && prefName != null)
                    prefName.setSummary(name);

                boolean on = intent.getBooleanExtra(AirAudioService.EXTRA_ON, false);
                serverSwitch.setChecked(on);
                serverSwitch.setEnabled(true);
            }
        }
    };

    private final Preference.OnPreferenceChangeListener onPreferenceChangeListener = new Preference.OnPreferenceChangeListener() {
        @Override
        public boolean onPreferenceChange(Preference preference, Object newValue) {
            restartService();
            return true;
        }
    };

    @Override
    protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);

        Intent intent = getIntent();
        Bundle extras = intent.getExtras();
        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(this);

        // set default name
        String name = AirAudioService.getName(extras, pref);
        pref.edit().putString(EXTRA_NAME, name).apply();

        addPreferencesFromResource(R.xml.main);

        prefName = findPreference(AirAudioService.EXTRA_NAME);
        prefName.setSummary(name);
        prefName.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                preference.setSummary((String) newValue);
                return onPreferenceChangeListener.onPreferenceChange(preference, newValue);
            }
        });

        findPreference(AirAudioService.EXTRA_OUTPUT_STREAM).setOnPreferenceChangeListener(onPreferenceChangeListener);
        findPreference(AirAudioService.EXTRA_CHANNEL_MODE).setOnPreferenceChangeListener(onPreferenceChangeListener);

        ActionBar actionBar = getActionBar();
        actionBar.setDisplayOptions(ActionBar.DISPLAY_SHOW_CUSTOM | actionBar.getDisplayOptions());

        ActionBar.LayoutParams params = new ActionBar.LayoutParams(
                ActionBar.LayoutParams.WRAP_CONTENT, ActionBar.LayoutParams.WRAP_CONTENT, Gravity.END);

        serverSwitch = new Switch(this);
        actionBar.setCustomView(serverSwitch, params);
        serverSwitch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                boolean on = serverSwitch.isChecked();
                Intent intent = new Intent(MainActivity.this, AirAudioService.class);
                intent.setAction(on ? Intent.ACTION_DEFAULT : Intent.ACTION_SHUTDOWN);
                startService(intent);
                serverSwitch.setEnabled(false);
            }
        });

        IntentFilter filter = new IntentFilter();
        filter.addAction(AirAudioService.BROADCAST_SERVER_STATE);
        registerReceiver(receiver, filter);

        AirAudioService.start(this, extras);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(receiver);
        super.onDestroy();
    }

    protected void restartService() {
        AirAudioService.start(this, getIntent().getExtras());
    }
}
