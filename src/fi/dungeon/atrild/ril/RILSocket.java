package fi.dungeon.atrild.ril;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.LocalSocketAddress.Namespace;
import android.net.UnixServerSocket;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.util.Log;

/**
 * RILSocket 
 * 
 * Listens commands from RIL
 *  
 */
public class RILSocket implements Bean {
	static final String LOG_TAG = "ATRILSJ";

	// ***** Instance Variables

	LocalSocket mSocket;
	HandlerThread mSenderThread;
	RILSender mSender;
	Thread mReceiverThread;
	RILReceiver mReceiver;
	Handler mRILHandler;
	String mState = "Stopped";

	// Real socket name is /dev/socket/rild
	// There might be some links involved
	private String rilSocketName = "/dev/socket/atrild/rild";
	private boolean running;

	// ***** Events

	static final int EVENT_SEND = 1;

	// ***** Constants

	// match with constant in ril.cpp
	static final int RIL_MAX_COMMAND_BYTES = (8 * 1024);
	static final int SOCKET_OPEN_RETRY_MILLIS = 4 * 1000;

	static class RILSender extends Handler implements Runnable {
		
		WeakReference<RILSocket> ref;

		public RILSender(RILSocket rilSocket, Looper looper) {
			super(looper);
			ref = new WeakReference<RILSocket>(rilSocket);
		}

		// Only allocated once
		byte[] dataLength = new byte[4];

		// ***** Runnable implementation
		public void run() {
			// setup if needed
		}

		// ***** Handler implementation
		@Override
		public void handleMessage(Message msg) {
			Log.d(LOG_TAG, "handleMessage: " + msg);

			switch (msg.what) {
			case EVENT_SEND:
				try {
					Parcel p = (Parcel) msg.obj;
					byte[] data = p.marshall();
					p.recycle();
					p = null;
					msg.obj = null;

					if (ref.get() == null) {
						return;
					}
					LocalSocket s = ref.get().mSocket;
					if (s == null) {
						return;
					}

					if (data.length > RIL_MAX_COMMAND_BYTES) {
						throw new RuntimeException(
								"Parcel larger than max bytes allowed! "
										+ data.length);
					}

					// parcel length in big endian
					dataLength[0] = dataLength[1] = 0;
					dataLength[2] = (byte) ((data.length >> 8) & 0xff);
					dataLength[3] = (byte) ((data.length) & 0xff);

					// Log.d(LOG_TAG, "Writing 4+" + data.length +" bytes");

					Log.d(LOG_TAG,
							"SOCKET>("
									+ (data.length + 4)
									+ ") "
									+ Utils.bytesToHexString(dataLength, 0,
											dataLength.length) + ":"
									+ Utils.bytesToHexString(data, 0, data.length));

					s.getOutputStream().write(dataLength);
					s.getOutputStream().write(data);
				} catch (IOException ex) {
					Log.e(LOG_TAG, "IOException", ex);
					// make sure this request has not already been handled,
					// eg, if RILReceiver cleared the list.
				} catch (RuntimeException exc) {
					Log.e(LOG_TAG, "Uncaught exception ", exc);
					// make sure this request has not already been handled,
					// eg, if RILReceiver cleared the list.
				} finally {
					// Note: We are "Done" only if there are no outstanding
					// requests or replies. Thus this code path will only
					// release
					// the wake lock on errors.
					// releaseWakeLockIfDone();
				}
				break;
			}
		}
	}

