// OpticalBro - based on:
// ViewFinder - a simple app to:
//	  (i) read camera & show preview image on screen,
//	 (ii) compute some simple statistics from preview image and
//	(iii) superimpose results on screen in text and graphic form
// Based originally on http://web.stanford.edu/class/ee368/Android/ViewfinderEE368/


package edu.mit.web.opticalbro;

import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.hardware.Camera;
import android.hardware.Camera.PreviewCallback;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.Window;
import android.view.WindowManager;
import android.graphics.Matrix;
import org.opencv.core.Core;
import org.opencv.core.Mat;
//import org.opencv.core.Core;

import java.io.IOException;
import java.util.List;

public class MainActivity extends Activity
{
    String TAG = "ViewFinder";    // tag for logcat output
    String asterisks = " *******************************************"; // for noticeable marker in log
    protected static int mCam = 0;      // the number of the camera to use (0 => rear facing)
    protected static Camera mCamera = null;
    int nPixels = 240 * 320;            // approx number of pixels desired in preview
    int downscalingFactor = 4;          // factor for downsampling the image after capture but prior to processing; the resolution will be divided by this number in each dimension
    protected static int mCameraHeight;   // preview height (determined later)
    protected static int mCameraWidth;    // preview width
    protected static Preview mPreview;
    protected static DrawOnTop mDrawOnTop;
    protected static LayoutParams mLayoutParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    private static boolean DBG=true;

    static boolean bDisplayInfoFlag = true;	// show info about display  in log file
    static boolean nCameraInfoFlag = true;	// show info about cameras in log file

    long timeSinceLastFrame = 0;
    long timeOfLastFrame = System.currentTimeMillis();
    float fps;

    @Override
    protected void onCreate (Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        if (DBG) Log.v(TAG, "onCreate" + asterisks);
        if (!checkCameraHardware(this)) {    // (need "context" as argument here)
            Log.e(TAG, "Device does not have a camera! Exiting"); // tablet perhaps?
            System.exit(0);    // finish()
        }
        // go full screen
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        // and hide the window title.
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        // optional dump of useful info into the log
        if (bDisplayInfoFlag) ExtraInfo.showDisplayInfo(this); // show some info about display
        if (nCameraInfoFlag) ExtraInfo.showCameraInfoAll(); // show some info about all cameras
    }

    // Because the CameraDevice object is not a shared resource,
    // it's very important to release it when the activity is paused.

    @Override
    protected void onPause ()
    {
        super.onPause();
        if (DBG) Log.v(TAG, "onPause" + asterisks);
        releaseCamera(mCam, true);    // release camera here
    }

    // which means the CameraDevice has to be (re-)opened when the activity is (re-)started

    @Override
    protected void onResume ()
    {
        super.onResume();
        if (DBG) Log.v(TAG, "onResume" + asterisks);
        openCamera(mCam);    // (re-)open camera here
        getPreviewSize(mCamera, nPixels);    // pick an available preview size

        // Create our DrawOnTop view.
        mDrawOnTop = new DrawOnTop(this);
        // Create our Preview view
        mPreview = new Preview(this, mDrawOnTop);
        // and set preview as the content of our activity.
        setContentView(mPreview);
        // and add overlay to content of our activity.
        addContentView(mDrawOnTop, mLayoutParams);
    }

    @Override
    protected void onDestroy ()
    {
        super.onDestroy();
        if (DBG) Log.v(TAG, "onDestroy" + asterisks);
        releaseCamera(mCam, true);    // if it hasn't been released yet...
    }

    //////////////////////////////////////////////////////////////////////////////

