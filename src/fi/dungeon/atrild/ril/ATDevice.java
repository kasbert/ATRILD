package fi.dungeon.atrild.ril;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

import reloc.com.android.internal.telephony.CommandException;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;

enum ATCommandType {
	NO_RESULT, /* no intermediate response expected */
	NUMERIC, /* a single intermediate response starting with a 0-9 */
	SINGLELINE, /* a single intermediate response starting with a prefix */
	MULTILINE /*
			 * multiple line intermediate response starting with a prefix
			 */
};

enum ATRequestStatus {
	NONE, OK, ERROR
};

class ATResponse {
	ATRequestStatus mStatus;
	String mFinalResponse; /* eg OK, ERROR */
	List<String> mIntermediates = new ArrayList<String>();

	/*
	 * any intermediate responses
	 */

	public String toString() {
		StringBuilder b = new StringBuilder();
		b.append("{");
		b.append(" mStatus=");
		b.append(mStatus);
		if (mFinalResponse != null) {
			b.append(" mFinalResponse=");
			b.append(mFinalResponse);
		}
		if (mIntermediates.size() > 0) {
			b.append(" mIntermediates=");
			b.append(mIntermediates);
		}

		b.append(" }");

		return b.toString();
	}

	public static final int CME_ERROR_NON_CME = -1;
	public static final int CME_SUCCESS = 0;
	public static final int CME_SIM_NOT_INSERTED = 10;

	int at_get_cme_error() {
		if (mStatus == ATRequestStatus.OK) {
			return CME_SUCCESS;
		}

		if (!mFinalResponse.startsWith("+CME ERROR:")) {
			return CME_ERROR_NON_CME;
		}
		String str = mFinalResponse.substring(mFinalResponse.indexOf(':'));
		return Integer.valueOf(str);
	}
}

class ATRequest {
	static final String LOG_TAG = "ATRILJ";

	// ***** Class Variables
	private static Object sPoolSync = new Object();
	private static ATRequest sPool = null;
	private static int sPoolSize = 0;
	private static final int MAX_POOL_SIZE = 4;

	// ***** Instance Variables
	String mCommand;
	ATCommandType mCommandType;
	long mTimeout;
	String responsePrefix;
	String smspdu;
	long creationTime;
	ATRequest mNext;
	ATResponse mResponse;

	public String toString() {
		StringBuilder b = new StringBuilder();
		b.append("{");
		b.append(" when=");
		// TimeUtils.formatDuration(creationTime - System.currentTimeMillis(),
		// b);
		b.append(creationTime - System.currentTimeMillis());
		if (mCommand != null) {
			b.append(" mCommand=");
			b.append(mCommand);
		}
		b.append(" commandType=");
		b.append(mCommandType);
		if (mResponse != null) {
			b.append(" mResponse=");
			b.append(mResponse);
		}
		b.append(" }");
		return b.toString();
	}

	/**
	 * Retrieves a new RILRequest instance from the pool.
	 * 
	 * @param request
	 *            RIL_REQUEST_*
	 * @param result
	 *            sent when operation completes
	 * @return a RILRequest instance from the pool.
	 */
	static ATRequest obtain(String command, ATCommandType type) {
		ATRequest rr = null;

		synchronized (sPoolSync) {
			if (sPool != null) {
				rr = sPool;
				sPool = rr.mNext;
				rr.mNext = null;
				sPoolSize--;
			}
		}

		if (rr == null) {
			rr = new ATRequest();
		}
		if (rr.mResponse == null) {
			rr.mResponse = new ATResponse();
		}

		rr.mCommand = command;
		rr.mCommandType = type;
		rr.creationTime = System.currentTimeMillis();
		rr.mResponse.mIntermediates.clear();

		return rr;
	}

	/**
	 * Returns a RILRequest instance to the pool.
	 * 
	 * Note: This should only be called once per use.
	 */
	void release() {
		synchronized (sPoolSync) {
			if (sPoolSize < MAX_POOL_SIZE) {
				this.mNext = sPool;
				sPool = this;
				sPoolSize++;
			}
		}
	}

	private ATRequest() {
	}

	void FIXMEonError(int error, Object ret) {
		Log.d(LOG_TAG, "< " + " error: " + error);

	}
}

public class ATDevice implements Bean {
	static final String LOG_TAG = "ATRIL";
	public static final int EVENT_SEND = 1;
	public static final int EVENT_WAKE_LOCK_TIMEOUT = 2;
	private static final int AT_MAX_COMMAND_BYTES = 4000;
	private static final long MAX_TIMEOUT = 120000L;

