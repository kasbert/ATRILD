#!/bin/sh

if ! [ -f /system/bin/rild.atrild.orig ]
then
    echo "Installation backup does not exist"
    exit 1
fi

if ! mv  /system/bin/rild.atrild.orig /system/bin/rild
then 
    echo "Cannot move rild. /system is read only ?"
    exit 1
fi

setprop ctl.stop ril-daemon
setprop ctl.start ril-daemon

echo "Uninstallation completed"
exit 0