    // Check if this device actually has a camera!
    public static boolean checkCameraHardware (Context context)
    {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA);
    }

    protected static void openCamera (int nCam)
    {
        String TAG = "openCamera";
        if (mCamera == null) {
            try {
                if (DBG) Log.i(TAG, "Opening camera " + nCam);
                mCamera = Camera.open(nCam);
            } catch (Exception e) {
                Log.e(TAG, "ERROR: camera open exception " + e);
                System.exit(0); // should not happen
            }
        }
        else Log.e(TAG, "Camera already open");
    }

    protected static void releaseCamera (int nCam, boolean previewFlag)
    {
        String TAG = "releaseCamera";
        if (mCamera != null) {
            if (DBG) Log.i(TAG, "Releasing camera " + nCam);
            if (previewFlag) {    // if we have been getting previews from this camera
                mCamera.setPreviewCallback(null);
                mCamera.stopPreview();
            }
            mCamera.release();
            mCamera = null;
        }
        else Log.e(TAG, "No camera to release");
    }

    private static void getPreviewSize (Camera mCamera, int nPixels)
    { //	pick one of the available preview size
        String TAG = "getPreviewSize";
        Camera.Parameters params = mCamera.getParameters();
        List<Camera.Size> cSizes = params.getSupportedPictureSizes();
        int dPixels, dMinPixels = -1;
        if (DBG) Log.i(TAG, "Looking for about " + nPixels + " pixels");
        for (Camera.Size cSize : cSizes) {    // step through available camera preview image sizes
            if (DBG) Log.i(TAG, "Size " + cSize.height + " x " + cSize.width); // debug log output
//			use desired pixel count as a guide to selection
            dPixels = Math.abs(cSize.height * cSize.width - nPixels);
            if (dMinPixels < 0 || dPixels < dMinPixels) {
                mCameraHeight = cSize.height;
                mCameraWidth = cSize.width;
                dMinPixels = dPixels;
            }
        }
        if (DBG) Log.i(TAG, "Nearest fit available preview image size: " + mCameraHeight + " x " + mCameraWidth);
    }

