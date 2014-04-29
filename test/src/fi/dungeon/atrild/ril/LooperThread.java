package fi.dungeon.atrild.ril;

import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

class LooperThread extends Thread {
	public Handler mHandler;
	public Throwable throwable;
	public Message lastMessage;

	public LooperThread() {
		super();
		String name = getClass().getSimpleName() + "-" + getId();
		setName(name);
	}

	public void start() {
		super.start();
		while (mHandler == null && throwable == null) {
			try {
				Thread.sleep(100L);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		if (throwable != null) {
			throw new RuntimeException(throwable);
		}
	}

	public void run() {
		try {
			Log.d("test", "LooperThread run");
			Looper.prepare();

			mHandler = new Handler() {
				public void handleMessage(Message msg) {
					// process incoming messages here
					Log.d("test", "LooperThread handleMessage: " + msg);
					lastMessage = Message.obtain(msg);
					try {
						sq.offer(lastMessage, 10, TimeUnit.SECONDS);
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
			};

			Log.d("test", "LooperThread loop start");
			Looper.loop();
		} catch (Throwable t) {
			throwable = t;
			t.printStackTrace();
		}
		Log.d("test", "LooperThread end");
	}

	SynchronousQueue<Message> sq = new SynchronousQueue<Message>();
	
	public Message getLastMessage() {
		try {
			return sq.poll(60, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			throw new RuntimeException(e);
		}
//		for (int i = 0; lastMessage == null && i < 100; i++) {
//			System.out.print(".");
//			System.out.flush();
//			try {
//				Thread.sleep(100L);
//			} catch (InterruptedException e) {
//				// TODO Auto-generated catch block
//				e.printStackTrace();
//			}
//		}
//		Message msg = lastMessage;
//		lastMessage = null;
//		return msg;
	}
}