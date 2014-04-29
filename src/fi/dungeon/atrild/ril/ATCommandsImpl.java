package fi.dungeon.atrild.ril;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import fi.dungeon.atrild.ril.RILProtocolHelper.FailCause;
import fi.dungeon.atrild.ril.RILProtocolHelper.RadioState;
import fi.dungeon.atrild.ril.RILProtocolHelper.RadioTechnology;
import fi.dungeon.atrild.ril.RILProtocolHelper.SIM_Status;

import reloc.com.android.internal.telephony.CallForwardInfo;
import reloc.com.android.internal.telephony.CommandException;
import reloc.com.android.internal.telephony.DataCallState;
import reloc.com.android.internal.telephony.DriverCall;
import reloc.com.android.internal.telephony.IccCardApplication;
import reloc.com.android.internal.telephony.IccCardStatus;
import reloc.com.android.internal.telephony.IccIoResult;
import reloc.com.android.internal.telephony.OperatorInfo;
import reloc.com.android.internal.telephony.RILConstants;
import reloc.com.android.internal.telephony.SmsResponse;
import reloc.com.android.internal.telephony.UUSInfo;
import reloc.com.android.internal.telephony.CommandException.Error;
import reloc.com.android.internal.telephony.DriverCall.State;
import reloc.com.android.internal.telephony.IccCardStatus.PinState;
import reloc.com.android.internal.telephony.gsm.SmsBroadcastConfigInfo;

import android.os.Handler;
import android.os.Message;
import android.telephony.NeighboringCellInfo;
import android.telephony.SmsManager;
import android.util.Log;

public class ATCommandsImpl {
	static final boolean RILDJ_LOGD = true;
	static final boolean RILDJ_LOGV = false; // STOP SHIP if true
	static final String LOG_TAG = "ATRILDJ";

	private int HANDSHAKE_RETRY_COUNT = 3;
	private long HANDSHAKE_TIMEOUT_MSEC = 2000;

	private boolean gsm = true;

	private Handler mDefaultHandler;
	private ATDevice at;
	private PPPDMonitor mPPPDMonitor;
	private RadioState mRadioState = RadioState.OFF;
	private FailCause callFailCause = FailCause.NONE;
	private String mIdentity = "";
	private String initializationCommands;

	private void setRadioState(RadioState state) {
		Log.d(LOG_TAG, "RadioState " + mRadioState + " -> " + state);
		if (mRadioState != state) {
			mRadioState = state;
			Message msg = mDefaultHandler.obtainMessage(
					RILD.EVENT_AT_RESPONSE_UNSOLICITED,
					RILConstants.RIL_UNSOL_RESPONSE_RADIO_STATE_CHANGED, 0,
					state.getIntValue());
			msg.sendToTarget();
			if (state == RadioState.SIM_READY) {
				onSIMReady();
			} else if (state == RadioState.SIM_NOT_READY) {
				onRadioPowerOn();
			}
		}
	}

	public void initialize() {

		callFailCause = FailCause.NONE;

		at_handshake();
		
		if (initializationCommands != null) {
			for (String s: initializationCommands.split("\n+")) {
				s = s.trim();
				if (s.length() == 0) {
					continue;
				}
				if (s.startsWith("-")) {
					try {
						at_send_command(s.substring(1));
					} catch (CommandException e) {
						// Ignore
					}
				} else {
					at_send_command(s);
				}
			}
		}
		
		try {
			List<String> id = at_send_command_multiline("ATI", "");
			Log.i(LOG_TAG, "Identity " + id);
			mIdentity = "";
			for (String s : id) {
				mIdentity = mIdentity + s + "\n";
			}
		} catch (CommandException e) {
		}

		/*
		 * note: we don't check errors here. Everything important will be
		 * handled in onATTimeout and onATReaderClosed
		 */

		/* atchannel is tolerant of echo but it must */
		/* have verbose result codes */
		at_send_command("ATE0Q0V1");

		/* No auto-answer */
		at_send_command("ATS0=0");

		/* Extended errors */
		at_send_command("AT+CMEE=1");

		/* Network registration events */
		try {
			at_send_command("AT+CREG=2");
		} catch (CommandException e) {
			/* some handsets -- in tethered mode -- don't support CREG=2 */
			at_send_command("AT+CREG=1");
		}

		/* GPRS registration events */
		at_send_command("AT+CGREG=2");

		/* Call Waiting notifications */
		at_send_command("AT+CCWA=1");

		/* Alternating voice/data off */
		at_send_command("AT+CMOD=0");

		/* Not muted */
		try {
			at_send_command("AT+CMUT=0");
		} catch (CommandException e) {
		}
		/* +CSSU unsolicited supp service notifications */
		at_send_command("AT+CSSN=0,1");

		/* no connected line identification */
		at_send_command("AT+COLP=0");

		/* HEX character set */
		try {
			at_send_command("AT+CSCS=\"HEX\"");
		} catch (CommandException e) {
		}

		/* USSD unsolicited */
		at_send_command("AT+CUSD=1");

		/* Enable +CGEV GPRS event notifications, but don't buffer */
		at_send_command("AT+CGEREP=1,0");

		/* SMS PDU mode */
		at_send_command("AT+CMGF=0");

		/* assume radio is off on error */
		/*
		 * if (isRadioOn() > 0) { setRadioState (RADIO_STATE_SIM_NOT_READY); }
		 */
		try {
			String supported = at_send_command_singleline("AT+CLAC", "+CLAC:");
			Log.i(LOG_TAG, "Supported commands " + supported);
		} catch (CommandException e) {
		}

	}

	public void onAtConnected() {
		Message msg = Message.obtain(mDefaultHandler, RILD.EVENT_AT_CONNECTED);
		msg.sendToTarget();
		mIdentity = "Unknown";
	}

	public void onAtDisconnected() {
		Message msg = Message.obtain(mDefaultHandler,
				RILD.EVENT_AT_DISCONNECTED);
		msg.sendToTarget();
		mIdentity = "";
		setRadioState(RadioState.OFF);
	}

	public void onUnsolicited(String line) {

		int response = -1;
		String str = "";

		Log.d(LOG_TAG, "processUnsolicited: '" + line + "'");

		if (line.startsWith("%CTZV:")) {
			/* TI specific -- NITZ time */
			line = line.substring(line.indexOf(':') + 1).trim();
			if ("".equals(line)) {
				Log.e(LOG_TAG, "invalid NITZ line " + line);
				return;
			} else {
				response = RILConstants.RIL_UNSOL_NITZ_TIME_RECEIVED;
				str = line;
			}
		} else if (line.startsWith("+CRING:") || line.startsWith("RING")
				|| line.startsWith("NO CARRIER") || line.startsWith("+CCWA")) {
			response = RILConstants.RIL_UNSOL_RESPONSE_CALL_STATE_CHANGED;
			Message msg = Message.obtain(mDefaultHandler,
					RILD.EVENT_TIMED_CALLBACK, RILD.onDataCallListChanged, 0);
			msg.sendToTarget();
		} else if (line.startsWith("+CREG:") || line.startsWith("+CGREG:")) {
			response = RILConstants.RIL_UNSOL_RESPONSE_VOICE_NETWORK_STATE_CHANGED;
			Message msg = Message.obtain(mDefaultHandler,
					RILD.EVENT_TIMED_CALLBACK, RILD.onDataCallListChanged, 0);
			msg.sendToTarget();
		} else if (line.startsWith("+CMT:")) {
			response = RILConstants.RIL_UNSOL_RESPONSE_NEW_SMS;
			String[] strs = line.split("\n", 2);
			str = strs[1];
		} else if (line.startsWith("+CDS:")) {
			response = RILConstants.RIL_UNSOL_RESPONSE_NEW_SMS_STATUS_REPORT;
			String[] strs = line.split("\n", 2);
			str = strs[1];
		} else if (line.startsWith("+CGEV:")) {
			/*
			 * Really, we can ignore NW CLASS and ME CLASS events here, but
			 * right now we don't since extranous
			 * RIL_UNSOL_DATA_CALL_LIST_CHANGED calls are tolerated
			 */
			/* can't issue AT commands here -- call on main thread */
			Message msg = Message.obtain(mDefaultHandler,
					RILD.EVENT_TIMED_CALLBACK, RILD.onDataCallListChanged, 0);
			msg.sendToTarget();
		} else if (line.startsWith("+CME ERROR: 150")) {
			Message msg = Message.obtain(mDefaultHandler,
					RILD.EVENT_TIMED_CALLBACK, RILD.onDataCallListChanged, 0);
			msg.sendToTarget();
		} else {
			Log.e(LOG_TAG, "Unknown unsolicited AT response '" + line + "'");
			return;
		}
		if (response > 0) {
			Message msg = Message.obtain(mDefaultHandler,
					RILD.EVENT_AT_RESPONSE_UNSOLICITED, response, 0, str);
			Log.d(LOG_TAG, "Sen unsol msg " + msg);
			msg.sendToTarget();
		}
	}

	private void onSIMReady() {
		Log.d(LOG_TAG, "onSIMReady");
		/* Common initialization commands */

		/* Network registration */
		at_send_command("AT+COPS=0");

		if (gsm) {
			/* Preferred RAT - UMTS Dualmode */
			// at_send_command("AT+XRAT=1,2");

			// debug what type of sim is it?
			// at_send_command("AT+SIMTYPE");

			/*
			 * Always send SMS messages directly to the TE
			 * 
			 * mode = 1 // discard when link is reserved (link should never be
			 * reserved) mt = 2 // most messages routed to TE bm = 2 // new cell
			 * BM's routed to TE ds = 1 // Status reports routed to TE bfr = 1
			 * // flush buffer
			 */
			at_send_command("AT+CNMI=1,2,2,1,1");

			at_send_command("AT+CSCB=1");

			/* Enable +CGEV GPRS event notifications, but don't buffer */
			// at_send_command("AT+CGEREP=1,0");

			/* Enable NITZ reporting */
			at_send_command("AT+CTZU=1");
			at_send_command("AT+CTZR=1");
			// at_send_command("AT+HTCCTZR=1");

			/* Enable unsolizited RSSI reporting */
			// at_send_command("AT@HTCCSQ=1");

			at_send_command_singleline("AT+CSMS=1", "+CSMS:");
		} else {

			// at_send_command("AT+HTC_GPSONE=4");
			at_send_command("AT+CLVL=5");
			at_send_command("AT+CLVL=4");
		}

	}

