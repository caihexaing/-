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
    static final long MIN_CAPTURE_GAP_MS = 1000, PROBE_GAP_MS = 2500;
    interface Callback { void complete(Result result, String failure); }
    static final class Match {
        final String label;
        final Rect bounds;
        Match(String label, Rect bounds) { this.label = label; this.bounds = new Rect(bounds); }
    }
    static final class Result {
        final List<Match> skips = new ArrayList<>();
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
            InputImage image = InputImage.fromBitmap(frame, 0);
            Task<Text> chineseTask = zh.process(image), latinTask = en.process(image);
            Bitmap ownedFrame = frame;
            Tasks.whenAllComplete(chineseTask, latinTask).addOnCompleteListener(callbackExecutor, ignored -> {
                try {
                    if (closed) return;
                    Result result = new Result();
                    if (chineseTask.isSuccessful()) consume(chineseTask.getResult(), ownedFrame, windowBounds, result);
                    if (latinTask.isSuccessful()) consume(latinTask.getResult(), ownedFrame, windowBounds, result);
                    if (!chineseTask.isSuccessful() && !latinTask.isSuccessful()) callback.complete(null, "recognition_failed");
                    else callback.complete(result, null);
                } catch (RuntimeException e) { callback.complete(null, "recognition_failed"); }
                finally { if (!ownedFrame.isRecycled()) ownedFrame.recycle(); }
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

    private static void addLine(String text, Rect box, Bitmap frame, Rect screenBounds, Result out) {
        if (SplashPolicy.adMarker(text)) out.adMarker = true;
        if (box == null || !SplashPolicy.skipLabel(text)) return;
        Rect screen = new Rect(
            screenBounds.left + Math.round(box.left * screenBounds.width() / (float) frame.getWidth()),
            screenBounds.top + Math.round(box.top * screenBounds.height() / (float) frame.getHeight()),
            screenBounds.left + Math.round(box.right * screenBounds.width() / (float) frame.getWidth()),
            screenBounds.top + Math.round(box.bottom * screenBounds.height() / (float) frame.getHeight()));
        for (Match match : out.skips) {
            Rect overlap = new Rect(match.bounds);
            if (overlap.intersect(screen) && overlap.width() * 2 >= screen.width()
                    && overlap.height() * 2 >= screen.height()) return;
        }
        out.skips.add(new Match(text, screen));
    }

    @Override public void close() {
        closed = true;
        if (chinese != null) chinese.close();
        if (latin != null) latin.close();
        chinese = null; latin = null;
    }
}