//------- nested class DrawOnTop ---------------------------------------------------------------

    class DrawOnTop extends View
    {
        Bitmap mBitmap;
        byte[] mYUVData;
        int[] mRGBData;
        int[][] mGrayscaleData;
        int[][] mPrevGrayscaleData;
        int mImageWidth, mImageHeight;
        Paint mPaintBlack;
        Paint mPaintYellow;
        Paint mPaintRed;
        Paint mPaintGreen;
        Paint mPaintBlue;
        int mTextsize = 90;		// controls size of text on screen
        int mLeading;			// spacing between text lines
        String TAG = "DrawOnTop";       // for logcat output

        // Derivatives of brightness in x, y, and time
        int[][] E_x;
        int[][] E_y;
        int[][] E_t;

        // optical flow in the x and y directions
        float u[][];
        float v[][];
        // optical flow local average (used in intermediate computation)
        float uAvg[][];
        float vAvg[][];

        // play around with this value
        float lambda = 5.0f;

        public DrawOnTop (Context context)
        { // constructor
            super(context);

            mPaintBlack = makePaint(Color.BLACK);
            mPaintYellow = makePaint(Color.YELLOW);
            mPaintRed = makePaint(Color.RED);
            mPaintGreen = makePaint(Color.GREEN);
            mPaintBlue = makePaint(Color.BLUE);

            mBitmap = null;	// will be set up later in Preview - PreviewCallback
            mYUVData = null;
            mRGBData = null;
            mGrayscaleData = null;
            if (DBG) Log.i(TAG, "DrawOnTop textsize " + mTextsize);
            mLeading = mTextsize * 6 / 5;    // adjust line spacing
            if (DBG) Log.i(TAG, "DrawOnTop Leading " + mLeading);

        }

        Paint makePaint (int color)
        {
            Paint mPaint = new Paint();
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(color);
            mPaint.setTextSize(mTextsize);
            mPaint.setTypeface(Typeface.MONOSPACE);
            return mPaint;
        }

        // Called when preview is drawn on screen

        @Override
        protected void onDraw (Canvas canvas)
        {
            String TAG="onDraw";
            if (mBitmap == null) {	// sanity check
                Log.w(TAG, "mBitMap is null");
                super.onDraw(canvas);
                return;	// because not yet set up
            }

            // Convert image from YUV to RGB format:
            //decodeYUV420SP(mRGBData, mYUVData, mImageWidth, mImageHeight);
            // Currently using the "grayscale" version
            decodeYUV420SPGrayscale(mGrayscaleData, mYUVData, mImageWidth, mImageHeight);

            // Now do some image processing here:
            // Calculate the FPS
            timeSinceLastFrame = System.currentTimeMillis() - timeOfLastFrame;
            timeOfLastFrame = System.currentTimeMillis();
            fps = 1000f / timeSinceLastFrame;

            //Mat mat1 = Mat();
            //mat1.put(0,0,mGrayscaleData[0]);

            // Calculate the brightness gradient in X, Y, and time
            for (int j = 0; j < mImageHeight - 1; j++) {
                for (int i = 0; i < mImageWidth - 1; i++) {
                    // units are [greyscale_value per pixel]
                    E_x[j][i] = (mPrevGrayscaleData[j][i+1]
                            + mGrayscaleData[j][i+1]
                            + mPrevGrayscaleData[j+1][i+1]
                            + mGrayscaleData[j+1][i+1]
                            - mPrevGrayscaleData[j][i]
                            - mGrayscaleData[j][i]
                            - mPrevGrayscaleData[j+1][i]
                            - mGrayscaleData[j+1][i])>>2;
                    E_y[j][i] = (mPrevGrayscaleData[j+1][i]
                            + mGrayscaleData[j+1][i]
                            + mPrevGrayscaleData[j+1][i+1]
                            + mGrayscaleData[j+1][i+1]
                            - mPrevGrayscaleData[j][i]
                            - mGrayscaleData[j][i]
                            - mPrevGrayscaleData[j][i+1]
                            - mGrayscaleData[j][i+1])>>2;
                    // units are [greyscale_value per second)
                    E_t[j][i] = (int)(fps*((mGrayscaleData[j][i]
                            + mGrayscaleData[j+1][i]
                            + mGrayscaleData[j][i+1]
                            + mGrayscaleData[j+1][i+1]
                            - mPrevGrayscaleData[j][i]
                            - mPrevGrayscaleData[j+1][i]
                            - mPrevGrayscaleData[j][i+1]
                            - mPrevGrayscaleData[j+1][i+1])>>2));
                }
            }


            // todo: move the following optical flow code into its own method
            
            // Initialize the guesses for optical flow as 0 everywhere
            u = new float[mImageHeight-1][mImageWidth-1];
            v = new float[mImageHeight-1][mImageWidth-1];
            uAvg = new float[mImageHeight-1][mImageWidth-1];
            vAvg = new float[mImageHeight-1][mImageWidth-1];

            // Calculate the optical flow using an iterative scheme
            for (int iterations = 0; iterations < 4; iterations++) {
                // first, calculate the averages
                for (int j = 0; j < mImageHeight-1; j++) {
                    for (int i = 0; i < mImageWidth-1; i++) {
                        uAvg[j][i] = getNeighborAverage(u, i, j, mImageWidth-1, mImageHeight-1);
                        vAvg[j][i] = getNeighborAverage(v, i, j, mImageWidth-1, mImageHeight-1);
                    }
                }

                // Then, calculate the new estimate for the velocities
                for (int j = 0; j < mImageHeight-1; j++) {
                    for (int i = 0; i < mImageWidth-1; i++) {
                        float adjustment = (E_x[j][i] * uAvg[j][i] + E_y[j][i] * vAvg[j][i] + E_t[j][i]) / (1 + lambda * ((float)Math.pow(E_x[j][i], 2) + (float)Math.pow(E_y[j][i],2)));
                        u[j][i] = uAvg[j][i] - E_x[j][i] * adjustment;
                        v[j][i] = vAvg[j][i] - E_y[j][i] * adjustment;
                    }
                }
            }

            // copy the new data to mPrevGrayscaleData for use in the next frame
            for (int j = 0; j < mImageHeight; j++) {
                System.arraycopy(mGrayscaleData[j], 0, mPrevGrayscaleData[j], 0, mImageWidth);
            }

            // Finally, use the results to draw things on top of screen:
            int canvasHeight = canvas.getHeight();
            int canvasWidth = canvas.getWidth();
            int newImageWidth = canvasWidth - 200;
            int marginWidth = (canvasWidth - newImageWidth) / 2;

            for(float x = mCameraWidth/downscalingFactor/8; x<mCameraWidth/downscalingFactor;
                x+=(mCameraWidth/downscalingFactor)/4) {
                for(float y = mCameraHeight/downscalingFactor/6; y<mCameraHeight/downscalingFactor;
                    y+=(mCameraHeight/downscalingFactor)/3) {
                    drawArrow(canvas, x, y, u, v,mPaintGreen);
                }
            }

            // Uncomment below line to draw the x gradient on top of the image
/*
            int[] E_x1D = new int[(mImageHeight - 1) * (mImageWidth - 1)];
            reshapeTo1DandThreshold(E_x, E_x1D, (mImageWidth - 1), (mImageHeight - 1));

            Bitmap gradientBitmap = Bitmap.createBitmap(E_x1D, mImageWidth-1, mImageHeight-1, Bitmap.Config.ARGB_8888);

            Rect imageRect = new Rect(0, 0, mImageWidth - 1, mImageHeight - 1);
            Rect canvasRect = new Rect(0, 0, canvasWidth, canvasHeight);
            canvas.drawBitmap(gradientBitmap, imageRect, canvasRect, null);*/


            String imageFrameRateStr = "Frame Rate: " + String.format("%4d", (int) fps);
            drawTextOnBlack(canvas, imageFrameRateStr, marginWidth+10, mLeading, mPaintYellow);

            super.onDraw(canvas);

        } // end onDraw method

        public void decodeYUV420SP (int[] rgb, byte[] yuv420sp, int width, int height)
        { // convert image in YUV420SP format to RGB format
            final int frameSize = width * height;

            for (int j = 0, pix = 0; j < height; j++) {
                int uvp = frameSize + (j >> 1) * width;	// index to start of u and v data for this row
                int u = 0, v = 0;
                for (int i = 0; i < width; i++, pix++) {
                    int y = (0xFF & ((int) yuv420sp[pix])) - 16;
                    if (y < 0) y = 0;
                    if ((i & 1) == 0) { // even row & column (u & v are at quarter resolution of y)
                        v = (0xFF & yuv420sp[uvp++]) - 128;
                        u = (0xFF & yuv420sp[uvp++]) - 128;
                    }

                    int y1192 = 1192 * y;
                    int r = (y1192 + 1634 * v);
                    int g = (y1192 - 833 * v - 400 * u);
                    int b = (y1192 + 2066 * u);

                    if (r < 0) r = 0;
                    else if (r > 0x3FFFF) r = 0x3FFFF;
                    if (g < 0) g = 0;
                    else if (g > 0x3FFFF) g = 0x3FFFF;
                    if (b < 0) b = 0;
                    else if (b > 0x3FFFF) b = 0x3FFFF;

                    rgb[pix] = 0xFF000000 | ((r << 6) & 0xFF0000) | ((g >> 2) & 0xFF00) | ((b >> 10) & 0xFF);
                }
            }
        }

        public void decodeYUV420SPGrayscale (int[][] greyscale, byte[] yuv420sp, int width, int height)
        { // extract grey RGB format image --- not used currently
            final int frameSize = width * height;

            // This is much simpler since we can ignore the u and v components
            for (int j = 0, pix = 0; j < height; j++) {
                for (int i = 0; i < width; i++, pix += downscalingFactor) {
                    greyscale[j][i] = (0xFF & ((int) yuv420sp[pix])); // this is y
                }
                pix += (downscalingFactor - 1) * width;
            }
        }

        public void reshapeTo2D (int[] rgb, int[][] greyscale, int width, int height) {
            for (int j = 0, pix = 0; j < height; j++) {
                for (int i = 0; i < width; i++, pix++) {
                    greyscale[j][i] = rgb[pix];
                }
            }
        }

        //returns 1D array that visually represents the brightness gradient
        public void reshapeTo1DandThreshold (int[][] srcArray, int[] dstArray, int width, int height) {
            for (int j = 0, pix = 0; j < height; j++) {
                for (int i = 0; i < width; i++, pix++) {
                    if(srcArray[j][i]<-20){ //below this threshold, color pixel red
                        dstArray[pix] = 0xffff0000;
                    }
                    else if (srcArray[j][i]>20){ //above this threshold, color pixel green
                        dstArray[pix] = 0xff00ff00;
                    }
                    //dstArray[pix] = srcArray[j][i];
                }
            }
        }

        // get the average of the pixels above, below, left, and right
        // or a limited set of those if it's an edge pixel
        public float getNeighborAverage(float[][] array, int i, int j, int width, int height){
            int neighborCount = 0;
            int neighborSum = 0;
            // For each neighbor, check if it's possible for that neighbor to exist
            if (i > 0) {
                neighborCount++;
                neighborSum += array[j][i-1];
            }
            if (j > 0) {
                neighborCount++;
                neighborSum += array[j-1][i];
            }
            if (i < width - 1) {
                neighborCount++;
                neighborSum += array[j][i+1];
            }
            if (j < height - 1) {
                neighborCount++;
                neighborSum += array[j+1][i];
            }
            return ((float)neighborSum / neighborCount);
        }


        private void drawTextOnBlack (Canvas canvas, String str, int rPos, int cPos, Paint mPaint)
        { // make text stand out from background by providing thin black border
            canvas.drawText(str, rPos - 1, cPos - 1, mPaintBlack);
            canvas.drawText(str, rPos + 1, cPos - 1, mPaintBlack);
            canvas.drawText(str, rPos + 1, cPos + 1, mPaintBlack);
            canvas.drawText(str, rPos - 1, cPos + 1, mPaintBlack);
            canvas.drawText(str, rPos, cPos, mPaint);
        }



        //this class draws an arrow to represent a velocity at a certain point
        private void drawArrow(Canvas canvas, float x, float y, float[][] u, float[][] v, Paint paint) {
            double avg_xvel = (u[(int)y][(int)x]
                    +u[(int)y-1][(int)x]
                    +u[(int)y+1][(int)x]
                    +u[(int)y][(int)x-1]
                    +u[(int)y][(int)x+1]
                    +u[(int)y-1][(int)x-1]
                    +u[(int)y-1][(int)x+1]
                    +u[(int)y+1][(int)x-1]
                    +u[(int)y+1][(int)x+1])/9;
            double avg_yvel = (v[(int)y][(int)x]
                    +v[(int)y-1][(int)x]
                    +v[(int)y+1][(int)x]
                    +v[(int)y][(int)x-1]
                    +v[(int)y][(int)x+1]
                    +v[(int)y-1][(int)x-1]
                    +v[(int)y-1][(int)x+1]
                    +v[(int)y+1][(int)x-1]
                    +v[(int)y+1][(int)x+1])/9;

            float mag = 5.0f*(float)Math.sqrt(Math.pow(avg_xvel,2.0) + Math.pow(avg_yvel,2.0)); //hold magnitude of arrow
            //float mag = 50.0f;

            float angle = (float)(Math.atan2(avg_yvel,avg_xvel)*360./(2*Math.PI)); //orientation of vector (u,v).T
            //float angle = 135.0f;

            float xadj = x*(float)canvas.getWidth()/(mCameraWidth/downscalingFactor);
            float yadj = y*(float)canvas.getHeight()/(mCameraHeight/downscalingFactor);

            Path p = new Path();
            p.moveTo(xadj, yadj);
            p.lineTo(xadj,yadj+mag/6);
            p.lineTo(xadj+mag*(5.0f/8.0f),yadj+mag/6);
            p.lineTo(xadj+mag*(5.0f/8.0f),yadj+mag/3);
            p.lineTo(xadj+mag,yadj);
            p.lineTo(xadj+mag*(5.0f/8.0f),yadj-mag/3);
            p.lineTo(xadj+mag*(5.0f/8.0f),yadj-mag/6);
            //p.lineTo(length, height / 2.0f);
            //p.lineTo(10.0f, height);
            p.lineTo(xadj,yadj-mag/6);
            p.close();
            Matrix mMatrix = new Matrix();
            RectF bounds = new RectF();
            p.computeBounds(bounds, true);
            mMatrix.postRotate(angle, xadj, yadj);
            p.transform(mMatrix);
            canvas.drawPath(p, paint);

        }


    }


