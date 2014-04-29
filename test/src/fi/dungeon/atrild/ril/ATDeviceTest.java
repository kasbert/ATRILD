package fi.dungeon.atrild.ril;

import java.util.List;

import junit.framework.TestCase;

public class ATDeviceTest extends TestCase{

	static ATDevice at = new ATDevice();
	static LooperThread t = new LooperThread();
	static {
		t.start();
	}
	
	public void testAT() throws InterruptedException {
		ATResponse res = at.at_send_command("AT", ATCommandType.NO_RESULT);
		String s =join(res.mIntermediates);
		android.util.Log.v("test", "" + s.length());
		assertEquals("", s);
	}

	public void testATI() throws InterruptedException {
		ATRequest req = ATRequest.obtain("ATI", ATCommandType.MULTILINE);
		ATResponse res = at.doAT(req);
		String s =join(res.mIntermediates);
		assertEquals("Manufacturer: huawei\nModel: E1552\nRevision: 11.608.13.02.00\nIMEI: 353143034375551\n+GCAP: +CGSM,+DS,+ES", s);
		req.release();
	}

	public void testATQ() throws InterruptedException {
		ATRequest req = ATRequest.obtain("AT+CREG?", ATCommandType.SINGLELINE);
		ATResponse res = at.doAT(req);
		String s =join(res.mIntermediates);
		assertEquals("+CREG: 0,1", s);
		req.release();
	}

	public void testAT_CIMI() throws InterruptedException {
		ATRequest req = ATRequest.obtain("AT+CIMI", ATCommandType.SINGLELINE);
		ATResponse res = at.doAT(req);
		assertEquals(ATRequestStatus.OK, res.mStatus);

		String s =join(res.mIntermediates);
		assertEquals(true, s.startsWith("24491"));
		req.release();
	}
	
	public void testAT_CGSN() throws InterruptedException {
		ATRequest req = ATRequest.obtain("AT+CGSN", ATCommandType.SINGLELINE);
		ATResponse res = at.doAT(req);
		String s =join(res.mIntermediates);
		assertEquals(true, s.startsWith("3531"));
		req.release();
	}

	public String join(List<String> mResponse) {
		String ss="";
		for (String sss: mResponse) {
			if (ss.length() != 0) {
				ss += "\n";
			}
			ss += sss;
		}
		return ss;
	}
	
}
