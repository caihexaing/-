package cn.quietguard.app;

import android.accessibilityservice.AccessibilityService;
import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;
import android.accessibilityservice.AccessibilityService.ScreenshotResult;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/** Captures one active app window and recognizes text locally without retaining its image. */
final class SplashOcr implements AutoCloseable {
    static final int MAX_FRAMES = 12;
    // A splash ad often lives ~5s. Capturing at a 1s floor left no room for the confirming second frame,
    // so the floor is short and MAX_FRAMES still caps the total screenshots per launch.
    static final long MIN_CAPTURE_GAP_MS = 400, PROBE_GAP_MS = 2500;
    interface Callback { void complete(Result result, String failure); }
    /**
     * One recognized line of text. {@code text} is the whole OCR line ("5s | 跳过"); {@code label} is what
     * {@link SplashPolicy#skipLabel} accepted. The extra geometry lets the policy join a "跳过" element with
     * the countdown digit the SDK draws beside it, instead of demanding one wide single box.
     */
    static final class Match {
        final String label;
        final Rect bounds;
        /** Width of the plain skip word inside this box, or 0 when the box is the whole label. */
        final int wordWidth;
        Match(String label, Rect bounds) { this(label, bounds, bounds.width()); }
        Match(String label, Rect bounds, int wordWidth) {
            this.label = label; this.bounds = new Rect(bounds); this.wordWidth = wordWidth;
        }
    }
    static final class Result {
        final List<Match> skips = new ArrayList<>();
        /** Bounds of a countdown digit drawn as its own element, e.g. the "5" in "跳过 5". */
        final List<Rect> countdownDigits = new ArrayList<>();
        boolean adMarker;
    }
    private static final int MAX_IMAGE_EDGE = 1600;
    private final AccessibilityService service;
    private final Handler handler;
    private final Executor callbackExecutor;
    private TextRecognizer chinese, latin;
    private volatile boolean closed;

    SplashOcr(AccessibilityService service, Handler handler) {
        this.service = service; this.handler = handler;
        callbackExecutor = command -> {
            if (closed || !handler.post(command)) command.run();
        };
    }

