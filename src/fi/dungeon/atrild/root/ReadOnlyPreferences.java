/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fi.dungeon.atrild.root;

import android.content.SharedPreferences;
import android.util.Log;
import android.util.Xml;

//import dalvik.system.BlockGuard;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

final class ReadOnlyPreferences implements SharedPreferences {
	private static final String TAG = "ReadOnlyPreferences";
	//private static final boolean DEBUG = false;

	// Lock ordering rules:
	// - acquire SharedPreferencesImpl.this before EditorImpl.this
	// - acquire mWritingToDiskLock before EditorImpl.this

	private final File mFile;

	private Map<String, Object> mMap; // guarded by 'this'
	private boolean mLoaded = false; // guarded by 'this'
	private long mStatTimestamp; // guarded by 'this'
	private long mStatSize; // guarded by 'this'

	private static final Object mContent = new Object();
	private final WeakHashMap<OnSharedPreferenceChangeListener, Object> mListeners = new WeakHashMap<OnSharedPreferenceChangeListener, Object>();

	public ReadOnlyPreferences(File file) {
		mFile = file;
		mLoaded = false;
		mMap = null;
		startPoller();
	}

	private void startPoller() {
		synchronized (this) {
			mLoaded = false;
		}
		Thread t = new Thread("ReadOnlyPreferences-load") {
			public void run() {
				synchronized (ReadOnlyPreferences.this) {
					loadFromDiskLocked();
				}
				while (true) {
					try {
						Thread.sleep(5000L);
					} catch (InterruptedException e) {
						Log.w(TAG, "Interrupted");
					}
					Map<String, Object> oldMap = mMap;
					synchronized (ReadOnlyPreferences.this) {
						if (!hasFileChanged()) {
							continue;
						}
						Log.d(TAG, "File change detected");
						mLoaded = false;
						loadFromDiskLocked();
					}
					notifyListeners(oldMap);
				}
			}

		};
		t.setDaemon(true);
		t.start();
	}

	private void loadFromDiskLocked() {
		if (mLoaded) {
			return;
		}
		Map<String, Object> map = loadFromDisk();
		if (map != null) {
			mMap = map;
			mStatTimestamp = mFile.lastModified();
			mStatSize = mFile.length();
		} else {
			mMap = new HashMap<String, Object>();
		}
		notifyAll();
	}

	private Map<String, Object> loadFromDisk() {
		// Debugging
		if (mFile.exists() && !mFile.canRead()) {
			Log.w(TAG, "Attempt to read preferences file " + mFile
					+ " without permission");
		}

		Map<String, Object> map = null;
		if (mFile.exists() && mFile.canRead()) {
			try {
				BufferedInputStream str = new BufferedInputStream(
						new FileInputStream(mFile), 16 * 1024);
				map = (Map<String, Object>) readMapXml(str);
				str.close();
			} catch (XmlPullParserException e) {
				Log.w(TAG, "getSharedPreferences", e);
			} catch (FileNotFoundException e) {
				Log.w(TAG, "getSharedPreferences", e);
			} catch (IOException e) {
				Log.w(TAG, "getSharedPreferences", e);
			}
		}
		mLoaded = true;
		return map;
	}

	// Has the file changed out from under us?
	private boolean hasFileChanged() {
		if (!mFile.exists()) {
			return false;
		}
		synchronized (this) {
			return mStatTimestamp != mFile.lastModified()
					|| mStatSize != mFile.length();
		}
	}

	private void notifyListeners(Map<String, Object> oldMap) {
		if (mListeners.size() == 0) {
			return;
		}
		ArrayList<String> keysModified = new ArrayList<String>();
		for (Map.Entry<String, Object> e : mMap.entrySet()) {
			String k = e.getKey();
			Object v = e.getValue();
			if (oldMap.containsKey(k)) {
				Object existingValue = oldMap.get(k);
				if (existingValue != null && existingValue.equals(v)) {
					continue;
				}
			}
			Log.d(TAG, "Value for "+ k + " changed to " + v);
			keysModified.add(k);
		}
		ArrayList<String> keysRemoved = new ArrayList<String>();
		for (Map.Entry<String, Object> e : oldMap.entrySet()) {
			String k = e.getKey();
			if (mMap.containsKey(k)) {
				continue;
			}
			Log.d(TAG, "Value for "+ k + " removed");
			keysRemoved.add(k);
		}
		for (OnSharedPreferenceChangeListener listener : mListeners.keySet()) {
			for (String key : keysRemoved) {
				listener.onSharedPreferenceChanged(this, key);
			}
			for (String key : keysModified) {
				listener.onSharedPreferenceChanged(this, key);
			}
		}
	}

