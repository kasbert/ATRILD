package fi.dungeon.atrild.root;

import fi.dungeon.atrild.ril.ATCommandsImpl;
import android.os.IBinder;
import android.util.Log;

/*
 * Test client for the system server
 */

public class Client {
	private static final String LOG_TAG = "CLIENT";

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		Log.e(LOG_TAG, "Hello, World!");
		try {
			IBinder binder = android.os.ServiceManager
					.getService(IRILStatImpl.SERVICE_NAME);
			Log.e(LOG_TAG, "binder " + binder);
			if (binder != null) {
				IRILStat stat = IRILStat.Stub.asInterface(binder);
				Log.e(LOG_TAG, "alive " + stat.asBinder().isBinderAlive());
				Log.e(LOG_TAG, "pingBinder " + stat.asBinder().pingBinder());
				Log.e(LOG_TAG, "getStat " + stat.getStat());
				Log.e(LOG_TAG,
						"identity "
								+ stat.getBeanProperty(
										ATCommandsImpl.class.getName(),
										"Identity"));
			}
		} catch (Exception e1) {
			Log.e(LOG_TAG, "remote", e1);
		}
		System.exit(0);
	}

}
