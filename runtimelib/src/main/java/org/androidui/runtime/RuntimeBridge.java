package org.androidui.runtime;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Build;
import android.os.Looper;
import android.support.annotation.Nullable;
import android.support.v4.view.ViewCompat;
import android.util.Log;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebBackForwardList;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AbsoluteLayout;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Vector;

/**
 * Created by linfaxin on 15/12/15.
 * Js Bridge
 */
public class RuntimeBridge {
    protected final static String TAG = "RuntimeBridge";
    public static boolean DEBUG = false;
    public static boolean DEBUG_RUNJS = false;
    public static boolean DEBUG_WEBVIEW = false;

    protected SparseArray<SurfaceApi> surfaceInstances = new SparseArray<SurfaceApi>();
    private SparseArray<CanvasApi> canvasInstances = new SparseArray<CanvasApi>();
    SparseArray<ImageApi> imageInstances = new SparseArray<ImageApi>();
    private SparseArray<WebView> webViewInstances = new SparseArray<WebView>();
    private SparseArray<Rect> drawHTMLBounds = new SparseArray<Rect>();
    private Rect mRectTmp = new Rect();
    private ShowFPSHelper showFPSHelper;

    private WeakReference<WebView> webViewRef;

    protected RuntimeBridge(WebView webView) {
        this.webViewRef = new WeakReference<WebView>(webView);
        showFPSHelper = new ShowFPSHelper(webView);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.addJavascriptInterface(this, "AndroidUIRuntime");
    }

    @Nullable
    protected WebView getWebView(){
        return this.webViewRef.get();
    }

    protected SurfaceApi createSurfaceApi(Context context, int surfaceId){
        return new SurfaceApi(context, RuntimeBridge.this);
    }

    protected CanvasApi createCanvasApi(int width, int height){
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new CanvasApi.BitmapCanvas(bitmap);
        return new CanvasApi(canvas);
    }

    void applyTextMeasure(){
        RuntimeInit.initMeasureWidthData();
        execJSOnUI(RuntimeInit.MeasureWidthsJS);
    }

    void notifySurfaceReady(SurfaceApi surfaceApi){
        int surfaceId = surfaceInstances.keyAt(surfaceInstances.indexOfValue(surfaceApi));
        execJSOnUI(String.format("androidui.native.NativeSurface.notifySurfaceReady(%d);", surfaceId));
    }

    void notifySurfaceSupportDirtyDraw(int surfaceId, boolean support){
        execJSOnUI(String.format("androidui.native.NativeSurface.notifySurfaceSupportDirtyDraw(%d, %b);", surfaceId, support));
    }

    void notifyImageLoadFinish(ImageApi imageApi, int width, int height){
        int imageId = imageInstances.keyAt(imageInstances.indexOfValue(imageApi));
        execJSOnUI(String.format("androidui.native.NativeImage.notifyLoadFinish(%d, %d, %d);", imageId, width, height));
    }
    void notifyImageLoadFinish(ImageApi imageApi, int width, int height,
                               int[] leftBorder, int[] topBorder, int[] rightBorder, int[] bottomBorder){
        int imageId = imageInstances.keyAt(imageInstances.indexOfValue(imageApi));
        execJSOnUI(String.format("androidui.native.NativeImage.notifyLoadFinish(%d, %d, %d, %s, %s, %s, %s);", imageId, width, height,
                Arrays.toString(leftBorder), Arrays.toString(topBorder), Arrays.toString(rightBorder), Arrays.toString(bottomBorder)));
    }
    void notifyImageLoadError(ImageApi imageApi){
        int imageId = imageInstances.keyAt(imageInstances.indexOfValue(imageApi));
        execJSOnUI(String.format("androidui.native.NativeImage.notifyLoadError(%d);", imageId));
    }
    void notifyImageGetPixels(int imageId, int callBackIndex, int[] data){
        execJSOnUI(String.format("androidui.native.NativeImage.notifyGetPixels(%d, %d, %s);", imageId, callBackIndex, Arrays.toString(data)));
    }

    protected void execJSOnUI(final String js){
        if(DEBUG_RUNJS) Log.d(TAG, "execJS:"+js.substring(0, Math.min(js.length(), 200)));
        if(Looper.myLooper() == Looper.getMainLooper()){
            execJS(js);
        }else {
            View webView = getWebView();
            if(webView!=null){
                webView.post(new Runnable() {
                    @Override
                    public void run() {
                        execJS(js);
                    }
                });
            }
        }
    }

    protected void execJS(String js){
        WebView webView = getWebView();
        if(webView!=null && js!=null) {
            if(js.startsWith("javascript:")) js = js.substring("javascript:".length());

            if(Build.VERSION.SDK_INT>=19){
                webView.evaluateJavascript(js, null);
            }else{
                webView.loadUrl("javascript:" + js);
            }
        }
    }