	private RandomAccessFile mFile;
	private OutputStream mDeviceOut;
	private InputStream mDeviceIn;
	private Thread mReceiverThread;
	private ATReceiver mReceiver;
	private ATCommandsImpl mCI;

	// Current request
	private Object requestLock = new Object();
	private Object responseLock = new Object();
	private ATRequest mRequest;
	private String mSMSUnsolicitedLine1;
	private boolean running;
	private String deviceName = "/dev/ttyS2"; // "/dev/ttyUSB2";
	private boolean useQemuSocket = false;

	static String[] finalResponsesError = new String[] { "ERROR",
			"+CMS ERROR:", "+CME ERROR:", "NO CARRIER", /* sometimes! */
			"NO ANSWER", "NO DIALTONE", };

	static String[] finalResponsesSuccess = new String[] { "OK", "CONNECT" };

	static String[] smsUnsoliciteds = new String[] { "+CMT:", "+CDS:", "+CBM:"};

	private int writeATMessage(OutputStream os, byte[] buffer)
			throws IOException {
		if (buffer.length > AT_MAX_COMMAND_BYTES) {
			throw new RuntimeException("Parcel larger than max bytes allowed! "
					+ buffer.length);
		}

		os.write(buffer);
		os.write('\r');
		os.flush();
		return buffer.length + 1;
	}

	private void writeCtrlZ(OutputStream os, String smspdu) throws IOException {
		os.write(smspdu.getBytes());
		/* the ^Z */
		os.write(26);
	}

	private static String readATMessage(InputStream is, byte[] buffer)
			throws IOException {
		for (int i = 0; i < buffer.length;) {
			int c = is.read();
			if (c < 0) {
				Log.e(LOG_TAG, "Hit EOS reading message.  messageLength=" + i);
				return null;
			}
			//Log.d(LOG_TAG, "Got " + c);
			if (c == '\n' || c == '\r') {
				if (i == 0) {
					continue;
				}
				return new String(buffer, 0, i);
			}
			buffer[i] = (byte) c;
			i++;
		}
		// FIXME something else.
		return null;
	}


	public String readATMessage(FileChannel channel, ByteBuffer bb, byte[] buffer) throws IOException {
		
		String line = null;
		while (line == null) {
			//Log.d(LOG_TAG, "got before "+  bb.limit() + "," + bb.position());
			int j = bb.position();
			for (int i = j; i < bb.limit(); i++) {
				byte b = buffer[i];
				if (b == '\n' || b == '\r') {
					bb.position(i + 1);
					if (i == j) {
						j++;
						continue;
					}
					line = new String(buffer, j, i - j);
					bb.compact();
					bb.flip();
					//Log.d(LOG_TAG, "after compact "+ i+","+j+","+ bb.limit() + "," + bb.position());
					return line;
				}
			}
			bb.flip();
			bb.limit(bb.capacity());
			Log.d(LOG_TAG, "before read "+  bb.limit() + "," + bb.position());
			int length =channel.read(bb);
			Log.d(LOG_TAG, "after read "+  bb.limit() + "," + bb.position());
			//Log.d(LOG_TAG, "got " + length);
			bb.flip();
			if (length < 0) {
				// End-of-stream reached
				break;
			}
		}
		
		Log.d(LOG_TAG, "got after "+  bb.limit() + "," + bb.position());
		return null;
	}

	class ATReceiver implements Runnable {
		private static final long DEVICE_OPEN_RETRY_MILLIS = 1000L;
		byte[] buffer;

		ATReceiver() {
			buffer = new byte[AT_MAX_COMMAND_BYTES];
		}

