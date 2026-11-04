"""Negative shell-UID checks on a disposable emulator, NOT ordinary-app or Root verification."""
import subprocess

for command in [
    ['content','call','--uri','content://io.github.stickerbox.bridge','--method','config'],
    ['content','read','--uri','content://io.github.stickerbox.files/outgoing/not-granted.gif'],
]:
    result=subprocess.run(['adb','shell',*command],capture_output=True,text=True,timeout=20)
    output=result.stdout+result.stderr
    assert 'SecurityException' in output, 'Expected shell UID access denial: '+output
    print('PASS: unauthorized shell UID denied', command[1])
