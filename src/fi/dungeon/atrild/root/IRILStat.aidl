package fi.dungeon.atrild.root;

import android.os.Messenger;

interface IRILStat {
	String getStat();
	void start();
	void stop();
	void setBeanProperty(in String name, in String property, in String value);
	String getBeanProperty(in String name, in String property);
	void register(in Messenger messenger);
	void unregister(in Messenger messenger);
}
