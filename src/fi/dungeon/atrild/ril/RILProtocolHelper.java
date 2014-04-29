package fi.dungeon.atrild.ril;

import static reloc.com.android.internal.telephony.RILConstants.*;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;

import reloc.com.android.internal.telephony.CallForwardInfo;
import reloc.com.android.internal.telephony.CommandException;
import reloc.com.android.internal.telephony.DataCallState;
import reloc.com.android.internal.telephony.DriverCall;
import reloc.com.android.internal.telephony.IccCardApplication;
import reloc.com.android.internal.telephony.IccCardStatus;
import reloc.com.android.internal.telephony.IccIoResult;
import reloc.com.android.internal.telephony.OperatorInfo;
import reloc.com.android.internal.telephony.SmsResponse;
import reloc.com.android.internal.telephony.gsm.SmsBroadcastConfigInfo;

import android.os.Parcel;
import android.telephony.NeighboringCellInfo;
import android.util.Log;

/**
 * {@hide}
 */
class RILRequest {
	static final String LOG_TAG = "ATRILDJ";

	// ***** Class Variables
	private static Object sPoolSync = new Object();
	private static RILRequest sPool = null;
	private static int sPoolSize = 0;
	private static final int MAX_POOL_SIZE = 4;

	// ***** Instance Variables
	int mRequest;
	int mSerial;
	long creationTime;
	Parcel mp;
	RILRequest mNext;

	public String toString() {
		StringBuilder b = new StringBuilder();
		b.append("{");
		b.append(" when=");
		// TimeUtils.formatDuration(creationTime - System.currentTimeMillis(),
		// b);
		b.append(creationTime - System.currentTimeMillis());
		b.append(" mRequest=");
		b.append(mRequest);
		b.append(" mSerial=");
		b.append(mSerial);
		if (mp != null) {
			b.append(" mp=");
			b.append(mp.toString());
		}
		b.append(" }");
		return b.toString();
	}

	static RILRequest obtain(Parcel p) {
		RILRequest rr = null;

		synchronized (sPoolSync) {
			if (sPool != null) {
				rr = sPool;
				sPool = rr.mNext;
				rr.mNext = null;
				sPoolSize--;
			}
		}
		if (rr == null) {
			rr = new RILRequest();
		}
		rr.mRequest = p.readInt();
		rr.mSerial = p.readInt();
		rr.mp = p;
		rr.creationTime = System.currentTimeMillis();

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
				// FIXME mp = null;
			}
		}
	}

	private RILRequest() {
	}

}

class RILResponse {
	static final String LOG_TAG = "ATRILDJ";

	// ***** Instance Variables
	int mRequest;
	int mType;
	int mSerial;
	private int mError;
	Parcel mp;

	public String toString() {
		StringBuilder b = new StringBuilder();
		b.append("{");
		b.append(" mRequest=");
		b.append(mRequest);
		b.append(" mType=");
		b.append(mType);
		b.append(" mSerial=");
		b.append(mSerial);
		if (mError != 0) {
			b.append(" mError=");
			b.append(mError);
		}
		if (mp != null) {
			b.append(" mp={ ");
			b.append(mp.toString());
		}
		b.append(" }");
		return b.toString();
	}

	static RILResponse obtain(int request, int response, int serial) {
		RILResponse rr = new RILResponse();

		rr.mRequest = request;
		rr.mType = response;
		rr.mSerial = serial;
		rr.mp = Parcel.obtain();

		rr.mp.writeInt(rr.mType);
		rr.mp.writeInt(rr.mSerial);
		rr.mp.writeInt(rr.mError);

		return rr;
	}

	public int getError() {
		return mError;
	}

	public void setError(CommandException.Error error) {
		setError(error.ordinal());
	}

	public void setError(int error) {
		mError = error;
		int position = mp.dataPosition();
		mp.setDataPosition(8);
		mp.writeInt(mError);
		// Truncate if error. Error PDU has no body
		if (error != 0) {
			mp.setDataPosition(position);
		}
	}

	void release() {
	}

	private RILResponse() {
	}
}

public class RILProtocolHelper {
	static final String LOG_TAG = "ATRILDJ";
	static final boolean RILDJ_LOGD = true;
	static final boolean RILDJ_LOGV = false; // STOP SHIP if true

	enum RadioState {
		OFF, /* Radio explicitly powered off (eg CFUN=0) */
		UNAVAILABLE, /* Radio unavailable (eg, resetting or not booted) */
		ON, /* Radio is on */

		/*
		 * States 2-9 below are deprecated. Just leaving them here for backward
		 * compatibility.
		 */
		SIM_NOT_READY, /* Radio is on, but the SIM interface is not ready */
		SIM_LOCKED_OR_ABSENT, /*
							 * SIM PIN locked, PUK required, network
							 * personalization locked, or SIM absent
							 */
		SIM_READY; /* Radio is on and SIM interface is available */

		public boolean isOn() /* and available... */{
			return this == ON;
		}

		public boolean isAvailable() {
			return this != UNAVAILABLE;
		}

		public int getIntValue() {
			int stateInt = 1;
			switch (this) {
			case OFF:
				stateInt = 0;
				break;
			case ON:
				stateInt = 10;
				break;
			case UNAVAILABLE:
				stateInt = 1;
				break;
			case SIM_LOCKED_OR_ABSENT:
				stateInt = 10;
				break;
			case SIM_NOT_READY:
				stateInt = 10;
				break;
			case SIM_READY:
				stateInt = 10;
				break;
			}
			return stateInt;
		}
	}

