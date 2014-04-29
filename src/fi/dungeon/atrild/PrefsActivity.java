package fi.dungeon.atrild;

import fi.dungeon.atrild.R;

import android.os.Bundle;
import android.preference.PreferenceActivity;

public class PrefsActivity extends PreferenceActivity {

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		//getPreferenceManager().setSharedPreferencesName("ATRILD");
		addPreferencesFromResource(R.xml.prefs);
	}
}