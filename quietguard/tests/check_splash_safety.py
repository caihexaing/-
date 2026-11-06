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
# Semantic and both text paths consume the same per-entry quota, so OCR cannot add a second action.
assert service.count('window.attempted(')==3, 'Shared action quota for semantic, tree-text and OCR paths'
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
assert 'ocrPolicy.observe(' in service and 'CAPABILITY_CAN_TAKE_SCREENSHOT' in service, 'OCR requires stable text and screenshot capability'
assert 'Prefs.screenshotOcrConsent(this)' in service and 'Prefs.textTapEnabled(this,pkg)' in service, 'OCR requires both user consent and the per-app touch option'
ocr=(project/'app/src/main/java/cn/quietguard/app/SplashOcr.java').read_text(encoding='utf-8')
main=(project/'app/src/main/java/cn/quietguard/app/MainActivity.java').read_text(encoding='utf-8')
config=(project/'app/src/main/res/xml/splash_accessibility.xml').read_text(encoding='utf-8')
assert 'takeScreenshotOfWindow(windowId' in ocr and 'takeScreenshot(Display.DEFAULT_DISPLAY' in ocr, 'Prefer target-window capture and retain the Android 11-13 fallback'
assert 'MAX_FRAMES = 12' in ocr and 'PROBE_GAP_MS = 2500' in ocr, 'Bound screenshot frequency and total frames'
assert 'frame.recycle()' in ocr and 'buffer.close()' in ocr, 'Release screenshot buffers after in-memory OCR'
assert 'putBoolean("splash_ocr_consent",false)' in main, 'Screenshot OCR needs its separate opt-in'
assert 'canTakeScreenshot="true"' in config, 'Screenshot capability must be declared to Android'
gradle=(project/'app/build.gradle').read_text(encoding='utf-8')
assert "abiFilters 'armeabi-v7a', 'arm64-v8a'" in gradle, 'Universal APK should include only phone ARM ABIs'
assert 'static int penalties(' in tap and 'MAX_PENALTIES' in tap
assert 'MAX_SPAN_MS' in tap, 'Stability window must be bounded'
assert 'public static boolean evidence(' in tap, 'Bare skip labels need the plain compatibility opt-in'
assert 'skip_text_tap_apps' in prefs, 'Legacy opt-ins must still be recognised'
assert 'Prefs.disableTextTap(this)' not in service, 'Text tap must not be force-disabled at service start'
# An advertised ACTION_CLICK that the ad SDK ignores must still allow one guarded coordinate fallback.
assert 'private boolean tapInert(' in service, 'Inert clickable skip controls need a coordinate fallback'
assert 'originalStillReadable && inertTapSession != sessionId' in service, 'Fallback must require a proven inert click'
assert service.count('inertTapSession = sessionId;')==1, 'At most one inert-click fallback per launch'
assert 'window.matchesAcceptedTarget(leaf.left, leaf.top, leaf.right, leaf.bottom)' in service, 'Fallback must re-check the accepted rectangle'
assert 'this.inertTapSession' not in service.replace('private long inertTapSession',''), 'Fallback gate must stay session scoped'
print('PASS: guarded text tap: single 50ms stroke on a stable labelled leaf, shared action quota, per-app off switch, auto-retire after two ineffective taps.')