	private void onRadioPowerOn() {
		/** do post-AT+CFUN=1 initialization */
		Log.d(LOG_TAG, "onRadioPowerOn");
		if (gsm) {
			// sleep(10);
			at_send_command("ATE0");
			at_send_command("AT+CLIP=1");
			at_send_command("AT+CLIR=0");
			// at_send_command("AT+CPPP=2");
			// at_send_command("AT+HTCNV=1,12,6");

			/* enable ENS mode, okay to fail */
			// at_send_command("AT+HTCENS=1");
			// at_send_command("AT+HSDPA=1");
			// at_send_command("AT+HTCAGPS=5");
			at_send_command("AT");
			// at_send_command("AT+ODEN=112");
			// at_send_command("AT+ODEN=911");
			// at_send_command("AT+ALS=4294967295");
		}
		pollSIMState();
		// FIXME timer
	}

	public void pollSIMState() {
		Log.d(LOG_TAG, "pollSIMState");

		if (mRadioState != RadioState.SIM_NOT_READY) {
			// no longer valid to poll
			return;
		}
		if (!gsm) {
			setRadioState(RadioState.SIM_READY);
			return;
		}

		switch (getSIMStatus()) {
		case SIM_ABSENT:
		case SIM_PIN:
		case SIM_PUK:
		case SIM_NETWORK_PERSONALIZATION:
		default:
			setRadioState(RadioState.SIM_LOCKED_OR_ABSENT);
			return;

		case SIM_NOT_READY:
			Message msg = Message.obtain(mDefaultHandler,
					RILD.EVENT_TIMED_CALLBACK, RILD.pollSIMState, 0, null);
			mDefaultHandler.sendMessageDelayed(msg, RILD.TIMEVAL_SIMPOLL);
			return;

		case SIM_READY:
			setRadioState(RadioState.SIM_READY);
			return;
		}
	}

	// ***** CommandsInterface implementation

	public int[] getVoiceRadioTechnology() {
		// RIL_REQUEST_VOICE_RADIO_TECH
		int result = -1;
		// FIXME Huawei specific
		String line = at_send_command_singleline("AT^SYSINFO", "^SYSINFO:");
		String[] tokens = line.split(",");
		switch (Integer.parseInt(tokens[3])) {
		case 0: // No service
			break;
		case 1: // GSM
			result = RadioTechnology.RADIO_TECH_GSM.ordinal();
			break;
		case 2: // GPRS
			result = RadioTechnology.RADIO_TECH_GPRS.ordinal();
			break;
		case 3: // EDGE
			result = RadioTechnology.RADIO_TECH_EDGE.ordinal();
			break;
		case 4: // WCDMA
			result = RadioTechnology.RADIO_TECH_UMTS.ordinal();
			break;
		case 5: // HSDPA
			result = RadioTechnology.RADIO_TECH_HSDPA.ordinal();
			break;
		case 6: // HSUPA
			result = RadioTechnology.RADIO_TECH_HSUPA.ordinal();
			break;
		case 7: // HSDPA/HSUPA
			result = RadioTechnology.RADIO_TECH_HSUPA.ordinal();
			break;
		}
		return new int[] { result };
	}

	/** Returns SIM_NOT_READY on error */
	protected SIM_Status getSIMStatus() {
		ATResponse response = at.at_send_command("AT+CPIN?",
				ATCommandType.SINGLELINE, "+CPIN:");
		if (response.mStatus != ATRequestStatus.OK) {
			switch (response.at_get_cme_error()) {
			case ATResponse.CME_SUCCESS:
				break;

			case ATResponse.CME_SIM_NOT_INSERTED:
				return SIM_Status.SIM_ABSENT;

			default:
				return SIM_Status.SIM_NOT_READY;
			}
		}
		if (response.mIntermediates.size() < 1) {
			Log.e(LOG_TAG, "No response");
			return SIM_Status.SIM_NOT_READY;
		}
		if (response.mIntermediates.size() > 1) {
			Log.e(LOG_TAG, "Too many responses");
			return SIM_Status.SIM_NOT_READY;
		}
		/* CPIN? has succeeded, now look at the result */
		String line = response.mIntermediates.get(0).trim();
		if ("SIM PIN".equals(line)) {
			return SIM_Status.SIM_PIN;
		} else if ("SIM_PUK".equals(line)) {
			return SIM_Status.SIM_PUK;
		} else if ("PH-NET PIN".equals(line)) {
			return SIM_Status.SIM_NETWORK_PERSONALIZATION;
		} else if ("READY".equals(line)) {
			return SIM_Status.SIM_READY;
		} else {
			/* we're treating unsupported lock types as "sim absent" */
			return SIM_Status.SIM_ABSENT;
		}
	}

	static IccCardApplication[] app_status_array = {
			// SIM_ABSENT = 0
			new IccCardApplication() {
				{
					app_type = AppType.APPTYPE_UNKNOWN;
					app_state = AppState.APPSTATE_UNKNOWN;
					perso_substate = PersoSubState.PERSOSUBSTATE_UNKNOWN;
					pin1 = PinState.PINSTATE_UNKNOWN;
					pin2 = PinState.PINSTATE_UNKNOWN;
				}
			},
			// SIM_NOT_READY = 1
			new IccCardApplication() {
				{
					app_type = AppType.APPTYPE_SIM;
					app_state = AppState.APPSTATE_DETECTED;
					perso_substate = PersoSubState.PERSOSUBSTATE_UNKNOWN;
					pin1 = PinState.PINSTATE_UNKNOWN;
					pin2 = PinState.PINSTATE_UNKNOWN;
				}
			},
			// SIM_READY = 2
			new IccCardApplication() {
				{
					app_type = AppType.APPTYPE_SIM;
					app_state = AppState.APPSTATE_READY;
					perso_substate = PersoSubState.PERSOSUBSTATE_UNKNOWN;
					pin1 = PinState.PINSTATE_UNKNOWN;
					pin2 = PinState.PINSTATE_UNKNOWN;
				}
			},
			// SIM_PIN = 3
			new IccCardApplication() {
				{
					app_type = AppType.APPTYPE_SIM;
					app_state = AppState.APPSTATE_PIN;
					perso_substate = PersoSubState.PERSOSUBSTATE_UNKNOWN;
					pin1 = PinState.PINSTATE_ENABLED_NOT_VERIFIED;
					pin2 = PinState.PINSTATE_UNKNOWN;
				}
			},
			// SIM_PUK = 4
			new IccCardApplication() {
				{
					app_type = AppType.APPTYPE_SIM;
					app_state = AppState.APPSTATE_PUK;
					perso_substate = PersoSubState.PERSOSUBSTATE_UNKNOWN;
					pin1 = PinState.PINSTATE_ENABLED_BLOCKED;
					pin2 = PinState.PINSTATE_UNKNOWN;
				}
			},
			// SIM_NETWORK_PERSONALIZATION = 5
			new IccCardApplication() {
				{
					app_type = AppType.APPTYPE_SIM;
					app_state = AppState.APPSTATE_SUBSCRIPTION_PERSO;
					perso_substate = PersoSubState.PERSOSUBSTATE_SIM_NETWORK;
					pin1 = PinState.PINSTATE_ENABLED_NOT_VERIFIED;
					pin2 = PinState.PINSTATE_UNKNOWN;
				}
			} };

	public IccCardStatus getIccCardStatus() {
		// RIL_REQUEST_GET_SIM_STATUS
		// Note: This RIL request has not been renamed to ICC,
		// but this request is also valid for SIM and RUIM
		IccCardStatus status = new IccCardStatus();
		status.setGsmUmtsSubscriptionAppIndex(IccCardStatus.CARD_MAX_APPS);
		status.setCdmaSubscriptionAppIndex(IccCardStatus.CARD_MAX_APPS);
		status.setImsSubscriptionAppIndex(IccCardStatus.CARD_MAX_APPS);
		status.setUniversalPinState(0); // PINSTATE_UNKNOWN

		SIM_Status sim_status = getSIMStatus();
		switch (sim_status) {
		case SIM_ABSENT:
			status.setCardState(0); // RIL_CARDSTATE_ABSENT;
			status.setNumApplications(0);
			break;
		case SIM_NOT_READY:
		case SIM_PIN:
		case SIM_PUK:
		case SIM_NETWORK_PERSONALIZATION:
		case SIM_READY:
			// Only support one app, gsm
			status.setCardState(1); // RIL_CARDSTATE_PRESENT;
			status.setNumApplications(1);
			status.setGsmUmtsSubscriptionAppIndex(0);
			status.addApplication(app_status_array[sim_status.ordinal()]);
			break;
		}
		return status;
	}

	public int[] supplyIccPinForApp(String pin, String aid) {
		// RIL_REQUEST_ENTER_SIM_PIN
		// Note: This RIL request has not been renamed to ICC,
		// but this request is also valid for SIM and RUIM
		return supplyIccPukForApp(pin, null, aid);
	}

	public int[] supplyIccPukForApp(String puk, String newPin, String naid) {
		// Note: This RIL request has not been renamed to ICC,
		// but this request is also valid for SIM and RUIM
		// RIL_REQUEST_ENTER_SIM_PUK
		if (!gsm) {
			setRadioState(RadioState.SIM_READY);
			return new int[0];
		}
		try {
			String str;
			if (newPin == null) {
				str = at_send_command_singleline("AT+CPIN=\"" + puk + "\"",
						"+CPIN:");
			} else {
				str = at_send_command_singleline("AT+CPIN=" + puk + ","
						+ newPin, "+CPIN:");
			}
			setRadioState(RadioState.SIM_READY);
		} catch (CommandException e) {
			// FIXME check
			throw new CommandException(Error.PASSWORD_INCORRECT);
		}
		return new int[0];
	}

	public int[] supplyIccPin2ForApp(String pin, String aid) {
		// Note: This RIL request has not been renamed to ICC,
		// but this request is also valid for SIM and RUIM
		// RIL_REQUEST_ENTER_SIM_PIN2
		return supplyIccPukForApp(pin, null, aid);
	}