    protected Vector<BatchCallHelper.BatchCallResult> pendingBatchResult = new Vector<BatchCallHelper.BatchCallResult>();
    private BatchCallHelper.BatchCallResult currentBatchRun;
    protected Runnable queryPendingAndRun = new Runnable() {
        @Override
        public void run() {
            int size = pendingBatchResult.size();
            BatchCallHelper.BatchCallResult willCallBatchRun;

            if(size==0){//no new draw batch call, draw last.
                willCallBatchRun = currentBatchRun;

            } else if(size==1){
                willCallBatchRun = pendingBatchResult.remove(0);

            }else{
                while(true){
                    BatchCallHelper.BatchCallResult call = pendingBatchResult.remove(0);
                    if(pendingBatchResult.size() == 0 || BatchCallHelper.cantSkipBatchCall(call)) {
                        willCallBatchRun = call;
                        break;
                    }
                    call.recycle();
                }
            }

            if(currentBatchRun!=null && currentBatchRun!=willCallBatchRun){
                currentBatchRun.recycle();
            }
            currentBatchRun = willCallBatchRun;
            currentBatchRun.run();

            showFPSHelper.trackUIFPS();
        }
    };

    @JavascriptInterface
    public void batchCall(final String batchString){
        final View webView = getWebView();
        if(webView!=null){
            BatchCallHelper.BatchCallResult result = BatchCallHelper.parse(this, batchString);
            pendingBatchResult.add(result);
            webView.removeCallbacks(queryPendingAndRun);
            ViewCompat.postOnAnimation(webView, queryPendingAndRun);
        }
    }

    @JavascriptInterface
    public void showJSFps(float fps){
        showFPSHelper.showJSFPS(fps);
    }

    private Runnable allViewInvisibleRun = new Runnable() {
        @Override
        public void run() {
            final ViewGroup webView = getWebView();
            if(webView!=null) {
                for (int i = 0, count = webView.getChildCount(); i < count; i++) {
                    webView.getChildAt(i).setVisibility(View.INVISIBLE);
                }
            }
        }
    };

    private Runnable allViewVisibleRun = new Runnable() {
        @Override
        public void run() {
            final ViewGroup webView = getWebView();
            if(webView!=null) {
                for (int i = 0, count = webView.getChildCount(); i < count; i++) {
                    webView.getChildAt(i).setVisibility(View.VISIBLE);
                }
            }
        }
    };

    @JavascriptInterface
    public void pageAlive(int deadDelay){
        final ViewGroup webView = getWebView();
        if(webView!=null){
            webView.removeCallbacks(allViewInvisibleRun);
            webView.postDelayed(allViewInvisibleRun, deadDelay);
            webView.post(allViewVisibleRun);
        }
    }

    @JavascriptInterface
    public void initRuntime(){
        final ViewGroup webView = getWebView();
        if(webView!=null){
            webView.post(new Runnable() {
                @Override
                public void run() {
                    webView.removeAllViews();
                    drawHTMLBounds.clear();
                    surfaceInstances.clear();
                    webViewInstances.clear();

                    for (int i = 0; i < canvasInstances.size(); i++) {
                        canvasInstances.valueAt(i).recycle();
                    }
                    canvasInstances.clear();

                    for (int i = 0; i < imageInstances.size(); i++) {
                        imageInstances.valueAt(i).recycle();
                    }
                    imageInstances.clear();
                    applyTextMeasure();
                }
            });
        }
    }

    @JavascriptInterface
    public void closeApp(){
        final WebView webView = getWebView();
        if(webView!=null) {
            ((Activity)webView.getContext()).finish();
        }
    }

    //================= surface api =================
    @JavascriptInterface
    @BatchCallHelper.BatchMethod(value = "10", batchCantSkip = true)
    public void createSurface(final int surfaceId, final float left, final float top, final float right, final float bottom){
        if(DEBUG) Log.d(TAG, "createSurface, surfaceId:" + surfaceId + ", left:" + left + ", top:" + top + ", right:" + right + ", bottom:" + bottom);
        final ViewGroup webView = getWebView();
        if(webView!=null) {
            //do on ui thread
            webView.post(new Runnable() {
                @Override
                public void run() {
                    int width = (int) (right - left);
                    int height = (int) (bottom - top);
                    if (width < 0 || right < 0) width = -1;
                    if (height < 0 || bottom < 0) height = -1;
                    final AbsoluteLayout.LayoutParams params = new AbsoluteLayout.LayoutParams(width, height, (int) left, (int) top);

                    SurfaceApi surfaceApi = createSurfaceApi(webView.getContext(), surfaceId);
                    webView.addView(surfaceApi.getSurfaceView(), params);

                    SurfaceApi oldApi = surfaceInstances.get(surfaceId);
                    if (oldApi != null) {
                        Log.e(TAG, "Create surface warn: there has a old surfaceId instance. Override it.");
                        View oldView = oldApi.getSurfaceView();
                        if (oldView != null) webView.removeView(oldView);
                    }

                    surfaceInstances.put(surfaceId, surfaceApi);
                }
            });
        }
    }