	public void registerOnSharedPreferenceChangeListener(
			OnSharedPreferenceChangeListener listener) {
		synchronized (this) {
			mListeners.put(listener, mContent);
		}
	}

	public void unregisterOnSharedPreferenceChangeListener(
			OnSharedPreferenceChangeListener listener) {
		synchronized (this) {
			mListeners.remove(listener);
		}
	}

	private void awaitLoadedLocked() {
		if (!mLoaded) {
			// Raise an explicit StrictMode onReadFromDisk for this
			// thread, since the real read will be in a different
			// thread and otherwise ignored by StrictMode.
			// BlockGuard.getThreadPolicy().onReadFromDisk();
		}
		while (!mLoaded) {
			try {
				wait();
			} catch (InterruptedException unused) {
			}
		}
	}

	public Map<String, ?> getAll() {
		synchronized (this) {
			awaitLoadedLocked();
			// noinspection unchecked
			return new HashMap<String, Object>(mMap);
		}
	}

	public String getString(String key, String defValue) {
		synchronized (this) {
			awaitLoadedLocked();
			String v = (String) mMap.get(key);
			return v != null ? v : defValue;
		}
	}

	public Set<String> getStringSet(String key, Set<String> defValues) {
		synchronized (this) {
			awaitLoadedLocked();
			Set<String> v = (Set<String>) mMap.get(key);
			return v != null ? v : defValues;
		}
	}

	public int getInt(String key, int defValue) {
		synchronized (this) {
			awaitLoadedLocked();
			Integer v = (Integer) mMap.get(key);
			return v != null ? v : defValue;
		}
	}

	public long getLong(String key, long defValue) {
		synchronized (this) {
			awaitLoadedLocked();
			Long v = (Long) mMap.get(key);
			return v != null ? v : defValue;
		}
	}

	public float getFloat(String key, float defValue) {
		synchronized (this) {
			awaitLoadedLocked();
			Float v = (Float) mMap.get(key);
			return v != null ? v : defValue;
		}
	}

	public boolean getBoolean(String key, boolean defValue) {
		synchronized (this) {
			awaitLoadedLocked();
			Boolean v = (Boolean) mMap.get(key);
			return v != null ? v : defValue;
		}
	}

	public boolean contains(String key) {
		synchronized (this) {
			awaitLoadedLocked();
			return mMap.containsKey(key);
		}
	}

	@Override
	public Editor edit() {
		throw new InternalError("Not implemented");
	}

	// Copy from XmlUtils

	/**
	 * Read a HashMap from an InputStream containing XML. The stream can
	 * previously have been written by writeMapXml().
	 * 
	 * @param in
	 *            The InputStream from which to read.
	 * 
	 * @return HashMap The resulting map.
	 * 
	 * @see #readListXml
	 * @see #readValueXml
	 * @see #readThisMapXml #see #writeMapXml
	 */
	public static final HashMap<String, Object> readMapXml(InputStream in)
			throws XmlPullParserException, java.io.IOException {
		XmlPullParser parser = Xml.newPullParser();
		parser.setInput(in, null);
		return (HashMap<String, Object>) readValueXml(parser, new String[1]);
	}

	public static final Object readValueXml(XmlPullParser parser, String[] name)
			throws XmlPullParserException, java.io.IOException {
		int eventType = parser.getEventType();
		do {
			if (eventType == XmlPullParser.START_TAG) {
				return readThisValueXml(parser, name);
			} else if (eventType == XmlPullParser.END_TAG) {
				throw new XmlPullParserException("Unexpected end tag at: "
						+ parser.getName());
			} else if (eventType == XmlPullParser.TEXT) {
				throw new XmlPullParserException("Unexpected text: "
						+ parser.getText());
			}
			eventType = parser.next();
		} while (eventType != XmlPullParser.END_DOCUMENT);

		throw new XmlPullParserException("Unexpected end of document");
	}

