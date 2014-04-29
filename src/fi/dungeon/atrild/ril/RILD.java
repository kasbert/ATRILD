package fi.dungeon.atrild.ril;

import static reloc.com.android.internal.telephony.RILConstants.*;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.List;

import reloc.com.android.internal.telephony.CallForwardInfo;
import reloc.com.android.internal.telephony.CommandException;
import reloc.com.android.internal.telephony.DataCallState;
import reloc.com.android.internal.telephony.DriverCall;
import reloc.com.android.internal.telephony.IccCardStatus;
import reloc.com.android.internal.telephony.IccIoResult;
import reloc.com.android.internal.telephony.OperatorInfo;
import reloc.com.android.internal.telephony.SmsResponse;
import reloc.com.android.internal.telephony.UUSInfo;
import reloc.com.android.internal.telephony.CommandException.Error;
import reloc.com.android.internal.telephony.DriverCall.State;
import reloc.com.android.internal.telephony.gsm.SmsBroadcastConfigInfo;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.telephony.NeighboringCellInfo;
import android.util.Log;

public class RILD extends RILProtocolHelper {

	public static final int EVENT_RIL_REQUEST = 1;
	public static final int EVENT_WAKE_LOCK_TIMEOUT = 2;
	public static final int EVENT_AT_RESPONSE_UNSOLICITED = 3;
	public static final int EVENT_AT_DISCONNECTED = 4;
	public static final int EVENT_AT_CONNECTED = 5;
	public static final int EVENT_SOCKET_DISCONNECTED = 6;
	public static final int EVENT_SOCKET_CONNECTED = 7;
	public static final int EVENT_TIMED_CALLBACK = 8;

	public static final int sendCallStateChanged = 1;
	public static final int pollSIMState = 2;
	public static final int onDataCallListChanged = 3;

	public static final long TIMEVAL_CALLSTATEPOLL = 500L;
	public static final long TIMEVAL_SIMPOLL = 1000L;

	public static final int RIL_VERSION = 6;

	/**
	 * Wake lock timeout should be longer than the longest timeout in the vendor
	 * ril.
	 */
	// private static final int DEFAULT_WAKE_LOCK_TIMEOUT = 60000;

	private HandlerThread mHandlerThread;
	RILHandler mHandler;
	private ATCommandsImpl mCI;
	private RILSocket mSocket;
	private Handler mStatusHandler;
	boolean mATConnected;
	boolean mSocketConnected;

	static class RILHandler extends Handler implements Runnable {
		
		WeakReference<RILD> ref;

		public RILHandler(RILD rild, Looper looper) {
			super(looper);
			ref = new WeakReference<RILD>(rild);
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
			Log.i(LOG_TAG, "RILHandler handleMessage: " + msg);
			RILD rild = ref.get();
			if (rild == null) {
				return;
			}
			

			try {
				switch (msg.what) {
				case EVENT_RIL_REQUEST:
					rild.handleRILRequest(msg);
					break;

				case EVENT_AT_CONNECTED:
					rild.sendStatusMessage();
					rild.handleATConnected();
					rild.sendStatusMessage();
					break;

				case EVENT_AT_DISCONNECTED:
					rild.mATConnected = false;
					rild.sendStatusMessage();
					break;

				case EVENT_SOCKET_CONNECTED:
					rild.mSocketConnected = true;
					rild.sendUnsolicitedResponse(RIL_UNSOL_RIL_CONNECTED,
							new int[] { RIL_VERSION });
					rild.sendUnsolicitedResponse(
							RIL_UNSOL_RESPONSE_RADIO_STATE_CHANGED, rild.mCI
									.getRadioState().getIntValue());
					// ALOGI("RIL Daemon version: %s\n", version);
					// FIXME see ril.cpp property_set(PROPERTY_RIL_IMPL,
					// version);
					rild.sendStatusMessage();
					break;

				case EVENT_SOCKET_DISCONNECTED:
					rild.mSocketConnected = false;
					rild.sendStatusMessage();
					break;

				case EVENT_AT_RESPONSE_UNSOLICITED:
					// Response from ATDevice
					rild.sendUnsolicitedResponse(msg.arg1, msg.obj);
					break;

				case EVENT_WAKE_LOCK_TIMEOUT:
					// Haven't heard back from the last request. Assume we're
					// not getting a response and release the wake lock.
					break;

				case EVENT_TIMED_CALLBACK:
					rild.handleTimedCallback(msg);
					break;
				}
			} catch (Exception e) {
				Log.e(LOG_TAG, "Unhandled exception", e);
			}
		}
	}

	public RILD() {
		mHandlerThread = new HandlerThread("RILHandler");
		mHandlerThread.start();

		Looper looper = mHandlerThread.getLooper();
		mHandler = new RILHandler(this, looper);
	}

	protected void sendStatusMessage() {
		if (mStatusHandler != null) {
			mStatusHandler.obtainMessage().sendToTarget();
		}
	}
	
	private void handleATConnected() {
		try {
			mCI.at_handshake();
			mCI.initialize();
			mATConnected = true;
		} catch (CommandException e) {
			mATConnected = false;
			Log.e(LOG_TAG, "AT not initialized", e);
		}
	}

	protected void handleRILRequest(Message msg) {
		// Log.d(LOG_TAG, "RILD handleMessage: " + msg);
		Parcel p = (Parcel) msg.obj;
		RILRequest request = RILRequest.obtain(p);
		RILResponse response = RILResponse.obtain(request.mRequest,
				RILProtocolHelper.RESPONSE_SOLICITED, request.mSerial);
		// FIXME timeouts
		try {
			// Message result = Message.obtain(mHandler, -1, request.mRequest,
			// request.mSerial);
			Log.d(LOG_TAG, "HandleRILRequest " + request);
			if (mATConnected) {
				processRequest(request, response);
			} else {
				response.setError(CommandException.Error.RADIO_NOT_AVAILABLE);
			}
			// processResponse(response, result);
			// result.recycle();
		} catch (CommandException ex) {
			Log.e(LOG_TAG,
					"CommandException:" + ex.getMessage() + ":"
							+ ex.getCommandError());
			response.setError(ex.getCommandError());
		} catch (IOException exc) {
			Log.e(LOG_TAG, "Uncaught exception ", exc);
			response.setError(CommandException.Error.GENERIC_FAILURE);
		} catch (RuntimeException exc) {
			Log.e(LOG_TAG, "Uncaught exception ", exc);
			response.setError(CommandException.Error.GENERIC_FAILURE);
		} finally {
		}
		// Log.i(LOG_TAG, "Sending to socket " + response);
		mSocket.send(response.mp);
		response.release();
	}