    @JavascriptInterface
    @BatchCallHelper.BatchMethod("11")
    public void onSurfaceBoundChange(int surfaceId, float left, float top, float right, float bottom){
        if(DEBUG) Log.d(TAG, "onSurfaceBoundChange, surfaceId:" + surfaceId + ", left:" + left + ", top:" + top + ", right:" + right + ", bottom:" + bottom);
        SurfaceApi surfaceApi = surfaceInstances.get(surfaceId);
        final View surfaceView = surfaceApi.getSurfaceView();
        if(surfaceView!=null){
            AbsoluteLayout.LayoutParams params = (AbsoluteLayout.LayoutParams) surfaceView.getLayoutParams();
            int width = (int) (right-left);
            int height = (int) (bottom - top);
            if(width<0 || right<0) width = -1;
            if(height<0 || bottom<0) height = -1;
            params.width = width;
            params.height = height;
            params.x = (int) left;
            params.y = (int) top;

            surfaceView.post(new Runnable() {
                @Override
                public void run() {
                    surfaceView.requestLayout();
                }
            });
        }
    }

    //================= canvas api =================
    @JavascriptInterface
    @BatchCallHelper.BatchMethod("31")
    public void lockCanvas(final int surfaceId, final int canvasId, final float left, final float top, final float right, final float bottom){
        if(DEBUG) Log.d(TAG, "lockCanvas, surfaceId:" + surfaceId + ", canvasId:" + canvasId + ", left:" + left + ", top:" + top + ", right:" + right + ", bottom:" + bottom);

        SurfaceApi surfaceApi = surfaceInstances.get(surfaceId);
        CanvasApi canvasApi = surfaceApi.lockCanvas(left, top, right, bottom);
        canvasInstances.put(canvasId, canvasApi);
    }

    @JavascriptInterface
    @BatchCallHelper.BatchMethod("32")
    public void unlockCanvasAndPost(final int surfaceId, final int canvasId){
        if(DEBUG) Log.d(TAG, "unlockCanvasAndPost, surfaceId:" + surfaceId + ", canvasId:" + canvasId);
        SurfaceApi surfaceApi = surfaceInstances.get(surfaceId);
        CanvasApi canvasApi = canvasInstances.get(canvasId);
        drawHTMLBoundToCanvas(canvasApi.getCanvas());
        surfaceApi.unlockCanvasAndPost(canvasApi);

        //recycle canvas
        CanvasApi oldCanvasApi = canvasInstances.get(canvasId);
        if(oldCanvasApi!=null) oldCanvasApi.recycle();
        else{
            Log.e(TAG, "unlockCanvasAndPost recycle canvas warn: no canvas exist, id: " + canvasId);
        }
        canvasInstances.remove(canvasId);
    }