// -------- nested class Preview --------------------------------------------------------------

    class Preview extends SurfaceView implements SurfaceHolder.Callback
    {	// deal with preview that will be shown on screen
        SurfaceHolder mHolder;
        DrawOnTop mDrawOnTop;
        boolean mFinished;
        String TAG="PreView";	// tag for LogCat

        public Preview (Context context, DrawOnTop drawOnTop)
        { // constructor
            super(context);

            mDrawOnTop = drawOnTop;
            mFinished = false;

            // Install a SurfaceHolder.Callback so we get notified when the
            // underlying surface is created and destroyed.
            mHolder = getHolder();
            mHolder.addCallback(this);
            //  Following is deprecated setting, but required on Android versions prior to 3.0:
            //  mHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        }

        public void surfaceCreated (SurfaceHolder holder)
        {
            String TAG="surfaceCreated";
            PreviewCallback mPreviewCallback;
            if (mCamera == null) {	// sanity check
                Log.e(TAG, "ERROR: camera not open");
                System.exit(0);
            }
            Camera.CameraInfo info = new android.hardware.Camera.CameraInfo();
            android.hardware.Camera.getCameraInfo(mCam, info);
            // show some potentially useful information in log file
            switch (info.facing)  {	// see which camera we are using
                case Camera.CameraInfo.CAMERA_FACING_BACK:
                    Log.i(TAG, "Camera "+mCam+" facing back");
                    break;
                case Camera.CameraInfo.CAMERA_FACING_FRONT:
                    Log.i(TAG, "Camera "+mCam+" facing front");
                    break;
            }
            if (DBG) Log.i(TAG, "Camera "+mCam+" orientation "+info.orientation);

            mPreviewCallback = new PreviewCallback() {
                public void onPreviewFrame(byte[] data, Camera camera) { // callback
                    String TAG = "onPreviewFrame";
                    if ((mDrawOnTop == null) || mFinished) return;
                    if (mDrawOnTop.mBitmap == null)  // need to initialize the drawOnTop companion?
                        setupArrays(data, camera);
                    // Pass YUV image data to draw-on-top companion
                    System.arraycopy(data, 0, mDrawOnTop.mYUVData, 0, data.length);
                    mDrawOnTop.invalidate();
                }
            };

            try {
                mCamera.setPreviewDisplay(holder);
                // Preview callback will be used whenever new viewfinder frame is available
                mCamera.setPreviewCallback(mPreviewCallback);
            }
            catch (IOException e) {
                Log.e(TAG, "ERROR: surfaceCreated - IOException " + e);
                mCamera.release();
                mCamera = null;
            }
        }

        public void surfaceDestroyed (SurfaceHolder holder)
        {
            String TAG="surfaceDestroyed";
            // Surface will be destroyed when we return, so stop the preview.
            mFinished = true;
            if (mCamera != null) {	// not expected
                Log.e(TAG, "ERROR: camera still open");
                mCamera.setPreviewCallback(null);
                mCamera.stopPreview();
                mCamera.release();
                mCamera = null;
            }
        }

        public void surfaceChanged (SurfaceHolder holder, int format, int w, int h)
        {
            String TAG="surfaceChanged";
            //	Now that the size is known, set up the camera parameters and begin the preview.
            if (mCamera == null) {	// sanity check
                Log.e(TAG, "ERROR: camera not open");
                System.exit(0);
            }
            if (DBG) Log.v(TAG, "Given parameters h " + h + " w " + w);
            if (DBG) Log.v(TAG, "What we are asking for h " + mCameraHeight + " w " + mCameraWidth);
            if (h != mCameraHeight || w != mCameraWidth)
                Log.w(TAG, "Mismatch in image size "+" "+h+" x "+w+" vs "+mCameraHeight+" x "+mCameraWidth);
            // this will be sorted out with a setParamaters() on mCamera
            Camera.Parameters parameters = mCamera.getParameters();
            parameters.setPreviewSize(mCameraWidth, mCameraHeight);
            // check whether following is within PreviewFpsRange ?
            parameters.setPreviewFrameRate(15);	// deprecated
            // parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            try {
                mCamera.setParameters(parameters);
            } catch (Exception e) {
                Log.e(TAG, "ERROR: setParameters exception " + e);
                System.exit(0);
            }
            mCamera.startPreview();
        }

        private void setupArrays (byte[] data, Camera camera)
        {
            String TAG="setupArrays";
            if (DBG) Log.i(TAG, "Setting up arrays");
            Camera.Parameters params = camera.getParameters();
            mDrawOnTop.mImageHeight = params.getPreviewSize().height / downscalingFactor;
            mDrawOnTop.mImageWidth = params.getPreviewSize().width / downscalingFactor;
            if (DBG) Log.i(TAG, "height " + mDrawOnTop.mImageHeight + " width " + mDrawOnTop.mImageWidth);
            mDrawOnTop.mBitmap = Bitmap.createBitmap(mDrawOnTop.mImageWidth,
                    mDrawOnTop.mImageHeight, Bitmap.Config.RGB_565);
            mDrawOnTop.mRGBData = new int[mDrawOnTop.mImageWidth * mDrawOnTop.mImageHeight];
            mDrawOnTop.mGrayscaleData = new int[mDrawOnTop.mImageHeight][mDrawOnTop.mImageWidth];
            mDrawOnTop.mPrevGrayscaleData = new int[mDrawOnTop.mImageHeight][mDrawOnTop.mImageWidth];
            mDrawOnTop.E_x = new int[mDrawOnTop.mImageHeight - 1][mDrawOnTop.mImageWidth - 1];
            mDrawOnTop.E_y = new int[mDrawOnTop.mImageHeight - 1][mDrawOnTop.mImageWidth - 1];
            mDrawOnTop.E_t = new int[mDrawOnTop.mImageHeight - 1][mDrawOnTop.mImageWidth - 1];
            if (DBG) Log.i(TAG, "data length " + data.length); // should be width*height*3/2 for YUV format
            mDrawOnTop.mYUVData = new byte[data.length];
            int dataLengthExpected = downscalingFactor * downscalingFactor * mDrawOnTop.mImageWidth * mDrawOnTop.mImageHeight * 3 / 2;
            if (data.length != dataLengthExpected)
                Log.e(TAG, "ERROR: data length mismatch "+data.length+" vs "+dataLengthExpected);
        }

    }
}

// NOTE: the "Camera" class is deprecated as of API 21, but very few
// devices support the new Camera2 API, and even fewer support it fully
// and correctly (as of summer 2015: Motorola Nexus 5 & 6 and just possibly Samsung S6)
// So, for now, we use the "old" Camera class here.

