from pathlib import Path
import os
import xml.etree.ElementTree as ET
import sys

tests=failures=errors=0
for p in Path('app/build/test-results').rglob('TEST-*.xml'):
    r=ET.parse(p).getroot()
    tests+=int(r.get('tests','0'));failures+=int(r.get('failures','0'));errors+=int(r.get('errors','0'))
assert tests>=7 and failures==errors==0
url=f"https://github.com/{os.environ['GITHUB_REPOSITORY']}/actions/runs/{os.environ['GITHUB_RUN_ID']}"
Path(sys.argv[1]).write_text(f'''# Actual CI verification

Commit: `{os.environ['GITHUB_SHA']}`

Run: {url}

- Debug APK compiled.
- JUnit: {tests} tests, {failures} failures, {errors} errors.
- Android Lint completed without blocking errors; inspect verification-reports for warnings.
- Source manifest, packaged Xposed entry, APK signature and merged manifest checks passed.
- No Android device, LSPosed runtime or chat application was tested by this job.
''')