	public int[] supplyIccPuk2ForApp(String puk, String newPin2, String aid) {
		// Note: This RIL request has not been renamed to ICC,
		// but this request is also valid for SIM and RUIM
		// RIL_REQUEST_ENTER_SIM_PUK2
		return supplyIccPukForApp(puk, newPin2, aid);
	}

	public int[] changeIccPinForApp(String oldPin, String newPin, String aid) {
		// Note: This RIL request has not been renamed to ICC,
		// but this request is also valid for SIM and RUIM
		// RIL_REQUEST_CHANGE_SIM_PIN
		return supplyIccPukForApp(oldPin, newPin, aid);
	}

	public int[] changeIccPin2ForApp(String oldPin2, String newPin2, String aid) {
		// Note: This RIL request has not been renamed to ICC,
		// but this request is also valid for SIM and RUIM
		// RIL_REQUEST_CHANGE_SIM_PIN2
		return supplyIccPukForApp(oldPin2, newPin2, aid);
	}

	public void changeBarringPassword(String facility, String oldPwd,
			String newPwd) {
		// RIL_REQUEST_CHANGE_BARRING_PASSWORD
		at_send_command("AT+CPWD=\"" + facility + "\",\"" + oldPwd + "\",\""
				+ newPwd + "\"");
	}

	public int[] supplyNetworkDepersonalization(String netpin) {
		// RIL_REQUEST_ENTER_NETWORK_DEPERSONALIZATION
		throw new CommandException(Error.REQUEST_NOT_SUPPORTED);
		// return new int[0];
	}

	protected State clccStateToRILState(int state) {
		switch (state) {
		case 0:
			return State.ACTIVE;
		case 1:
			return State.HOLDING;
		case 2:
			return State.DIALING; // MO call only
		case 3:
			return State.ALERTING; // MO call only
		case 4:
			return State.INCOMING; // MT call only
		case 5:
			return State.WAITING; // MT call only
		}
		throw new CommandException(Error.GENERIC_FAILURE);
	}

	protected DriverCall callFromCLCCLine(String line) {
		// +CLCC: 1,0,2,0,0,\"+18005551212\",145
		// index,isMT,state,mode,isMpty(,number,TOA)?
		DriverCall dc = new DriverCall();
		int err;
		int state;
		int mode;
		String[] tokens = line.split(",");
		if (tokens.length < 7) {
			throw new CommandException(Error.GENERIC_FAILURE);
		}
		dc.index = Integer.parseInt(tokens[0]);
		dc.isMT = Integer.parseInt(tokens[1]) != 0;
		state = Integer.parseInt(tokens[2]);
		dc.state = clccStateToRILState(Integer.parseInt(tokens[3]));
		mode = Integer.parseInt(tokens[4]);
		dc.isVoice = (mode == 0);
		dc.isMpty = Integer.parseInt(tokens[6]) != 0;
		if (tokens.length > 7) {
			dc.number = tokens[7];
			/* FIXME tolerate null here */
		}
		if (tokens.length > 8) {
			// Some lame implementations return strings
			// like "NOT AVAILABLE" in the CLCC line
			if (Character.isDigit(tokens[8].charAt(0))
					|| tokens[8].charAt(0) == '+') {
				dc.TOA = Integer.parseInt(tokens[8]);
			} else {
				if (tokens.length > 9) {
					dc.TOA = Integer.parseInt(tokens[9]);
				}
			}
		}
		return dc;
	}

	public List<DriverCall> getCurrentCalls() {
		// RIL_REQUEST_GET_CURRENT_CALLS
		List<DriverCall> calls = new ArrayList<DriverCall>();

		List<String> lines = at_send_command_multiline("AT+CLCC", "+CLCC:");
		for (String s : lines) {
			DriverCall call = callFromCLCCLine(s);
			calls.add(call);
		}
		return calls;
	}

	public List<DataCallState> getDataCallList() {
		// RIL_REQUEST_DATA_CALL_LIST
		List<DataCallState> calls = new ArrayList<DataCallState>();
		List<String> lines = at_send_command_multiline("AT+CGACT?", "+CGACT:");
		// +CGACT: <cid>,<state>[...]]
		// +CGACT: 1,0
		for (String s : lines) {
			DataCallState call = new DataCallState();
			String[] tokens = s.split(",");
			call.cid = Integer.parseInt(tokens[0]);
			call.active = Integer.parseInt(tokens[1]);
			call.ifname = mPPPDMonitor.getInterfaceName(call.cid);
			//
			calls.add(call);
		}

		lines = at_send_command_multiline("AT+CGDCONT?", "+CGDCONT:");
		// +CGDCONT: <cid>,<pdp_type>,<apn>,<pdp_address>,<d_comp>,<h_comp>
		// ,<pd1>,<pd2>,<pd3>,<pd4>,<pd5>,<pd6>
		// +CGDCONT: 1,"IP","SONERA","0.0.0.0",0,0
		for (String s : lines) {
			String[] tokens = s.split(",");
			int cid = Integer.parseInt(tokens[0]);
			for (DataCallState call : calls) {
				if (call.cid == cid) {
					// Assume no error
					call.status = 0;
					call.type = unquote(tokens[1]);
					// APN ignored for v5
					call.addresses = new String[] { unquote(tokens[3]) };
					if ("0.0.0.0".equals(call.addresses)) {
						// get address from ppp0 interface
						List<String> addrs = mPPPDMonitor
								.getInterfaceAddresses(call.ifname);
						if (addrs.size() > 0) {
							call.addresses = addrs.toArray(new String[0]);
						}
					}
					/*
					 * I don't know where we are, so use the public Google DNS
					 * servers by default and no gateway.
					 */
					call.dnses = mPPPDMonitor.getDNSAddresses(call.ifname);
					call.gateways = mPPPDMonitor.getGateways(call.ifname);
				}
			}
		}

		return calls;
	}

	public void dial(String address, int clirMode, UUSInfo uusInfo) {
		// RIL_REQUEST_DIAL
		callFailCause = FailCause.NONE;
		String clir;
		switch (clirMode) {
		case 1:
			clir = "I";
			break; /* invocation */
		case 2:
			clir = "i";
			break; /* suppression */
		default:
		case 0:
			clir = "";
			break; /* subscription default */
		}
		try {
			at_send_command("ATD" + address + clir);
		} catch (CommandException e) {
			Log.i(LOG_TAG, "Dial failed ", e);
		}
		/*
		 * success or failure is ignored by the upper layer here. it will call
		 * GET_CURRENT_CALLS and determine success that way
		 */
	}

	public String getIMSIForApp(String aid) {
		// RIL_REQUEST_GET_IMSI
		return at_send_command_numeric("AT+CIMI");
	}

	public String getIMEI() {
		// RIL_REQUEST_GET_IMEI
		return at_send_command_numeric("AT+CGSN");
	}

	public String getIMEISV() {
		// RIL_REQUEST_GET_IMEISV
		// FIXME check
		return at_send_command_numeric("AT+CGSN");
	}

	public void hangupConnection(int gsmIndex) {
		// RIL_REQUEST_HANGUP
		// 3GPP 22.030 6.5.5
		// "Releases a specific active call X"
		at_send_command("AT+CHLD=1" + gsmIndex);
		/*
		 * success or failure is ignored by the upper layer here. it will call
		 * GET_CURRENT_CALLS and determine success that way
		 */
	}

	public void hangupWaitingOrBackground() {
		// RIL_REQUEST_HANGUP_WAITING_OR_BACKGROUND
		// 3GPP 22.030 6.5.5
		// "Releases all held calls or sets User Determined User Busy
		// (UDUB) for a waiting call."
		at_send_command("AT+CHLD=0");

		/*
		 * success or failure is ignored by the upper layer here. it will call
		 * GET_CURRENT_CALLS and determine success that way
		 */
	}

	public void hangupForegroundResumeBackground() {
		// RIL_REQUEST_HANGUP_FOREGROUND_RESUME_BACKGROUND
		// 3GPP 22.030 6.5.5
		// "Releases all active calls (if any exist) and accepts
		// the other (held or waiting) call."
		at_send_command("AT+CHLD=1");

		/*
		 * success or failure is ignored by the upper layer here. it will call
		 * GET_CURRENT_CALLS and determine success that way
		 */
	}

	public void switchWaitingOrHoldingAndActive() {
		// RIL_REQUEST_SWITCH_WAITING_OR_HOLDING_AND_ACTIVE
		// 3GPP 22.030 6.5.5
		// "Places all active calls (if any exist) on hold and accepts
		// the other (held or waiting) call."
		at_send_command("AT+CHLD=2");
	}

	public void conference() {
		// RIL_REQUEST_CONFERENCE
		// 3GPP 22.030 6.5.5
		// "Adds a held call to the conversation"
		at_send_command("AT+CHLD=3");

		/*
		 * success or failure is ignored by the upper layer here. it will call
		 * GET_CURRENT_CALLS and determine success that way
		 */
	}

	public void setPreferredVoicePrivacy(boolean enable) {
		// RIL_REQUEST_CDMA_SET_PREFERRED_VOICE_PRIVACY_MODE
		throw new CommandException(Error.REQUEST_NOT_SUPPORTED);
	}

	public int[] getPreferredVoicePrivacy() {
		// RIL_REQUEST_CDMA_QUERY_PREFERRED_VOICE_PRIVACY_MODE
		throw new CommandException(Error.REQUEST_NOT_SUPPORTED);
	}

	public void separateConnection(int gsmIndex) {
		// RIL_REQUEST_SEPARATE_CONNECTION
		if (gsmIndex > 0 && gsmIndex < 10) {
			at_send_command("AT+CHLD=2" + gsmIndex);
		} else {
			throw new CommandException(Error.GENERIC_FAILURE);
		}
	}

	public void acceptCall() {
		// RIL_REQUEST_ANSWER
		/*
		 * success or failure is ignored by the upper layer here. it will call
		 * GET_CURRENT_CALLS and determine success that way
		 */
		at_send_command("ATA");
	}