    //canvas api
    @JavascriptInterface
    @BatchCallHelper.BatchMethod(value = "33", batchCantSkip = true)
    public void createCanvas(final int canvasId, final float width, final float height){
        if(DEBUG) Log.d(TAG, "createCanvas, canvasId:" + canvasId + ", width:" + width + ", height:" + height);
        CanvasApi newCanvasApi = createCanvasApi((int) width, (int) height);
        CanvasApi oldCanvasApi = canvasInstances.get(canvasId);
        if(oldCanvasApi!=null){
            Log.e(TAG, "Create canvas warn: there has a old canvasId instance. Override it. canvasId: " + canvasId);
            oldCanvasApi.recycle();
        }
        canvasInstances.put(canvasId, newCanvasApi);
    }
    @JavascriptInterface
    @BatchCallHelper.BatchMethod("34")
    public void recycleCanvas(final int canvasId){
        if(DEBUG) Log.d(TAG, "recycleCanvas, canvasId:" + canvasId);
        CanvasApi oldCanvasApi = canvasInstances.get(canvasId);
        if(oldCanvasApi!=null) oldCanvasApi.recycle();
        else{
            Log.e(TAG, "recycle canvas warn: no canvas exist, id: " + canvasId);
        }
        canvasInstances.remove(canvasId);
    }
    @JavascriptInterface
    @BatchCallHelper.BatchMethod("35")
    public void translate(int canvasId, float tx, float ty){
        if(DEBUG) Log.d(TAG, "translate, canvasId:" + canvasId + ", tx:" + tx + ", ty:" + ty);
        CanvasApi canvasApi = canvasInstances.get(canvasId);
        canvasApi.translate(tx, ty);
    }
    @JavascriptInterface
    @BatchCallHelper.BatchMethod("36")
    public void scale(int canvasId, float sx, float sy){
        if(DEBUG) Log.d(TAG, "scale, canvasId:" + canvasId + ", sx:" + sx + ", sy:" + sy);
        CanvasApi canvasApi = canvasInstances.get(canvasId);
        canvasApi.scale(sx, sy);
    }
    @JavascriptInterface
    @BatchCallHelper.BatchMethod("37")
    public void rotate(int canvasId, float degrees){
        if(DEBUG) Log.d(TAG, "rotate, canvasId:" + canvasId + ", degrees:" + degrees);
        CanvasApi canvasApi = canvasInstances.get(canvasId);
        canvasApi.rotate(degrees);
    }
    @JavascriptInterface
    @BatchCallHelper.BatchMethod("38")
    public void concat(int canvasId, float MSCALE_X, float MSKEW_X, float MTRANS_X, float MSKEW_Y, float MSCALE_Y, float MTRANS_Y){
        if(DEBUG) Log.d(TAG, "concat, canvasId:" + canvasId + ", MSCALE_X:" + MSCALE_X + ", MSKEW_X:" + MSKEW_X
                + ", MTRANS_X:" + MTRANS_X + ", MSKEW_Y:" + MSKEW_Y + ", MSCALE_Y:" + MSCALE_Y + ", MTRANS_Y:" + MTRANS_Y);
        CanvasApi canvasApi = canvasInstances.get(canvasId);
        canvasApi.concat(MSCALE_X, MSKEW_X, MTRANS_X, MSKEW_Y, MSCALE_Y, MTRANS_Y);
    }
    @JavascriptInterface
    @BatchCallHelper.BatchMethod("39")
    public void drawColor(int canvasId, long color){
        if(DEBUG) Log.d(TAG, "drawColor, canvasId:" + canvasId + ", color:" + Integer.toHexString((int) color));
        CanvasApi canvasApi = canvasInstances.get(canvasId);
        canvasApi.drawColor((int) color);
    }
    @JavascriptInterface
    @BatchCallHelper.BatchMethod(value = "40", batchCantSkip = true)
    public void clearColor(int canvasId){
        if(DEBUG) Log.d(TAG, "clearColor, canvasId:" + canvasId);
        CanvasApi canvasApi = canvasInstances.get(canvasId);
        canvasApi.clearColor();
    }
    @JavascriptInterface
    @BatchCallHelper.BatchMethod("41")
    public void drawRect(int canvasId, float left, float top, float width, float height, int fillStyle){
        if(DEBUG) Log.d(TAG, "drawRect, canvasId:" + canvasId + ", left:" + left + ", top:" + top + ", width:" + width + ", height:" + height + ", fillStyle:" + fillStyle);
        CanvasApi canvasApi = canvasInstances.get(canvasId);
        canvasApi.drawRect(left, top, width, height, fillStyle);
    }
    @JavascriptInterface
    @BatchCallHelper.BatchMethod("42")
    public void clipRect(int canvasId, float left, float top, float width, float height){
        if(DEBUG) Log.d(TAG, "clipRect, canvasId:" + canvasId + ", left:" + left + ", top:" + top + ", width:" + width + ", height:" + height);
        CanvasApi canvasApi = canvasInstances.get(canvasId);
        canvasApi.clipRect(left, top, width, height);
    }
    @JavascriptInterface
    @BatchCallHelper.BatchMethod("43")
    public void save(int canvasId){
        if(DEBUG) Log.d(TAG, "save, canvasId:" + canvasId);
        CanvasApi canvasApi = canvasInstances.get(canvasId);
        canvasApi.save();
    }
    @JavascriptInterface
    @BatchCallHelper.BatchMethod("44")
    public void restore(int canvasId){
        if(DEBUG) Log.d(TAG, "restore, canvasId:" + canvasId);
        CanvasApi canvasApi = canvasInstances.get(canvasId);
        canvasApi.restore();
    }
    @JavascriptInterface
    @BatchCallHelper.BatchMethod("45")
    public void drawCanvas(int canvasId, int drawCanvasId, float offsetX, float offsetY){
        if(DEBUG) Log.d(TAG, "drawCanvas, canvasId:" + canvasId + ", drawCanvasId:" + drawCanvasId + ", offsetX:" + offsetX + ", offsetY:" + offsetY);
        CanvasApi canvasApi = canvasInstances.get(canvasId);
        if(canvasApi==null){
            Log.e(TAG, "canvas not found, id: " + canvasId);
            return;
        }
        CanvasApi drawCanvasApi = canvasInstances.get(drawCanvasId);
        if(drawCanvasApi==null){
            Log.e(TAG, "draw canvas not found, id: " + drawCanvasId);
            return;
        }
        canvasApi.drawCanvas(drawCanvasApi, offsetX, offsetY);
    }
    @JavascriptInterface
    @BatchCallHelper.BatchMethod("47")
    public void drawText(int canvasId, String text, float x, float y, int fillStyle){
        if(DEBUG) Log.d(TAG, "drawText, canvasId:" + canvasId + ", text:" + text + ", x:" + x + ", y:" + y + ", fillStyle:" + fillStyle);
        CanvasApi canvasApi = canvasInstances.get(canvasId);
        canvasApi.drawText(text, x, y, fillStyle);
    }

    private final Paint measureTextPaint = new Paint();
    @JavascriptInterface
    @BatchCallHelper.BatchMethod("48")
    public float measureText(String text, float textSize){
        if(DEBUG) Log.d(TAG, "measureText, text:" + text + ", textSize:" + textSize);
        synchronized (measureTextPaint) {
            measureTextPaint.setTextSize(textSize);
            return measureTextPaint.measureText(text);
        }
    }

