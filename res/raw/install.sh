#!/bin/sh

if [ -f /system/bin/rild.atrild.orig ]
then
    echo "Already installed"
    exit 1
fi

if ! mv /system/bin/rild /system/bin/rild.atrild.orig
then 
    echo "Cannot move rild. /system is read only ?"
    exit 1
fi

setprop ctl.stop ril-daemon

cat > /system/bin/rild <<EOF
#!/system/bin/sh
# ATRILD start script

while true
do
    for apk in /system/app/fi.dungeon.atrild*.apk /data/app/fi.dungeon.atrild*.apk
    do
	if [ -f "\$apk" ] ; then
	    log -p i -t ATRIL Starting \$apk  
	    
	    export CLASSPATH="\$apk"
	    app_process -Xbootclasspath:\$BOOTCLASSPATH:\$CLASSPATH com.android.internal.util.WithFramework fi.dungeon.atrild.root.Main
	    log -p e -t ATRIL Process stopped, retrying in 600s
	    sleep 600
	fi
    done
done
EOF

chmod 755 /system/bin/rild
setprop ctl.start ril-daemon

echo "Installation completed"
exit 0
