package com.github.neithern.airaudio;

import android.content.Context;
import android.preference.ListPreference;
import android.util.AttributeSet;

public class IntegerListPreference extends ListPreference {
	private int mValue;

	public IntegerListPreference(Context context) {
		super(context);
	}

	public IntegerListPreference(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	public IntegerListPreference(Context context, AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
	}

	@Override
	protected void onSetInitialValue(boolean restoreValue, Object defaultValue) {
		setValue(restoreValue ? getPersistedString(getValue()) : (String) defaultValue);
	}

	@Override
	public CharSequence getSummary() {
		CharSequence[] values = getEntryValues();
		CharSequence[] entries = getEntries();
		int index = -1;
		for (int i = 0; i < values.length; i++) {
			if (stringToInteger(values[i].toString()) == mValue) {
				index = i;
				break;
			}
		}
		if (index >= 0 && index < entries.length)
			return entries[index];
		return super.getSummary();
	}

	@Override
	protected String getPersistedString(String defaultReturnValue) {
		if (!shouldPersist())
			return defaultReturnValue;

		int value = stringToInteger(defaultReturnValue);
		mValue = getSharedPreferences().getInt(getKey(), value);
		return Integer.toString(mValue);
	}

	@Override
	protected boolean persistString(String value) {
		if (!shouldPersist())
			return false;

		mValue = stringToInteger(value);
		getSharedPreferences().edit().putInt(getKey(), mValue).apply();
		return true;
	}

	public static int stringToInteger(String value) {
		return stringToInteger(value, 0);
	}

	public static int stringToInteger(String value, int defValue) {
		try {
			return Integer.valueOf(value);
		} catch (Exception e) {
			return defValue;
		}
	}
}
