package fi.dungeon.atrild;

import fi.dungeon.atrild.ril.RILDModule;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;

//This class is not used

public class RILDService extends Service implements
		OnSharedPreferenceChangeListener {
	static final String LOG_TAG = "ATRILJ";

	@Override
	public IBinder onBind(Intent intent) {
		Log.d(LOG_TAG, "onBind RILDService " + intent);
		return null;
	}

	@Override
	public void onCreate() {
		super.onCreate();
		Log.i(LOG_TAG, "onCreate RILDService");
		RILDModule.getSingleton();
	}

	@Override
	public void onStart(Intent intent, int startId) {
		try {
			Log.d(LOG_TAG, "onStart RILDService " + startId + ":" + intent);
			SharedPreferences prefs = PreferenceManager
					.getDefaultSharedPreferences(this);
			// Set all preferences before start
			for (String key : prefs.getAll().keySet()) {
				if (!"enabled".equals(key)) {
					onSharedPreferenceChanged(prefs, key);
				}
			}
			prefs.registerOnSharedPreferenceChangeListener(this);

			if (prefs.getBoolean("enabled", false)) {
				Log.i(LOG_TAG, "Starting RILD.." + startId + ":" + intent);
				RILDModule.getSingleton().start();
			}
		} catch (Exception e) {
			Log.e(LOG_TAG, "Error starting ATRILD", e);
		}
	}

	@Override
	public void onDestroy() {
		Log.d(LOG_TAG, "onDestroy");
	}

	@Override
	public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
		Log.d(LOG_TAG,
				"onSharedPreferenceChanged " + key + "="
						+ prefs.getAll().get(key));
		try {
			if ("enabled".equals(key)) {
				if (prefs.getBoolean(key, true)) {
					RILDModule.getSingleton().start();
				} else {
					RILDModule.getSingleton().stop();
				}
				return;
			}
			int i = key.lastIndexOf('.');
			if (i > 0) {
				String beanName = key.substring(0, i);
				String propertyName = key.substring(i + 1);
				RILDModule.getSingleton().setBeanProperty(beanName,
						propertyName, prefs.getString(key, null));
			}
		} catch (Exception e) {
			Log.e(LOG_TAG, "Error in setting property '" + key + "'", e);
		}
	}
}
