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
import android.view.Gravity;
import android.view.View;
import android.widget.Switch;

import java.util.ArrayList;
import java.util.Set;

import static com.github.neithern.airaudio.AirAudioService.EXTRA_GROUP_NAME;
import static com.github.neithern.airaudio.AirAudioService.EXTRA_PLAYER_NAME;

public class MainActivity extends PreferenceActivity {
    private Switch serverSwitch;
    private Preference prefName;
    private Preference prefGroup;
    private Preference prefGroupName;
    private MultiSelectListPreference prefGroupAddresses;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent != null ? intent.getAction() : null;
            if (AirAudioService.BROADCAST_SERVER_STATE.equals(action)) {
                boolean on = intent.getBooleanExtra(AirAudioService.EXTRA_ON, false);
                serverSwitch.setChecked(on);
                serverSwitch.setEnabled(true);
            }
        }
    };

    private final Preference.OnPreferenceChangeListener preferenceChangeListener = new Preference.OnPreferenceChangeListener() {
        @Override
        public boolean onPreferenceChange(Preference preference, Object newValue) {
            if (preference == prefName || preference == prefGroupName)
                preference.setSummary(newValue.toString());
            else if (preference == prefGroupAddresses)
                updateServerNames((Set<String>) newValue);
            restartService();
            return true;
        }
    };

    private final ArrayList<CharSequence> deviceNames = new ArrayList<>();
    private final ArrayList<CharSequence> deviceAddresses = new ArrayList<>();
    private final AirPlayDiscovery discovery = new AirPlayDiscovery();
    private final AirPlayDiscovery.Listener serviceListener = new AirPlayDiscovery.Listener() {
        @Override
        public void onServiceAdded(String name, String address, boolean self) {
            if (lookupName(address) == null) {
                if (self)
                    name = name + getString(R.string.self);
                deviceNames.add(name);
                deviceAddresses.add(address);
            }
            updateServerList();
        }

        @Override
        public void onServiceRemoved(String name, String address) {
            deviceNames.remove(name);
            deviceAddresses.remove(address);
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
        String name = AirAudioService.getPlayerName(extras, pref);
        String groupName = AirAudioService.getGroupName(extras, pref);
        pref.edit().putString(EXTRA_PLAYER_NAME, name)
                .putString(EXTRA_GROUP_NAME, groupName)
                .apply();

        addPreferencesFromResource(R.xml.main);

        prefName = findPreference(AirAudioService.EXTRA_PLAYER_NAME);
        prefName.setSummary(name);
        prefName.setOnPreferenceChangeListener(preferenceChangeListener);

        findPreference(AirAudioService.EXTRA_OUTPUT_STREAM).setOnPreferenceChangeListener(preferenceChangeListener);
        findPreference(AirAudioService.EXTRA_CHANNEL_MODE).setOnPreferenceChangeListener(preferenceChangeListener);

        prefGroup = findPreference("group");
        prefGroupName = findPreference(AirAudioService.EXTRA_GROUP_NAME);
        prefGroupName.setSummary(groupName);
        prefGroupName.setOnPreferenceChangeListener(preferenceChangeListener);

        discovery.create(serviceListener);
        prefGroupAddresses = (MultiSelectListPreference) findPreference(AirAudioService.EXTRA_GROUP_ADDRESSES);
        prefGroupAddresses.setOnPreferenceChangeListener(preferenceChangeListener);
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

        if (AirAudioServer.instance().isRunning())
            serverSwitch.setChecked(true);
        else
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
        prefGroupAddresses.setEntries(deviceNames.toArray(new CharSequence[0]));
        prefGroupAddresses.setEntryValues(deviceAddresses.toArray(new CharSequence[0]));

        Set<String> addresses = PreferenceManager.getDefaultSharedPreferences(this)
                .getStringSet(AirAudioService.EXTRA_GROUP_ADDRESSES, null);
        updateServerNames(addresses);
    }

    private void updateServerNames(Set<String> addresses) {
        if (addresses != null && !addresses.isEmpty()) {
            StringBuilder builder = new StringBuilder(256);
            for (String addr : addresses) {
                CharSequence name = lookupName(addr);
                if (builder.length() > 0)
                    builder.append(", ");
                builder.append(name != null ? name : addr);
            }
            prefGroupAddresses.setSummary(builder.toString());
        } else {
            prefGroupAddresses.setSummary(R.string.not_specified);
        }
        prefGroup.setSummary(addresses != null && !addresses.isEmpty() ? R.string.group_enabled : R.string.group_disabled);
    }
}
