package fi.dungeon.atrild.root;

import java.util.Iterator;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import fi.dungeon.atrild.ril.RILD;
import fi.dungeon.atrild.ril.RILDModule;
import fi.dungeon.atrild.ril.RILSocket;

import android.os.DeadObjectException;
import android.os.Handler;
import android.os.Handler.Callback;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

public class IRILStatImpl extends IRILStat.Stub implements Callback {

	public static final String SERVICE_NAME = "rilstat";
	static final String LOG_TAG = "ATRILJ";
	private WeakHashMap<Messenger, String> messengers = new WeakHashMap<Messenger, String>();
	private ExecutorService es = Executors.newFixedThreadPool(5);

	public IRILStatImpl() {
		HandlerThread handlerThread = new HandlerThread("statusHandler");
		handlerThread.start();
		Looper looper = handlerThread.getLooper();
		Handler handler = new Handler(looper, this);
		RILDModule.getSingleton().getBean(RILD.class).setStatusHandler(handler);
	}

	@Override
	public boolean handleMessage(Message arg0) {
		// Handle status change event
		sendToMessengers();
		return true;
	}

	@Override
	public String getStat() throws RemoteException {
		Log.e(LOG_TAG, "getStat");
		return RILDModule.getSingleton().getBeanProperty(RILSocket.class.getName(), "State");
	}

	@Override
	public void start() throws RemoteException {
		// TODO remove as not needed
		Log.e(LOG_TAG, "start");
		RILDModule.getSingleton().start();
	}

	@Override
	public void stop() throws RemoteException {
		// TODO remove as not needed
		Log.e(LOG_TAG, "stop");
		RILDModule.getSingleton().stop();
	}

	@Override
	public void setBeanProperty(String name, String property, String value)
			throws RemoteException {
		// FIXME security
		Log.e(LOG_TAG, "setBeanProperty" + name + "." + property + "=" + value);
		RILDModule.getSingleton().setBeanProperty(name, property, value);
	}

	@Override
	public String getBeanProperty(String name, String property)
			throws RemoteException {
		// FIXME security
		Log.e(LOG_TAG, "getBeanProperty " + name + "." + property);
		return RILDModule.getSingleton().getBeanProperty(name, property);
	}

	@Override
	public void register(Messenger messenger) throws RemoteException {
		// FIXME security - check client signature or something
		messengers.put(messenger, "X");
		Log.e(LOG_TAG, "register");
		sendToMessengers();
	}

	@Override
	public void unregister(Messenger messenger) throws RemoteException {
		Log.e(LOG_TAG, "unregister");
		messengers.remove(messenger);
	}

	protected void sendToMessengers() {
		es.execute(new Runnable() {
			@Override
			public void run() {
				Log.e(LOG_TAG, "runs");
				try {
					Thread.sleep(200);
				} catch (InterruptedException e1) {
					Log.e(LOG_TAG, "Interrupted", e1);
				}
				for (Iterator<Messenger> it = messengers.keySet().iterator(); it
						.hasNext();) {
					Messenger messenger = it.next();
					if (!messenger.getBinder().isBinderAlive()) {
						Log.e(LOG_TAG, "dead");
						it.remove();
						continue;
					}
					try {
						Log.e(LOG_TAG, "sending");
						messenger.send(Message.obtain());
					} catch (DeadObjectException e) {
						Log.e(LOG_TAG, "remoteex", e);
						it.remove();
					} catch (Exception e) {
						Log.e(LOG_TAG, "remoteex", e);
					}
				}
				Log.e(LOG_TAG, "runned");
			}
		});
		
	}
	
}