	public void rejectCall() {
		// RIL_REQUEST_UDUB
		/* user determined user busy */
		/* sometimes used: ATH */
		at_send_command("ATH");

		/*
		 * success or failure is ignored by the upper layer here. it will call
		 * GET_CURRENT_CALLS and determine success that way
		 */
	}

	public void explicitCallTransfer() {
		// RIL_REQUEST_EXPLICIT_CALL_TRANSFER,
		at_send_command("AT+CHLD=4");
	}

	public int[] getLastCallFailCause() {
		// RIL_REQUEST_LAST_CALL_FAIL_CAUSE,
		if (callFailCause != FailCause.NONE) {
			return new int[] { callFailCause.getErrorCode() };
		}
		String line = at_send_command_singleline("AT+CEER", "+CEER:");
		String[] tokens = line.split(",");
		if (tokens.length < 2) {
			throw new CommandException(Error.GENERIC_FAILURE);
		}
		return new int[] { Integer.parseInt(tokens[1]) };
	}

	/**
	 * The preferred new alternative to getLastPdpFailCause
	 */
	public int[] getLastDataCallFailCause() {
		// RIL_REQUEST_LAST_DATA_CALL_FAIL_CAUSE
		return getLastCallFailCause();
	}

	public void setMute(boolean enableMute) {
		// RIL_REQUEST_SET_MUTE
		at_send_command("AT+CMUT=" + (enableMute ? "1" : "0"));
	}

	public int[] getMute() {
		// RIL_REQUEST_GET_MUTE
		String line;
		if (!gsm) {
			line = at_send_command_singleline("AT+CMUT?", "+CMUT:");
		} else {
			line = at_send_command_singleline("AT+MUT", "+CMUT:");
		}
		return new int[] { Integer.parseInt(line) };
	}

	public int[] getSignalStrength() {
		// RIL_REQUEST_SIGNAL_STRENGTH
		String str = at_send_command_singleline("AT+CSQ", "+CSQ:");
		int[] ret = new int[] { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 };
		int i = 0;
		for (String s : str.split(",", 12)) {
			ret[i++] = Integer.parseInt(s);
		}
		return ret;
	}

	public String[] parseRegistrationState(String line) {
		int count = 3;
		int response[] = new int[] { -1, -1, -1, -1 };
		String responseStr[] = new String[4];

		/*
		 * Ok you have to be careful here The solicited version of the CREG
		 * response is +CREG: n, stat, [lac, cid] and the unsolicited version is
		 * +CREG: stat, [lac, cid] The <n> parameter is basically
		 * "is unsolicited creg on?" which it should always be
		 * 
		 * Now we should normally get the solicited version here, but the
		 * unsolicited version could have snuck in so we have to handle both
		 * 
		 * Also since the LAC and CID are only reported when registered, we can
		 * have 1, 2, 3, or 4 arguments here
		 * 
		 * finally, a +CGREG: answer may have a fifth value that corresponds to
		 * the network type, as in;
		 * 
		 * +CGREG: n, stat [,lac, cid [,networkType]]
		 */
		String[] tokens = line.split(",");

		switch (tokens.length) {
		case 1: /* +CREG: <stat> */
			response[0] = Integer.parseInt(tokens[0]);
			break;

		case 2: /* +CREG: <n>, <stat> */
			// skip first
			response[0] = Integer.parseInt(tokens[1]);
			break;

		case 3: /* +CREG: <stat>, <lac>, <cid> */
			response[0] = Integer.parseInt(tokens[0]);
			response[1] = Integer.parseInt(tokens[1]);
			response[2] = (int)Long.parseLong(unquote(tokens[2]), 16);
			break;
		case 4: /* +CREG: <n>, <stat>, <lac>, <cid> */
			// skip first
			response[0] = Integer.parseInt(tokens[1]);
			response[1] = (int)Long.parseLong(unquote(tokens[2]), 16);
			response[2] = (int)Long.parseLong(unquote(tokens[3]), 16);
			break;
		/*
		 * special case for CGREG, there is a fourth parameter that is the
		 * network type (unknown/gprs/edge/umts)
		 */
		case 5: /* +CGREG: <n>, <stat>, <lac>, <cid>, <networkType> */
			// skip first
			response[0] = Integer.parseInt(tokens[1]);
			response[1] = (int)Long.parseLong(unquote(tokens[2]), 16);
			response[2] = (int)Long.parseLong(unquote(tokens[3]), 16);
			response[3] = (int)Long.parseLong(unquote(tokens[3]), 16);
			count = 4;
			break;
		default:
			throw new CommandException(Error.GENERIC_FAILURE);
		}
		responseStr[0] = response[0] + "";
		responseStr[1] = Integer.toHexString(response[1]);
		responseStr[2] = Integer.toHexString(response[2]);

		if (count > 3) {
			responseStr[3] = Integer.toHexString(response[3]);
		}
		return responseStr;
	}

	public String[] getVoiceRegistrationState() {
		// RIL_REQUEST_VOICE_REGISTRATION_STATE
		if (gsm) {
			String line = at_send_command_singleline("AT+CREG?", "+CREG:");
			String[] result = parseRegistrationState(line);
			if (result[3] == null) {
				// FIXME Huawei specific
				line = at_send_command_singleline("AT^SYSINFO", "^SYSINFO:");
				String[] tokens = line.split(",");
				// D/AT ( 98): AT> AT^SYSINFO
				// D/AT ( 98): AT< ^SYSINFO:2,2,0,5,1,,4
				// D/RIL ( 98): home network
				switch (Integer.parseInt(tokens[6])) {
				case 0: // No service
					break;
				case 1: // GSM
					result[3] = "" + RadioTechnology.RADIO_TECH_GSM.ordinal();
					break;
				case 2: // GPRS
					result[3] = "" + RadioTechnology.RADIO_TECH_GPRS.ordinal();
					break;
				case 3: // EDGE
					result[3] = "" + RadioTechnology.RADIO_TECH_EDGE.ordinal();
					break;
				case 4: // WCDMA
					result[3] = "" + RadioTechnology.RADIO_TECH_UMTS.ordinal();
					break;
				case 5: // HSDPA
					result[3] = "" + RadioTechnology.RADIO_TECH_HSDPA.ordinal();
					break;
				case 6: // HSUPA
					result[3] = "" + RadioTechnology.RADIO_TECH_HSUPA.ordinal();
					break;
				case 7: // HSDPA/HSUPA
					result[3] = "" + RadioTechnology.RADIO_TECH_HSUPA.ordinal();
					break;
				}
			}
			return result;
		} else {
			String line = at_send_command_singleline("AT+HTC_GETSYSTYPE=0",
					"+HTC_GETSYSTYPE:");
			int cdma_systype = Integer.parseInt(line);
			line = at_send_command_singleline("AT+CREG?", "+CREG:");
			if (cdma_systype == 3)
				cdma_systype = 9;
			if (cdma_systype == 2)
				cdma_systype = 3;
			String[] response = parseRegistrationState(line);
			response[3] = "" + cdma_systype;
			return response;
		}
	}

	public String[] getDataRegistrationState() {
		// RIL_REQUEST_DATA_REGISTRATION_STATE,
		if (gsm) {
			String line = at_send_command_singleline("AT+CGREG?", "+CGREG:");
			String[] result = parseRegistrationState(line);
			if (result[3] == null) {
				// FIXME Huawei specific
				line = at_send_command_singleline("AT^SYSINFO", "^SYSINFO:");
				String[] tokens = line.split(",");
				// D/AT ( 98): AT> AT^SYSINFO
				// D/AT ( 98): AT< ^SYSINFO:2,2,0,5,1,,4
				// D/RIL ( 98): home network
				switch (Integer.parseInt(tokens[3])) {
				case 0: // No service
					break;
				case 1: // GSM
					result[3] = "" + RadioTechnology.RADIO_TECH_GSM.ordinal();
					break;
				case 2: // GPRS
					result[3] = "" + RadioTechnology.RADIO_TECH_GPRS.ordinal();
					break;
				case 3: // EDGE
					result[3] = "" + RadioTechnology.RADIO_TECH_EDGE.ordinal();
					break;
				case 4: // WCDMA
					result[3] = "" + RadioTechnology.RADIO_TECH_UMTS.ordinal();
					break;
				case 5: // HSDPA
					result[3] = "" + RadioTechnology.RADIO_TECH_HSDPA.ordinal();
					break;
				case 6: // HSUPA
					result[3] = "" + RadioTechnology.RADIO_TECH_HSUPA.ordinal();
					break;
				case 7: // HSDPA/HSUPA
					result[3] = "" + RadioTechnology.RADIO_TECH_HSUPA.ordinal();
					break;
				}
			}
			return result;
		} else {
			String line = at_send_command_singleline("AT+HTC_GETSYSTYPE=0",
					"+HTC_GETSYSTYPE:");
			int cdma_systype = Integer.parseInt(line);
			line = at_send_command_singleline("AT+CREG?", "+CREG:");
			if (cdma_systype == 3)
				cdma_systype = 9;
			if (cdma_systype == 2)
				cdma_systype = 3;
			String[] response = parseRegistrationState(line);
			response[3] = "" + cdma_systype;
			return response;
		}
	}

	public String[] getOperator() {
		// RIL_REQUEST_OPERATOR
		List<String> l = at_send_command_multiline(
				"AT+COPS=3,0;+COPS?;+COPS=3,1;+COPS?;+COPS=3,2;+COPS?",
				"+COPS:");
		/*
		 * we expect 3 lines here: +COPS: 0,0,"T - Mobile" +COPS: 0,1,"TMO"
		 * +COPS: 0,2,"310170"
		 */
		for (int i = 0; i < l.size(); i++) {
			String[] tokens = l.get(i).split(",");
			// If we're unregistered, we may just get
			// a "+COPS: 0" response
			// a "+COPS: 0, n" response is also possible
			if (tokens.length > 2) {
				l.set(i, unquote(tokens[2]));
			} else {
				l.set(i, "");
			}
		}
		return l.toArray(new String[0]);
	}

	public void sendDtmf(char c) {
		// RIL_REQUEST_DTMF
		at_send_command("AT+VTS=" + c);
	}