		public void run() {
			int retryCount = 0;

			boolean inEmulator = false;
			String emulatorDeviceName = "/dev/qemu_pipe";
			if (new File(emulatorDeviceName).exists()) {
				inEmulator = true;
			}

			Log.d(LOG_TAG, "Opening device " + deviceName);
			try {
				while (running) {

					try {
						if (useQemuSocket ) {
							LocalSocket ls = new LocalSocket();
							LocalSocketAddress lsa = new LocalSocketAddress(
									"/dev/socket/qemud");
							ls.connect(lsa);
							mDeviceIn = ls.getInputStream();
							mDeviceOut = ls.getOutputStream();
							mDeviceOut.write("gsm".getBytes());
						} else {
							File deviceFile = new File(deviceName);
							if (inEmulator && ! deviceFile.exists()) {
								deviceName = emulatorDeviceName;
								deviceFile = new File(deviceName);
							}
							mFile = new RandomAccessFile(deviceFile, "rw");
							mDeviceIn = new FileInputStream(mFile.getFD());
							mDeviceOut = new FileOutputStream(mFile.getFD());
							if (inEmulator) {
								mDeviceOut.write("pipe:qemud:gsm\0".getBytes());
							}
						}
					} catch (Exception ex) {
						// don't print an error message after the the first time
						// or after the 8th time

						if (retryCount == 8) {
							Log.e(LOG_TAG, "Couldn't find '" + deviceName
									+ "' device after " + retryCount
									+ " times, continuing to retry silently");
						} else if (retryCount > 0 && retryCount < 8) {
							Log.i(LOG_TAG, "Couldn't find '" + deviceName
									+ "' device; retrying after timeout");
						}

						try {
							Thread.sleep(DEVICE_OPEN_RETRY_MILLIS);
						} catch (InterruptedException er) {
						}

						retryCount++;
						mDeviceIn = null; // TODO close
						continue;
					}

					retryCount = 0;

					Log.i(LOG_TAG, "Connected to '" + deviceName + "' device");
					if (mCI != null) {
						mCI.onAtConnected();
					}

					int length = 0;
					try {

						//ByteBuffer bb = ByteBuffer.wrap(buffer);
						while (running) {
							String line = readATMessage(mDeviceIn, buffer);
							//String line = readATMessage(mFile.getChannel(), bb, buffer);
							Log.d(LOG_TAG, "AT< '" + line + "'");
							if (line == null) {
								// End-of-stream reached
								break;
							}

							ATRequest ar = null;
							synchronized (requestLock) {
								ar = mRequest;
							}
							if (ar != null) {
								process(ar, line);
							} else {
								processUnsolicited(line);
							}
						}
					} catch (java.io.IOException ex) {
						Log.i(LOG_TAG, "'" + deviceName + "' device closed", ex);
					} catch (Throwable tr) {
						Log.e(LOG_TAG, "Uncaught exception read length="
								+ length, tr);
					}

					Log.i(LOG_TAG, "Disconnected from '" + deviceName
							+ "' device");
					close();
					if (mCI != null) {
						mCI.onAtDisconnected();
					}
					try {
						Thread.sleep(DEVICE_OPEN_RETRY_MILLIS);
					} catch (InterruptedException er) {
					}
					retryCount = 0;
				}
			} catch (Throwable tr) {
				Log.e(LOG_TAG, "Uncaught exception", tr);
			} finally {
				close();
			}
			Log.d(LOG_TAG, "Reader stopped");
		}

	}

	// ***** Constructors
	public ATDevice() {
		Log.d(LOG_TAG, "ATDevice()");
		Log.d(LOG_TAG, "Starting ATReceiver");
		mReceiver = new ATReceiver();
	}

	public void start() {
		running = true;
		if (mReceiverThread == null || !mReceiverThread.isAlive()) {
			mReceiverThread = new Thread(mReceiver, "ATReceiver");
			mReceiverThread.start();
		}
	}

	public void afterStart() {
	}

	public void beforeStop() {
	}

