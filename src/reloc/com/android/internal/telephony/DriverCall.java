/*
 * Copyright (C) 2006 The Android Open Source Project
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

package reloc.com.android.internal.telephony;
//import com.android.internal.telephony.*;

/**
 * {@hide}
 */
public class DriverCall implements Comparable<DriverCall> {
    static final String LOG_TAG = "ATRILB";

    public enum State {
        ACTIVE,
        HOLDING,
        DIALING,    // MO call only
        ALERTING,   // MO call only
        INCOMING,   // MT call only
        WAITING;    // MT call only
        // If you add a state, make sure to look for the switch()
        // statements that use this enum
    }

    public int index;
    public boolean isMT;
    public State state;     // May be null if unavail
    public boolean isMpty;
    public String number;
    public int TOA;
    public boolean isVoice;
    public boolean isVoicePrivacy;
    public int als;
    public int numberPresentation;
    public String name;
    public int namePresentation;
    public UUSInfo uusInfo;


    public
    DriverCall() {
    }

    public String
    toString() {
        return "id=" + index + ","
                + state + ","
                + "toa=" + TOA + ","
                + (isMpty ? "conf" : "norm") + ","
                + (isMT ? "mt" : "mo") + ","
                + als + ","
                + (isVoice ? "voc" : "nonvoc") + ","
                + (isVoicePrivacy ? "evp" : "noevp") + ","
                /*+ "number=" + number */ + ",cli=" + numberPresentation + ","
                /*+ "name="+ name */ + "," + namePresentation;
    }

    //***** Comparable Implementation

    /** For sorting by index */
    public int
    compareTo (DriverCall o) {
        DriverCall dc;

        dc = (DriverCall)o;

        if (index < dc.index) {
            return -1;
        } else if (index == dc.index) {
            return 0;
        } else { /*index > dc.index*/
            return 1;
        }
    }
}
