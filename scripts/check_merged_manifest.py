import sys
import xml.etree.ElementTree as ET

ns='{http://schemas.android.com/apk/res/android}'
r=ET.parse(sys.argv[1]).getroot()
assert r.get('package')=='io.github.stickerbox'
permissions=[p.get(ns+'name') for p in r.findall('uses-permission')]
private_receiver='io.github.stickerbox.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
assert set(permissions)<={private_receiver}, 'Unexpected merged permissions: '+str(permissions)
if private_receiver in permissions:
    definition=next(p for p in r.findall('permission') if p.get(ns+'name')==private_receiver)
    protection=definition.get(ns+'protectionLevel')
    assert protection=='signature' or int(protection,0)==2
metadata={m.get(ns+'name'):m for m in r.find('application').findall('meta-data')}
assert metadata['xposedmodule'].get(ns+'value')=='true'
fp=next(p for p in r.find('application').findall('provider') if p.get(ns+'authorities')=='io.github.stickerbox.files')
assert fp.get(ns+'exported')=='false' and fp.get(ns+'grantUriPermissions')=='true'
print('PASS: Merged APK manifest permissions and provider declarations')