	protected void processRequest(RILRequest request, RILResponse response)
			throws IOException {

		// Message msg = Message.obtain();
		Parcel po = response.mp;
		Parcel pi = request.mp;

		// Common return objects, used also in logging
		int[] ints = null;
		String str = null;
		String[] strings = null;
		Object other = null;

		String logPrefix = "[" + request.mSerial + "]< "
				+ requestToString(request.mRequest);

		switch (request.mRequest) {
		/*
		 * cat libs/telephony/ril_commands.h \ | egrep "^ *{RIL_" \ | sed -re
		 * 's/\{([^,]+),[^,]+,([^}]+).+/case \1: ret = \2(p); break;/'
		 */
		case RIL_REQUEST_GET_SIM_STATUS: {
			riljLog(logPrefix);
			IccCardStatus ics = mCI.getIccCardStatus();
			writeIccCardStatus(po, ics);
			other = ics;
			break;
		}
		case RIL_REQUEST_ENTER_SIM_PIN: {
			String[] arr = pi.createStringArray();
			String pin = arr[0];
			String aid = null;
			if (arr.length > 1) {
				aid = arr[1];
			}
			riljLog(logPrefix + " " + pin + " " + aid);
			// FIXME don't show
			ints = mCI.supplyIccPinForApp(pin, aid);
			writeInts(po, ints);
			break;
		}
		case RIL_REQUEST_ENTER_SIM_PUK: {
			String[] arr = pi.createStringArray();
			String puk = arr[0];
			String newPin = arr[1];
			String aid = null;
			if (arr.length > 2) {
				aid = arr[2];
			}
			riljLog(logPrefix + " " + puk + " " + newPin + " " + aid);
			// FIXME don't show
			ints = mCI.supplyIccPukForApp(puk, newPin, aid);
			writeInts(po, ints);
			break;
		}
		case RIL_REQUEST_ENTER_SIM_PIN2: {
			String[] arr = pi.createStringArray();
			String pin = arr[0];
			String aid = (arr.length > 1) ? arr[0] : null;
			riljLog(logPrefix + " " + pin + " " + aid);
			// FIXME don't show
			ints = mCI.supplyIccPin2ForApp(pin, aid);
			writeInts(po, ints);
			break;
		}
		case RIL_REQUEST_ENTER_SIM_PUK2: {
			String[] arr = pi.createStringArray();
			String puk = arr[0];
			String newPin2 = arr[1];
			String aid = null;
			if (arr.length > 2) {
				aid = arr[2];
			}
			riljLog(logPrefix + " " + puk + " " + newPin2 + " " + aid);
			// FIXME don't show
			ints = mCI.supplyIccPuk2ForApp(puk, newPin2, aid);
			writeInts(po, ints);
			break;
		}
		case RIL_REQUEST_CHANGE_SIM_PIN: {
			String[] arr = pi.createStringArray();
			String oldPin = arr[0];
			String newPin = arr[1];
			String aid = null;
			if (arr.length > 2) {
				aid = arr[2];
			}
			riljLog(logPrefix + " " + oldPin + " " + newPin + " " + aid);
			// FIXME don't show
			ints = mCI.changeIccPinForApp(oldPin, newPin, aid);
			writeInts(po, ints);
			break;
		}
		case RIL_REQUEST_CHANGE_SIM_PIN2: {
			String[] arr = pi.createStringArray();
			String oldPin2 = arr[0];
			String newPin2 = arr[1];
			String aid = null;
			if (arr.length > 2) {
				aid = arr[2];
			}
			riljLog(logPrefix + " " + oldPin2 + " " + newPin2 + " " + aid);
			ints = mCI.changeIccPin2ForApp(oldPin2, newPin2, aid);
			writeInts(po, ints);
			break;
		}
		case RIL_REQUEST_ENTER_NETWORK_DEPERSONALIZATION: {
			String[] arr = pi.createStringArray();
			String netpin = null;
			if (arr.length > 0) {
				netpin = arr[0];
			}
			riljLog(logPrefix + " " + netpin);
			ints = mCI.supplyNetworkDepersonalization(netpin);
			writeInts(po, ints);
			break;
		}
		case RIL_REQUEST_GET_CURRENT_CALLS: {
			riljLog(logPrefix);
			List<DriverCall> calls = mCI.getCurrentCalls();
			for (DriverCall call : calls) {
				if (call.state != State.ACTIVE && call.state != State.HOLDING) {
					Message msg = Message.obtain(mHandler,
							RILD.EVENT_TIMED_CALLBACK, sendCallStateChanged, 0);
					mHandler.sendMessageDelayed(msg, TIMEVAL_CALLSTATEPOLL);
					break;
				}
			}
			writeCallList(po, calls);
			other = calls;
			break;
		}
		case RIL_REQUEST_DIAL: {
			String address = pi.readString();
			int clirMode = pi.readInt();
			UUSInfo uusInfo = null;
			if (pi.readInt() == 1) {
				uusInfo = new UUSInfo();
				uusInfo.setType(pi.readInt());
				uusInfo.setDcs(pi.readInt());
				byte[] val = pi.createByteArray();
				uusInfo.setUserData(val);
			}
			riljLog(logPrefix + " " + clirMode + " " + uusInfo.getType() + " "
					+ uusInfo.getDcs());
			mCI.dial(address, clirMode, uusInfo);
			break;
		}
		case RIL_REQUEST_GET_IMSI: {
			// FIXME sometimes without the leading int
			String[] arr = pi.createStringArray();
			String aid = null;
			if (arr.length > 0) {
				aid = arr[0];
			}
			riljLog(logPrefix + " " + aid);
			str = mCI.getIMSIForApp(aid);
			writeString(po, str);
			break;
		}
		case RIL_REQUEST_HANGUP: {
			int[] arr = pi.createIntArray();
			int gsmIndex = -1;
			if (arr.length > 0) {
				gsmIndex = arr[0];
			}
			riljLog(logPrefix + " " + gsmIndex);
			mCI.hangupConnection(gsmIndex);
			break;
		}
		case RIL_REQUEST_HANGUP_WAITING_OR_BACKGROUND:
			riljLog(logPrefix);
			mCI.hangupWaitingOrBackground();
			break;
		case RIL_REQUEST_HANGUP_FOREGROUND_RESUME_BACKGROUND: {
			riljLog(logPrefix);
			mCI.hangupForegroundResumeBackground();
			break;
		}
		case RIL_REQUEST_SWITCH_WAITING_OR_HOLDING_AND_ACTIVE:
			riljLog(logPrefix);
			mCI.switchWaitingOrHoldingAndActive();
			break;
		case RIL_REQUEST_CONFERENCE:
			riljLog(logPrefix);
			mCI.conference();
			break;
		case RIL_REQUEST_UDUB:
			riljLog(logPrefix);
			mCI.rejectCall();
			break;
		case RIL_REQUEST_LAST_CALL_FAIL_CAUSE: {
			riljLog(logPrefix);
			ints = mCI.getLastCallFailCause();
			writeInts(po, ints);
			break;
		}
		case RIL_REQUEST_SIGNAL_STRENGTH: {
			riljLog(logPrefix);
			ints = mCI.getSignalStrength();
			writeSignalStrength(po, ints);
			break;
		}
		case RIL_REQUEST_VOICE_REGISTRATION_STATE: {
			riljLog(logPrefix);
			strings = mCI.getVoiceRegistrationState();
			writeStrings(po, strings);
			break;
		}
		case RIL_REQUEST_DATA_REGISTRATION_STATE: {
			riljLog(logPrefix);
			strings = mCI.getDataRegistrationState();
			writeStrings(po, strings);
			break;
		}
		case RIL_REQUEST_OPERATOR: {
			riljLog(logPrefix);
			strings = mCI.getOperator();
			writeStrings(po, strings);
			break;
		}
		case RIL_REQUEST_RADIO_POWER: {
			boolean on = pi.readInt() == 1;
			riljLog(logPrefix + " " + on);
			mCI.setRadioPower(on);
			break;
		}
		case RIL_REQUEST_DTMF: {
			char c = pi.readString().charAt(0);
			riljLog(logPrefix + " " + c);
			mCI.sendDtmf(c);
			break;
		}
		case RIL_REQUEST_SEND_SMS: {
			String[] arr = pi.createStringArray();
			String smscPDU = arr[0];
			String pdu = arr[1];
			riljLog(logPrefix + " " + smscPDU + " " + pdu);
			SmsResponse smsResponse = mCI.sendSMS(smscPDU, pdu);
			writeSmsResponse(po, smsResponse);
			other = smsResponse;
			break;
		}
		case RIL_REQUEST_SEND_SMS_EXPECT_MORE: {
			// FIXME unknown
			String[] arr = pi.createStringArray();
			String smscPDU = arr[0];
			String pdu = arr[1];
			mCI.sendSMSExpectMore(smscPDU, pdu);
			riljLog(logPrefix + " " + smscPDU + " " + pdu);
			// writeSmsResponse(po, (SmsResponse) null);
			throw new CommandException(Error.REQUEST_NOT_SUPPORTED);
			// break;
		}
		case RIL_REQUEST_SETUP_DATA_CALL: {
			String[] arr = pi.createStringArray();
			String radioTechnology = arr[0];
			String profile = arr[1];
			String apn = arr[2];
			String user = arr[3];
			String password = arr[4];
			String authType = arr[5];
			String protocol = arr[6];
			riljLog(logPrefix + " " + radioTechnology + " " + profile + " "
					+ apn + " " + user + " " + password + " " + authType + " "
					+ protocol);
			DataCallState dcs = mCI.setupDataCall(radioTechnology, profile,
					apn, user, password, authType, protocol);
			writeSetupDataCallState(po, dcs);
			other = dcs;
			break;
		}
		case RIL_REQUEST_SIM_IO: {
			int command = pi.readInt();
			int fileid = pi.readInt();
			String path = pi.readString();
			int p1 = pi.readInt();
			int p2 = pi.readInt();
			int p3 = pi.readInt();
			String data = pi.readString();
			String pin2 = pi.readString();
			String aid = pi.readString();
			IccIoResult iir = mCI.iccIOForApp(command, fileid, path, p1, p2,
					p3, data, pin2, aid);
			riljLog(logPrefix + " iccIO: " + " 0x"
					+ Integer.toHexString(command) + " 0x"
					+ Integer.toHexString(fileid) + " " + " path: " + path
					+ "," + p1 + "," + p2 + "," + p3 + " aid: " + aid);
			writeIccIoResult(po, iir);
			other = iir;
			break;
		}
		case RIL_REQUEST_SEND_USSD: {
			String ussdString = pi.readString();
			riljLog(logPrefix + " " + ussdString);
			mCI.sendUSSD(ussdString);
			break;
		}
		case RIL_REQUEST_CANCEL_USSD:
			riljLog(logPrefix);
			mCI.cancelPendingUssd();
			break;
		case RIL_REQUEST_GET_CLIR:
			riljLog(logPrefix);
			ints = mCI.getCLIR();
			writeInts(po, ints);
			break;
		case RIL_REQUEST_SET_CLIR: {
			int[] arr = pi.createIntArray();
			int i = arr[0];
			int clirMode = arr[1];
			riljLog(logPrefix + " " + clirMode);
			mCI.setCLIR(clirMode);
			break;
		}
		case RIL_REQUEST_QUERY_CALL_FORWARD_STATUS: {
			int i = pi.readInt(); // 2
			int cfReason = pi.readInt();
			int serviceClass = pi.readInt();
			int aNumber = pi.readInt();
			String number = pi.readString();
			int j = pi.readInt(); // 0
			riljLog(logPrefix + " " + cfReason + " " + serviceClass + " "
					+ number);
			CallForwardInfo[] cfia = mCI.queryCallForwardStatus(cfReason,
					serviceClass, number);
			writeCallForwardInfo(po, cfia);
			other = cfia;
			break;
		}
		case RIL_REQUEST_SET_CALL_FORWARD: {
			int action = pi.readInt();
			int cfReason = pi.readInt();
			int serviceClass = pi.readInt();
			int aNumber = pi.readInt();
			String number = pi.readString();
			int timeSeconds = pi.readInt();
			riljLog(logPrefix + " " + action + " " + cfReason + " "
					+ serviceClass + " " + timeSeconds);
			mCI.setCallForward(action, cfReason, serviceClass, number,
					timeSeconds);
			break;
		}
		case RIL_REQUEST_QUERY_CALL_WAITING: {
			int[] arr = pi.createIntArray();
			int serviceClass = arr[0];
			riljLog(logPrefix + " " + serviceClass);
			ints = mCI.queryCallWaiting(serviceClass);
			writeInts(po, ints);
			break;
		}
		case RIL_REQUEST_SET_CALL_WAITING: {
			int[] arr = pi.createIntArray();
			boolean enable = arr[0] == 1;
			int serviceClass = arr[1];
			riljLog(logPrefix + " " + enable + " " + serviceClass);
			mCI.setCallWaiting(enable, serviceClass);
			break;
		}
		case RIL_REQUEST_SMS_ACKNOWLEDGE: {
			int[] arr = pi.createIntArray();
			boolean success = arr[0] == 1;
			int cause = arr[1];
			riljLog(logPrefix + " " + success + " " + cause);
			mCI.acknowledgeLastIncomingGsmSms(success, cause);
			break;
		}
		case RIL_REQUEST_GET_IMEI: {
			riljLog(logPrefix);
			str = mCI.getIMEI();
			writeString(po, str);
			break;
		}
		case RIL_REQUEST_GET_IMEISV: {
			riljLog(logPrefix);
			str = mCI.getIMEISV();
			writeString(po, str);
			break;
		}
		case RIL_REQUEST_ANSWER:
			riljLog(logPrefix);
			mCI.acceptCall();
			break;
		case RIL_REQUEST_DEACTIVATE_DATA_CALL: {
			String[] arr = pi.createStringArray();
			int cid = Integer.parseInt(arr[0]);
			int reason = Integer.parseInt(arr[1]);
			riljLog(logPrefix + " " + cid + " " + reason);
			mCI.deactivateDataCall(cid, reason);
			break;
		}
		case RIL_REQUEST_QUERY_FACILITY_LOCK: {
			int i = pi.readInt(); // 3 or 4
			String facility = pi.readString();
			String password = pi.readString();
			int serviceClass = pi.readInt();
			String appId = null;
			if (i == 4) {
				appId = pi.readString();
			}
			riljLog(logPrefix + " " + facility + " " + "***" + " "
					+ serviceClass + " " + appId);
			ints = mCI.queryFacilityLockForApp(facility, password,
					serviceClass, appId);
			writeInts(po, ints);
			break;
		}
		case RIL_REQUEST_SET_FACILITY_LOCK: {
			int i = pi.readInt(); // 4 or 5
			String facility = pi.readString();
			boolean lockState = Integer.parseInt(pi.readString()) == 1;
			String password = pi.readString();
			int serviceClass = Integer.parseInt(pi.readString());
			String appId = null;
			if (i == 4) {
				appId = pi.readString();
			}
			riljLog(logPrefix + " " + facility + " " + lockState + " " + "***"
					+ " " + serviceClass + " " + appId);
			ints = mCI.setFacilityLockForApp(facility, lockState, password,
					serviceClass, appId);
			writeInts(po, ints);
			break;
		}
		case RIL_REQUEST_CHANGE_BARRING_PASSWORD: {
			String[] arr = pi.createStringArray();
			String facility = arr[0];
			String oldPwd = arr[1];
			String newPwd = arr[2];
			// FIXME don't show
			riljLog(logPrefix + " " + facility + " " + oldPwd + " " + newPwd);
			mCI.changeBarringPassword(facility, oldPwd, newPwd);
			break;
		}
		case RIL_REQUEST_QUERY_NETWORK_SELECTION_MODE:
			riljLog(logPrefix);
			ints = mCI.getNetworkSelectionMode();
			writeInts(po, ints);
			break;
		case RIL_REQUEST_SET_NETWORK_SELECTION_AUTOMATIC:
			riljLog(logPrefix);
			mCI.setNetworkSelectionModeAutomatic();
			break;
		case RIL_REQUEST_SET_NETWORK_SELECTION_MANUAL: {
			String operatorNumeric = pi.readString();
			riljLog(logPrefix + " " + operatorNumeric);
			mCI.setNetworkSelectionModeManual(operatorNumeric);
			break;
		}
		case RIL_REQUEST_QUERY_AVAILABLE_NETWORKS:
			riljLog(logPrefix);
			List<OperatorInfo> oi = mCI.getAvailableNetworks();
			writeOperatorInfo(po, oi);
			other = oi;
			break;
		case RIL_REQUEST_DTMF_START:
			char c = pi.readString().charAt(0);
			riljLog(logPrefix + " " + c);
			mCI.startDtmf(c);
			break;
		case RIL_REQUEST_DTMF_STOP:
			riljLog(logPrefix);
			mCI.stopDtmf();
			break;
		case RIL_REQUEST_BASEBAND_VERSION: {
			riljLog(logPrefix);
			str = mCI.getBasebandVersion();
			writeString(po, str);
			break;
		}
		case RIL_REQUEST_SEPARATE_CONNECTION: {
			int[] arr = pi.createIntArray();
			int gsmIndex = arr[0];
			riljLog(logPrefix + " " + gsmIndex);
			mCI.separateConnection(gsmIndex);
			break;
		}
		case RIL_REQUEST_SET_MUTE: {
			int[] arr = pi.createIntArray();
			boolean enableMute = (arr[0] == 1);
			riljLog(logPrefix + " " + enableMute);
			mCI.setMute(enableMute);
			break;
		}
		case RIL_REQUEST_GET_MUTE:
			riljLog(logPrefix);
			ints = mCI.getMute();
			writeInts(po, ints);
			break;
		case RIL_REQUEST_QUERY_CLIP:
			riljLog(logPrefix);
			ints = mCI.queryCLIP();
			writeInts(po, ints);
			break;
		case RIL_REQUEST_LAST_DATA_CALL_FAIL_CAUSE:
			riljLog(logPrefix);
			ints = mCI.getLastDataCallFailCause();
			writeInts(po, ints);
			break;
		case RIL_REQUEST_DATA_CALL_LIST:
			riljLog(logPrefix);
			List<DataCallState> dcs = mCI.getDataCallList();
			writeDataCallList(po, dcs);
			other = dcs;
			break;
		case RIL_REQUEST_RESET_RADIO:
			riljLog(logPrefix);
			mCI.resetRadio();
			break;
		case RIL_REQUEST_OEM_HOOK_RAW: {
			byte[] data = pi.createByteArray();
			riljLog(logPrefix + " " + data);
			byte[] raw = mCI.invokeOemRilRequestRaw(data);
			writeRaw(po, raw);
			other = raw;
			break;
		}
		case RIL_REQUEST_OEM_HOOK_STRINGS: {
			String[] strs = pi.createStringArray();
			riljLog(logPrefix + " " + Arrays.asList(strs));
			strings = mCI.invokeOemRilRequestStrings(strs);
			writeStrings(po, strings);
			break;
		}
		case RIL_REQUEST_SCREEN_STATE: {
			int[] arr = pi.createIntArray();
			boolean on = (arr[0] == 1);
			riljLog(logPrefix + " " + on);
			mCI.sendScreenState(on);
			break;
		}
		case RIL_REQUEST_SET_SUPP_SVC_NOTIFICATION: {
			int[] arr = pi.createIntArray();
			boolean enable = (arr[0] == 1);
			riljLog(logPrefix + " " + enable);
			mCI.setSuppServiceNotifications(enable);
			break;
		}
		case RIL_REQUEST_WRITE_SMS_TO_SIM: {
			int status = pi.readInt();
			String smsc = pi.readString();
			String pdu = pi.readString();
			riljLog(logPrefix + " " + status + " " + smsc + " " + pdu);
			ints = mCI.writeSmsToSim(status, smsc, pdu);
			writeInts(po, ints);
			break;

		}
		case RIL_REQUEST_DELETE_SMS_ON_SIM: {
			int[] arr = pi.createIntArray();
			int index = arr[0];
			riljLog(logPrefix + " " + index);
			mCI.deleteSmsOnSim(index);
			break;
		}
		case RIL_REQUEST_SET_BAND_MODE: {
			int[] arr = pi.createIntArray();
			int bandMode = arr[0];
			riljLog(logPrefix + " " + bandMode);
			mCI.setBandMode(bandMode);
			break;
		}
		case RIL_REQUEST_QUERY_AVAILABLE_BAND_MODE:
			ints = mCI.queryAvailableBandMode();
			riljLog(logPrefix);
			writeInts(po, ints);
			break;
		case RIL_REQUEST_STK_GET_PROFILE: {
			riljLog(logPrefix);
			str = mCI.getProfile();
			writeString(po, str);
			break;
		}
		case RIL_REQUEST_STK_SET_PROFILE: {
			String profile = pi.readString();
			riljLog(logPrefix + " " + profile);
			mCI.setProfile(profile);
			break;
		}
		case RIL_REQUEST_STK_SEND_ENVELOPE_COMMAND: {
			String contents = pi.readString();
			riljLog(logPrefix + " " + contents);
			str = mCI.sendEnvelope(contents);
			writeString(po, str);
			break;
		}
		case RIL_REQUEST_STK_SEND_TERMINAL_RESPONSE: {
			String contents = pi.readString();
			riljLog(logPrefix + " " + contents);
			mCI.sendTerminalResponse(contents);
			break;
		}
		case RIL_REQUEST_STK_HANDLE_CALL_SETUP_REQUESTED_FROM_SIM: {
			int[] arr = pi.createIntArray();
			boolean accept = (arr[0] == 1);
			riljLog(logPrefix + " " + accept);
			ints = mCI.handleCallSetupRequestFromSim(accept);
			writeInts(po, ints);
			break;
		}
		case RIL_REQUEST_EXPLICIT_CALL_TRANSFER:
			riljLog(logPrefix);
			mCI.explicitCallTransfer();
			break;
		case RIL_REQUEST_SET_PREFERRED_NETWORK_TYPE: {
			int[] arr = pi.createIntArray();
			int networkType = arr[0];
			riljLog(logPrefix + " " + networkType);
			mCI.setPreferredNetworkType(networkType);
			break;
		}
		case RIL_REQUEST_GET_PREFERRED_NETWORK_TYPE:
			riljLog(logPrefix);
			ints = mCI.getPreferredNetworkType();
			writeInts(po, ints);
			break;
		case RIL_REQUEST_GET_NEIGHBORING_CELL_IDS:
			riljLog(logPrefix);
			List<NeighboringCellInfo> nci = mCI.getNeighboringCids();
			writeNeighboringCellInfo(po, nci);
			break;
		case RIL_REQUEST_SET_LOCATION_UPDATES: {
			int[] arr = pi.createIntArray();
			boolean enable = (arr[0] == 1);
			riljLog(logPrefix + " " + enable);
			mCI.setLocationUpdates(enable);
			break;
		}
		case RIL_REQUEST_CDMA_SET_SUBSCRIPTION_SOURCE: {
			int[] arr = pi.createIntArray();
			int cdmaSubscription = arr[0];
			riljLog(logPrefix + " " + cdmaSubscription);
			mCI.setCdmaSubscriptionSource(cdmaSubscription);
			break;
		}
		case RIL_REQUEST_CDMA_SET_ROAMING_PREFERENCE: {
			int[] arr = pi.createIntArray();
			int cdmaRoamingType = arr[0];
			riljLog(logPrefix + " " + cdmaRoamingType);
			mCI.setCdmaRoamingPreference(cdmaRoamingType);
			break;
		}
		case RIL_REQUEST_CDMA_QUERY_ROAMING_PREFERENCE:
			riljLog(logPrefix);
			ints = mCI.queryCdmaRoamingPreference();
			writeInts(po, ints);
			break;
		case RIL_REQUEST_SET_TTY_MODE: {
			int[] arr = pi.createIntArray();
			int ttyMode = arr[0];
			riljLog(logPrefix + " " + ttyMode);
			mCI.setTTYMode(ttyMode);
			break;
		}
		case RIL_REQUEST_QUERY_TTY_MODE:
			riljLog(logPrefix);
			ints = mCI.queryTTYMode();
			writeInts(po, ints);
			break;
		case RIL_REQUEST_CDMA_SET_PREFERRED_VOICE_PRIVACY_MODE: {
			int[] arr = pi.createIntArray();
			boolean enable = (arr[0] == 1);
			riljLog(logPrefix + " " + enable);
			mCI.setPreferredVoicePrivacy(enable);
			break;
		}
		case RIL_REQUEST_CDMA_QUERY_PREFERRED_VOICE_PRIVACY_MODE:
			riljLog(logPrefix);
			ints = mCI.getPreferredVoicePrivacy();
			writeInts(po, ints);
			break;
		case RIL_REQUEST_CDMA_FLASH: {
			String featureCode = pi.readString();
			riljLog(logPrefix + " " + featureCode);
			mCI.sendCDMAFeatureCode(featureCode);
			break;
		}
		case RIL_REQUEST_CDMA_BURST_DTMF: {
			String[] arr = pi.createStringArray();
			String dtmfString = arr[0];
			int on = Integer.parseInt(arr[1]);
			int off = Integer.parseInt(arr[2]);
			riljLog(logPrefix + " " + dtmfString + " " + on + " " + off);
			mCI.sendBurstDtmf(dtmfString, on, off);
			break;
		}
		case RIL_REQUEST_CDMA_SEND_SMS: {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			DataOutputStream dos = new DataOutputStream(baos);
			dos.writeInt(pi.readInt()); // teleServiceId
			dos.writeInt(pi.readByte()); // servicePresent
			dos.writeInt(pi.readInt()); // serviceCategory
			dos.write(pi.readInt()); // address_digit_mode
			dos.write(pi.readInt()); // address_nbr_mode
			dos.write(pi.readInt()); // address_ton
			dos.write(pi.readInt()); // address_nbr_plan
			int address_nbr_of_digits = pi.readByte();
			dos.write(address_nbr_of_digits);
			for (int i = 0; i < address_nbr_of_digits; i++) {
				dos.write(pi.readByte()); // address_orig_bytes[i]
			}
			dos.write(pi.readInt()); // subaddressType
			dos.write(pi.readByte()); // subaddr_odd
			int subaddr_nbr_of_digits = pi.readByte();
			dos.write(address_nbr_of_digits);
			for (int i = 0; i < subaddr_nbr_of_digits; i++) {
				dos.write(pi.readByte()); // subaddr_orig_bytes[i]
			}
			int bearerDataLength = pi.readInt();
			dos.write(bearerDataLength);
			for (int i = 0; i < bearerDataLength; i++) {
				dos.write(pi.readByte()); // bearerData[i]
			}
			dos.close();
			byte[] pdu = baos.toByteArray();
			riljLog(logPrefix + " " + pdu);
			SmsResponse sr = mCI.sendCdmaSms(pdu);
			writeSmsResponse(po, sr);
			other = sr;
			break;
		}
		case RIL_REQUEST_CDMA_SMS_ACKNOWLEDGE: {
			boolean success = pi.readInt() == 1;
			// cause code according to X.S004-550E
			int cause = pi.readInt();
			riljLog(logPrefix + " " + success + " " + cause);
			mCI.acknowledgeLastIncomingCdmaSms(success, cause);
			break;
		}
		case RIL_REQUEST_GSM_GET_BROADCAST_CONFIG:
			riljLog(logPrefix);
			List<SmsBroadcastConfigInfo> sbc = mCI.getGsmBroadcastConfig();
			writeGsmBroadcastConfigInfo(po, sbc);
			break;
		case RIL_REQUEST_GSM_SET_BROADCAST_CONFIG: {
			int numOfConfig = pi.readInt();
			SmsBroadcastConfigInfo[] config = new SmsBroadcastConfigInfo[numOfConfig];
			for (int i = 0; i < numOfConfig; i++) {
				config[i].setFromServiceId(pi.readInt());
				config[i].setToServiceId(pi.readInt());
				config[i].setFromCodeScheme(pi.readInt());
				config[i].setToCodeScheme(pi.readInt());
				config[i].setSelected(pi.readInt() == 1);
			}
			riljLog(logPrefix + " with " + numOfConfig + " configs : " + " "
					+ Arrays.asList(config));
			mCI.setGsmBroadcastConfig(config);
			break;
		}
		case RIL_REQUEST_GSM_BROADCAST_ACTIVATION: {
			int[] arr = pi.createIntArray();
			boolean activate = arr[0] == 1;
			riljLog(logPrefix + " " + activate);
			mCI.setGsmBroadcastActivation(activate);
			break;
		}
		case RIL_REQUEST_CDMA_GET_BROADCAST_CONFIG: {
			riljLog(logPrefix);
			ints = mCI.getCdmaBroadcastConfig();
			writeCdmaBroadcastConfig(po, ints);
			break;
		}
		case RIL_REQUEST_CDMA_SET_BROADCAST_CONFIG: {
			int len = 1; // FIXME
			int[] configValuesArray = new int[len];
			for (int i = 0; i < configValuesArray.length; i++) {
				configValuesArray[i] = pi.readInt();
			}
			riljLog(logPrefix + " " + Arrays.asList(configValuesArray));
			mCI.setCdmaBroadcastConfig(configValuesArray);
			break;
		}
		case RIL_REQUEST_CDMA_BROADCAST_ACTIVATION: {
			int[] arr = pi.createIntArray();
			boolean activate = (arr[0] == 1);
			riljLog(logPrefix + " " + activate);
			mCI.setCdmaBroadcastActivation(activate);
			break;
		}
		case RIL_REQUEST_CDMA_VALIDATE_AND_WRITE_AKEY:
			// FIXME
			riljLog(logPrefix);
			// mCI.x
			throw new CommandException(Error.REQUEST_NOT_SUPPORTED);
			// break;
		case RIL_REQUEST_CDMA_SUBSCRIPTION: {
			riljLog(logPrefix);
			strings = mCI.getCDMASubscription();
			writeStrings(po, strings);
			break;
		}
		case RIL_REQUEST_CDMA_WRITE_SMS_TO_RUIM: {
			int status = pi.readInt();
			String pdu = pi.readString();
			riljLog(logPrefix + " " + status + " " + pdu);
			ints = mCI.writeSmsToRuim(status, pdu);
			writeInts(po, ints);
			break;
		}
		case RIL_REQUEST_CDMA_DELETE_SMS_ON_RUIM: {
			int[] arr = pi.createIntArray();
			int index = arr[0];
			riljLog(logPrefix + " " + index);
			mCI.deleteSmsOnRuim(index);
			break;
		}
		case RIL_REQUEST_DEVICE_IDENTITY:
			riljLog(logPrefix);
			strings = mCI.getDeviceIdentity();
			writeStrings(po, strings);
			break;
		case RIL_REQUEST_GET_SMSC_ADDRESS: {
			riljLog(logPrefix);
			str = mCI.getSmscAddress();
			writeString(po, str);
			break;
		}
		case RIL_REQUEST_SET_SMSC_ADDRESS: {
			String address = pi.readString();
			riljLog(logPrefix + " " + address);
			mCI.setSmscAddress(address);
			break;
		}
		case RIL_REQUEST_EXIT_EMERGENCY_CALLBACK_MODE:
			riljLog(logPrefix);
			mCI.exitEmergencyCallbackMode();
			break;
		case RIL_REQUEST_REPORT_SMS_MEMORY_STATUS: {
			int[] arr = pi.createIntArray();
			boolean available = (arr[0] == 1);
			riljLog(logPrefix + " " + available);
			mCI.reportSmsMemoryStatus(available);
			break;
		}
		case RIL_REQUEST_REPORT_STK_SERVICE_IS_RUNNING:
			riljLog(logPrefix);
			mCI.reportStkServiceIsRunning();
			break;
		case RIL_REQUEST_CDMA_GET_SUBSCRIPTION_SOURCE:
			riljLog(logPrefix);
			ints = mCI.getCdmaSubscriptionSource();
			writeInts(po, ints);
			break;
		case RIL_REQUEST_ISIM_AUTHENTICATION: {
			String nonce = pi.readString();
			riljLog(logPrefix + " " + nonce);
			str = mCI.requestIsimAuthentication(nonce);
			writeString(po, str);
			break;
		}
		case RIL_REQUEST_ACKNOWLEDGE_INCOMING_GSM_SMS_WITH_PDU: {
			String[] arr = pi.createStringArray();
			boolean success = Integer.parseInt(arr[0]) == 1;
			String ackPdu = arr[1];
			riljLog(logPrefix + " " + success + " " + ackPdu);
			mCI.acknowledgeIncomingGsmSmsWithPdu(success, ackPdu);
			break;
		}
		case RIL_REQUEST_STK_SEND_ENVELOPE_WITH_STATUS: {
			String contents = pi.readString();
			riljLog(logPrefix + " " + contents);
			IccIoResult iir = mCI.sendEnvelopeWithStatus(contents);
			writeIccIoResult(po, iir);
			other = iir;
			break;
		}
		case RIL_REQUEST_VOICE_RADIO_TECH:
			riljLog(logPrefix);
			ints = mCI.getVoiceRadioTechnology();
			writeInts(po, ints);
			break;
		default:
			Log.w(LOG_TAG, logPrefix
					+ " exception, possible invalid RIL request");
			throw new CommandException(Error.REQUEST_NOT_SUPPORTED);
			// break;
		}

		if (RILDJ_LOGD) {
			if (ints != null) {
				other = ints;
			} else if (strings != null) {
				other = strings;
			} else if (str != null) {
				other = str;
			}
			if (other == null) {
				other = "";
			}
			riljLog("[" + request.mSerial + "]> "
					+ requestToString(request.mRequest) + " "
					+ retToString(request.mRequest, other));
		}

	}