	private static final Object readThisValueXml(XmlPullParser parser,
			String[] name) throws XmlPullParserException, java.io.IOException {
		final String valueName = parser.getAttributeValue(null, "name");
		final String tagName = parser.getName();

		// System.out.println("Reading this value tag: " + tagName + ", name=" +
		// valueName);

		Object res;

		if (tagName.equals("null")) {
			res = null;
		} else if (tagName.equals("string")) {
			String value = "";
			int eventType;
			while ((eventType = parser.next()) != XmlPullParser.END_DOCUMENT) {
				if (eventType == XmlPullParser.END_TAG) {
					if (parser.getName().equals("string")) {
						name[0] = valueName;
						// System.out.println("Returning value for " + valueName
						// + ": " + value);
						return value;
					}
					throw new XmlPullParserException(
							"Unexpected end tag in <string>: "
									+ parser.getName());
				} else if (eventType == XmlPullParser.TEXT) {
					value += parser.getText();
				} else if (eventType == XmlPullParser.START_TAG) {
					throw new XmlPullParserException(
							"Unexpected start tag in <string>: "
									+ parser.getName());
				}
			}
			throw new XmlPullParserException(
					"Unexpected end of document in <string>");
		} else if (tagName.equals("int")) {
			res = Integer.parseInt(parser.getAttributeValue(null, "value"));
		} else if (tagName.equals("long")) {
			res = Long.valueOf(parser.getAttributeValue(null, "value"));
		} else if (tagName.equals("float")) {
			res = Float.valueOf(parser.getAttributeValue(null, "value"));
		} else if (tagName.equals("double")) {
			res = Double.valueOf(parser.getAttributeValue(null, "value"));
		} else if (tagName.equals("boolean")) {
			res = Boolean.valueOf(parser.getAttributeValue(null, "value"));
		} else if (tagName.equals("int-array")) {
			parser.next();
			res = readThisIntArrayXml(parser, "int-array", name);
			name[0] = valueName;
			// System.out.println("Returning value for " + valueName + ": " +
			// res);
			return res;
		} else if (tagName.equals("map")) {
			parser.next();
			res = readThisMapXml(parser, "map", name);
			name[0] = valueName;
			// System.out.println("Returning value for " + valueName + ": " +
			// res);
			return res;
		} else if (tagName.equals("list")) {
			parser.next();
			res = readThisListXml(parser, "list", name);
			name[0] = valueName;
			// System.out.println("Returning value for " + valueName + ": " +
			// res);
			return res;
		} else if (tagName.equals("set")) {
			parser.next();
			res = readThisSetXml(parser, "set", name);
			name[0] = valueName;
			// System.out.println("Returning value for " + valueName + ": " +
			// res);
			return res;
		} else {
			throw new XmlPullParserException("Unknown tag: " + tagName);
		}

		// Skip through to end tag.
		int eventType;
		while ((eventType = parser.next()) != XmlPullParser.END_DOCUMENT) {
			if (eventType == XmlPullParser.END_TAG) {
				if (parser.getName().equals(tagName)) {
					name[0] = valueName;
					// System.out.println("Returning value for " + valueName +
					// ": " + res);
					return res;
				}
				throw new XmlPullParserException("Unexpected end tag in <"
						+ tagName + ">: " + parser.getName());
			} else if (eventType == XmlPullParser.TEXT) {
				throw new XmlPullParserException("Unexpected text in <"
						+ tagName + ">: " + parser.getName());
			} else if (eventType == XmlPullParser.START_TAG) {
				throw new XmlPullParserException("Unexpected start tag in <"
						+ tagName + ">: " + parser.getName());
			}
		}
		throw new XmlPullParserException("Unexpected end of document in <"
				+ tagName + ">");
	}