    @JavascriptInterface
    @BatchCallHelper.BatchMethod("49")
    public void setFillColor(int canvasId, long color, int fillStyle){
        if(DEBUG) Log.d(TAG, "setFillColor, canvasId:" + canvasId + ", color:" + color + ", fillStyle:" + fillStyle);
        CanvasApi canvasApi = canvasInstances.get(canvasId);
        canvasApi.setColor((int) color, fillStyle);
    }
    @JavascriptInterface
    @BatchCallHelper.BatchMethod("50")
    public void multiplyGlobalAlpha(int canvasId, float alpha){
        if(DEBUG) Log.d(TAG, "multiplyGlobalAlpha, canvasId:" + canvasId + ", alpha:" + alpha);
        CanvasApi canvasApi = canvasInstances.get(canvasId);
        canvasApi.multiplyGlobalAlpha(alpha);
    }
    @JavascriptInterface
    @BatchCallHelper.BatchMethod("51")
    public void setGlobalAlpha(int canvasId, float alpha){
        if(DEBUG) Log.d(TAG, "setGlobalAlpha, canvasId:" + canvasId + ", alpha:" + alpha);
        CanvasApi canvasApi = canvasInstances.get(canvasId);
        canvasApi.setGlobalAlpha(alpha);
    }
    @JavascriptInterface
    @BatchCallHelper.BatchMethod("52")
    public void setTextAlign(int canvasId, String textAlign){
        if(DEBUG) Log.d(TAG, "setTextAlign, canvasId:" + canvasId + ", textAlign:" + textAlign);
        CanvasApi canvasApi = canvasInstances.get(canvasId);
        canvasApi.setTextAlign(textAlign);
    }
    @JavascriptInterface
    @BatchCallHelper.BatchMethod("53")
    public void setLineWidth(int canvasId, float lineWidth){
        if(DEBUG) Log.d(TAG, "setLineWidth, canvasId:" + canvasId + ", lineWidth:" + lineWidth);
        CanvasApi canvasApi = canvasInstances.get(canvasId);
        canvasApi.setLineWidth(lineWidth);
    }
    @JavascriptInterface
    @BatchCallHelper.BatchMethod("54")
    public void setLineCap(int canvasId, String cap){
        if(DEBUG) Log.d(TAG, "setLineCap, canvasId:" + canvasId + ", cap:" + cap);
        CanvasApi canvasApi = canvasInstances.get(canvasId);
        canvasApi.setLineCap(cap);
    }
    @JavascriptInterface
    @BatchCallHelper.BatchMethod("55")
    public void setLineJoin(int canvasId, String lineJoin){
        if(DEBUG) Log.d(TAG, "setLineJoin, canvasId:" + canvasId + ", lineJoin:" + lineJoin);
        CanvasApi canvasApi = canvasInstances.get(canvasId);
        canvasApi.setLineJoin(lineJoin);
    }
    @JavascriptInterface
    @BatchCallHelper.BatchMethod("56")
    public void setShadow(int canvasId, float radius, float dx, float dy, long color){
        if(DEBUG) Log.d(TAG, "setShadow, canvasId:" + canvasId + ", radius:" + radius + ", dx:" + dx + ", dy:" + dy + ", color:" + color);
        CanvasApi canvasApi = canvasInstances.get(canvasId);
        canvasApi.setShadow(radius, dx, dy, (int) color);
    }
    @JavascriptInterface
    @BatchCallHelper.BatchMethod("57")
    public void setFontSize(int canvasId, float textSize){
        if(DEBUG) Log.d(TAG, "setFontSize, canvasId:" + canvasId + ", textSize:" + textSize);
        CanvasApi canvasApi = canvasInstances.get(canvasId);
        canvasApi.setFontSize(textSize);
    }

    @JavascriptInterface
    @BatchCallHelper.BatchMethod("58")
    public void setFont(int canvasId, String fontName){
        if(DEBUG) Log.d(TAG, "setFont, canvasId:" + canvasId + ", fontName:" + fontName);
        CanvasApi canvasApi = canvasInstances.get(canvasId);
        canvasApi.setFont(fontName);
    }

    @JavascriptInterface
    @BatchCallHelper.BatchMethod("59")
    public void drawOval(int canvasId, float left, float top, float right, float bottom, int fillStyle){
        if(DEBUG) Log.d(TAG, "drawOval, canvasId:" + canvasId + ", left:" + left + ", top:" + top + ", right:" + right + ", bottom:" + bottom + ", fillStyle:" + fillStyle);
        CanvasApi canvasApi = canvasInstances.get(canvasId);
        canvasApi.drawOval(left, top, right, bottom, fillStyle);
    }

    @JavascriptInterface
    @BatchCallHelper.BatchMethod("60")
    public void drawCircle(int canvasId, float cx, float cy, float radius, int fillStyle){
        if(DEBUG) Log.d(TAG,"drawCircle, canvasId:"+ canvasId + ", cx:" + cx + ", cy:" + cy + ", radius:" + radius + ", fillStyle:" + fillStyle);
        CanvasApi canvasApi = canvasInstances.get(canvasId);
        canvasApi.drawCircle(cx, cy, radius, fillStyle);
    }