	enum SIM_Status {
		SIM_ABSENT, // 0
		SIM_NOT_READY, // 1
		SIM_READY, /*
					 * 2 SIM_READY means the radio state is
					 * RADIO_STATE_SIM_READY
					 */
		SIM_PIN, // 3
		SIM_PUK, // 4
		SIM_NETWORK_PERSONALIZATION // 5
	};

	enum RadioTechnology {
		RADIO_TECH_UNKNOWN, // 0,
		RADIO_TECH_GPRS, // 1,
		RADIO_TECH_EDGE, // 2,
		RADIO_TECH_UMTS, // 3,
		RADIO_TECH_IS95A, // 4,
		RADIO_TECH_IS95B, // 5,
		RADIO_TECH_1xRTT, // 6,
		RADIO_TECH_EVDO_0, // 7,
		RADIO_TECH_EVDO_A, // 8,
		RADIO_TECH_HSDPA, // 9,
		RADIO_TECH_HSUPA, // 10,
		RADIO_TECH_HSPA, // 11,
		RADIO_TECH_EVDO_B, // 12,
		RADIO_TECH_EHRPD, // 13,
		RADIO_TECH_LTE, // 14,
		RADIO_TECH_HSPAP, // 15, // HSPA+
		RADIO_TECH_GSM // 16 // Only supports voice
	};

    public enum FailCause {
        NONE(0),

        // This series of errors as specified by the standards
        // specified in ril.h
        OPERATOR_BARRED(0x08),
        INSUFFICIENT_RESOURCES(0x1A),
        MISSING_UNKNOWN_APN(0x1B),
        UNKNOWN_PDP_ADDRESS_TYPE(0x1C),
        USER_AUTHENTICATION(0x1D),
        ACTIVATION_REJECT_GGSN(0x1E),
        ACTIVATION_REJECT_UNSPECIFIED(0x1F),
        SERVICE_OPTION_NOT_SUPPORTED(0x20),
        SERVICE_OPTION_NOT_SUBSCRIBED(0x21),
        SERVICE_OPTION_OUT_OF_ORDER(0x22),
        NSAPI_IN_USE(0x23),
        ONLY_IPV4_ALLOWED(0x32),
        ONLY_IPV6_ALLOWED(0x33),
        ONLY_SINGLE_BEARER_ALLOWED(0x34),
        PROTOCOL_ERRORS(0x6F),

        // Local errors generated by Vendor RIL
        // specified in ril.h
        REGISTRATION_FAIL(-1),
        GPRS_REGISTRATION_FAIL(-2),
        SIGNAL_LOST(-3),
        PREF_RADIO_TECH_CHANGED(-4),
        RADIO_POWER_OFF(-5),
        TETHERED_CALL_ACTIVE(-6),
        ERROR_UNSPECIFIED(0xFFFF),

        // Errors generated by the Framework
        // specified here
        UNKNOWN(0x10000),
        RADIO_NOT_AVAILABLE(0x10001),
        UNACCEPTABLE_NETWORK_PARAMETER(0x10002),
        CONNECTION_TO_DATACONNECTIONAC_BROKEN(0x10003);

        private final int mErrorCode;
        private static final HashMap<Integer, FailCause> sErrorCodeToFailCauseMap;
        static {
            sErrorCodeToFailCauseMap = new HashMap<Integer, FailCause>();
            for (FailCause fc : values()) {
                sErrorCodeToFailCauseMap.put(fc.getErrorCode(), fc);
            }
        }

        FailCause(int errorCode) {
            mErrorCode = errorCode;
        }

        public int getErrorCode() {
            return mErrorCode;
        }

        public boolean isPermanentFail() {
            return (this == OPERATOR_BARRED) || (this == MISSING_UNKNOWN_APN) ||
                   (this == UNKNOWN_PDP_ADDRESS_TYPE) || (this == USER_AUTHENTICATION) ||
                   (this == SERVICE_OPTION_NOT_SUPPORTED) ||
                   (this == SERVICE_OPTION_NOT_SUBSCRIBED) || (this == NSAPI_IN_USE) ||
                   (this == PROTOCOL_ERRORS);
        }

        public boolean isEventLoggable() {
            return (this == OPERATOR_BARRED) || (this == INSUFFICIENT_RESOURCES) ||
                    (this == UNKNOWN_PDP_ADDRESS_TYPE) || (this == USER_AUTHENTICATION) ||
                    (this == ACTIVATION_REJECT_GGSN) || (this == ACTIVATION_REJECT_UNSPECIFIED) ||
                    (this == SERVICE_OPTION_NOT_SUBSCRIBED) ||
                    (this == SERVICE_OPTION_NOT_SUPPORTED) ||
                    (this == SERVICE_OPTION_OUT_OF_ORDER) || (this == NSAPI_IN_USE) ||
                    (this == PROTOCOL_ERRORS) ||
                    (this == UNACCEPTABLE_NETWORK_PARAMETER);
        }

        public static FailCause fromInt(int errorCode) {
            FailCause fc = sErrorCodeToFailCauseMap.get(errorCode);
            if (fc == null) {
                fc = UNKNOWN;
            }
            return fc;
        }
    }

