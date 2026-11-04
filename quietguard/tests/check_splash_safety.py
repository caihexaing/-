"""Action wiring guards. Gesture capability only serves explicit per-application opt-in."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET
project=Path(__file__).resolve().parent.parent
service=(project/'app/src/main/java/cn/quietguard/app/SplashSkipService.java').read_text(encoding='utf-8')
prefs=(project/'app/src/main/java/cn/quietguard/app/Prefs.java').read_text(encoding='utf-8')
config=ET.parse(project/'app/src/main/res/xml/splash_accessibility.xml').getroot()
android='{http://schemas.android.com/apk/res/android}'
assert config.get(android+'canPerformGestures')=='true', 'Opt-in gesture mode requires system capability'
assert 'performGlobalAction' not in service, 'No global back or home actions'
assert service.count('.getParent(')==1, 'Only bounded semantic resolver may read ancestors'
assert 'SplashButton.resolve(' in service
assert re.findall(r'\.performAction\s*\(([^)]*)\)',service)==['AccessibilityNodeInfo.ACTION_CLICK']
assert service.count('if (!window.attempted(SystemClock.elapsedRealtime())) return false;')==2
assert service.count('dispatchGesture(')==1, 'Exactly one gesture submission site'
assert 'unique && target.textTapEligible && Prefs.textTap(this,pkg)' in service
assert 'new GestureDescription.StrokeDescription(point,0,50)' in service
assert 'point.moveTo(x,y)' in service and 'lineTo(' not in service
assert 'textTap.observe(' in service and 'textTap.matches(' in service and 'sameScreen(pkg,windowId,screen)' in service
assert 'getStringSet("skip_text_tap_apps",new HashSet<>())' in prefs, 'No application is opted in by default'
assert 'id==sessionId' in service and 'source.children()!=0' in service
print('PASS: 13 release guards: explicit opt-in, stable leaf text, current screen, one shared action quota, no global/back/swipe or repeated dispatch.')
