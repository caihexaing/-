"""Release guards for paths implicated in unwanted ad navigation; no device success claims."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

project = Path(__file__).resolve().parent.parent
service = (project / 'app/src/main/java/cn/quietguard/app/SplashSkipService.java').read_text(encoding='utf-8')
config = ET.parse(project / 'app/src/main/res/xml/splash_accessibility.xml').getroot()
android = '{http://schemas.android.com/apk/res/android}'
assert config.get(android + 'canPerformGestures') == 'false', 'Gesture capability must remain disabled'
for forbidden in ('dispatchGesture', 'GestureDescription', 'performGlobalAction'):
    assert forbidden not in service, 'Forbidden action path: ' + forbidden
assert not re.search(r'\.getParent\s*\(', service), 'Never click an ancestor of skip text'
actions = re.findall(r'\.performAction\s*\(([^)]*)\)', service)
assert actions == ['AccessibilityNodeInfo.ACTION_CLICK'], 'Only one direct semantic click call site is allowed'
assert 'if (!window.attempted(SystemClock.elapsedRealtime())) return false;' in service, 'Single-action reservation must gate click'
print('PASS: 7 release guards: gestures disabled, no gesture/global/ancestor fallback, direct click only and single-action gate.')