    public static final int RESPONSE_SOLICITED = 0;
	public static final int RESPONSE_UNSOLICITED = 1;

	public static final int AT_ERROR_GENERIC = -1;
	public static final int AT_ERROR_COMMAND_PENDING = -2;
	public static final int AT_ERROR_CHANNEL_CLOSED = -3;
	public static final int AT_ERROR_TIMEOUT = -4;
	public static final int AT_ERROR_INVALID_THREAD = -5; /*
														 * AT commands may not
														 * be issued from reader
														 * thread (or
														 * unsolicited response
														 * callback
														 */
	public static final int AT_ERROR_INVALID_RESPONSE = -6; /*
															 * eg an
															 * at_send_command_singleline
															 * that did not get
															 * back an
															 * intermediate
															 * response
															 */

	// The number of the required config values for broadcast SMS stored in the
	// C struct RIL_CDMA_BroadcastServiceInfo
	private static final int CDMA_BSI_NO_OF_INTS_STRUCT = 3;

	protected void writeInts(Parcel p, int[] ints) {
		int numInts = ints.length;
		p.writeInt(numInts);
		for (int i = 0; i < numInts; i++) {
			p.writeInt(ints[i]);
		}
	}

	protected void writeString(Parcel p, String val) {
		p.writeString(val);
	}

	protected void writeStrings(Parcel p, String[] val) {
		p.writeStringArray(val);
	}

	protected void writeRaw(Parcel p, byte[] val) {
		p.writeByteArray(val);
	}

	protected void writeCallList(Parcel p, List<DriverCall> calls) {
		// Collections.sort(calls);
		p.writeInt(calls.size());
		int num = calls.size();
		for (int i = 0; i < num; i++) {
			DriverCall dc = calls.get(i);
			p.writeInt(dc.state.ordinal());
			p.writeInt(dc.index);
			p.writeInt(dc.TOA);
			p.writeInt(dc.isMpty ? 1 : 0);
			p.writeInt(dc.isMT ? 1 : 0);
			p.writeInt(dc.als);
			p.writeInt(dc.isVoice ? 1 : 0);
			p.writeInt(dc.isVoicePrivacy ? 1 : 0);
			p.writeString(dc.number);
			p.writeInt(dc.numberPresentation);
			p.writeString(dc.name);
			p.writeInt(dc.namePresentation);
			p.writeInt(dc.uusInfo != null ? 1 : 0);
			if (dc.uusInfo != null) {
				p.writeInt(dc.uusInfo.getType());
				p.writeInt(dc.uusInfo.getDcs());
				p.writeByteArray(dc.uusInfo.getUserData());
				riljLogv(String.format(
						"Incoming UUS : type=%d, dcs=%d, length=%d",
						dc.uusInfo.getType(), dc.uusInfo.getDcs(),
						dc.uusInfo.getUserData().length));
				riljLogv("Incoming UUS : data (string)="
						+ new String(dc.uusInfo.getUserData()));
				riljLogv("Incoming UUS : data (hex): "
						+ Utils.bytesToHexString(dc.uusInfo.getUserData()));
			} else {
				riljLogv("Incoming UUS : NOT present!");
			}

			if (dc.isVoicePrivacy) {
				riljLog("InCall VoicePrivacy is enabled");
			} else {
				riljLog("InCall VoicePrivacy is disabled");
			}
		}
	}

	protected void writeDataCallList(Parcel p, List<DataCallState> list) {
		int ver = 6; // 3 old ? FIXME
		int num = list.size();
		p.writeInt(ver);
		p.writeInt(num);
		riljLog("responseDataCallList ver=" + ver + " num=" + num);

		for (int i = 0; i < num; i++) {
			writeDataCallState(p, ver, list.get(i));
		}
	}

	protected void writeDataCallState(Parcel p, int version,
			DataCallState dataCall) {
		p.writeInt(dataCall.status);
		p.writeInt(dataCall.suggestedRetryTime); // FIXME
		p.writeInt(dataCall.cid);
		p.writeInt(dataCall.active);
		p.writeString(dataCall.type);
		p.writeString(dataCall.ifname);
		p.writeString(join(" ", dataCall.addresses));
		p.writeString(join(" ", dataCall.dnses));
		p.writeString(join(" ", dataCall.gateways));
	}

	protected void writeSignalStrength(Parcel p, int values[]) {
		int numInts = 12;
		for (int i = 0; i < numInts; i++) {
			p.writeInt(values[i]);
		}
	}

	protected void writeSmsResponse(Parcel p, SmsResponse response) {
		p.writeInt(response.messageRef);
		p.writeString(response.ackPdu);
		p.writeInt(response.errorCode);
	}

	protected void writeSetupDataCallState(Parcel p, DataCallState dataCall) {
		int ver = 6; // 3 old ? FIXME
		int num = 1;
		p.writeInt(ver);
		p.writeInt(num);

		Log.d(LOG_TAG, "responseSetupDataCall ver=" + ver + " num=" + num);

		writeDataCallState(p, ver, dataCall);
	}