    @JavascriptInterface
    @BatchCallHelper.BatchMethod("61")
    public void drawArc(int canvasId, float left, float top, float right, float bottom, float startAngle, float sweepAngle, boolean useCenter, int fillStyle){
        if(DEBUG) Log.d(TAG,"drawArc, canvasId:"+ canvasId +", left:" + left + ", top:" + top + ", right:" + right + ", bottom:" + bottom
                + ", startAngle:" + startAngle + ", sweepAngle:" + sweepAngle + ", useCenter:" + useCenter + ", fillStyle:" + fillStyle);
        CanvasApi canvasApi = canvasInstances.get(canvasId);
        canvasApi.drawArc(left, top, right, bottom, startAngle, sweepAngle, useCenter, fillStyle);
    }

    @JavascriptInterface
    @BatchCallHelper.BatchMethod("62")
    public void drawRoundRect(int canvasId, float left, float top, float width, float height, float radiusTopLeft,
                              float radiusTopRight, float radiusBottomRight, float radiusBottomLeft, int fillStyle) {
        if (DEBUG) Log.d(TAG, "drawRoundRect, canvasId:"+ canvasId +", left:" + left + ", top:" + top + ", width:" + width + ", height:" + height
                    + ", radiusTopLeft:" + radiusTopLeft + ", radiusTopRight:" + radiusTopRight + ", radiusBottomRight:"
                    + radiusBottomRight + ", radiusBottomLeft:" + radiusBottomLeft + ", fillStyle:" + fillStyle);
        CanvasApi canvasApi = canvasInstances.get(canvasId);
        canvasApi.drawRoundRect(left, top, width, height, radiusTopLeft, radiusTopRight, radiusBottomRight, radiusBottomLeft, fillStyle);
    }

    @JavascriptInterface
    @BatchCallHelper.BatchMethod("63")
    public void clipRoundRect(int canvasId, float left, float top, float width, float height, float radiusTopLeft,
                              float radiusTopRight, float radiusBottomRight, float radiusBottomLeft) {
        if (DEBUG) Log.d(TAG, "clipRoundRect, canvasId:"+ canvasId +", left:" + left + ", top:" + top + ", width:" + width + ", height:" + height
                    + ", radiusTopLeft:" + radiusTopLeft + ", radiusTopRight:" + radiusTopRight + ", radiusBottomRight:"
                    + radiusBottomRight + ", radiusBottomLeft:" + radiusBottomLeft);
        CanvasApi canvasApi = canvasInstances.get(canvasId);
        canvasApi.clipRoundRect(left, top, width, height, radiusTopLeft, radiusTopRight, radiusBottomRight, radiusBottomLeft);
    }

    //=================draw image=================

    @JavascriptInterface
    @BatchCallHelper.BatchMethod("70")
    public void drawImage(int canvasId, int drawImageId, float left, float top){
        if(DEBUG) Log.d(TAG, "drawImage, canvasId:" + canvasId + ", drawImageId:" + drawImageId + ", left:" + left + ", top:" + top);
        CanvasApi canvasApi = canvasInstances.get(canvasId);
        ImageApi imageApi = imageInstances.get(drawImageId);
        canvasApi.drawImage(imageApi, left, top);
    }
    @JavascriptInterface
    @BatchCallHelper.BatchMethod("71")
    public void drawImage(int canvasId, int drawImageId, float dstLeft, float dstTop, float dstRight, float dstBottom){
        if(DEBUG) Log.d(TAG, "drawImage, canvasId:" + canvasId + ", drawImageId:" + drawImageId
                + ", dstLeft:" + dstLeft + ", dstTop:" + dstTop + ", dstRight:" + dstRight + ", dstBottom:" + dstBottom);
        CanvasApi canvasApi = canvasInstances.get(canvasId);
        ImageApi imageApi = imageInstances.get(drawImageId);
        canvasApi.drawImage(imageApi, dstLeft, dstTop, dstRight, dstBottom);
    }
    @JavascriptInterface
    @BatchCallHelper.BatchMethod("72")
    public void drawImage(int canvasId, int drawImageId, float srcLeft, float srcTop, float srcRight, float srcBottom,
                          float dstLeft, float dstTop, float dstRight, float dstBottom){
        if(DEBUG) Log.d(TAG, "drawImage, canvasId:" + canvasId + ", drawImageId:" + drawImageId
                + ", srcLeft:" + srcLeft + ", srcTop:" + srcTop + ", srcRight:" + srcRight + ", srcBottom:" + srcBottom
                + ", dstLeft:" + dstLeft + ", dstTop:" + dstTop + ", dstRight:" + dstRight + ", dstBottom:" + dstBottom);
        CanvasApi canvasApi = canvasInstances.get(canvasId);
        ImageApi imageApi = imageInstances.get(drawImageId);
        canvasApi.drawImage(imageApi, srcLeft, srcTop, srcRight, srcBottom, dstLeft, dstTop, dstRight, dstBottom);
    }

