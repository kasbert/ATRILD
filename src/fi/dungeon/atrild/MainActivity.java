package fi.dungeon.atrild;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Handler.Callback;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import fi.dungeon.atrild.ril.ATCommandsImpl;
import fi.dungeon.atrild.ril.AndroidSetup;
import fi.dungeon.atrild.ril.RILSocket;
import fi.dungeon.atrild.root.IRILStat;
import fi.dungeon.atrild.root.IRILStatImpl;

public class MainActivity extends Activity implements Callback {

	static final String LOG_TAG = "ATRILJ";

	private TextView textView;
	private TextView txtRadioState, txtRILState, txtIdentity;

	AndroidSetup util = new AndroidSetup();

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.main, menu);
		return true;
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		// startService(new Intent(this, RILDService.class));

		Button btnPrefs = (Button) findViewById(R.id.btnPrefs);
		//Button btnGetPrefs = (Button) findViewById(R.id.btnGetPreferences);
		Button btnStart = (Button) findViewById(R.id.btnStart);
		//final Button btnStop = (Button) findViewById(R.id.btnStop);
		final Button btnInstall = (Button) findViewById(R.id.btnInstall);

		if (util.isSystemServiceInstalled()) {
			btnInstall.setText(R.string.uninstall);
		} else {
			btnInstall.setText(R.string.install);
		}

		textView = (TextView) findViewById(R.id.txtPrefs);
		txtRadioState = (TextView) findViewById(R.id.txtRadioState);
		txtRILState = (TextView) findViewById(R.id.txtRILState);
		txtIdentity = (TextView) findViewById(R.id.txtIdentity);

		View.OnClickListener listener = new View.OnClickListener() {

			@Override
			public void onClick(View v) {
				switch (v.getId()) {
				case R.id.btnPrefs:
					Intent intent = new Intent(MainActivity.this,
							PrefsActivity.class);
					startActivity(intent);
					break;

//				case R.id.btnGetPreferences:
//					displaySharedPreferences();
//					displayState();
//					break;

				case R.id.btnStart:
					start();
					break;

//				case R.id.btnStop:
//					btnStop.setText("Restop");
//					stop();
//					break;

				case R.id.btnInstall:
					install();
					break;

				default:
					break;
				}
			}

		};

		btnPrefs.setOnClickListener(listener);
//		btnGetPrefs.setOnClickListener(listener);
		btnStart.setOnClickListener(listener);
//		btnStop.setOnClickListener(listener);
		btnInstall.setOnClickListener(listener);

		Handler statusHandler = new Handler(Looper.getMainLooper(), this);
		Messenger messenger = new Messenger(statusHandler);
		try {
			IRILStat stat = getIRILStat();
			if (stat == null) {
				Log.e(LOG_TAG, "RILD service is not running.");
			} else {
				stat.register(messenger);
				Log.e(LOG_TAG, "Registered");
			}
		} catch (Exception e) {
			Log.e(LOG_TAG, "Cannot register", e);
		}
		statusHandler.obtainMessage().sendToTarget(); // first update
	}

	private void start() {
		try {
			IRILStat stat = getIRILStat();
			if (stat == null) {
				Log.e(LOG_TAG, "RILD service is not running.");
			} else {
				stat.start();
			}
		} catch (Exception e) {
			Log.e(LOG_TAG, "Cannot start", e);
		}
	}

	private void stop() {
		try {
			IRILStat stat = getIRILStat();
			if (stat == null) {
				Log.e(LOG_TAG, "RILD service is not running.");
			} else {
				stat.stop();
			}
		} catch (Exception e) {
			Log.e(LOG_TAG, "Cannot stop", e);
		}
	}

	private void displaySharedPreferences() {
		SharedPreferences prefs = PreferenceManager
				.getDefaultSharedPreferences(this);

		String username = prefs.getString("username", "Default NickName");
		String passw = prefs.getString("password", "Default Password");
		boolean checkBox = prefs.getBoolean("checkBox", false);
		String listPrefs = prefs.getString("listpref", "Default list prefs");

		StringBuilder builder = new StringBuilder();
		builder.append("Username: " + username + "\n");
		builder.append("Password: " + passw + "\n");
		builder.append("Keep me logged in: " + String.valueOf(checkBox) + "\n");
		builder.append("List preference: " + listPrefs + "\n");
		textView.setText(builder.toString());
	}

	private void displayState() {
		textView.setText(util.checkInstallation());
		try {
			IRILStat stat = getIRILStat();
			if (stat == null) {
				Log.e(LOG_TAG, "RILD service is not running.");
			} else {
				txtRadioState.setText(stat.getBeanProperty(
						ATCommandsImpl.class.getName(), "RadioState"));
				txtRILState.setText(stat.getBeanProperty(
						RILSocket.class.getName(), "State"));
				txtIdentity.setText(stat.getBeanProperty(
						ATCommandsImpl.class.getName(), "Identity"));
			}
		} catch (Exception e) {
			txtRILState.setText("Not running");
			Log.e(LOG_TAG, "Cannot display state", e);
		}
	}

	private IRILStat getIRILStat() {
		IBinder binder = android.os.ServiceManager
				.getService(IRILStatImpl.SERVICE_NAME);
		if (binder != null) {
			IRILStat stat = IRILStat.Stub.asInterface(binder);
			Log.e(LOG_TAG, "alive " + stat.asBinder().isBinderAlive());
			Log.e(LOG_TAG, "pingBinder " + stat.asBinder().pingBinder());
			return stat;
		}
		return null;
	}

	@Override
	public boolean handleMessage(Message msg) {
		// Status change callback from service
		displayState();
		return true;
	}

	private void install() {
		try {
			copyResourceFile("install.sh", R.raw.install);
			copyResourceFile("uninstall.sh", R.raw.uninstall);
			copyResourceFile("rild", R.raw.rild);
			
			Button btnInstall = (Button) findViewById(R.id.btnInstall);
			if (util.isSystemServiceInstalled()) {
				textView.setText(unInstallSystemService());
				btnInstall.setText(R.string.install);
			} else {
				textView.setText(installSystemService());
				btnInstall.setText(R.string.uninstall);
			}
		} catch (IOException e) {
			Log.e(LOG_TAG, "Installation failed", e);
			textView.setText(e.getMessage());
		}

	}

	protected void copyResourceFile(String filename, int id) throws IOException {
		FileOutputStream fos = null;
		InputStream is = null;
		try {
			fos = openFileOutput(filename, MODE_WORLD_READABLE);
			is = getResources().openRawResource(id);
			byte[] buffer = new byte[1024];
			int count;
			while ((count = is.read(buffer)) > 0) {
				fos.write(buffer, 0, count);
			}
		} finally {
			if (is != null) {
				is.close();
			}
			if (fos != null) {
				fos.close();
			}
		}
		
	}
	
	protected String installSystemService() throws IOException {
		if (util.isSystemServiceInstalled()) {
			Log.e(LOG_TAG, "ATRILD system service is already installed");
			return "Already installed";
		}
		String file = getFilesDir().getPath() + "/install.sh";
		return util.runInstall(file);
	}

	protected String unInstallSystemService() throws IOException {
		if (!util.isSystemServiceInstalled()) {
			Log.e(LOG_TAG, "ATRILD system service is already not installed");
			return "Already installed";
		}
		String file = getFilesDir().getPath() + "/uninstall.sh";
		return util.runInstall(file);
	}
}
