package fi.dungeon.atrild.ril;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;

import reloc.com.android.internal.telephony.CommandException;
import reloc.com.android.internal.telephony.CommandException.Error;

//import libcore.util.Arrays;
import android.annotation.TargetApi;
import android.os.Build;
import android.util.Log;

public class PPPDMonitor {
	static final String LOG_TAG = "PPPDJ";

	private static final String DEFAULT_INTERFACE_NAME = "ppp0";
	private static final String DEFAULT_PRIMARY_DNS = "8.8.8.8";
	private static final String DEFAULT_SECONDARY_DNS = "8.8.4.4";

	private static final long MAX_WAIT_CONNECT = 10000L;

	private InputStream is;
	private Process process;
	private Thread reader;
	private String interfaceName = DEFAULT_INTERFACE_NAME;
	private String localIPAddress = "";
	private String remoteIPAddress = "";
	private String primaryDNSAddress = DEFAULT_PRIMARY_DNS;
	private String secondaryDNAddress = DEFAULT_SECONDARY_DNS;
	private boolean connected;
	private boolean running;

	private String pppdDevice = "ttyS2"; // "/dev/ttyUSB0";

	public class PPPDReader implements Runnable {

		@Override
		public void run() {
			BufferedReader br = new BufferedReader(new InputStreamReader(is));
			try {
				String line;
				while (running && (line = br.readLine()) != null) {
					Log.d(LOG_TAG, "< '" + line + "'");
					parsePPPDLine(line);
				}
			} catch (IOException e) {
				if (running) {
					Log.e(LOG_TAG, "Error reading pppd output", e);
				} else {
					Log.d(LOG_TAG,
							"Error reading pppd output: " + e.getMessage());
				}
			} finally {
				try {
					br.close();
				} catch (IOException e) {
					Log.e(LOG_TAG, "Internal error", e);
				}
			}
			Log.d(LOG_TAG, "End of stream");
		}

	};

	public void connect() throws IOException {
		Log.i(LOG_TAG, "Starting pppd");
		interfaceName = DEFAULT_INTERFACE_NAME;
		localIPAddress = "";
		remoteIPAddress = "";
		primaryDNSAddress = DEFAULT_PRIMARY_DNS;
		secondaryDNAddress = DEFAULT_SECONDARY_DNS;
		connected = false;

		ProcessBuilder pb = new ProcessBuilder();
		String[] commandLine = new String[] { "/system/bin/pppd", "nodetach", "debug",
				"ttyS2"  };
		pb.command(commandLine);
		pb.directory(new File("/"));
		pb.redirectErrorStream(true);
		Log.i(LOG_TAG, "Starting '" + Arrays.asList(commandLine) + "'");
		process = pb.start();
		is = process.getInputStream();
		process.getOutputStream().close();
		PPPDReader pppdReader = new PPPDReader();
		running = true;
		reader = new Thread(pppdReader);
		reader.start();
		for (long i = 0; reader.isAlive() && !connected && i < MAX_WAIT_CONNECT; i += 200) {
			uninterruptedSleep(200L);
		}
		if (connected) {
			uninterruptedSleep(1000L);
			Log.i(LOG_TAG, "pppd connected, address '" + localIPAddress + "'");
		} else {
			Log.e(LOG_TAG, "pppd connection failed");
			disconnect();
			throw new CommandException(Error.GENERIC_FAILURE);
		}
	}

	public void disconnect() {
		Log.i(LOG_TAG, "Stopping pppd");
		running = false;
		if (process != null) {
			try {
				process.destroy();
				uninterruptedSleep(2000L);
				Log.i(LOG_TAG, "pppd exit status " + process.exitValue());
			} catch (Exception e) {
				Log.e(LOG_TAG, "Error destroying pppd", e);
			} finally {
				process = null;
			}
		}
		if (reader != null) {
			try {
				if (reader.isAlive()) {
					reader.interrupt();
				}
				reader.join(2000L);
			} catch (Exception e) {
				Log.e(LOG_TAG, "Internal error", e);
			} finally {
				reader = null;
			}
		}
		Log.i(LOG_TAG, "pppd stopped");
	}

	protected void uninterruptedSleep(long delay) {
		try {
			Thread.sleep(delay);
		} catch (Exception e) {
			Log.e(LOG_TAG, "Internal error", e);
		}
	}

	public void parsePPPDLine(String line) {
		String[] tokens = line.split("[ ]+");
		if (line.startsWith("Using interface ")) {
			interfaceName = tokens[2];
		} else if (line.startsWith("local  IP address ")) {
			connected = true;
			localIPAddress = tokens[3];
		} else if (line.startsWith("remote IP address ")) {
			remoteIPAddress = tokens[3];
		} else if (line.startsWith("primary   DNS address ")) {
			primaryDNSAddress = tokens[3];
		} else if (line.startsWith("secondary DNS address ")) {
			secondaryDNAddress = tokens[3];
		}
	}

	public boolean isStarted() {
		return reader != null;
	}

	public String getInterfaceName(int cid) {
		return interfaceName;
	}

	@TargetApi(Build.VERSION_CODES.GINGERBREAD)
	public List<String> getInterfaceAddresses(String ifname) {
		List<String> addrs = new ArrayList<String>();
		if (connected && ifname.equals(interfaceName)) {
			addrs.add(localIPAddress);
			return addrs;
		}
		Enumeration<NetworkInterface> ni;
		try {
			ni = NetworkInterface.getNetworkInterfaces();
			while (ni.hasMoreElements()) {
				NetworkInterface nextElement = ni.nextElement();
				if (nextElement.getDisplayName().equals(ifname)) {
					for (InterfaceAddress f : nextElement
							.getInterfaceAddresses()) {
						addrs.add(f.getAddress().getHostAddress());
					}
					break;
				}
			}
		} catch (SocketException e) {
			Log.e(LOG_TAG, "Cannt get network intefaces", e);
		}
		return addrs;
	}

	public String[] getDNSAddresses(String ifname) {
		return new String[] { primaryDNSAddress, secondaryDNAddress };
	}

	public String[] getGateways(String ifname) {
		return new String[] { remoteIPAddress };
	}

	public String getPPPDDevice() {
		return pppdDevice;
	}

	public void setDeviceName(String pppdDevice) {
		this.pppdDevice = pppdDevice;
	}

}