	protected void writeIccCardStatus(Parcel po, IccCardStatus ics) {
		boolean oldRil = false;
		po.writeInt(ics.getCardState().ordinal());
		po.writeInt(ics.getUniversalPinState().ordinal());
		po.writeInt(ics.getGsmUmtsSubscriptionAppIndex());
		po.writeInt(ics.getCdmaSubscriptionAppIndex());
		if (!oldRil)
			po.writeInt(ics.getImsSubscriptionAppIndex());
		int numApplications = ics.getNumApplications();
		// limit to maximum allowed applications
		if (numApplications > IccCardStatus.CARD_MAX_APPS) {
			numApplications = IccCardStatus.CARD_MAX_APPS;
		}
		po.writeInt(numApplications);

		for (int i = 0; i < numApplications; i++) {
			IccCardApplication ca = ics.getApplication(i);
			po.writeInt(ca.app_type.ordinal());
			po.writeInt(ca.app_state.ordinal());
			po.writeInt(ca.perso_substate.ordinal());
			po.writeString(ca.aid);
			po.writeString(ca.app_label);
			po.writeInt(ca.pin1_replaced);
			po.writeInt(ca.pin1.ordinal());
			po.writeInt(ca.pin2.ordinal());
		}
	}

	protected void writeIccIoResult(Parcel p, IccIoResult result) {
		p.writeInt(result.sw1);
		p.writeInt(result.sw2);
		p.writeString(Utils.bytesToHexString(result.payload));
	}

	protected void writeCallForwardInfo(Parcel p, CallForwardInfo[] infos) {
		int numInfos = infos.length;
		p.writeInt(numInfos);

		for (int i = 0; i < numInfos; i++) {
			p.writeInt(infos[i].status);
			p.writeInt(infos[i].reason);
			p.writeInt(infos[i].serviceClass);
			p.writeInt(infos[i].toa);
			p.writeString(infos[i].number);
			p.writeInt(infos[i].timeSeconds);
		}
	}

	protected void writeOperatorInfo(Parcel p, List<OperatorInfo> infos) {

		p.writeInt(infos.size() * 4);
		for (OperatorInfo info : infos) {
			p.writeString(info.getOperatorAlphaLong());
			p.writeString(info.getOperatorAlphaShort());
			p.writeString(info.getOperatorNumeric());
			// TODO one should map the state to lowercase strings..
			p.writeString(info.getState().name()
					.toLowerCase(Locale.getDefault()));
		}
	}

	protected void writeNeighboringCellInfo(Parcel p,
			List<NeighboringCellInfo> infos) {

		int num = infos.size();
		p.writeInt(num);
		for (NeighboringCellInfo info : infos) {
			p.writeInt(info.getRssi());
			// Checkme
			p.writeString(String.format("%04X%04X", info.getLac(),
					info.getCid()));
		}
	}

	protected void writeGsmBroadcastConfigInfo(Parcel p,
			List<SmsBroadcastConfigInfo> config) {
		p.writeInt(config.size());
		for (SmsBroadcastConfigInfo info : config) {
			p.writeInt(info.getFromServiceId());
			p.writeInt(info.getToServiceId());
			p.writeInt(info.getFromCodeScheme());
			p.writeInt(info.getToCodeScheme());
			p.writeInt(info.isSelected() ? 1 : 0);
		}
	}

	protected void writeCdmaBroadcastConfig(Parcel p, int[] config) {
		int numServiceCategories = config.length / CDMA_BSI_NO_OF_INTS_STRUCT;
		p.writeInt(numServiceCategories);
		for (int i = 1; i < config.length; i++) {
			p.writeInt(config[i]);
		}
	}

	// ///////////////////////