	/**
	 * Reads in a single RIL message off the wire. A RIL message consists of a
	 * 4-byte little-endian length and a subsequent series of bytes. The final
	 * message (length header omitted) is read into <code>buffer</code> and the
	 * length of the final message (less header) is returned. A return value of
	 * -1 indicates end-of-stream.
	 * 
	 * @param is
	 *            non-null; Stream to read from
	 * @param buffer
	 *            Buffer to fill in. Must be as large as maximum message size,
	 *            or an ArrayOutOfBounds exception will be thrown.
	 * @return Length of message less header, or -1 on end of stream.
	 * @throws IOException
	 */
	private static int readRilMessage(InputStream is, byte[] buffer)
			throws IOException {
		int countRead;
		int offset;
		int remaining;
		int messageLength;

		// First, read in the length of the message
		offset = 0;
		remaining = 4;
		do {
			// Log.d(LOG_TAG, "reading "+ offset);
			countRead = is.read(buffer, offset, remaining);
			// Log.d(LOG_TAG, "read  "+ countRead);

			if (countRead < 0) {
				Log.e(LOG_TAG, "Hit EOS reading message length");
				return -1;
			}

			offset += countRead;
			remaining -= countRead;
		} while (remaining > 0);

		messageLength = ((buffer[0] & 0xff) << 24) | ((buffer[1] & 0xff) << 16)
				| ((buffer[2] & 0xff) << 8) | (buffer[3] & 0xff);

		String header = Utils.bytesToHexString(buffer, 0, 4);
		// Log.d(LOG_TAG, "Read 4+" + messageLength + " bytes");
		// Then, re-use the buffer and read in the message itself
		offset = 0;
		remaining = messageLength;
		do {
			countRead = is.read(buffer, offset, remaining);

			if (countRead < 0) {
				Log.e(LOG_TAG, "Hit EOS reading message.  messageLength="
						+ messageLength + " remaining=" + remaining);
				return -1;
			}

			offset += countRead;
			remaining -= countRead;
		} while (remaining > 0);
		Log.d(LOG_TAG, "SOCKET<(" + (messageLength + 4) + ") " + header + ":"
				+ Utils.bytesToHexString(buffer, 0, messageLength));

		return messageLength;
	}

	class RILReceiver implements Runnable {
		byte[] buffer;
		public InputStream is;

		RILReceiver() {
			buffer = new byte[RIL_MAX_COMMAND_BYTES];
		}

		public void run() {
			int retryCount = 0;

			Log.d(LOG_TAG, "Listening connections on '" + rilSocketName + "'");
			UnixServerSocket ss = null;
			mState = "Stopped";
			while (running) {
				try {
					String socketFd = System.getenv("ANDROID_SOCKET_rild");
					if (socketFd != null) {
						// We are started by init. There is a socket open for us.
						ss = new UnixServerSocket(Integer.parseInt(socketFd));
					} else {
						LocalSocketAddress lsa = new LocalSocketAddress(
								rilSocketName, Namespace.FILESYSTEM);
						ss = new UnixServerSocket(lsa);
					}
					break;
				} catch (Exception e) {
					mState = "Stopped: " + e.getMessage();
					// don't print an error message after the the first time
					// or after the 8th time
					if (retryCount == 8) {
						Log.e(LOG_TAG, "Couldn't bind '" + rilSocketName
								+ "' socket after " + retryCount
								+ " times, continuing to retry silently");
					} else if (retryCount > 0 && retryCount < 8) {
						Log.i(LOG_TAG, "Couldn't bind '" + rilSocketName
								+ "' socket; retrying after timeout", e);
					}
					retryCount++;
					try {
						Thread.sleep(SOCKET_OPEN_RETRY_MILLIS);
					} catch (InterruptedException er) {
					}
					continue;
				}
			}

			retryCount = 0;
			while (running) {
				mState = "Listening";
				LocalSocket s = null;
				if (retryCount > 0) {
					try {
						Thread.sleep(SOCKET_OPEN_RETRY_MILLIS);
					} catch (InterruptedException er) {
					}
				}
				retryCount++;
				try {
					s = ss.accept();
				} catch (Exception e) {
					Log.i(LOG_TAG, "Error in accept", e);
					continue;
				}

				mSocket = s;
				Log.i(LOG_TAG, "Accepted connection on '" + rilSocketName
						+ "' socket");
				Message msg = Message.obtain(mRILHandler,
						RILD.EVENT_SOCKET_CONNECTED);
				msg.sendToTarget();
				mState = "Connected";

				int length = 0;
				try {
					is = mSocket.getInputStream();

					while (running) {
						Parcel p;

						length = readRilMessage(is, buffer);

						if (length < 0) {
							// End-of-stream reached
							break;
						}

						p = Parcel.obtain();
						p.unmarshall(buffer, 0, length);
						p.setDataPosition(0);

						msg = Message.obtain(mRILHandler,
								RILD.EVENT_RIL_REQUEST, p);
						msg.sendToTarget();

						// Log.v(LOG_TAG, "Read packet: " + length +
						// " bytes");
					}
				} catch (IOException ex) {
					Log.i(LOG_TAG, "Error reading from socket", ex);
				} catch (Throwable tr) {
					Log.e(LOG_TAG, "Uncaught exception read length=" + length,
							tr);
				} finally {
					close();
				}

				mState = "Disconnected";
				Log.i(LOG_TAG, "Disconnected from '" + rilSocketName
						+ "' socket");
				msg = Message.obtain(mRILHandler,
						RILD.EVENT_SOCKET_DISCONNECTED);
				msg.sendToTarget();
			}
			if (ss != null) {
				try {
					ss.close();
				} catch (IOException e) {
					Log.i(LOG_TAG, "'" + rilSocketName + "' socket closed", e);
				}
			}
			Log.i(LOG_TAG, "Listener stopped");
		}
	}

