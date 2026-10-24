#!/usr/bin/env python3
import sys, xml.etree.ElementTree as E
r=E.parse(sys.argv[1]).getroot(); a='{http://schemas.android.com/apk/res/android}'
app=r.find('application'); assert app is not None
assert app.get(a+'debuggable','false')=='false'
assert app.get(a+'testOnly','false')=='false'
assert app.get(a+'allowBackup')=='false'
exported={e.get(a+'name'):e for e in app if e.get(a+'exported')=='true'}
assert set(exported)=={'ru.focusguard.android.MainActivity','ru.focusguard.android.AdminReceiver','ru.focusguard.android.PlaybackAccess'},set(exported)
assert exported['ru.focusguard.android.AdminReceiver'].get(a+'permission')=='android.permission.BIND_DEVICE_ADMIN'
assert exported['ru.focusguard.android.PlaybackAccess'].get(a+'permission')=='android.permission.BIND_NOTIFICATION_LISTENER_SERVICE'
assert app.find("receiver[@"+a+"name='androidx.profileinstaller.ProfileInstallReceiver']") is None
assert not any(e.tag=='service' and 'Accessibility' in e.get(a+'name','') for e in app)
print('PASS production APK manifest: only HOME/admin and protected media listener exported; no debug/testOnly/backup/diagnostic receiver')
