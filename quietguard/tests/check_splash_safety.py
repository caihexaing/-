"""Action wiring guards. Only verified semantic actions or a strictly guarded text tap may be dispatched."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET
project=Path(__file__).resolve().parent.parent
service=(project/'app/src/main/java/cn/quietguard/app/SplashSkipService.java').read_text(encoding='utf-8')
prefs=(project/'app/src/main/java/cn/quietguard/app/Prefs.java').read_text(encoding='utf-8')
tap=(project/'app/src/main/java/cn/quietguard/app/SplashTextTap.java').read_text(encoding='utf-8')
config=ET.parse(project/'app/src/main/res/xml/splash_accessibility.xml').getroot()
android='{http://schemas.android.com/apk/res/android}'
assert config.get(android+'canPerformGestures')=='true', 'Text tap needs the gesture capability'
assert 'performGlobalAction' not in service, 'No global back or home actions'
assert service.count('.getParent(')==1, 'Only bounded semantic resolver may read ancestors'
assert 'SplashButton.resolve(' in service
assert re.findall(r'\.performAction\s*\(([^)]*)\)',service)==['AccessibilityNodeInfo.ACTION_CLICK']
# Both paths consume the same per-entry quota, so a coordinate tap can never add a second action.
assert service.count('if (!window.attempted(SystemClock.elapsedRealtime())) return false;')==2, 'Shared action quota'
# Exactly one coordinate stroke, 1:1 with the shared action quota, never a swipe or a repeat dispatch.
assert service.count('dispatchGesture(')==1, 'One dispatch site only'
assert service.count('new GestureDescription.Builder()')==1
assert service.count('.addStroke(')==1
assert re.findall(r'new GestureDescription\.StrokeDescription\(([^)]*)\)',service)==['point,0,50']
assert service.count('.performGlobalAction(')==0
assert 'target.textTapEligible' in service and 'target.reason' in service
# The tap must be reachable only from the button refusal branch, and gated by stability, foreground and evidence.
assert 'if (target.textTapEligible && validTextTarget(' in service, 'Text tap is the documented fallback only'
assert service.count('private boolean validTextTarget(')==1
assert service.count('private boolean tapText(')==1
assert 'CAPABILITY_CAN_PERFORM_GESTURES' in service, 'Missing gesture capability must be reported'
assert 'getMagnificationController().getScale() != 1f' in service, 'Magnified screens distort coordinates'
assert 'source.children() != 0 || !SplashTextTap.valid(' in service, 'Only a labelled leaf, never a wrapper'
assert '!SplashTextTap.evidence(fresh.text,fresh.description,plain)' in service, 'Re-read evidence before input'
assert 'textTap.matches(fresh,screen,pkg,windowId,identity)' in service, 'Input requires the confirmed position'
assert 'sameScreen(pkg,windowId,screen)' in service, 'Input requires unchanged window bounds'
assert 'Prefs.textTapEnabled(this,pkg)' in service, 'Per-app off switch must be honoured before input'
assert 'SplashTextTap.disabled(' in service, 'Auto-retire after ineffective taps must be honoured'
assert 'holdVerification(' in service and 'VERIFY_MS' in tap, 'Ineffective taps must be verified'
assert 'static int penalties(' in tap and 'MAX_PENALTIES' in tap
assert 'MAX_SPAN_MS' in tap, 'Stability window must be bounded'
assert 'public static boolean evidence(' in tap, 'Bare skip labels need the plain compatibility opt-in'
assert 'skip_text_tap_apps' in prefs, 'Legacy opt-ins must still be recognised'
assert 'Prefs.disableTextTap(this)' not in service, 'Text tap must not be force-disabled at service start'
print('PASS: guarded text tap: single 50ms stroke on a stable labelled leaf, shared action quota, per-app off switch, auto-retire after two ineffective taps.')