	// ***** Constructors

	public RILSocket() {
		Log.d(LOG_TAG, "RILSocket()");
		mSenderThread = new HandlerThread("RILSender");
		mSenderThread.start();

		Looper looper = mSenderThread.getLooper();
		mSender = new RILSender(this, looper);

		mReceiver = new RILReceiver();
	}

	public void start() {
		Log.d(LOG_TAG, "Starting RILSocket");
		running = true;
		if (mReceiverThread == null || !mReceiverThread.isAlive()) {
			mReceiverThread = new Thread(mReceiver, "RILReceiver");
			mReceiverThread.start();
		}
	}

	public void afterStart() {
	}

	public void beforeStop() {
	}

	public void stop() {
		Log.d(LOG_TAG, "Stopping RILSocket");
		running = false;
		close();
		if (mReceiverThread != null) {
			mReceiverThread.interrupt();
			try {
				mReceiverThread.join(5000L);
			} catch (InterruptedException e) {
				Log.e(LOG_TAG, "Uncaught exception", e);
			}
			if (mReceiverThread.isAlive()) {
				Log.e(LOG_TAG, "Not stopped");
			} else {
				mReceiverThread = null;
			}
		}
	}

	public void close() {
		if (mSocket != null) {
			try {
				mSocket.close();
			} catch (IOException ex) {
				Log.e(LOG_TAG, "Error in closing socket");
			}
		}
		mSocket = null;
		if (mReceiver.is != null) {
			try {
				mReceiver.is.close();
			} catch (IOException e) {
				Log.e(LOG_TAG, "Error in closing socket stream");
			}
		}
	}

	protected void send(Parcel p) {
		Message msg;

		if (mSocket == null) {
			Log.i(LOG_TAG, "Ignore send on closed socket");
			p.recycle();
			return;
		}

		msg = mSender.obtainMessage(EVENT_SEND, p);

		msg.sendToTarget();
	}

	public Handler getRILHandler() {
		return mRILHandler;
	}

	public void setRILHandler(Handler mRILHandler) {
		this.mRILHandler = mRILHandler;
	}

	public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
		pw.println("RIL:");
		pw.println(" mSocket=" + mSocket);
		pw.println(" mSenderThread=" + mSenderThread);
		pw.println(" mSender=" + mSender);
		pw.println(" mReceiverThread=" + mReceiverThread);
		pw.println(" mReceiver=" + mReceiver);
	}

	public String getState() {
		return mState;
	}
	
	public void setSocketName(String name) {
		rilSocketName = name;
	}
}
