package fi.dungeon.atrild;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

// This class is not used

public class BootBroadcastReceiver extends BroadcastReceiver {     
    static final String ACTION = "android.intent.action.BOOT_COMPLETED";   
    @Override   
    public void onReceive(Context context, Intent intent) {   
        // BOOT_COMPLETED start Service    
        if (intent.getAction().equals(ACTION)) {
    		Log.i("ATRILD", "Starting RILD");
            Intent serviceIntent = new Intent(context, RILDService.class);       
            context.startService(serviceIntent);   
        }   
    }    
}   