	static String requestToString(int request) {
		/*
		 * cat libs/telephony/ril_commands.h \ | egrep "^ *{RIL_" \ | sed -re
		 * 's/\{RIL_([^,]+),[^,]+,([^}]+).+/case RIL_\1: return "\1";/'
		 */
		switch (request) {
		case RIL_REQUEST_GET_SIM_STATUS:
			return "GET_SIM_STATUS";
		case RIL_REQUEST_ENTER_SIM_PIN:
			return "ENTER_SIM_PIN";
		case RIL_REQUEST_ENTER_SIM_PUK:
			return "ENTER_SIM_PUK";
		case RIL_REQUEST_ENTER_SIM_PIN2:
			return "ENTER_SIM_PIN2";
		case RIL_REQUEST_ENTER_SIM_PUK2:
			return "ENTER_SIM_PUK2";
		case RIL_REQUEST_CHANGE_SIM_PIN:
			return "CHANGE_SIM_PIN";
		case RIL_REQUEST_CHANGE_SIM_PIN2:
			return "CHANGE_SIM_PIN2";
		case RIL_REQUEST_ENTER_NETWORK_DEPERSONALIZATION:
			return "ENTER_NETWORK_DEPERSONALIZATION";
		case RIL_REQUEST_GET_CURRENT_CALLS:
			return "GET_CURRENT_CALLS";
		case RIL_REQUEST_DIAL:
			return "DIAL";
		case RIL_REQUEST_GET_IMSI:
			return "GET_IMSI";
		case RIL_REQUEST_HANGUP:
			return "HANGUP";
		case RIL_REQUEST_HANGUP_WAITING_OR_BACKGROUND:
			return "HANGUP_WAITING_OR_BACKGROUND";
		case RIL_REQUEST_HANGUP_FOREGROUND_RESUME_BACKGROUND:
			return "HANGUP_FOREGROUND_RESUME_BACKGROUND";
		case RIL_REQUEST_SWITCH_WAITING_OR_HOLDING_AND_ACTIVE:
			return "REQUEST_SWITCH_WAITING_OR_HOLDING_AND_ACTIVE";
		case RIL_REQUEST_CONFERENCE:
			return "CONFERENCE";
		case RIL_REQUEST_UDUB:
			return "UDUB";
		case RIL_REQUEST_LAST_CALL_FAIL_CAUSE:
			return "LAST_CALL_FAIL_CAUSE";
		case RIL_REQUEST_SIGNAL_STRENGTH:
			return "SIGNAL_STRENGTH";
		case RIL_REQUEST_VOICE_REGISTRATION_STATE:
			return "VOICE_REGISTRATION_STATE";
		case RIL_REQUEST_DATA_REGISTRATION_STATE:
			return "DATA_REGISTRATION_STATE";
		case RIL_REQUEST_OPERATOR:
			return "OPERATOR";
		case RIL_REQUEST_RADIO_POWER:
			return "RADIO_POWER";
		case RIL_REQUEST_DTMF:
			return "DTMF";
		case RIL_REQUEST_SEND_SMS:
			return "SEND_SMS";
		case RIL_REQUEST_SEND_SMS_EXPECT_MORE:
			return "SEND_SMS_EXPECT_MORE";
		case RIL_REQUEST_SETUP_DATA_CALL:
			return "SETUP_DATA_CALL";
		case RIL_REQUEST_SIM_IO:
			return "SIM_IO";
		case RIL_REQUEST_SEND_USSD:
			return "SEND_USSD";
		case RIL_REQUEST_CANCEL_USSD:
			return "CANCEL_USSD";
		case RIL_REQUEST_GET_CLIR:
			return "GET_CLIR";
		case RIL_REQUEST_SET_CLIR:
			return "SET_CLIR";
		case RIL_REQUEST_QUERY_CALL_FORWARD_STATUS:
			return "QUERY_CALL_FORWARD_STATUS";
		case RIL_REQUEST_SET_CALL_FORWARD:
			return "SET_CALL_FORWARD";
		case RIL_REQUEST_QUERY_CALL_WAITING:
			return "QUERY_CALL_WAITING";
		case RIL_REQUEST_SET_CALL_WAITING:
			return "SET_CALL_WAITING";
		case RIL_REQUEST_SMS_ACKNOWLEDGE:
			return "SMS_ACKNOWLEDGE";
		case RIL_REQUEST_GET_IMEI:
			return "GET_IMEI";
		case RIL_REQUEST_GET_IMEISV:
			return "GET_IMEISV";
		case RIL_REQUEST_ANSWER:
			return "ANSWER";
		case RIL_REQUEST_DEACTIVATE_DATA_CALL:
			return "DEACTIVATE_DATA_CALL";
		case RIL_REQUEST_QUERY_FACILITY_LOCK:
			return "QUERY_FACILITY_LOCK";
		case RIL_REQUEST_SET_FACILITY_LOCK:
			return "SET_FACILITY_LOCK";
		case RIL_REQUEST_CHANGE_BARRING_PASSWORD:
			return "CHANGE_BARRING_PASSWORD";
		case RIL_REQUEST_QUERY_NETWORK_SELECTION_MODE:
			return "QUERY_NETWORK_SELECTION_MODE";
		case RIL_REQUEST_SET_NETWORK_SELECTION_AUTOMATIC:
			return "SET_NETWORK_SELECTION_AUTOMATIC";
		case RIL_REQUEST_SET_NETWORK_SELECTION_MANUAL:
			return "SET_NETWORK_SELECTION_MANUAL";
		case RIL_REQUEST_QUERY_AVAILABLE_NETWORKS:
			return "QUERY_AVAILABLE_NETWORKS ";
		case RIL_REQUEST_DTMF_START:
			return "DTMF_START";
		case RIL_REQUEST_DTMF_STOP:
			return "DTMF_STOP";
		case RIL_REQUEST_BASEBAND_VERSION:
			return "BASEBAND_VERSION";
		case RIL_REQUEST_SEPARATE_CONNECTION:
			return "SEPARATE_CONNECTION";
		case RIL_REQUEST_SET_MUTE:
			return "SET_MUTE";
		case RIL_REQUEST_GET_MUTE:
			return "GET_MUTE";
		case RIL_REQUEST_QUERY_CLIP:
			return "QUERY_CLIP";
		case RIL_REQUEST_LAST_DATA_CALL_FAIL_CAUSE:
			return "LAST_DATA_CALL_FAIL_CAUSE";
		case RIL_REQUEST_DATA_CALL_LIST:
			return "DATA_CALL_LIST";
		case RIL_REQUEST_RESET_RADIO:
			return "RESET_RADIO";
		case RIL_REQUEST_OEM_HOOK_RAW:
			return "OEM_HOOK_RAW";
		case RIL_REQUEST_OEM_HOOK_STRINGS:
			return "OEM_HOOK_STRINGS";
		case RIL_REQUEST_SCREEN_STATE:
			return "SCREEN_STATE";
		case RIL_REQUEST_SET_SUPP_SVC_NOTIFICATION:
			return "SET_SUPP_SVC_NOTIFICATION";
		case RIL_REQUEST_WRITE_SMS_TO_SIM:
			return "WRITE_SMS_TO_SIM";
		case RIL_REQUEST_DELETE_SMS_ON_SIM:
			return "DELETE_SMS_ON_SIM";
		case RIL_REQUEST_SET_BAND_MODE:
			return "SET_BAND_MODE";
		case RIL_REQUEST_QUERY_AVAILABLE_BAND_MODE:
			return "QUERY_AVAILABLE_BAND_MODE";
		case RIL_REQUEST_STK_GET_PROFILE:
			return "REQUEST_STK_GET_PROFILE";
		case RIL_REQUEST_STK_SET_PROFILE:
			return "REQUEST_STK_SET_PROFILE";
		case RIL_REQUEST_STK_SEND_ENVELOPE_COMMAND:
			return "REQUEST_STK_SEND_ENVELOPE_COMMAND";
		case RIL_REQUEST_STK_SEND_TERMINAL_RESPONSE:
			return "REQUEST_STK_SEND_TERMINAL_RESPONSE";
		case RIL_REQUEST_STK_HANDLE_CALL_SETUP_REQUESTED_FROM_SIM:
			return "REQUEST_STK_HANDLE_CALL_SETUP_REQUESTED_FROM_SIM";
		case RIL_REQUEST_EXPLICIT_CALL_TRANSFER:
			return "REQUEST_EXPLICIT_CALL_TRANSFER";
		case RIL_REQUEST_SET_PREFERRED_NETWORK_TYPE:
			return "REQUEST_SET_PREFERRED_NETWORK_TYPE";
		case RIL_REQUEST_GET_PREFERRED_NETWORK_TYPE:
			return "REQUEST_GET_PREFERRED_NETWORK_TYPE";
		case RIL_REQUEST_GET_NEIGHBORING_CELL_IDS:
			return "REQUEST_GET_NEIGHBORING_CELL_IDS";
		case RIL_REQUEST_SET_LOCATION_UPDATES:
			return "REQUEST_SET_LOCATION_UPDATES";
		case RIL_REQUEST_CDMA_SET_SUBSCRIPTION_SOURCE:
			return "RIL_REQUEST_CDMA_SET_SUBSCRIPTION_SOURCE";
		case RIL_REQUEST_CDMA_SET_ROAMING_PREFERENCE:
			return "RIL_REQUEST_CDMA_SET_ROAMING_PREFERENCE";
		case RIL_REQUEST_CDMA_QUERY_ROAMING_PREFERENCE:
			return "RIL_REQUEST_CDMA_QUERY_ROAMING_PREFERENCE";
		case RIL_REQUEST_SET_TTY_MODE:
			return "RIL_REQUEST_SET_TTY_MODE";
		case RIL_REQUEST_QUERY_TTY_MODE:
			return "RIL_REQUEST_QUERY_TTY_MODE";
		case RIL_REQUEST_CDMA_SET_PREFERRED_VOICE_PRIVACY_MODE:
			return "RIL_REQUEST_CDMA_SET_PREFERRED_VOICE_PRIVACY_MODE";
		case RIL_REQUEST_CDMA_QUERY_PREFERRED_VOICE_PRIVACY_MODE:
			return "RIL_REQUEST_CDMA_QUERY_PREFERRED_VOICE_PRIVACY_MODE";
		case RIL_REQUEST_CDMA_FLASH:
			return "RIL_REQUEST_CDMA_FLASH";
		case RIL_REQUEST_CDMA_BURST_DTMF:
			return "RIL_REQUEST_CDMA_BURST_DTMF";
		case RIL_REQUEST_CDMA_SEND_SMS:
			return "RIL_REQUEST_CDMA_SEND_SMS";
		case RIL_REQUEST_CDMA_SMS_ACKNOWLEDGE:
			return "RIL_REQUEST_CDMA_SMS_ACKNOWLEDGE";
		case RIL_REQUEST_GSM_GET_BROADCAST_CONFIG:
			return "RIL_REQUEST_GSM_GET_BROADCAST_CONFIG";
		case RIL_REQUEST_GSM_SET_BROADCAST_CONFIG:
			return "RIL_REQUEST_GSM_SET_BROADCAST_CONFIG";
		case RIL_REQUEST_CDMA_GET_BROADCAST_CONFIG:
			return "RIL_REQUEST_CDMA_GET_BROADCAST_CONFIG";
		case RIL_REQUEST_CDMA_SET_BROADCAST_CONFIG:
			return "RIL_REQUEST_CDMA_SET_BROADCAST_CONFIG";
		case RIL_REQUEST_GSM_BROADCAST_ACTIVATION:
			return "RIL_REQUEST_GSM_BROADCAST_ACTIVATION";
		case RIL_REQUEST_CDMA_VALIDATE_AND_WRITE_AKEY:
			return "RIL_REQUEST_CDMA_VALIDATE_AND_WRITE_AKEY";
		case RIL_REQUEST_CDMA_BROADCAST_ACTIVATION:
			return "RIL_REQUEST_CDMA_BROADCAST_ACTIVATION";
		case RIL_REQUEST_CDMA_SUBSCRIPTION:
			return "RIL_REQUEST_CDMA_SUBSCRIPTION";
		case RIL_REQUEST_CDMA_WRITE_SMS_TO_RUIM:
			return "RIL_REQUEST_CDMA_WRITE_SMS_TO_RUIM";
		case RIL_REQUEST_CDMA_DELETE_SMS_ON_RUIM:
			return "RIL_REQUEST_CDMA_DELETE_SMS_ON_RUIM";
		case RIL_REQUEST_DEVICE_IDENTITY:
			return "RIL_REQUEST_DEVICE_IDENTITY";
		case RIL_REQUEST_GET_SMSC_ADDRESS:
			return "RIL_REQUEST_GET_SMSC_ADDRESS";
		case RIL_REQUEST_SET_SMSC_ADDRESS:
			return "RIL_REQUEST_SET_SMSC_ADDRESS";
		case RIL_REQUEST_EXIT_EMERGENCY_CALLBACK_MODE:
			return "REQUEST_EXIT_EMERGENCY_CALLBACK_MODE";
		case RIL_REQUEST_REPORT_SMS_MEMORY_STATUS:
			return "RIL_REQUEST_REPORT_SMS_MEMORY_STATUS";
		case RIL_REQUEST_REPORT_STK_SERVICE_IS_RUNNING:
			return "RIL_REQUEST_REPORT_STK_SERVICE_IS_RUNNING";
		case RIL_REQUEST_CDMA_GET_SUBSCRIPTION_SOURCE:
			return "RIL_REQUEST_CDMA_GET_SUBSCRIPTION_SOURCE";
		case RIL_REQUEST_ISIM_AUTHENTICATION:
			return "RIL_REQUEST_ISIM_AUTHENTICATION";
		case RIL_REQUEST_ACKNOWLEDGE_INCOMING_GSM_SMS_WITH_PDU:
			return "RIL_REQUEST_ACKNOWLEDGE_INCOMING_GSM_SMS_WITH_PDU";
		case RIL_REQUEST_STK_SEND_ENVELOPE_WITH_STATUS:
			return "RIL_REQUEST_STK_SEND_ENVELOPE_WITH_STATUS";
		case RIL_REQUEST_VOICE_RADIO_TECH:
			return "RIL_REQUEST_VOICE_RADIO_TECH";
		default:
			return "<unknown request: " + request + ">";
		}
	}

