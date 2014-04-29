ATRILD - Android Java RILD
==========================

Note: This is still work-in-progress and probably won't work for you.

ATRILD is a Android RIL daemon for external AT command set modems.
This is useful on Android tablets and Android TV devices without internal 3G and with an USB host port.
ATRILD intends to replace reference rild written in C and it should provide easier upgrade path.

Requirements
------------
- Kernel must support ppp (check /proc/devices)
- Kernel must support 3g dongle serial
- Tablet must be rooted 
- /system must be writable (no cramfs etc.)

Some challenges:
----------------
- RILD communicates with rest of the Android platform via a socket. Socket /dev/socket/rild is owned by radio and applications are not allowed to create files in /dev/socket. Socket is provided by init process. 
- pppd cannot be started, becaus SUID executables are disallowed on later Android.
- Access to /dev/ttyUSB0 is typically not allowed.
- It would be better if it was run as user radio
 - Needs to be added to system frameworks ? or working su
- pppd could be started with init:
 init.rc:
 service pppd /system/bin/pppd
    user root
    group radio cache inet misc
    disabled 
    oneshot
 
  setprop ctl.start pppd:ttyS2
- Current solution is to replace /system/bin/rild with a script for starting ATRILD. ATRILD will be run as root and will potentially open security holes.
  
TODO
----
- Check signing or installing to /system/app ?
- Check existense of /dev/ttyUSB* (serial modules)
- Check permissions for /dev/ttyUSB*
- Check existence of pppd module (feature)
- chmod 4755 /system/bin/pppd
- mkdir /system/etc/ppp/peers
- touch   /system/etc/ppp/ttyS2 
- Installation of /system/bin/rild script by a button
- check installation status
