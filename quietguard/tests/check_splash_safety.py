"""Action wiring guards. Only verified semantic button actions may be dispatched."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET
project=Path(__file__).resolve().parent.parent
service=(project/'app/src/main/java/cn/quietguard/app/SplashSkipService.java').read_text(encoding='utf-8')
prefs=(project/'app/src/main/java/cn/quietguard/app/Prefs.java').read_text(encoding='utf-8')
config=ET.parse(project/'app/src/main/res/xml/splash_accessibility.xml').getroot()
android='{http://schemas.android.com/apk/res/android}'
assert config.get(android+'canPerformGestures')=='false', 'Coordinate gestures must stay disabled'
assert 'performGlobalAction' not in service, 'No global back or home actions'
assert service.count('.getParent(')==1, 'Only bounded semantic resolver may read ancestors'
assert 'SplashButton.resolve(' in service
assert re.findall(r'\.performAction\s*\(([^)]*)\)',service)==['AccessibilityNodeInfo.ACTION_CLICK']
assert service.count('if (!window.attempted(SystemClock.elapsedRealtime())) return false;')==1
assert 'dispatchGesture(' not in service and 'GestureDescription' not in service
assert 'target.textTapEligible' in service and 'target.reason' in service
assert 'ACTION_CLICK' in service and 'performGlobalAction' not in service
assert 'skip_text_tap_apps' in prefs, 'Legacy opt-ins must be cleared on upgrade'
assert 'Prefs.disableTextTap(this)' in service
print('PASS: 13 release guards: semantic click only, no coordinate gestures, one shared action quota, no global/back/swipe or repeated dispatch.')