	public void startDtmf(char c) {
		// RIL_REQUEST_DTMF_START
		at_send_command("AT+CMUT=1");
		if (c == '*') {
			at_send_command("AT+WFSH");
		}
		at_send_command("AT+VTS=" + c);
	}

	public void stopDtmf() {
		// RIL_REQUEST_DTMF_STOP
		at_send_command("AT+CMUT=0");
	}

	public void sendBurstDtmf(String dtmfString, int on, int off) {
		// RIL_REQUEST_CDMA_BURST_DTMF
		if (gsm) {
			throw new CommandException(Error.REQUEST_NOT_SUPPORTED);
		}
	}

	public SmsResponse sendSMSExpectMore(String smscPDU, String pdu) {
		at_send_command("AT+CMMS=1");
		return sendSMS(smscPDU, pdu);
	}

	public SmsResponse sendSMS(String smscPDU, String pdu) {
		// RIL_REQUEST_SEND_SMS
		// "NULL for default SMSC"
		if (smscPDU == null) {
			smscPDU = "00";

			if (gsm) {
				String line = at_send_command_singleline("AT+CSCA?", "+CSCA:");
				String[] tokens = line.split(",");
				if (tokens.length < 2) {
					throw new CommandException(Error.GENERIC_FAILURE);
				}
				int tosca = Integer.parseInt(tokens[1]);
				String number = unquote(tokens[0]);
				if (number.charAt(0) == '+') {
					number = number.substring(1);
				}
				smscPDU = String.format("%.2x%.2x",
						(number.length() + 1) / 2 + 1, tosca);
				int i;
				for (i = 0; i < number.length() - 1; i += 2) {
					smscPDU += number.charAt(i + 1);
					smscPDU += number.charAt(i);
				}
				if (number.length() % 2 == 1) {
					// One extra number
					smscPDU += 'F';
					smscPDU += number.charAt(i);
				}
			}
		}

		if (!gsm) {
			// TODO cdma
			pdu = gsm_to_cdmapdu("00" + pdu);
			smscPDU = "";
		}
		String line = at_send_command_sms("AT+CMGS=" + pdu.length() / 2,
				"+CMGS:", smscPDU + pdu);
		String[] strs = line.split(",");
		int messageRef = Integer.parseInt(strs[0]);
		String ackPdu = null;
		int errorCode = 0;
		if (strs.length > 1) {
			ackPdu = strs[1];
		}
		// FIXME error handling
		SmsResponse sr = new SmsResponse(messageRef, ackPdu, errorCode);
		return sr;
	}

