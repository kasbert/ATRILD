package fi.dungeon.atrild.ril;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import fi.dungeon.atrild.R;

import android.util.Log;

/*
 * Misc hacks for setting up ATRILD 
 */

interface ProcessOutput {
	void output(String s);
}

public class AndroidSetup implements Bean, ProcessOutput {

	static final String LOG_TAG = "ATRILJ";
	private static final long MAX_WAIT = 10;

	public static class ProcessOutputReader implements Runnable {

		private InputStream is;
		private ProcessOutput po;
		public ProcessOutputReader(InputStream is, ProcessOutput po) {
			this.is = is;
			this.po = po;
		}

		@Override
		public void run() {
			BufferedReader br = new BufferedReader(new InputStreamReader(is));
			try {
				String line;
				while ((line = br.readLine()) != null) {
					po.output(line);
				}
			} catch (IOException e) {
				Log.e(LOG_TAG, "Error reading output", e);
			} finally {
				try {
					br.close();
				} catch (IOException e) {
					Log.e(LOG_TAG, "Internal error", e);
				}
			}
			try {
				is.close();
			} catch (IOException e) {
				Log.e(LOG_TAG, "Internal error", e);
			}
			Log.d(LOG_TAG, "End of stream");
		}
	};

	
	
	public AndroidSetup() {
		/* FIXME
		 * - Check existense of /dev/ttyUSB* (serial modules)
		 * - Check permissions for /dev/ttyUSB*
		 * - Check existence of pppd module (feature)
		 */		
		try {
			runCommand(this, "id");
		} catch (IOException e) {
			Log.e(LOG_TAG, "Error in starting command", e);
			return;
		}
	}

	public void start() {
		Log.i(LOG_TAG, "Starting");
		/*
		try {
			runCommand(this, "mkdir", "/dev/socket/atrild");
		} catch (IOException e) {
			Log.e(LOG_TAG, "Error in starting command", e);
			return;
		}
		try {
			runCommand(this, "chmod", "777", "/dev/socket");
		} catch (IOException e) {
			Log.e(LOG_TAG, "Error in starting command", e);
			return;
		}
		*/
	}

	public void stop() {
	}

	public boolean isSystemServiceInstalled() {
		// check if /system/bin/rild contains 'ATRILD' string
		BufferedReader br = null;
		try {
			br = new BufferedReader(new FileReader("/system/bin/rild"));
			String s = br.readLine();
			for (int i = 0; s != null && i < 5; i++) {
				if (s.contains("ATRILD")) {
					return true;
				}
				s = br.readLine();
			}
			Log.e(LOG_TAG, "/system/bin/rild does not contain string 'ATRILD'");
		} catch (Exception e) {
			Log.e(LOG_TAG, "Cannot read /system/bin/rild", e);
		} finally {
			try {
				br.close();
			} catch (IOException e) {
				Log.e(LOG_TAG, "Internal error", e);
			}
		}
		return false;
	}

	private boolean isPPPEnabled() {
		// Check kernel support for ppp (/proc/devices)
		BufferedReader br = null;
		try {
			br = new BufferedReader(new FileReader("/proc/devices"));
			String s = br.readLine();
			while (s != null) {
				if (s.contains(" ppp")) {
					return true;
				}
				s = br.readLine();
			}
			Log.e(LOG_TAG, "/proc/devices does not contain string ' ppp'");
		} catch (Exception e) {
			Log.e(LOG_TAG, "Cannot read /proc/devices", e);
		} finally {
			try {
				br.close();
			} catch (IOException e) {
				Log.e(LOG_TAG, "Internal error", e);
			}
		}
		return false;
	}

	private boolean isDevicePresent() {
		// Check existence of /dev/ttyUSB*
		File dev = new File("/dev");
		for (File f : dev.listFiles()) {
			if (f.getName().contains("ttyUSB")) {
				return true;
			}
		}
		Log.e(LOG_TAG, "No /dev/ttyUSB*");
		return false;
	}

	public String checkInstallation() {
		List<String> errors = new ArrayList<String>();

		if (!isSystemServiceInstalled()) {
			errors.add("ATRIL system service is not installed");
		}
		if (!isPPPEnabled()) {
			errors.add("Kernel does not support PPP");
		}
		if (!isDevicePresent()) {
			errors.add("No /dev/ttyUSB*");
		}

		// TODO Check serial modules /proc/tty/drivers)
		// TODO Check permissions for /dev/ttyUSB*
		StringBuilder sb = new StringBuilder();
		for (String error : errors) {
			sb.append(error);
			sb.append("\n");
		}
		return sb.toString();
	}

	public String runInstall (String file) throws IOException {
		if (!new File("/system/bin/su").exists()) {
			throw new FileNotFoundException("No /system/bin/su. Install manually \"adb shell /system/bin/sh "+ file + "\"");
		}
		
		final StringBuilder sb = new StringBuilder();
		ProcessOutput po = new ProcessOutput() {
			@Override
			public void output(String s) {
				sb.append(s);
				sb.append("\n");
			}
		};
		runCommand(po, "/system/bin/su", "/system/bin/sh", file);
		return sb.toString();
	}
	
	@Override
	public void afterStart() {
		Log.i(LOG_TAG, "Starting");
		/*
		try {
			runCommand(this, "chmod", "755", "/dev/socket");
		} catch (IOException e) {
			Log.e(LOG_TAG, "Error in starting command", e);
			return;
		}
		uninterruptedSleep(500L);
		try {
			runCommand(this, "chmod", "666", "/dev/socket/atrild/rild");
		} catch (IOException e) {
			Log.e(LOG_TAG, "Error in starting command", e);
			return;
		}
		*/
	}

	@Override
	public void beforeStop() {
	}

	protected int runCommand(ProcessOutput po, String... commandLine) throws IOException {
		Log.i(LOG_TAG, "Starting '" + Arrays.asList(commandLine) + "'");
		ProcessBuilder pb = new ProcessBuilder();
		pb.command(commandLine);
		pb.redirectErrorStream(true);

		Process process = pb.start();
		Thread t = null;
		
		try {
			InputStream is = process.getInputStream();
			ProcessOutputReader reader = new ProcessOutputReader(is, po);
			t = new Thread(reader);
			t.start();
			
//			StringBuilder sb = new StringBuilder();
//			sb.append("\n\r");
//			for (String s: commandLine) {
//				sb.append(s).append(" ");
//			}
//			sb.append("\n\r");
//			process.getOutputStream().write(sb.toString().getBytes());
			process.getOutputStream().close();
			
			for (long i = 0; t.isAlive() && i < MAX_WAIT; i += 200) {
				uninterruptedSleep(200L);
			}
		} finally {
			try {
				process.destroy();
				uninterruptedSleep(100L);
			} catch (Exception e) {
				Log.e(LOG_TAG, "Error destroying", e);
			}
			if (t != null) {
				if (t.isAlive()) {
					t.interrupt();
				}
				try {
					t.join(2000L);
				} catch (Exception e) {
					Log.e(LOG_TAG, "Internal error", e);
				}
			}
		}
		int exitValue = process.exitValue();
		Log.i(LOG_TAG, "exit status " + exitValue);
		return exitValue;
	}

	protected void uninterruptedSleep(long delay) {
		try {
			Thread.sleep(delay);
		} catch (Exception e) {
			Log.e(LOG_TAG, "Internal error", e);
		}
	}

	public void output(String line) {
		Log.d(LOG_TAG, "< '" + line + "'");
	}
}