	public void stop() {
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

	public synchronized void close() {
		if (mDeviceIn != null) {
			try {
				mDeviceIn.close();
			} catch (IOException e) {
				Log.e(LOG_TAG, "Uncaught exception", e);
			}
			mDeviceIn = null;
		}
		if (mDeviceOut != null) {
			try {
				mDeviceOut.close();
			} catch (IOException e) {
				Log.e(LOG_TAG, "Uncaught exception", e);
			}
			mDeviceOut = null;
		}
		if (mFile != null) {
			try {
				mFile.close();
			} catch (IOException e) {
				Log.e(LOG_TAG, "Uncaught exception", e);
			}
			mFile = null;
		}
	}

	public ATResponse at_send_command(String command, ATCommandType type) {
		return at_send_command(command, type, null, null, MAX_TIMEOUT);
	}

	public ATResponse at_send_command(String command, ATCommandType type,
			String responsePrefix) {
		return at_send_command(command, type, responsePrefix, null, MAX_TIMEOUT);
	}

	public ATResponse at_send_command(String command, ATCommandType type,
			long timeoutMsec) {
		return at_send_command(command, type, null, null, timeoutMsec);
	}

	public ATResponse at_send_command(String command, ATCommandType type,
			String responsePrefix, String smspdu, long timeoutMsec) {
		ATRequest ar = ATRequest.obtain(command, type);
		ar.mTimeout = timeoutMsec;
		ar.responsePrefix = responsePrefix;
		ar.smspdu = smspdu;
		ATResponse response = doAT(ar);
		ar.release();
		return response;
	}

	public ATResponse doAT(ATRequest ar) {
		Log.d(LOG_TAG, "ATDevice send: " + ar);

		try {

			if (mDeviceOut == null) {
				Log.i(LOG_TAG, "Not connected");
				throw new CommandException(
						CommandException.Error.RADIO_NOT_AVAILABLE);
			}

			String s = ar.mCommand;
			byte[] data = s.getBytes();

			synchronized (responseLock) {
				synchronized (requestLock) {
					mRequest = ar;
					mRequest.mResponse.mStatus = ATRequestStatus.NONE;
				}

				Log.v(LOG_TAG, "AT> '" + s + "'");

				writeATMessage(mDeviceOut, data);

				if (mRequest.mResponse.mStatus == ATRequestStatus.NONE) {
					try {
						responseLock.wait(ar.mTimeout);
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
				mRequest = null;
			}

		} catch (CommandException ex) {
			Log.e(LOG_TAG, "CommandException", ex);
			throw ex;
		} catch (IOException ex) {
			Log.e(LOG_TAG, "IOException", ex);
			throw new CommandException(CommandException.Error.GENERIC_FAILURE);
		} catch (RuntimeException exc) {
			Log.e(LOG_TAG, "Uncaught exception ", exc);
			throw new CommandException(CommandException.Error.GENERIC_FAILURE);
		} finally {
		}
		Log.v(LOG_TAG, "Got response " + ar.mResponse.toString());
		ATResponse response = ar.mResponse;
		ar.mResponse = null;
		return response;
	}

	private boolean contains(String[] table, String value) {
		for (String s : table) {
			if (value.startsWith(s)) {
				return true;
			}
		}
		return false;
	}

	private void process(ATRequest ar, String str) throws IOException {
		//Log.d(LOG_TAG, "Processing '" + str + "' request " + ar);
		if (mSMSUnsolicitedLine1 != null) {
			processUnsolicited(str);
		}
		if (contains(finalResponsesSuccess, str)) {
			ar.mResponse.mFinalResponse = str;
			synchronized (responseLock) {
				ar.mResponse.mStatus = ATRequestStatus.OK;
				responseLock.notifyAll();
			}
		} else if (contains(finalResponsesError, str)) {
			ar.mResponse.mFinalResponse = str;
			synchronized (responseLock) {
				ar.mResponse.mStatus = ATRequestStatus.ERROR;
				responseLock.notifyAll();
			}
		} else if (ar.smspdu != null && "> ".equals(str)) {
			// See eg. TS 27.005 4.3
			// Commands like AT+CMGS have a "> " prompt
			writeCtrlZ(mDeviceOut, ar.smspdu);
			ar.smspdu = null;
		} else {
			switch (ar.mCommandType) {
			case NUMERIC:
				if (Character.isDigit(str.charAt(0))) {
					ar.mResponse.mIntermediates.add(str);
				} else {
					processUnsolicited(str);
				}
				break;
			case SINGLELINE:
				if (str.startsWith(ar.responsePrefix)
						&& ar.mResponse.mIntermediates.size() == 0) {
					str = str.substring(ar.responsePrefix.length());
					if (str.charAt(0) == ' ') {
						str = str.substring(1);
					}
					ar.mResponse.mIntermediates.add(str);
				} else {
					processUnsolicited(str);
				}
				break;
			case MULTILINE:
				if (str.startsWith(ar.responsePrefix)) {
					str = str.substring(ar.responsePrefix.length());
					if (str.charAt(0) == ' ') {
						str = str.substring(1);
					}
					ar.mResponse.mIntermediates.add(str);
				} else {
					processUnsolicited(str);
				}
				break;

			default:
				processUnsolicited(str);
				break;
			}
		}
	}

	private void processUnsolicited(String str) {
		Log.d(LOG_TAG, "processUnsolicited '" + str + "'");
		if ("ATE0Q0V1".equals(str)) {
			return; // First poll may be with echo
		}
		if (contains(smsUnsoliciteds, str)) {
			mSMSUnsolicitedLine1 += str + "\n";
		} else {
			if (mSMSUnsolicitedLine1 != null) {
				str = mSMSUnsolicitedLine1 + str; // hmm
				mSMSUnsolicitedLine1 = null;
			}
		}
		if (mCI != null) {
			mCI.onUnsolicited(str);
		} else {
			Log.e(LOG_TAG, "CommandsImplementation is not connected.");
		}
	}

	public ATCommandsImpl getCI() {
		return mCI;
	}

	public void setCI(ATCommandsImpl mCI) {
		this.mCI = mCI;
	}
	
	public void setDeviceName(String deviceName) {
		this.deviceName = deviceName;
	}

}