    @SuppressLint("NewApi")
    void capture(int windowId, Rect windowBounds, Callback callback) {
        if (closed) { callback.complete(null, "closed"); return; }
        if (Build.VERSION.SDK_INT < 30) { callback.complete(null, "unsupported"); return; }
        Rect bounds = new Rect(windowBounds);
        AccessibilityService.TakeScreenshotCallback screenshotCallback = new AccessibilityService.TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult screenshot) { recognize(screenshot, bounds, Build.VERSION.SDK_INT >= 34, callback); }
            @Override public void onFailure(int errorCode) { callback.complete(null, "capture_failed"); }
        };
        try {
            if (Build.VERSION.SDK_INT >= 34) service.takeScreenshotOfWindow(windowId, callbackExecutor, screenshotCallback);
            else service.takeScreenshot(Display.DEFAULT_DISPLAY, callbackExecutor, screenshotCallback);
        } catch (RuntimeException | LinkageError e) { callback.complete(null, "capture_failed"); }
    }

    @SuppressLint("NewApi")
    private void recognize(ScreenshotResult screenshot, Rect windowBounds, boolean windowOnly, Callback callback) {
        Bitmap frame = null;
        HardwareBuffer buffer = null;
        Bitmap wrapped = null;
        try {
            buffer = screenshot.getHardwareBuffer();
            if (buffer == null) { callback.complete(null, "capture_failed"); return; }
            ColorSpace colorSpace = screenshot.getColorSpace();
            if (colorSpace == null) colorSpace = ColorSpace.get(ColorSpace.Named.SRGB);
            wrapped = Bitmap.wrapHardwareBuffer(buffer, colorSpace);
            if (wrapped == null) { callback.complete(null, "capture_failed"); return; }
            Bitmap software = wrapped.copy(Bitmap.Config.ARGB_8888, false);
            if (software == null) { callback.complete(null, "capture_failed"); return; }
            frame = cropWindow(software, windowBounds, windowOnly);
            if (frame != software) software.recycle();
            if (frame == null) { callback.complete(null, "capture_failed"); return; }
            Bitmap scaled = scale(frame);
            if (scaled != frame) { frame.recycle(); frame = scaled; }
            process(frame, windowBounds, callback);
            frame = null;
        } catch (RuntimeException | LinkageError e) {
            callback.complete(null, "capture_failed");
        } finally {
            if (frame != null && !frame.isRecycled()) frame.recycle();
            if (wrapped != null && !wrapped.isRecycled()) wrapped.recycle();
            if (buffer != null) buffer.close();
        }
    }

    private Bitmap cropWindow(Bitmap screenshot, Rect windowBounds, boolean windowOnly) {
        Rect crop;
        if (windowOnly) crop = new Rect(0, 0, screenshot.getWidth(), screenshot.getHeight());
        else {
            WindowManager manager = service.getSystemService(WindowManager.class);
            Display display = manager == null ? null : manager.getDefaultDisplay();
            if (display == null) return null;
            DisplayMetrics metrics = new DisplayMetrics(); display.getRealMetrics(metrics);
            if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) return null;
            float sx = screenshot.getWidth() / (float) metrics.widthPixels;
            float sy = screenshot.getHeight() / (float) metrics.heightPixels;
            crop = new Rect(Math.round(windowBounds.left * sx), Math.round(windowBounds.top * sy),
                Math.round(windowBounds.right * sx), Math.round(windowBounds.bottom * sy));
            if (crop.left < 0 || crop.top < 0 || crop.right > screenshot.getWidth()
                    || crop.bottom > screenshot.getHeight()) return null;
        }
        if (crop.width() <= 0 || crop.height() <= 0) return null;
        return Bitmap.createBitmap(screenshot, crop.left, crop.top, crop.width(), crop.height());
    }

    private static Bitmap scale(Bitmap bitmap) {
        int longest = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (longest <= MAX_IMAGE_EDGE) return bitmap;
        float scale = MAX_IMAGE_EDGE / (float) longest;
        return Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(bitmap.getWidth() * scale)),
            Math.max(1, Math.round(bitmap.getHeight() * scale)), true);
    }

    private void process(Bitmap frame, Rect windowBounds, Callback callback) {
        try {
            TextRecognizer zh = chinese;
            TextRecognizer en = latin;
            if (zh == null) { zh = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build()); chinese = zh; }
            if (en == null) { en = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS); latin = en; }
            final TextRecognizer latinClient = en;
            InputImage image = InputImage.fromBitmap(frame, 0);
            // Chinese first: it reads "跳过" and most Latin labels too, and the Latin model is only worth its
            // round trip when the Chinese pass found nothing. Running both in parallel doubled the wait and
            // made the app enter late on five second splashes.
            Bitmap ownedFrame = frame;
            zh.process(image).addOnCompleteListener(callbackExecutor, chineseDone -> {
                Result result = new Result();
                boolean chineseSuccess = chineseDone.isSuccessful();
                if (chineseSuccess) {
                    try { if (!closed) consume(chineseDone.getResult(), ownedFrame, windowBounds, result); }
                    catch (RuntimeException e) { chineseSuccess = false; }
                }
                final boolean chineseOk = chineseSuccess;
                if (!chineseOk || !result.skips.isEmpty() || closed) {
                    if (!chineseOk) { if (!ownedFrame.isRecycled()) ownedFrame.recycle(); callback.complete(null, "recognition_failed"); }
                    else { if (!ownedFrame.isRecycled()) ownedFrame.recycle(); callback.complete(result, null); }
                    return;
                }
                latinClient.process(image).addOnCompleteListener(callbackExecutor, latinDone -> {
                    try {
                        if (!closed && latinDone.isSuccessful()) consume(latinDone.getResult(), ownedFrame, windowBounds, result);
                        callback.complete(result, null);
                    } catch (RuntimeException e) { callback.complete(result, null); }
                    finally { if (!ownedFrame.isRecycled()) ownedFrame.recycle(); }
                });
            });
        } catch (RuntimeException | LinkageError e) {
            if (!frame.isRecycled()) frame.recycle();
            callback.complete(null, "recognition_failed");
        }
    }

    private static void consume(Text recognized, Bitmap frame, Rect screenBounds, Result out) {
        for (Text.TextBlock block : recognized.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                addLine(line.getText(), line.getBoundingBox(), frame, screenBounds, out);
                for (Text.Element element : line.getElements())
                    addLine(element.getText(), element.getBoundingBox(), frame, screenBounds, out);
            }
        }
    }

    /** Countdown digits on ad skip controls are 1-2 characters, e.g. "5", "08", "5s". */
    static boolean countdownText(String text) { return SplashOcrPolicy.countdownText(text); }

    private static void addLine(String text, Rect box, Bitmap frame, Rect screenBounds, Result out) {
        if (SplashPolicy.adMarker(text)) out.adMarker = true;
        if (box == null) return;
        Rect screen = map(box, frame, screenBounds);
        if (countdownText(text) && screen.width() < screenBounds.width()) {
            for (Rect digit : out.countdownDigits) if (digit.equals(screen)) return;
            out.countdownDigits.add(screen);
        }
        if (!SplashPolicy.skipLabel(text)) return;
        int word = mapWidth(wordOf(text, box), frame, screenBounds);
        for (Match match : out.skips) {
            Rect overlap = new Rect(match.bounds);
            if (overlap.intersect(screen) && overlap.width() * 2 >= screen.width()
                    && overlap.height() * 2 >= screen.height()) return;
        }
        out.skips.add(new Match(text, screen, word));
    }

    /** Pixel width of the skip word itself, so "跳过 3" reads as a 27dp word, not a 60dp box. */
    private static Rect wordOf(String text, Rect box) {
        String compact = text == null ? "" : text.replaceAll("[\\s\u00a0]", "");
        String digits = compact.replaceAll("[^0-9]", "");
        if (digits.isEmpty() || box.width() <= 0) return box;
        // Drop the countdown share of the box so the guard measures the skip word.
        float share = digits.length() / (float) Math.max(compact.length(), 1);
        int keep = Math.max(1, Math.round(box.width() * (1f - share)));
        return new Rect(box.left, box.top, box.left + keep, box.bottom);
    }

    private static Rect map(Rect box, Bitmap frame, Rect screenBounds) {
        if (frame.getWidth() <= 0 || frame.getHeight() <= 0) return new Rect(box);
        return new Rect(
            screenBounds.left + Math.round(box.left * screenBounds.width() / (float) frame.getWidth()),
            screenBounds.top + Math.round(box.top * screenBounds.height() / (float) frame.getHeight()),
            screenBounds.left + Math.round(box.right * screenBounds.width() / (float) frame.getWidth()),
            screenBounds.top + Math.round(box.bottom * screenBounds.height() / (float) frame.getHeight()));
    }

    private static int mapWidth(Rect box, Bitmap frame, Rect screenBounds) {
        if (frame.getWidth() <= 0) return box.width();
        return Math.round(box.width() * screenBounds.width() / (float) frame.getWidth());
    }

    @Override public void close() {
        closed = true;
        if (chinese != null) chinese.close();
        if (latin != null) latin.close();
        chinese = null; latin = null;
    }
}