    //=================image api==================
    @JavascriptInterface
//    @BatchCallHelper.BatchMethod(value = "80", batchCantSkip = true)
    public void createImage(int imageId){
        if(DEBUG) Log.d(TAG, "createImage, imageId:" + imageId);
        ImageApi imageApi = new ImageApi(this);
        ImageApi oldImage = imageInstances.get(imageId);
        if(oldImage!=null){
            Log.e(TAG, "Create image warn: there has a old imageId instance. Override it.");
            oldImage.recycle();
        }
        imageInstances.put(imageId, imageApi);
    }
    @JavascriptInterface
//    @BatchCallHelper.BatchMethod(value = "81", batchCantSkip = true)
    public void loadImage(int imageId, String src){
        if(DEBUG) Log.d(TAG, "loadImage, imageId:" + imageId + ", src:" + src);
        ImageApi imageApi = imageInstances.get(imageId);
        imageApi.loadImage(src);
    }
    @JavascriptInterface
//    @BatchCallHelper.BatchMethod(value = "82", batchCantSkip = true)
    public void recycleImage(int imageId){
        if(DEBUG) Log.d(TAG, "recycleImage, imageId:" + imageId);
        ImageApi imageApi = imageInstances.get(imageId);
        imageApi.recycle();
    }
    @JavascriptInterface
//    @BatchCallHelper.BatchMethod(value = "83", batchCantSkip = true)
    public void getPixels(int imageId, int callBackIndex, float left, float top, float right, float bottom){
        if(DEBUG) Log.d(TAG, "getPixels, imageId:" + imageId + ", callBackIndex:" + callBackIndex
                + ", left:" + left+ ", top:" + top+ ", right:" + right+ ", bottom:" + bottom);
        ImageApi imageApi = imageInstances.get(imageId);
        Bitmap bitmap = imageApi.getBitmap();
        if(bitmap==null) notifyImageGetPixels(imageId, callBackIndex, new int[0]);
        else{
            int width = (int)right - (int)left;
            int height = (int)bottom - (int)top;
            int[] data = new int[width*height];
            bitmap.getPixels(data, 0, width, (int)left, (int)top, width, height);
            notifyImageGetPixels(imageId, callBackIndex, data);
        }
    }

    //==========================DrawHTMLBound api==========================
    @JavascriptInterface
    public void showDrawHTMLBound(int viewHash, float left, float top, float right, float bottom){
        if(DEBUG) Log.d(TAG, "showDrawHTMLBound, viewHash:" + viewHash + ", left:"+left + ", top:"+top + ", right:"+right+", bottom:"+bottom);
        Rect drawHTMLBound = drawHTMLBounds.get(viewHash);
        if(drawHTMLBound==null){
            drawHTMLBound = new Rect((int) left, (int) top, (int) right, (int) bottom);
            drawHTMLBounds.put(viewHash, drawHTMLBound);
        } else {
            drawHTMLBound.set((int) left, (int) top, (int) right, (int) bottom);
        }
    }
    @JavascriptInterface
    public void hideDrawHTMLBound(int viewHash){
        if(DEBUG) Log.d(TAG, "hideDrawHTMLBound, viewHash:" + viewHash);
        drawHTMLBounds.remove(viewHash);
    }

    void drawHTMLBoundToCanvas(Canvas canvas){
        WebView webView = getWebView();
        if(webView!=null){
            mRectTmp.setEmpty();
            for (int i = 0; i < drawHTMLBounds.size(); i++) {
                mRectTmp.union(drawHTMLBounds.valueAt(i));
            }
            if(!mRectTmp.isEmpty()) {
                Bitmap bitmap = Bitmap.createBitmap(mRectTmp.width(), mRectTmp.height(), Bitmap.Config.ARGB_8888);
                Canvas canvasTmp = new Canvas(bitmap);
                canvasTmp.translate(-mRectTmp.left, -mRectTmp.top);
                webView.draw(canvasTmp);
                canvas.drawBitmap(bitmap, mRectTmp.left, mRectTmp.top, null);
                bitmap.recycle();
            }
        }
    }