	protected String retToString(int req, Object ret) {
		if (ret == null)
			return "";
		switch (req) {
		// Don't log these return values, for privacy's sake.
		case RIL_REQUEST_GET_IMSI:
		case RIL_REQUEST_GET_IMEI:
		case RIL_REQUEST_GET_IMEISV:
			if (!RILDJ_LOGV) {
				// If not verbose logging just return and don't display IMSI
				// and IMEI, IMEISV
				return "*****";
			}
		}

		StringBuilder sb;
		String s;
		int length;
		if (ret instanceof int[]) {
			int[] intArray = (int[]) ret;
			length = intArray.length;
			sb = new StringBuilder("{");
			if (length > 0) {
				int i = 0;
				sb.append(intArray[i++]);
				while (i < length) {
					sb.append(", ").append(intArray[i++]);
				}
			}
			sb.append("}");
			s = sb.toString();
		} else if (ret instanceof String[]) {
			String[] strings = (String[]) ret;
			length = strings.length;
			sb = new StringBuilder("{");
			if (length > 0) {
				int i = 0;
				sb.append(strings[i++]);
				while (i < length) {
					sb.append(", ").append(strings[i++]);
				}
			}
			sb.append("}");
			s = sb.toString();
		} else if (ret instanceof List<?>) {
			List<?> list = ((List<?>) ret);
			Object o = "";
			if (list.size() > 0) {
				o = list.get(0);
			}
			if (o instanceof DriverCall) {
				@SuppressWarnings("unchecked")
				List<DriverCall> calls = (List<DriverCall>) list;
				sb = new StringBuilder(" ");
				for (DriverCall dc : calls) {
					sb.append("[").append(dc).append("] ");
				}
				s = sb.toString();
			} else if (o instanceof NeighboringCellInfo) {
				@SuppressWarnings("unchecked")
				List<NeighboringCellInfo> cells = (List<NeighboringCellInfo>) list;
				sb = new StringBuilder(" ");
				for (NeighboringCellInfo cell : cells) {
					sb.append(cell).append(" ");
				}
				s = sb.toString();
			} else {
				s = ret.toString();
			}
		} else {
			s = ret.toString();
		}
		return s;
	}