	public static byte[] hexStringToByteArray(String s) {
		int len = s.length();
		byte[] data = new byte[len / 2];
		for (int i = 0; i < len; i += 2) {
			data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4) + Character
					.digit(s.charAt(i + 1), 16));
		}
		return data;
	}

	private String gsm_to_cdmapdu(String str) {
		// FIXME this is probably wrong
		int i = 0;
		int is_vm = 0;
		String from = "000000";
		String message = "UNKNOWN";

		byte[] pdu = hexStringToByteArray(str);

		for (; i + 4 < pdu.length;) {
			int code = (pdu[i++] << 8) | pdu[i++];
			int length = (pdu[i++] << 8) | pdu[i++];
			if (code == 2) {
				// from
				from = decode_number(pdu, i, length);
			} else if (code == 8) {
				// bearer_data
				// message = decode_bearer_data(pdu, i, length, is_vm);
			}
		}

		if (is_vm != 0) {
			/* voicemail notifications must have a 4 byte address */
			if ((is_vm & 0x10) != 0) {
				/* set message waiting indicator */
				from = "1100";
			} else {
				/* clear message waiting indicator */
				from = "0100";
			}
		}
		// Naah
		// SmsAddressRec smsaddr;
		// SmsTimeStampRec smstime;
		// sms_address_from_str(&smsaddr,from,strlen(from));
		// if (is_vm != 0) {
		// /* voicemail notifications have a clear bottom nibble in toa
		// * and an alphanumeric address type */
		// smsaddr.toa = 0xd0;
		// }
		// sms_timestamp_now(&smstime);
		// SmsPDU *pdu=smspdu_create_deliver_utf8((const unsigned char
		// *)message,strlen(message),&smsaddr,&smstime);
		// //hexpdu=malloc(512);
		// char *s=hexpdu;
		// while(*pdu) {
		// smspdu_to_hex(*pdu, s,512);
		// hexpdus[i]=s;
		// s=s+strlen(s)+2;
		// smspdu_free(*pdu);
		// i++;
		// pdu++;
		// }
		// hexpdus[i]=0;
		// return hexpdus;

		return str;
	}

	int getbit(byte[] s, int offset, int b) {
		int i = b / 4;
		int bit = b % 4;

		int data = s[offset + i];
		return ((data & (1 << (3 - bit))) != 0) ? 1 : 0;
	}

	int getbits(byte[] s, int offset, int startbit, int nbits) {
		int val = 0;
		int i;
		for (i = 0; i < nbits; i++)
			val = val | (getbit(s, offset, startbit + i) << (nbits - i - 1));
		return val;
	}

	String decode_table = ".1234567890*#...";

	private String decode_number(byte[] pdu, int offset, int length) {
		// TODO Auto-generated method stub
		int ndigits = getbits(pdu, offset, 2, 8);
		int j;
		String no = "";
		for (j = 0; j < ndigits; j++)
			no += decode_table.charAt(getbits(pdu, offset, 10 + j * 4, 4));
		return no;
	}

	public SmsResponse sendCdmaSms(byte[] pdu) {
		// RIL_REQUEST_CDMA_SEND_SMS
		if (gsm) {
			throw new CommandException(Error.REQUEST_NOT_SUPPORTED);
		}
		return null;
	}

	public void deleteSmsOnSim(int index) {
		// RIL_REQUEST_DELETE_SMS_ON_SIM,
		at_send_command("AT+CMGD=" + index);
	}

	public void deleteSmsOnRuim(int index) {
		// RIL_REQUEST_CDMA_DELETE_SMS_ON_RUIM
		at_send_command("AT+CMGD=" + index);
	}

	public int[] writeSmsToSim(int status, String smsc, String pdu) {
		// RIL_REQUEST_WRITE_SMS_TO_SIM
		if (!gsm) {
			// FIXME CDMA
			throw new CommandException(Error.REQUEST_NOT_SUPPORTED);
		}
		String line = at_send_command_sms("AT+CMGW=" + (pdu.length() / 2) + ","
				+ status, "+CMGW:", pdu);
		return new int[0]; // FIXME ?
	}

	public int[] writeSmsToRuim(int status, String pdu) {
		// RIL_REQUEST_CDMA_WRITE_SMS_TO_RUIM,
		status = translateStatus(status);
		if (gsm) {
			throw new CommandException(Error.REQUEST_NOT_SUPPORTED);
		}
		return new int[0];
	}

	/**
	 * Translates EF_SMS status bits to a status value compatible with SMS AT
	 * commands. See TS 27.005 3.1.
	 */
	private int translateStatus(int status) {
		switch (status & 0x7) {
		case SmsManager.STATUS_ON_ICC_READ:
			return 1;
		case SmsManager.STATUS_ON_ICC_UNREAD:
			return 0;
		case SmsManager.STATUS_ON_ICC_SENT:
			return 3;
		case SmsManager.STATUS_ON_ICC_UNSENT:
			return 2;
		}

		// Default to READ.
		return 1;
	}

	public DataCallState setupDataCall(String radioTechnology, String profile,
			String apn, String user, String password, String authType,
			String protocol) {
		// RIL_REQUEST_SETUP_DATA_CALL
		Log.d(LOG_TAG, "Requesting data connection to APN '" + apn + "'");
		if (protocol == null) {
			protocol = "IP";
		}
		mPPPDMonitor.disconnect();
		callFailCause = FailCause.NONE;
		
		if (gsm) {

			at_send_command("AT+CGDCONT=1,\"" + protocol + "\",\"" + apn
					+ "\",,0,0");
			// Set required QoS params to default
			at_send_command("AT+CGQREQ=1");

			// Set minimum QoS params to default
			at_send_command("AT+CGQMIN=1");

			// packet-domain event reporting
			at_send_command("AT+CGEREP=1,0");

			// Hangup anything that's happening there now
			at_send_command("AT+CGACT=0,1");

			// Start data on PDP context 1
			at_send_command("ATD*99***1#");

			// at.at_send_command("+++", ATCommandType.NO_RESULT);
		} else {
			// CDMA
			at_send_command("AT+HTC_DUN=0");
			at_send_command("ATDT#777");
		}

		callFailCause = FailCause.CONNECTION_TO_DATACONNECTIONAC_BROKEN;
		try {
			mPPPDMonitor.connect();
		} catch (IOException e) {
			Log.e(LOG_TAG, "Failed to start pppd", e);
			throw new CommandException(Error.GENERIC_FAILURE);
		}
		callFailCause = FailCause.NONE;

		List<DataCallState> calls = getDataCallList();
		return calls.get(0); // FIXME check
	}

	public void deactivateDataCall(int cid, int reason) {
		// RIL_REQUEST_DEACTIVATE_DATA_CALL
		callFailCause = FailCause.NONE;
		mPPPDMonitor.disconnect();
		if (gsm) {
			at_send_command("AT+CGACT=0," + cid);
		} else {
			at_send_command("ATH");
		}
	}

	protected boolean isRadioOn() {
		String str = at_send_command_singleline("AT+CFUN=?", "+CFUN:");
		return Integer.parseInt(str) != 0;
	}

	public void setRadioPower(boolean on) {
		// RIL_REQUEST_RADIO_POWER
		if (on) {
			if (mRadioState == RadioState.OFF) {
				try {
					at_send_command("AT+CFUN=1");
				} catch (CommandException e) {
					/*
					 * Some stacks return an error when there is no SIM, but
					 * they really turn the RF portion on So, if we get an
					 * error, let's check to see if it turned on anyway
					 */
					if (isRadioOn() != on) {
						throw new CommandException(Error.ILLEGAL_SIM_OR_ME);
					}
				}
				setRadioState(RadioState.SIM_NOT_READY);
			}
		} else {
			if (mRadioState != RadioState.OFF) {
				if (gsm) {
					at_send_command("AT+CFUN=0");
				} else {
					at_send_command("AT+CFUN=66");
				}
				setRadioState(RadioState.OFF);
			}
		}

	}

	public void setSuppServiceNotifications(boolean enable) {
		// RIL_REQUEST_SET_SUPP_SVC_NOTIFICATION
		String e = enable ? "1" : "0";
		at_send_command("AT+CSSN=" + e + "," + e);
	}

	public void acknowledgeLastIncomingGsmSms(boolean success, int cause) {
		// RIL_REQUEST_SMS_ACKNOWLEDGE
		if (success) {
			at_send_command("AT+CNMA=1");
		} else {
			at_send_command("AT+CNMA=2");
		}
	}

	public void acknowledgeLastIncomingCdmaSms(boolean success, int cause) {
		// RIL_REQUEST_CDMA_SMS_ACKNOWLEDGE
		if (gsm) {
			throw new CommandException(Error.REQUEST_NOT_SUPPORTED);
		}
	}

	public void acknowledgeIncomingGsmSmsWithPdu(boolean success, String ackPdu) {
		// RIL_REQUEST_ACKNOWLEDGE_INCOMING_GSM_SMS_WITH_PDU
		if (gsm) {
			throw new CommandException(Error.REQUEST_NOT_SUPPORTED);
		}
	}

	public IccIoResult iccIOForApp(int command, int fileid, String path,
			int p1, int p2, int p3, String data, String pin2, String aid) {
		// Note: This RIL request has not been renamed to ICC,
		// but this request is also valid for SIM and RUIM
		// RIL_REQUEST_SIM_IO
		if (!gsm) {
			// FIXME CDMA
			throw new CommandException(Error.REQUEST_NOT_SUPPORTED);
		}
		String line;
		if (data != null && !"".equals(data)) {
			line = at_send_command_singleline("AT+CRSM=" + command + ","
					+ fileid + "," + p1 + "," + p2 + "," + p3 + "," + data,
					"+CRSM:");
		} else {
			line = at_send_command_singleline("AT+CRSM=" + command + ","
					+ fileid + "," + p1 + "," + p2 + "," + p3, "+CRSM:");
		}
		String[] tokens = line.split(",", 3);
		if (tokens.length < 2) {
			throw new CommandException(Error.GENERIC_FAILURE);
		}
		int sw1 = Integer.parseInt(tokens[0]);
		int sw2 = Integer.parseInt(tokens[1]);
		String payload = "";
		if (tokens.length > 2) {
			payload = unquote(tokens[2]);
		}
		return new IccIoResult(sw1, sw2, Utils.hexStringToBytes(payload));
	}

	public int[] getCLIR() {
		// RIL_REQUEST_GET_CLIR
		String line = at_send_command_singleline("AT+CLIR?", "+CLIR:");
		String[] tokens = line.split(",");
		if (tokens.length < 2) {
			throw new CommandException(Error.GENERIC_FAILURE);
		}
		return new int[] { Integer.parseInt(tokens[0]),
				Integer.parseInt(tokens[1]) };
	}

	public void setCLIR(int clirMode) {
		// RIL_REQUEST_SET_CLIR
		try {
			at_send_command("AT+CLIR=" + clirMode);
		} catch (CommandException e) {
			// FIXME check
			throw new CommandException(Error.PASSWORD_INCORRECT);
		}
	}

	public int[] queryCallWaiting(int serviceClass) {
		// RIL_REQUEST_QUERY_CALL_WAITING,
		String line = at_send_command_singleline("AT+CCWA=1,2," + serviceClass,
				"+CCWA:");
		String[] tokens = line.split(",");
		if (tokens.length < 1) {
			throw new CommandException(Error.GENERIC_FAILURE);
		}
		int r1 = Integer.parseInt(tokens[0]);
		int r2 = -1;
		if (tokens.length > 1) {
			r2 = Integer.parseInt(tokens[1]);
		}
		return new int[] { r1, r2 };
	}

	public void setCallWaiting(boolean enable, int serviceClass) {
		// RIL_REQUEST_SET_CALL_WAITING
		at_send_command("AT+CCWA=0," + (enable ? "1" : "0") + ","
				+ serviceClass);
	}

	public void setNetworkSelectionModeAutomatic() {
		// RIL_REQUEST_SET_NETWORK_SELECTION_AUTOMATIC, response);
		at_send_command("AT+COPS=0");
	}

	public void setNetworkSelectionModeManual(String operatorNumeric) {
		// RIL_REQUEST_SET_NETWORK_SELECTION_MANUAL
		try {
			at_send_command("AT+COPS=1,2,\"" + operatorNumeric + "\"");
		} catch (CommandException e) {
			at_send_command("AT+COPS=0");
		}
	}

	public int[] getNetworkSelectionMode() {
		// RIL_REQUEST_QUERY_NETWORK_SELECTION_MODE
		if (gsm) { // this command conflicts with the network status command
			String line = at_send_command_singleline("AT+COPS?", "+COPS:");
			String[] tokens = line.split(",");
			if (tokens.length >= 2) {
				return new int[] { Integer.parseInt(tokens[1]) };
			} // FIXME else error ?
		}
		return new int[0];
	}

	private String networkStatusToRilString(int state) {
		switch (state) {
		case 0:
			return ("unknown");
		case 1:
			return ("available");
		case 2:
			return ("current");
		case 3:
			return ("forbidden");
		default:
			return null;
		}
	}

	public List<OperatorInfo> getAvailableNetworks() {
		// RIL_REQUEST_QUERY_AVAILABLE_NETWORKS
		/*
		 * We expect an answer on the following form: +COPS:
		 * (2,"AT&T","AT&T","310410",0),(1,"T-Mobile ","TMO","310260",0)
		 */
		// '+COPS:
		// (2,"FI SONERA","SONERA","24491",2),(1,"dna","dna","24412",2),(1,"FI elisa","elisa","24405",2),(1,"","","24407",2),,(0,1,2,3,4),(0,1,2)'
		List<OperatorInfo> result = new ArrayList<OperatorInfo>();
		String line = at_send_command_singleline("AT+COPS=?", "+COPS:");
		// I hope there is no () in operator names.
		String[] operators = line.split("[)(]");
		for (String operator : operators) {
			operator = operator.trim();
			if ("".equals(operator) || ",".equals(operator)) {
				continue;
			}
			if (",,".equals(operator)) {
				break;
			}
			String[] tokens = operator.split(",");
			if (tokens.length < 4) {
				throw new CommandException(Error.GENERIC_FAILURE);
			}
			String operatorAlphaLong = unquote(tokens[1]);
			String operatorAlphaShort = unquote(tokens[2]);
			String operatorNumeric = unquote(tokens[3]);
			String stateString = networkStatusToRilString(Integer
					.parseInt(tokens[0]));
			OperatorInfo oi = new OperatorInfo(operatorAlphaLong,
					operatorAlphaShort, operatorNumeric, stateString);
			result.add(oi);
		}
		return result;
	}

	public void setCallForward(int action, int cfReason, int serviceClass,
			String number, int timeSeconds) {
		// RIL_REQUEST_SET_CALL_FORWARD
		at_send_command("AT+CCFC=" + cfReason + "," + serviceClass + "\""
				+ number + "\"");
	}

	public CallForwardInfo[] queryCallForwardStatus(int cfReason,
			int serviceClass, String number) {
		// RIL_REQUEST_QUERY_CALL_FORWARD_STATUS
		List<String> lines = at_send_command_multiline("AT+CCFC=0,2", "+CCFC:");
		CallForwardInfo[] result = new CallForwardInfo[lines.size()];
		int i = 0;
		for (String line : lines) {
			String[] tokens = line.split(",");
			switch (tokens.length) {
			case 10:
			case 9:
			case 8:
				result[i].timeSeconds = Integer.parseInt(tokens[7]);
			case 7:
			case 6:
			case 5:
				result[i].toa = Integer.parseInt(tokens[4]);
			case 4:
				result[i].toa = Integer.parseInt(tokens[3]);
			case 3:
				result[i].number = unquote(tokens[2]);
			case 2:
				result[i].serviceClass = Integer.parseInt(tokens[1]);
				result[i].status = Integer.parseInt(tokens[0]);
				break;
			case 1:
			case 0:
				throw new CommandException(Error.GENERIC_FAILURE);
			}

			if (tokens.length < 4) {
				throw new CommandException(Error.GENERIC_FAILURE);
			}
			i++;
		}
		return result;
	}

	public int[] queryCLIP() {
		// RIL_REQUEST_QUERY_CLIP
		String line = at_send_command_singleline("AT+CLIP?", "+CLIP:");
		String[] tokens = line.split(",");
		if (tokens.length < 2) {
			throw new CommandException(Error.GENERIC_FAILURE);
		}
		return new int[] { Integer.parseInt(tokens[1]) };
	}

	public String getBasebandVersion() {
		if (gsm) {
			return at_send_command_singleline("AT+CGMR", "");
			// FIXME which ?
			// return at_send_command_singleline("AT+CGMM", "");
		} else {
			throw new CommandException(Error.REQUEST_NOT_SUPPORTED);
		}
	}

	public int[] queryFacilityLockForApp(String facility, String password,
			int serviceClass, String appId) {
		// RIL_REQUEST_QUERY_FACILITY_LOCK
		String line = at_send_command_singleline("AT+CLCK=\"" + facility
				+ "\",2," + password + "," + serviceClass, "+CLCK:");
		return new int[] { Integer.parseInt(line) };
	}

	public int[] setFacilityLockForApp(String facility, boolean lockState,
			String password, int serviceClass, String appId) {
		// RIL_REQUEST_SET_FACILITY_LOCK
		at_send_command("AT+CLCK=\"" + facility + "\","
				+ (lockState ? "1" : "0") + "," + password + "," + serviceClass);
		return new int[0];
	}

	public void sendUSSD(String ussdString) {
		// RIL_REQUEST_SEND_USSD
		byte[] temp = utf8_to_gsm8(ussdString);
		String hex = gsm_hex_from_bytes(temp);
		at_send_command("AT+CUSD=1,\"" + hex + "\",15");
	}

	private String gsm_hex_from_bytes(byte[] temp) {
		String s = "";
		for (byte b : temp) {
			// FIXME performance
			s += String.format("%02X", b);
		}
		return s;
	}

	static final byte GSM_7BITS_ESCAPE = 0x1b;

	private byte[] utf8_to_gsm8(String ussdString) {
		ByteBuffer bb = ByteBuffer.allocate(ussdString.length() * 2);
		for (int i = 0; i < ussdString.length(); i++) {
			char c = ussdString.charAt(i);
			int nn;
			nn = unichar_to_gsm7(c);
			if (nn >= 0) {
				bb.put((byte) nn);
				continue;
			}
			nn = unichar_to_gsm7_extend(c);
			if (nn >= 0) {
				bb.put(GSM_7BITS_ESCAPE);
				bb.put((byte) nn);
				continue;
			}
			/* unknown => space */
			bb.put((byte) 0x20);
		}
		return bb.compact().array();
	}

	static char gsm7bits_to_unicode[] = { '@', 0xa3, '$', 0xa5, 0xe8, 0xe9,
			0xf9, 0xec, 0xf2, 0xc7, '\n', 0xd8, 0xf8, '\r', 0xc5, 0xe5, 0x394,
			'_', 0x3a6, 0x393, 0x39b, 0x3a9, 0x3a0, 0x3a8, 0x3a3, 0x398, 0x39e,
			0, 0xc6, 0xe6, 0xdf, 0xc9, ' ', '!', '"', '#', 0xa4, '%', '&',
			'\'', '(', ')', '*', '+', ',', '-', '.', '/', '0', '1', '2', '3',
			'4', '5', '6', '7', '8', '9', ':', ';', '<', '=', '>', '?', 0xa1,
			'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M',
			'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z',
			0xc4, 0xd6, 0x147, 0xdc, 0xa7, 0xbf, 'a', 'b', 'c', 'd', 'e', 'f',
			'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's',
			't', 'u', 'v', 'w', 'x', 'y', 'z', 0xe4, 0xf6, 0xf1, 0xfc, 0xe0, };

	static char gsm7bits_extend_to_unicode[] = { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
			'\f', 0, 0, 0, 0, 0, 0, 0, 0, 0, '^', 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
			0, 0, 0, 0, 0, 0, 0, 0, 0, '{', '}', 0, 0, 0, 0, 0, '\\', 0, 0, 0,
			0, 0, 0, 0, 0, 0, 0, 0, 0, '[', '~', ']', 0, '|', 0, 0, 0, 0, 0, 0,
			0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
			0, 0, 0, 0, 0, 0, 0, 0, 0x20ac, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
			0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, };

	private int unichar_to_gsm7(char unicode) {
		int nn;
		for (nn = 0; nn < 128; nn++) {
			if (gsm7bits_to_unicode[nn] == unicode) {
				return nn;
			}
		}
		return -1;

	}

	private int unichar_to_gsm7_extend(char unichar) {
		int nn;
		for (nn = 0; nn < 128; nn++) {
			if (gsm7bits_extend_to_unicode[nn] == unichar) {
				return nn;
			}
		}
		return -1;
	}

	public void cancelPendingUssd() {
		// RIL_REQUEST_CANCEL_USSD
		at_send_command_numeric("AT+CUSD=2");
	}

	public void resetRadio() {
		// RIL_REQUEST_RESET_RADIO
		at_send_command("AT+CFUN=16");
	}

	public byte[] invokeOemRilRequestRaw(byte[] data) {
		// RIL_REQUEST_OEM_HOOK_RAW
		throw new CommandException(Error.REQUEST_NOT_SUPPORTED);
	}

	public String[] invokeOemRilRequestStrings(String[] strings) {
		// RIL_REQUEST_OEM_HOOK_STRINGS
		switch (strings.length) {
		case 0:
			throw new CommandException(Error.GENERIC_FAILURE);
		case 1:
			at_send_command(strings[0]);
			return new String[0];
		case 2:
		default: // FIXME ignore rest ?
			String line = at_send_command_singleline(strings[0], strings[1]);
			return new String[] { line };
		}
	}

	public void setBandMode(int bandMode) {
		// RIL_REQUEST_SET_BAND_MODE, response);
	}

	public int[] queryAvailableBandMode() {
		// RIL_REQUEST_QUERY_AVAILABLE_BAND_MODE, response);
		return new int[0];
	}

	public void sendTerminalResponse(String contents) {
		// RIL_REQUEST_STK_SEND_TERMINAL_RESPONSE
		short[] intdata = hexStringToShortArray(contents);
		int command = intdata[2];

		switch (command) {
		case 21:
			command = 33;
			break;
		case 20:
			command = 32;
			break;
		case 15:
			command = 21;
			break;
		case 22:
			command = 34;
			break;
		case 23:
			command = 35;
			break;
		case 24:
			command = 36;
			break;
		default:
			throw new CommandException(Error.REQUEST_NOT_SUPPORTED);
		}

		switch (command) {
		case 32:
		case 33: {
			int result = intdata[11];
			int additionalInfo = 0;
			if (intdata[10] > 1)
				additionalInfo = intdata[12];
			at_send_command("AT+STKTR=" + command + "," + result + ","
					+ additionalInfo);
			break;
		}
		case 21: {
			int result = intdata[11];
			at_send_command("AT+STKTR=" + command + "," + result);
			break;
		}
		case 34:
		case 35:
		case 36: {
			int result = intdata[11];
			int additionalInfo = 0;
			int offset = 0;
			if (intdata[10] > 1) {
				additionalInfo = intdata[12];
				offset = 1;
			}
			int optInfoLen = (intdata[13] + offset) * 2;
			String optInfo = contents.substring(15 + offset, 15 + offset
					+ optInfoLen);
			at_send_command("AT+STKTR=" + command + "," + result + ","
					+ additionalInfo + ",0," + intdata[14 + offset] + ",\""
					+ optInfo + "\"");
			break;
		}
		}

	}

	public String getProfile() {
		// RIL_REQUEST_STK_GET_PROFILE
		String line = at_send_command_singleline("AT+STKPROF?", "+STKPROF:");
		String[] tokens = line.split(",");
		if (tokens.length < 2) {
			throw new CommandException(Error.GENERIC_FAILURE);
		}
		return tokens[1];
	}

	public void setProfile(String profile) {
		at_send_command("AT+STKPROF=" + profile.length() + ",\"" + profile
				+ "\"");
	}

	public static short[] hexStringToShortArray(String s) {
		int len = s.length();
		short[] data = new short[len / 4];
		for (int i = 0; i < len; i += 4) {
			data[i / 4] = (short) ((Character.digit(s.charAt(i), 16) << 12)
					+ (Character.digit(s.charAt(i + 1), 16) << 8)
					+ (Character.digit(s.charAt(i + 2), 16) << 4) + Character
					.digit(s.charAt(i + 3), 16));
		}
		return data;
	}

	public String sendEnvelope(String contents) {
		// RIL_REQUEST_STK_SEND_ENVELOPE_COMMAND
		short[] intdata = hexStringToShortArray(contents);
		short envcmd = intdata[0];
		if (envcmd == 211) {
			int itemid = intdata[8];
			int helpreq = intdata[9];
			at_send_command("AT+STKENV=" + envcmd + "," + itemid + ","
					+ helpreq);
		} else if (envcmd == 214) {
			int lang_cause = 0;
			int len = intdata[1];
			int eventlst = intdata[4];
			if (len > 7)
				lang_cause = intdata[9];
			at_send_command("AT+STKENV=" + envcmd + "," + eventlst + ","
					+ lang_cause);
		} else {
			throw new CommandException(Error.REQUEST_NOT_SUPPORTED);
		}
		return "";
	}

	public IccIoResult sendEnvelopeWithStatus(String contents) {
		// RIL_REQUEST_STK_SEND_ENVELOPE_WITH_STATUS
		return null;
	}

	public int[] handleCallSetupRequestFromSim(boolean accept) {
		// RIL_REQUEST_STK_HANDLE_CALL_SETUP_REQUESTED_FROM_SIM,
		return new int[0];
	}

	public void setPreferredNetworkType(int networkType) {
		// RIL_REQUEST_SET_PREFERRED_NETWORK_TYPE
		String at_rat;
		switch (networkType) {
		case 1:
			at_rat = "2,1,1";
			break; /* GSM only */
		case 2:
			at_rat = "2,1,2";
			break; /* WsCDMA only */
		default:
			at_rat = "2,1,0";
			break; /* Dual Mode - WCDMA preferred */
		}
		// at_send_command("AT+BANDSET=0");
		at_send_command("AT+CGAATT=" + at_rat);
		/* Trigger autoregister */
		at_send_command("AT+COPS=0");
	}

	public int[] getPreferredNetworkType() {
		// RIL_REQUEST_GET_PREFERRED_NETWORK_TYPE
		String line = at_send_command_singleline("AT+CGAATT?", "+CGAATT:");
		String[] tokens = line.split(",");
		if (tokens.length < 3) {
			throw new CommandException(Error.GENERIC_FAILURE);
		}
		return new int[] { Integer.parseInt(tokens[2]) };
	}

	public List<NeighboringCellInfo> getNeighboringCids() {
		// RIL_REQUEST_GET_NEIGHBORING_CELL_IDS
		// TODO AT+CNCI
		return null;
	}

	public void setLocationUpdates(boolean enable) {
		// RIL_REQUEST_SET_LOCATION_UPDATES
		if (gsm) {
			String line = at_send_command_singleline("AT+CREG="
					+ (enable ? "1" : "0"), "+CLIP:");
		}
		// Always return success for CDMA (for now)
		return;
	}

	public String getSmscAddress() {
		// RIL_REQUEST_GET_SMSC_ADDRESS
		String line = at_send_command_singleline("AT+CSCA?", "+CSCA:");
		String[] tokens = line.split(",");
		if (tokens.length < 2) {
			throw new CommandException(Error.GENERIC_FAILURE);
		}
		int tosca = Integer.parseInt(tokens[1]);
		String number = unquote(tokens[0]);
		// if (number.charAt(0) == '+') {
		// number = number.substring(1);
		// }
		return number;
	}

	public void setSmscAddress(String address) {
		// RIL_REQUEST_SET_SMSC_ADDRESS
		at_send_command("AT+CSCA=" + address);
	}

	public void reportSmsMemoryStatus(boolean available) {
		// RIL_REQUEST_REPORT_SMS_MEMORY_STATUS,
	}

	public void reportStkServiceIsRunning() {
		// RIL_REQUEST_REPORT_STK_SERVICE_IS_RUNNING
	}

	public List<SmsBroadcastConfigInfo> getGsmBroadcastConfig() {
		// RIL_REQUEST_GSM_GET_BROADCAST_CONFIG
		return null;
	}

	public void setGsmBroadcastConfig(SmsBroadcastConfigInfo[] config) {
		// RIL_REQUEST_GSM_SET_BROADCAST_CONFIG,
	}

	public void setGsmBroadcastActivation(boolean activate) {
		// RIL_REQUEST_GSM_BROADCAST_ACTIVATION,
	}

	// private

	protected void sendScreenState(boolean on) {
		// RIL_REQUEST_SCREEN_STATE

		if (on) {
			if (gsm) {
				/*
				 * Screen is on - be sure to enable all unsolicited
				 * notifications again
				 */
				at_send_command("AT+CREG=2");
				at_send_command("AT+CGREG=2");
				at_send_command("AT+CGEREP=1,0");
				// err = at_send_command("AT@HTCPDPFD=0", NULL);
				// err = at_send_command("AT+ENCSQ=1",NULL);
				// err = at_send_command("AT@HTCCSQ=1", NULL);
				// err = at_send_command("AT+HTCCTZR=1", NULL);
			} else {
			}
		} else {
			if (gsm) {
				/* Screen is off - disable all unsolicited notifications */
				at_send_command("AT+CREG=0");
				at_send_command("AT+CGREG=0");
				at_send_command("AT+CGEREP=0,0");
				// err = at_send_command("AT@HTCPDPFD=1", NULL);
				// err = at_send_command("AT+ENCSQ=0",NULL);
				// err = at_send_command("AT@HTCCSQ=0", NULL);
			}
		}
	}

	// ***** Methods for CDMA support
	public String[] getDeviceIdentity() {
		// RIL_REQUEST_DEVICE_IDENTITY
		if (gsm) {
			throw new CommandException(Error.REQUEST_NOT_SUPPORTED);
		}
		return new String[0];

	}

	public String[] getCDMASubscription() {
		// RIL_REQUEST_CDMA_SUBSCRIPTION
		if (gsm) {
			throw new CommandException(Error.REQUEST_NOT_SUPPORTED);
		}
		return new String[0];
	}

	public int[] queryCdmaRoamingPreference() {
		// RIL_REQUEST_CDMA_QUERY_ROAMING_PREFERENCE
		if (gsm) {
			throw new CommandException(Error.REQUEST_NOT_SUPPORTED);
		}
		return new int[0];
	}

	public void setCdmaRoamingPreference(int cdmaRoamingType) {
		// RIL_REQUEST_CDMA_SET_ROAMING_PREFERENCE
		if (gsm) {
			throw new CommandException(Error.REQUEST_NOT_SUPPORTED);
		}
	}

	public void setCdmaSubscriptionSource(int cdmaSubscription) {
		// RIL_REQUEST_CDMA_SET_SUBSCRIPTION_SOURCE
		if (gsm) {
			throw new CommandException(Error.REQUEST_NOT_SUPPORTED);
		}
	}

	public int[] getCdmaSubscriptionSource() {
		// RIL_REQUEST_CDMA_GET_SUBSCRIPTION_SOURCE
		if (gsm) {
			throw new CommandException(Error.REQUEST_NOT_SUPPORTED);
		}
		return new int[0];
	}

	public int[] queryTTYMode() {
		// RIL_REQUEST_QUERY_TTY_MODE
		if (gsm) {
			throw new CommandException(Error.REQUEST_NOT_SUPPORTED);
		}
		return new int[0];
	}

	public void setTTYMode(int ttyMode) {
		if (gsm) {
			throw new CommandException(Error.REQUEST_NOT_SUPPORTED);
		}
		// RIL_REQUEST_SET_TTY_MODE
	}

	public void sendCDMAFeatureCode(String FeatureCode) {
		// RIL_REQUEST_CDMA_FLASH
		if (gsm) {
			throw new CommandException(Error.REQUEST_NOT_SUPPORTED);
		}
	}

	public int[] getCdmaBroadcastConfig() {
		// RIL_REQUEST_CDMA_GET_BROADCAST_CONFIG
		if (gsm) {
			throw new CommandException(Error.REQUEST_NOT_SUPPORTED);
		}
		return null;
	}

	// TODO: Change the configValuesArray to a RIL_BroadcastSMSConfig
	public void setCdmaBroadcastConfig(int[] configValuesArray) {
		if (gsm) {
			throw new CommandException(Error.REQUEST_NOT_SUPPORTED);
		}
		// RIL_REQUEST_CDMA_SET_BROADCAST_CONFIG
	}

	public void setCdmaBroadcastActivation(boolean activate) {
		if (gsm) {
			throw new CommandException(Error.REQUEST_NOT_SUPPORTED);
		}
		// RIL_REQUEST_CDMA_BROADCAST_ACTIVATION
	}

	public void exitEmergencyCallbackMode() {
		// RIL_REQUEST_EXIT_EMERGENCY_CALLBACK_MODE
		if (gsm) {
			throw new CommandException(Error.REQUEST_NOT_SUPPORTED);
		}
	}

	public String requestIsimAuthentication(String nonce) {
		// RIL_REQUEST_ISIM_AUTHENTICATION,
		if (gsm) {
			throw new CommandException(Error.REQUEST_NOT_SUPPORTED);
		}
		return "";
	}
	
	//

	public void at_handshake() {
		CommandException ex = null;
		for (int i = 0; i < HANDSHAKE_RETRY_COUNT; i++) {
			try {
				ATResponse response = at.at_send_command("ATE0Q0V1",
						ATCommandType.NO_RESULT, null, null,
						HANDSHAKE_TIMEOUT_MSEC);

				if (response.mStatus == ATRequestStatus.OK) {
					return;
				}
			} catch (CommandException e) {
				ex = e;
			}
		}
		if (ex == null) {
			ex = new CommandException(Error.RADIO_NOT_AVAILABLE);
		}
		throw ex;
	}

	// /////////

	public void at_send_command(String command) {
		ATResponse response = at.at_send_command(command,
				ATCommandType.NO_RESULT);
		if (response.mStatus != ATRequestStatus.OK) {
			throw new CommandException(Error.GENERIC_FAILURE);
		}
		if (response.mIntermediates.size() > 1) {
			Log.e(LOG_TAG, "Too many responses");
			throw new CommandException(Error.INVALID_RESPONSE);
		}
	}

	public String at_send_command_numeric(String command) {
		ATResponse response = at
				.at_send_command(command, ATCommandType.NUMERIC);
		if (response.mStatus != ATRequestStatus.OK) {
			throw new CommandException(Error.GENERIC_FAILURE);
		}
		if (response.mIntermediates.size() < 1) {
			Log.e(LOG_TAG, "No response");
			throw new CommandException(Error.INVALID_RESPONSE);
		}
		if (response.mIntermediates.size() > 1) {
			Log.e(LOG_TAG, "Too many responses");
			throw new CommandException(Error.INVALID_RESPONSE);
		}
		return response.mIntermediates.get(0);
	}

	public String at_send_command_singleline(String command, String prefix) {
		ATResponse response = at.at_send_command(command,
				ATCommandType.SINGLELINE, prefix);
		if (response.mStatus != ATRequestStatus.OK) {
			throw new CommandException(Error.GENERIC_FAILURE);
		}
		if (response.mIntermediates.size() < 1) {
			Log.e(LOG_TAG, "No response");
			throw new CommandException(Error.INVALID_RESPONSE);
		}
		if (response.mIntermediates.size() > 1) {
			Log.e(LOG_TAG, "Too many responses");
			throw new CommandException(Error.INVALID_RESPONSE);
		}
		return response.mIntermediates.get(0);
	}

	public List<String> at_send_command_multiline(String command, String prefix) {
		ATResponse response = at.at_send_command(command,
				ATCommandType.MULTILINE, prefix);
		if (response.mStatus != ATRequestStatus.OK) {
			throw new CommandException(Error.GENERIC_FAILURE);
		}
		return response.mIntermediates;
	}

	public String at_send_command_sms(String command, String prefix, String pdu) {
		ATResponse response = at.at_send_command(command,
				ATCommandType.SINGLELINE, prefix, pdu, 0);
		if (response.mStatus != ATRequestStatus.OK) {
			throw new CommandException(Error.GENERIC_FAILURE);
		}
		if (response.mIntermediates.size() < 1) {
			Log.e(LOG_TAG, "No response");
			throw new CommandException(Error.INVALID_RESPONSE);
		}
		if (response.mIntermediates.size() > 1) {
			Log.e(LOG_TAG, "Too many responses");
			throw new CommandException(Error.INVALID_RESPONSE);
		}
		return response.mIntermediates.get(0);
	}

	private String unquote(String string) {
		string = string.trim();
		if (string.charAt(0) == '"'
				&& string.charAt(string.length() - 1) == '"') {
			return string.substring(1, string.length() - 1);
		}
		return string;
	}

	// /////////

	public Handler getDefaultHandler() {
		return mDefaultHandler;
	}

	public void setDefaultHandler(Handler mDefaultHandler) {
		this.mDefaultHandler = mDefaultHandler;
	}

	public ATDevice getAt() {
		return at;
	}

	public void setAt(ATDevice at) {
		this.at = at;
	}

	public PPPDMonitor getmPPPDMonitor() {
		return mPPPDMonitor;
	}

	public void setmPPPDMonitor(PPPDMonitor mPPPDMonitor) {
		this.mPPPDMonitor = mPPPDMonitor;
	}

	public String getIdentity() {
		return mIdentity;
	}

	public RadioState getRadioState() {
		return mRadioState;
	}

	public String getInitializationCommands() {
		return initializationCommands;
	}

	public void setInitializationCommands(String initializationCommands) {
		this.initializationCommands = initializationCommands;
	}
}
