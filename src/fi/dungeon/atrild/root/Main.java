package fi.dungeon.atrild.root;

import java.io.File;

import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.ServiceManager;
import android.util.Log;
import fi.dungeon.atrild.ril.RILDModule;

/*
 * This class is run as root, calling dalvikvm
 * 
 * It provides a method for starting and stopping pppd.
 * 
 * dalvikvm -classpath /system/app/ATRILD.apk com.android.internal.util.WithFramework fi.dungeon.atrild.root.Main 
 * dalvikvm -classpath /data/app/fi.dungeon.atrild-*.apk com.android.internal.util.WithFramework fi.dungeon.atrild.root.Main
 export CLASSPATH=$(echo /data/app/fi.dungeon.atrild-*.apk)
 app_process -Xbootclasspath:$BOOTCLASSPATH:$CLASSPATH com.android.internal.util.WithFramework fi.dungeon.atrild.root.Main
 */

public class Main implements OnSharedPreferenceChangeListener {

	private static final String LOG_TAG = "MAIN";

	private SharedPreferences mPrefs;
	private IRILStatImpl mStat;
	
	/**
	 * @param args
	 */
	public static void main(String[] args) {

		try {
			Log.e(LOG_TAG, "Hello, World!");
			// RILDModule.getSingleton().getBean(PPPDMonitor.class).connect();
			new Main().start();
		} catch (Exception e1) {
			Log.e("MAIN", "connect failed", e1);
		}
	}

	public Main() {
	}

	public void start() {
		Log.i(LOG_TAG, "Start RILD system service ");

		String app = "fi.dungeon.atrild";
		// FIXME find a way not to hardcode /data
		String file = "/data/data/" + app + "/shared_prefs/" + app
				+ "_preferences.xml";
		mPrefs = new ReadOnlyPreferences(new File(file));

		// Set all preferences before start
		for (String key : mPrefs.getAll().keySet()) {
			if (!"enabled".equals(key)) {
				onSharedPreferenceChanged(mPrefs, key);
			}
		}
		mPrefs.registerOnSharedPreferenceChangeListener(this);

		if (mPrefs.getBoolean("enabled", false)) {
			Log.i(LOG_TAG, "Starting RILD..");
			RILDModule.getSingleton().start();
		} else {
			Log.i(LOG_TAG, "Not starting RILD..");
		}

		IRILStatImpl mStat = new IRILStatImpl();
		if (ServiceManager.getService(IRILStatImpl.SERVICE_NAME) == null) {
			ServiceManager.addService(IRILStatImpl.SERVICE_NAME, mStat);
		}

		// Main loop sleeps forever
		while (true) {
			try {
				// TODO Could we join some thread
				Thread.sleep(120000L);
			} catch (InterruptedException e) {
				Log.e(LOG_TAG, "Interrupted");
			}
		}

	}

	@Override
	public void onSharedPreferenceChanged(SharedPreferences sharedPreferences,
			String key) {
		Log.d(LOG_TAG, "onSharedPreferenceChanged " + key + "="
				+ mPrefs.getAll().get(key));
		try {
			if ("enabled".equals(key)) {
				if (mPrefs.getBoolean(key, true)) {
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
						propertyName, mPrefs.getString(key, null));
			}
		} catch (Exception e) {
			Log.e(LOG_TAG, "Error in setting property '" + key + "'", e);
		}
	}

}