	private void handleTimedCallback(Message msg) {
		switch (msg.arg1) {
		case sendCallStateChanged:
			sendUnsolicitedResponse(RIL_UNSOL_RESPONSE_CALL_STATE_CHANGED, "");
			break;
		case pollSIMState:
			mCI.pollSIMState();
			break;
		case onDataCallListChanged:
			List<DataCallState> dcs = mCI.getDataCallList();
			sendUnsolicitedResponse(RIL_UNSOL_DATA_CALL_LIST_CHANGED, dcs);
			break;
		}
	}

	public void sendUnsolicitedResponse(int response, Object obj) {

		Parcel po = Parcel.obtain();
		po.writeInt(RILProtocolHelper.RESPONSE_UNSOLICITED);
		po.writeInt(response);

		riljLog("[UNSOL]> " + responseToString(response) + " "
				+ retToString(response, obj));
		try {
			switch (response) {
			/*
			 * cat libs/telephony/ril_unsol_commands.h \ | egrep "^ *{RIL_" \ |
			 * sed -re 's/\{([^,]+),[^,]+,([^}]+).+/case \1: \2(rr, p); break;/'
			 */

			case RIL_UNSOL_RESPONSE_RADIO_STATE_CHANGED:
				po.writeInt((Integer) obj);
				break;
			case RIL_UNSOL_RESPONSE_CALL_STATE_CHANGED:
				break;
			case RIL_UNSOL_RESPONSE_VOICE_NETWORK_STATE_CHANGED:
				break;
			case RIL_UNSOL_RESPONSE_NEW_SMS:
				po.writeString((String) obj);
				break;
			case RIL_UNSOL_RESPONSE_NEW_SMS_STATUS_REPORT:
				po.writeString((String) obj);
				break;
			case RIL_UNSOL_RESPONSE_NEW_SMS_ON_SIM:
				po.writeIntArray((int[]) obj);
				break;
			case RIL_UNSOL_ON_USSD:
				po.writeStringArray((String[]) obj);
				break;
			case RIL_UNSOL_NITZ_TIME_RECEIVED:
				po.writeString((String) obj);
				break;
			case RIL_UNSOL_SIGNAL_STRENGTH:
				writeSignalStrength(po, (int[]) obj);
				break;
			case RIL_UNSOL_DATA_CALL_LIST_CHANGED:
				writeDataCallList(po, (List<DataCallState>) obj);
				break;
			case RIL_UNSOL_SUPP_SVC_NOTIFICATION:
				// FIXME ret = responseSuppServiceNotification(p);
				break;
			case RIL_UNSOL_STK_SESSION_END:
				break;
			case RIL_UNSOL_STK_PROACTIVE_COMMAND:
				po.writeString((String) obj);
				break;
			case RIL_UNSOL_STK_EVENT_NOTIFY:
				po.writeString((String) obj);
				break;
			case RIL_UNSOL_STK_CALL_SETUP:
				po.writeIntArray((int[]) obj);
				break;
			case RIL_UNSOL_SIM_SMS_STORAGE_FULL:
				break;
			case RIL_UNSOL_SIM_REFRESH:
				// FIXME ret = responseSimRefresh(p);
				break;
			case RIL_UNSOL_CALL_RING:
				// ret = responseCallRing(p);
				break;
			case RIL_UNSOL_RESTRICTED_STATE_CHANGED:
				po.writeIntArray((int[]) obj);
				break;
			case RIL_UNSOL_RESPONSE_SIM_STATUS_CHANGED:
				break;
			case RIL_UNSOL_RESPONSE_CDMA_NEW_SMS:
				// ret = responseCdmaSms(p);
				break;
			case RIL_UNSOL_RESPONSE_NEW_BROADCAST_SMS:
				writeRaw(po, (byte[]) obj);
				break;
			case RIL_UNSOL_CDMA_RUIM_SMS_STORAGE_FULL:
				break;
			case RIL_UNSOL_ENTER_EMERGENCY_CALLBACK_MODE:
				break;
			case RIL_UNSOL_CDMA_CALL_WAITING:
				// ret = responseCdmaCallWaiting(p);
				break;
			case RIL_UNSOL_CDMA_OTA_PROVISION_STATUS:
				po.writeIntArray((int[]) obj);
				break;
			case RIL_UNSOL_CDMA_INFO_REC:
				// ret = responseCdmaInformationRecord(p);
				break;
			case RIL_UNSOL_OEM_HOOK_RAW:
				writeRaw(po, (byte[]) obj);
				break;
			case RIL_UNSOL_RINGBACK_TONE:
				po.writeIntArray((int[]) obj);
				break;
			case RIL_UNSOL_RESEND_INCALL_MUTE:
				break;
			case RIL_UNSOL_CDMA_SUBSCRIPTION_SOURCE_CHANGED:
				po.writeIntArray((int[]) obj);
				break;
			case RIL_UNSOL_CDMA_PRL_CHANGED:
				po.writeIntArray((int[]) obj);
				break;
			case RIL_UNSOL_EXIT_EMERGENCY_CALLBACK_MODE:
				break;
			case RIL_UNSOL_RIL_CONNECTED:
				po.writeIntArray((int[]) obj);
				break;
			case RIL_UNSOL_VOICE_RADIO_TECH_CHANGED:
				po.writeIntArray((int[]) obj);
				break;
			// samsung stk service implementation
			case RIL_UNSOL_STK_SEND_SMS_RESULT:
				po.writeIntArray((int[]) obj);
				break;

			default:
				throw new RuntimeException("Unrecognized unsol response: "
						+ response);
				// break; (implied)
			}
		} catch (Throwable tr) {
			Log.e(LOG_TAG, "Exception processing unsol response: " + response,
					tr);
			return;
		}
		// Log.i(LOG_TAG, "Sending to socket " + po);
		mSocket.send(po);
	}

	// //////////////////77

	public ATCommandsImpl getCI() {
		return mCI;
	}

	public void setCI(ATCommandsImpl mCI) {
		this.mCI = mCI;
	}

	public RILSocket getSocket() {
		return mSocket;
	}

	public void setSocket(RILSocket mSocket) {
		this.mSocket = mSocket;
	}

	public void setStatusHandler(Handler handler) {
		this.mStatusHandler = handler;

	}
}
