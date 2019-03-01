package com.github.neithern.airaudio;

import android.app.ActionBar;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.MultiSelectListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Switch;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Set;

import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;

import static com.github.neithern.airaudio.AirAudioService.EXTRA_NAME;

public class MainActivity extends PreferenceActivity {
    private Switch serverSwitch;
    private Preference prefName;
    private MultiSelectListPreference prefServers;

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

    private final Preference.OnPreferenceChangeListener preferenceChangeListener = new Preference.OnPreferenceChangeListener() {
        @Override
        public boolean onPreferenceChange(Preference preference, Object newValue) {
            if (preference == prefName)
                preference.setSummary(newValue.toString());
            else if (preference == prefServers)
                updateServerNames((Set<String>) newValue);
            restartService();
            return true;
        }
    };

    private final ArrayList<CharSequence> deviceNames = new ArrayList<>();
    private final ArrayList<CharSequence> deviceAddresses = new ArrayList<>();
    private final AirPlayDiscovery discovery = new AirPlayDiscovery();
    private final ServiceListener serviceListener = new ServiceListener() {
        @Override
        public void serviceAdded(ServiceEvent event) {
        }

        @Override
        public void serviceRemoved(ServiceEvent event) {
            ServiceInfo si = event.getInfo();
            deviceNames.remove(si.getName());
            deviceAddresses.remove(si.getHostAddress());
            updateServerList();
        }

        @Override
        public void serviceResolved(ServiceEvent event) {
            ServiceInfo si = event.getInfo();
            String name = si.getName();
            String address = si.getHostAddress() + ':' + si.getPort();
            if (lookupName(address) == null) {
                int pos = name.indexOf('@');
                name = name.substring(pos + 1);
                deviceNames.add(name);
                deviceAddresses.add(address);
            }
            Log.d("Device", name + ", " + address + ", " + new String(si.getTextBytes()));
            updateServerList();
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
        prefName.setOnPreferenceChangeListener(preferenceChangeListener);

        findPreference(AirAudioService.EXTRA_OUTPUT_STREAM).setOnPreferenceChangeListener(preferenceChangeListener);
        findPreference(AirAudioService.EXTRA_CHANNEL_MODE).setOnPreferenceChangeListener(preferenceChangeListener);

        discovery.create(serviceListener);
        prefServers = (MultiSelectListPreference) findPreference(AirAudioService.EXTRA_FORWARD_SERVERS);
        prefServers.setSummary("");
        prefServers.setOnPreferenceChangeListener(preferenceChangeListener);
        updateServerList();

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
        discovery.close();
        unregisterReceiver(receiver);
        super.onDestroy();
    }

    private void restartService() {
        AirAudioService.start(this, getIntent().getExtras());
    }

    private CharSequence lookupName(String address) {
        for (int i = deviceAddresses.size() - 1; i >= 0; i--) {
            if (deviceAddresses.get(i).equals(address))
                return deviceNames.get(i);
        }
        return null;
    }

    private void updateServerList() {
        prefServers.setEntries(deviceNames.toArray(new CharSequence[0]));
        prefServers.setEntryValues(deviceAddresses.toArray(new CharSequence[0]));

        Set<String> addresses = PreferenceManager.getDefaultSharedPreferences(this)
                .getStringSet(AirAudioService.EXTRA_FORWARD_SERVERS, null);
        updateServerNames(addresses);
    }

    private void updateServerNames(Set<String> addresses) {
        StringBuilder builder = new StringBuilder(getString(R.string.play_self));
        if (addresses != null) {
            for (String addr : addresses) {
                CharSequence name = lookupName(addr);
                builder.append(", ");
                builder.append(name != null ? name : addr);
            }
        }
        prefServers.setSummary(builder.toString());
    }
}