	static String responseToString(int request) {
		/*
		 * cat libs/telephony/ril_unsol_commands.h \ | egrep "^ *{RIL_" \ | sed
		 * -re 's/\{RIL_([^,]+),[^,]+,([^}]+).+/case RIL_\1: return "\1";/'
		 */
		switch (request) {
		case RIL_UNSOL_RESPONSE_RADIO_STATE_CHANGED:
			return "UNSOL_RESPONSE_RADIO_STATE_CHANGED";
		case RIL_UNSOL_RESPONSE_CALL_STATE_CHANGED:
			return "UNSOL_RESPONSE_CALL_STATE_CHANGED";
		case RIL_UNSOL_RESPONSE_VOICE_NETWORK_STATE_CHANGED:
			return "UNSOL_RESPONSE_VOICE_NETWORK_STATE_CHANGED";
		case RIL_UNSOL_RESPONSE_NEW_SMS:
			return "UNSOL_RESPONSE_NEW_SMS";
		case RIL_UNSOL_RESPONSE_NEW_SMS_STATUS_REPORT:
			return "UNSOL_RESPONSE_NEW_SMS_STATUS_REPORT";
		case RIL_UNSOL_RESPONSE_NEW_SMS_ON_SIM:
			return "UNSOL_RESPONSE_NEW_SMS_ON_SIM";
		case RIL_UNSOL_ON_USSD:
			return "UNSOL_ON_USSD";
		case RIL_UNSOL_ON_USSD_REQUEST:
			return "UNSOL_ON_USSD_REQUEST";
		case RIL_UNSOL_NITZ_TIME_RECEIVED:
			return "UNSOL_NITZ_TIME_RECEIVED";
		case RIL_UNSOL_SIGNAL_STRENGTH:
			return "UNSOL_SIGNAL_STRENGTH";
		case RIL_UNSOL_DATA_CALL_LIST_CHANGED:
			return "UNSOL_DATA_CALL_LIST_CHANGED";
		case RIL_UNSOL_SUPP_SVC_NOTIFICATION:
			return "UNSOL_SUPP_SVC_NOTIFICATION";
		case RIL_UNSOL_STK_SESSION_END:
			return "UNSOL_STK_SESSION_END";
		case RIL_UNSOL_STK_PROACTIVE_COMMAND:
			return "UNSOL_STK_PROACTIVE_COMMAND";
		case RIL_UNSOL_STK_EVENT_NOTIFY:
			return "UNSOL_STK_EVENT_NOTIFY";
		case RIL_UNSOL_STK_CALL_SETUP:
			return "UNSOL_STK_CALL_SETUP";
		case RIL_UNSOL_SIM_SMS_STORAGE_FULL:
			return "UNSOL_SIM_SMS_STORAGE_FULL";
		case RIL_UNSOL_SIM_REFRESH:
			return "UNSOL_SIM_REFRESH";
		case RIL_UNSOL_CALL_RING:
			return "UNSOL_CALL_RING";
		case RIL_UNSOL_RESPONSE_SIM_STATUS_CHANGED:
			return "UNSOL_RESPONSE_SIM_STATUS_CHANGED";
		case RIL_UNSOL_RESPONSE_CDMA_NEW_SMS:
			return "UNSOL_RESPONSE_CDMA_NEW_SMS";
		case RIL_UNSOL_RESPONSE_NEW_BROADCAST_SMS:
			return "UNSOL_RESPONSE_NEW_BROADCAST_SMS";
		case RIL_UNSOL_CDMA_RUIM_SMS_STORAGE_FULL:
			return "UNSOL_CDMA_RUIM_SMS_STORAGE_FULL";
		case RIL_UNSOL_RESTRICTED_STATE_CHANGED:
			return "UNSOL_RESTRICTED_STATE_CHANGED";
		case RIL_UNSOL_ENTER_EMERGENCY_CALLBACK_MODE:
			return "UNSOL_ENTER_EMERGENCY_CALLBACK_MODE";
		case RIL_UNSOL_CDMA_CALL_WAITING:
			return "UNSOL_CDMA_CALL_WAITING";
		case RIL_UNSOL_CDMA_OTA_PROVISION_STATUS:
			return "UNSOL_CDMA_OTA_PROVISION_STATUS";
		case RIL_UNSOL_CDMA_INFO_REC:
			return "UNSOL_CDMA_INFO_REC";
		case RIL_UNSOL_OEM_HOOK_RAW:
			return "UNSOL_OEM_HOOK_RAW";
		case RIL_UNSOL_RINGBACK_TONE:
			return "UNSOL_RINGBACK_TONE";
		case RIL_UNSOL_RESEND_INCALL_MUTE:
			return "UNSOL_RESEND_INCALL_MUTE";
		case RIL_UNSOL_CDMA_SUBSCRIPTION_SOURCE_CHANGED:
			return "CDMA_SUBSCRIPTION_SOURCE_CHANGED";
		case RIL_UNSOL_CDMA_PRL_CHANGED:
			return "UNSOL_CDMA_PRL_CHANGED";
		case RIL_UNSOL_EXIT_EMERGENCY_CALLBACK_MODE:
			return "UNSOL_EXIT_EMERGENCY_CALLBACK_MODE";
		case RIL_UNSOL_RIL_CONNECTED:
			return "UNSOL_RIL_CONNECTED";
		case RIL_UNSOL_VOICE_RADIO_TECH_CHANGED:
			return "UNSOL_VOICE_RADIO_TECH_CHANGED";
		case RIL_UNSOL_STK_SEND_SMS_RESULT:
			return "RIL_UNSOL_STK_SEND_SMS_RESULT";
		default:
			return "<unknown response: " + request + ">";
		}
	}

	protected void riljLog(String msg) {
		Log.d(LOG_TAG, msg);
	}

	protected void riljLogv(String msg) {
		Log.v(LOG_TAG, msg);
	}

	protected void unsljLog(int response) {
		riljLog("[UNSL]< " + responseToString(response));
	}

	protected void unsljLogMore(int response, String more) {
		riljLog("[UNSL]< " + responseToString(response) + " " + more);
	}

	protected void unsljLogRet(int response, Object ret) {
		riljLog("[UNSL]< " + responseToString(response) + " "
				+ retToString(response, ret));
	}

	protected void unsljLogvRet(int response, Object ret) {
		riljLogv("[UNSL]< " + responseToString(response) + " "
				+ retToString(response, ret));
	}

	public static String join(CharSequence delimiter, Object[] tokens) {
		StringBuilder sb = new StringBuilder();
		boolean firstTime = true;
		for (Object token : tokens) {
			if (firstTime) {
				firstTime = false;
			} else {
				sb.append(delimiter);
			}
			sb.append(token);
		}
		return sb.toString();
	}
}
