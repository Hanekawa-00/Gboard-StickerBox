#!/usr/bin/env python3
"""Structural checks; these do NOT establish Android runtime compatibility."""
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile
import sys

root=Path(__file__).resolve().parents[1]
ns='{http://schemas.android.com/apk/res/android}'
manifest=ET.parse(root/'app/src/main/AndroidManifest.xml').getroot()
assert not manifest.findall('uses-permission'), 'MVP must not request Internet, storage or root permissions'
app=manifest.find('application')
assert app.get(ns+'allowBackup')=='false'
providers={p.get(ns+'name'):p for p in app.findall('provider')}
fp=providers['androidx.core.content.FileProvider']
assert fp.get(ns+'exported')=='false' and fp.get(ns+'grantUriPermissions')=='true'
assert fp.get(ns+'authorities')=='io.github.stickerbox.files'
bridge=providers['.data.BridgeProvider']
assert bridge.get(ns+'grantUriPermissions')=='false'
share=next(x for x in app.findall('activity') if x.get(ns+'name')=='.ui.ShareActivity')
assert share.get(ns+'exported')=='false'
paths=ET.parse(root/'app/src/main/res/xml/file_paths.xml').getroot()
assert len(paths)==1 and paths[0].tag=='cache-path' and paths[0].get('path')=='outgoing/'
entry=(root/'app/src/main/assets/xposed_init').read_text().strip()
assert entry=='io.github.stickerbox.hook.StickerBoxHook'
assert (root/'app/src/main/java'/Path(*entry.split('.'))).with_suffix('.kt').is_file()
source=(root/'app/src/main/java/io/github/stickerbox/hook/StickerBoxHook.kt').read_text()
for forbidden in ['getTextBeforeCursor','getTextAfterCursor','getExtractedText','getPrimaryClip','Runtime.getRuntime']:
    assert forbidden not in source,forbidden
for path in (root/'app/src/main/res').rglob('*.xml'): ET.parse(path)
assert zipfile.is_zipfile(root/'gradle/wrapper/gradle-wrapper.jar')
print('PASS: Manifest, FileProvider scope, LSPosed entry, XML and no input/clipboard readers')

if len(sys.argv)>1:
    with zipfile.ZipFile(sys.argv[1]) as apk:
        assert apk.read('assets/xposed_init').decode().strip()==entry
        assert any(n.endswith('.dex') for n in apk.namelist())
    print('PASS: Packaged Xposed entry and DEX present')
