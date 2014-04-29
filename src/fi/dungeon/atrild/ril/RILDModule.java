package fi.dungeon.atrild.ril;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import android.util.Log;

public class RILDModule implements BeanFactory {

	private static final String LOG_TAG = "ATRILD";
	public final Map<String, Object> instances = new LinkedHashMap<String, Object>();
	private boolean running;

	static RILDModule rildModule;

	public static synchronized BeanFactory getSingleton() {
		if (rildModule == null) {
			rildModule = new RILDModule();
			rildModule.initialize();
		}
		return rildModule;
	}

	private RILDModule() {
	}

	public void initialize() {
		registerBean(new AndroidSetup());

		RILSocket socket = new RILSocket();
		registerBean(socket);

		ATDevice at = new ATDevice();
		registerBean(at);

		PPPDMonitor pm = new PPPDMonitor();
		registerBean(pm);

		ATCommandsImpl ci = new ATCommandsImpl();
		registerBean(ci);
		ci.setAt(at);
		ci.setmPPPDMonitor(pm);

		at.setCI(ci);

		RILD rild = new RILD();
		registerBean(rild);
		rild.setCI(ci);
		rild.setSocket(socket);
		ci.setDefaultHandler(rild.mHandler);
		socket.setRILHandler(rild.mHandler);
	}

	public void registerBean(Object o) {
		instances.put(o.getClass().getName(), o);
	}

	public void destroy() {
		instances.clear();
	}

	public void start() {
		Log.i(LOG_TAG, "Starting");
		if (isRunning()) {
			return;
		}
		for (Map.Entry<String, Object> entry : instances.entrySet()) {
			try {
				Object o = entry.getValue();
				if (o instanceof Bean) {
					Log.d(LOG_TAG, "Starting " + entry.getKey());
					((Bean) o).start();
				} else {
					Method m = o.getClass().getDeclaredMethod("start");
					Log.d(LOG_TAG, "Starting " + entry.getKey());
					m.invoke(o);
				}
			} catch (NoSuchMethodException e) {
				// Ignore
			} catch (Exception e) {
				Log.e(LOG_TAG, "Error starting " + entry.getKey(), e);
			}
		}
		running = true;
		afterStart();
		Log.i(LOG_TAG, "Started");
	}

	public void afterStart() {
		for (Map.Entry<String, Object> entry : instances.entrySet()) {
			try {
				Object o = entry.getValue();
				if (o instanceof Bean) {
					Log.d(LOG_TAG, "Calling afterStart() on " + entry.getKey());
					((Bean) o).afterStart();
				}
			} catch (Exception e) {
				Log.e(LOG_TAG, "Error in calling afterStart() on " + entry.getKey(), e);
			}
		}
	}

	public void beforeStop() {
		for (Map.Entry<String, Object> entry : instances.entrySet()) {
			try {
				Object o = entry.getValue();
				if (o instanceof Bean) {
					Log.d(LOG_TAG, "Calling beforeStop() on " + entry.getKey());
					((Bean) o).beforeStop();
				}
			} catch (Exception e) {
				Log.e(LOG_TAG, "Error in calling beforeStop() on " + entry.getKey(), e);
			}
		}
	}

	public void stop() {
		Log.i(LOG_TAG, "Stopping");
		//if (!isRunning()) {
		//	return;
		//}
		beforeStop();
		running = false;
		for (Map.Entry<String, Object> entry : instances.entrySet()) {
			try {
				Object o = entry.getValue();
				if (o instanceof Bean) {
					Log.d(LOG_TAG, "Stopping " + entry.getKey());
					((Bean) o).stop();
				} else {
					Method m = o.getClass().getDeclaredMethod("stop");
					Log.d(LOG_TAG, "Stopping " + entry.getKey());
					m.invoke(o);
				}
			} catch (NoSuchMethodException e) {
				// Ignore
			} catch (Exception e) {
				Log.e(LOG_TAG, "Error stopping " + entry.getKey(), e);
			}
		}
		Log.i(LOG_TAG, "Stopped");
	}

	public Object getBean(String key) {
		return instances.get(key);
	}

	@SuppressWarnings("unchecked")
	public <T> T getBean(Class<T> type) {
		for (Map.Entry<String, Object> entry : instances.entrySet()) {
			if (entry.getValue().getClass().equals(type)) {
				return (T) entry.getValue();
			}
		}
		return null;
	}

	public void setBeanProperty(String name, String property, String value) {
		Object bean = getBean(name);
		if (bean == null) {
			throw new IllegalArgumentException("No such bean: " + name);
		}
		String setter = "set" + property;
		try {
			Method m = bean.getClass().getDeclaredMethod(setter, String.class);
			m.invoke(bean, value);
		} catch (Exception e) {
			throw new IllegalArgumentException("Error setting: " + name + "."
					+ property + "=" + value, e);
		}
	}

	@Override
	public String getBeanProperty(String name, String property) {
		Object bean = getBean(name);
		if (bean == null) {
			throw new IllegalArgumentException("No such bean: " + name);
		}
		String getter = "get" + property;
		Object value;
		try {
			Method m = bean.getClass().getDeclaredMethod(getter);
			value = m.invoke(bean);
		} catch (Exception e) {
			throw new IllegalArgumentException("Error getting: " + name + "."
					+ property, e);
		}
		return String.valueOf(value);
	}
	
	public boolean isRunning() {
		return running;
	}

	public void setRunning(boolean running) {
		this.running = running;
	}

}