    //==========================webView api==========================
    private void notifyWebViewLoadFinish(int viewHash, String url, String title){
        execJSOnUI(String.format("androidui.native.NativeWebView.notifyLoadFinish(%d, '%s', '%s');", viewHash, url, title));
    }
    private void notifyWebViewHistoryChange(int viewHash, int currentHistoryIndex, int historySize){
        execJSOnUI(String.format("androidui.native.NativeWebView.notifyWebViewHistoryChange(%d, %d, %d);", viewHash, currentHistoryIndex, historySize));
    }
    @JavascriptInterface
    public void createWebView(final int viewHash){
        if(DEBUG_WEBVIEW) Log.d(TAG, "createWebView, viewHash:" + viewHash);
        final WebView containWebView = getWebView();
        if(containWebView!=null) {
            containWebView.post(new Runnable() {
                @Override
                public void run() {
                    WebView newWebView = new WebView(containWebView.getContext());
                    newWebView.setWebViewClient(new WebViewClient() {
                        @Override
                        public void onPageFinished(WebView view, String url) {
                            super.onPageFinished(view, url);
                            notifyWebViewLoadFinish(viewHash, url, view.getTitle());

                            WebBackForwardList historyList = view.copyBackForwardList();
                            notifyWebViewHistoryChange(viewHash, historyList.getCurrentIndex(), historyList.getSize());
                        }

                        @Override
                        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                            super.doUpdateVisitedHistory(view, url, isReload);

                            WebBackForwardList historyList = view.copyBackForwardList();
                            notifyWebViewHistoryChange(viewHash, historyList.getCurrentIndex(), historyList.getSize());
                        }
                    });
                    WebView oldWebView = webViewInstances.get(viewHash);
                    if (oldWebView != null) {
                        Log.e(TAG, "Create WebView warn: there has a old WebView instance. Override it. viewHash:" + viewHash);
                        containWebView.removeView(oldWebView);
                    }
                    webViewInstances.put(viewHash, newWebView);
                    containWebView.addView(newWebView, 0, 0);
                }
            });
        }
    }

    @JavascriptInterface
    public void destroyWebView(final int viewHash){
        if(DEBUG_WEBVIEW) Log.d(TAG, "destroyWebView, viewHash:" + viewHash);
        final WebView containWebView = getWebView();
        if(containWebView!=null) {
            containWebView.post(new Runnable() {
                @Override
                public void run() {
                    WebView oldWebView = webViewInstances.get(viewHash);
                    if (oldWebView != null) {
                        containWebView.removeView(oldWebView);
                        webViewInstances.remove(viewHash);
                    }
                }
            });
        }
    }

    @JavascriptInterface
    public void webViewBoundChange(final int viewHash, final float left, final float top, final float right, final float bottom){
        if(DEBUG_WEBVIEW) Log.d(TAG, "webViewBoundChange, viewHash:" + viewHash + ", left:"+left + ", top:"+top + ", right:"+right+", bottom:"+bottom);

        final WebView containWebView = getWebView();
        if(containWebView!=null) {
            containWebView.post(new Runnable() {
                @Override
                public void run() {
                    WebView subWebView = webViewInstances.get(viewHash);
                    if (subWebView != null) {
                        if (subWebView.getParent() == null) {
                            WebView containWebView = getWebView();
                            if (containWebView != null) {
                                AbsoluteLayout.LayoutParams layoutParams = new AbsoluteLayout.LayoutParams((int) (right - left), (int) (bottom - top), (int) left, (int) top);
                                containWebView.addView(subWebView, layoutParams);
                            }
                        } else {
                            AbsoluteLayout.LayoutParams layoutParams = (AbsoluteLayout.LayoutParams) subWebView.getLayoutParams();
                            if (layoutParams == null) {
                                layoutParams = new AbsoluteLayout.LayoutParams((int) (right - left), (int) (bottom - top), (int) left, (int) top);
                            } else {
                                layoutParams.x = (int) left;
                                layoutParams.y = (int) top;
                                layoutParams.width = (int) (right - left);
                                layoutParams.height = (int) (bottom - top);
                            }
                            subWebView.setLayoutParams(layoutParams);
                        }

                    } else {
                        Log.w(TAG, "warn: no WebView instance. viewHash:" + viewHash);
                    }
                }
            });
        }
    }

    @JavascriptInterface
    public void webViewLoadUrl(final int viewHash, final String url){
        if(DEBUG_WEBVIEW) Log.d(TAG, "webViewLoadUrl, viewHash:" + viewHash + ", url:"+url);

        final WebView containWebView = getWebView();
        if(containWebView!=null) {
            containWebView.post(new Runnable() {
                @Override
                public void run() {
                    WebView subWebView = webViewInstances.get(viewHash);
                    if(subWebView!=null) {
                        subWebView.loadUrl(url);
                    } else {
                        Log.w(TAG, "warn: no WebView instance. viewHash:" + viewHash);
                    }
                }
            });
        }
    }

    @JavascriptInterface
    public void webViewGoBack(final int viewHash){
        if(DEBUG_WEBVIEW) Log.d(TAG, "webViewGoBack, viewHash:" + viewHash);
        final WebView containWebView = getWebView();
        if(containWebView!=null) {
            containWebView.post(new Runnable() {
                @Override
                public void run() {
                    final WebView subWebView = webViewInstances.get(viewHash);
                    if (subWebView != null) {
                        subWebView.goBack();
                    } else {
                        Log.w(TAG, "warn: no WebView instance. viewHash:" + viewHash);
                    }
                }
            });
        }
    }

    @JavascriptInterface
    public void webViewReload(final int viewHash){
        if(DEBUG_WEBVIEW) Log.d(TAG, "webViewReload, viewHash:" + viewHash);
        final WebView containWebView = getWebView();
        if(containWebView!=null) {
            containWebView.post(new Runnable() {
                @Override
                public void run() {
                    final WebView subWebView = webViewInstances.get(viewHash);
                    if (subWebView != null) {
                        subWebView.reload();
                    } else {
                        Log.w(TAG, "warn: no WebView instance. viewHash:" + viewHash);
                    }
                }
            });
        }
    }
}