	public static final HashMap<String,Object> readThisMapXml(XmlPullParser parser,
			String endTag, String[] name) throws XmlPullParserException,
			java.io.IOException {
		HashMap<String,Object> map = new HashMap<String,Object>();

		int eventType = parser.getEventType();
		do {
			if (eventType == XmlPullParser.START_TAG) {
				Object val = readThisValueXml(parser, name);
				if (name[0] != null) {
					// System.out.println("Adding to map: " + name + " -> " +
					// val);
					map.put(name[0], val);
				} else {
					throw new XmlPullParserException(
							"Map value without name attribute: "
									+ parser.getName());
				}
			} else if (eventType == XmlPullParser.END_TAG) {
				if (parser.getName().equals(endTag)) {
					return map;
				}
				throw new XmlPullParserException("Expected " + endTag
						+ " end tag at: " + parser.getName());
			}
			eventType = parser.next();
		} while (eventType != XmlPullParser.END_DOCUMENT);

		throw new XmlPullParserException("Document ended before " + endTag
				+ " end tag");
	}

	public static final ArrayList<Object> readThisListXml(XmlPullParser parser,
			String endTag, String[] name) throws XmlPullParserException,
			java.io.IOException {
		ArrayList<Object> list = new ArrayList<Object>();

		int eventType = parser.getEventType();
		do {
			if (eventType == XmlPullParser.START_TAG) {
				Object val = readThisValueXml(parser, name);
				list.add(val);
				// System.out.println("Adding to list: " + val);
			} else if (eventType == XmlPullParser.END_TAG) {
				if (parser.getName().equals(endTag)) {
					return list;
				}
				throw new XmlPullParserException("Expected " + endTag
						+ " end tag at: " + parser.getName());
			}
			eventType = parser.next();
		} while (eventType != XmlPullParser.END_DOCUMENT);

		throw new XmlPullParserException("Document ended before " + endTag
				+ " end tag");
	}

	public static final HashSet<Object> readThisSetXml(XmlPullParser parser,
			String endTag, String[] name) throws XmlPullParserException,
			java.io.IOException {
		HashSet<Object> set = new HashSet<Object>();

		int eventType = parser.getEventType();
		do {
			if (eventType == XmlPullParser.START_TAG) {
				Object val = readThisValueXml(parser, name);
				set.add(val);
				// System.out.println("Adding to set: " + val);
			} else if (eventType == XmlPullParser.END_TAG) {
				if (parser.getName().equals(endTag)) {
					return set;
				}
				throw new XmlPullParserException("Expected " + endTag
						+ " end tag at: " + parser.getName());
			}
			eventType = parser.next();
		} while (eventType != XmlPullParser.END_DOCUMENT);

		throw new XmlPullParserException("Document ended before " + endTag
				+ " end tag");
	}

	public static final int[] readThisIntArrayXml(XmlPullParser parser,
			String endTag, String[] name) throws XmlPullParserException,
			java.io.IOException {

		int num;
		try {
			num = Integer.parseInt(parser.getAttributeValue(null, "num"));
		} catch (NullPointerException e) {
			throw new XmlPullParserException("Need num attribute in byte-array");
		} catch (NumberFormatException e) {
			throw new XmlPullParserException(
					"Not a number in num attribute in byte-array");
		}

		int[] array = new int[num];
		int i = 0;

		int eventType = parser.getEventType();
		do {
			if (eventType == XmlPullParser.START_TAG) {
				if (parser.getName().equals("item")) {
					try {
						array[i] = Integer.parseInt(parser.getAttributeValue(
								null, "value"));
					} catch (NullPointerException e) {
						throw new XmlPullParserException(
								"Need value attribute in item");
					} catch (NumberFormatException e) {
						throw new XmlPullParserException(
								"Not a number in value attribute in item");
					}
				} else {
					throw new XmlPullParserException("Expected item tag at: "
							+ parser.getName());
				}
			} else if (eventType == XmlPullParser.END_TAG) {
				if (parser.getName().equals(endTag)) {
					return array;
				} else if (parser.getName().equals("item")) {
					i++;
				} else {
					throw new XmlPullParserException("Expected " + endTag
							+ " end tag at: " + parser.getName());
				}
			}
			eventType = parser.next();
		} while (eventType != XmlPullParser.END_DOCUMENT);

		throw new XmlPullParserException("Document ended before " + endTag
				+ " end tag");
	}
}
