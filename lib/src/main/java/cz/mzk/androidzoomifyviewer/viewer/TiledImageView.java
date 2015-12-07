package cz.mzk.androidzoomifyviewer.viewer;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Build;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.List;

import cz.mzk.androidzoomifyviewer.CacheManager;
import cz.mzk.androidzoomifyviewer.Logger;
import cz.mzk.androidzoomifyviewer.R;
import cz.mzk.androidzoomifyviewer.cache.TileBitmap;
import cz.mzk.androidzoomifyviewer.cache.TilesCache.FetchingBitmapFromDiskHandler;
import cz.mzk.androidzoomifyviewer.gestures.MyGestureListener;
import cz.mzk.androidzoomifyviewer.rectangles.FramingRectangle;
import cz.mzk.androidzoomifyviewer.rectangles.FramingRectangleDrawer;
import cz.mzk.androidzoomifyviewer.tiles.ImageManager;
import cz.mzk.androidzoomifyviewer.tiles.MetadataInitializationHandler;
import cz.mzk.androidzoomifyviewer.tiles.TilePositionInPyramid;
import cz.mzk.androidzoomifyviewer.tiles.zoomify.ZoomifyImageManager;

/**
 * @author Martin Řehánek
 */
public class TiledImageView extends View implements TiledImageViewApi {
    public static final boolean DEV_MODE = true;// TODO: 7.12.15 configurable
    private static final Logger LOGGER = new Logger(TiledImageView.class);
    //STATE
    boolean mMinZoomCanvasImagePaddingInitialized = false;
    //CANVAS
    private double mCanvasImagePaddingHorizontal = -1;
    private double mCanvasImagePaddingVertical = -1;
    private Rect mWholeImageAreaInCanvasCoords = null; // whole image area in canvas coords, even from invisible canvas part (i.e. top and left can be negative)
    private Rect mVisibleImageAreaInCanvas = null;     // only part of image (in canvas coords) that is in visible part of canvas
    private boolean mViewmodeScaleFactorsInitialized = false;

    // SHIFT
    private boolean mViewmodeShiftInitialized = false;
    private VectorD mViewmodeShift = VectorD.ZERO_VECTOR;
    private boolean mDrawLayerWithWorseResolution = true;

    //SCALE
    private double mInitialScaleFactor = -1.0;
    private double mMinScaleFactor = -1.0;
    private double mMaxScaleFactor = -1.0;

    //VIEW MODE
    private ViewMode mViewMode = ViewMode.FIT_TO_SCREEN;

    // TILES ACCESS
    private String mZoomifyBaseUrl; //todo: vyhledove odstranit, tady to nepatri
    private ImageManager mImageManager;

    //EVENT HANDLERS
    private ImageInitializationHandler mImageInitializationHandler;
    private TileDownloadHandler mTileDownloadHandler;

    //GESTURES
    private MyGestureListener mGestureListener;
    private SingleTapListener mSingleTapListener;

    //FRAMING RECTANGLES
    private FramingRectangleDrawer mFramingRectDrawer;

    //DEV
    private DevTools mDevTools = null;
    private ImageCoordsPoints mTestPoints = null;

    public TiledImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initCache(context);
        init(context);
    }

    public TiledImageView(Context context) {
        super(context);
        initCache(context);
        init(context);
    }

    private void init(Context context) {
        if (DEV_MODE) {
            mDevTools = new DevTools(context);
            logDeviceScreenCategory();
            logHwAcceleration();
        }
        //mTilesCache = CacheManager.getTilesCache();
        mGestureListener = new MyGestureListener(context, this, mDevTools);
        mFramingRectDrawer = new FramingRectangleDrawer(context);
    }

    /**
     * Must be called at least once so that cache can be initialized. Typically in Application.onCreate()
     *
     * @param context
     */
    private void initCache(Context context) {
        if (!CacheManager.isInitialized()) {
            Resources res = context.getResources();
            boolean diskCacheEnabled = res.getBoolean(R.bool.androidzoomifyviewer_disk_cache_enabled);
            boolean clearDiskCacheOnStart = res.getBoolean(R.bool.androidzoomifyviewer_disk_cache_clear_on_startup);
            long tileDiskCacheBytes = res.getInteger(R.integer.androidzoomifyviewer_tile_disk_cache_size_kb) * 1024;
            CacheManager.initialize(context, diskCacheEnabled, clearDiskCacheOnStart, tileDiskCacheBytes);
        }
    }

    private void logDeviceScreenCategory() {
        // int size = getResources().getConfiguration().screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK;
        // String category = size == Configuration.SCREENLAYOUT_SIZE_SMALL ? "small"
        // : size == Configuration.SCREENLAYOUT_SIZE_NORMAL ? "normal"
        // : size == Configuration.SCREENLAYOUT_SIZE_LARGE ? "large" : "xlarge";
        // Log.d(TestTags.DISPLAY, "display size: " + category);
        String screenType = getResources().getString(R.string.androidzoomifyviewer_screen_type);
        TestLoggers.DISPLAY.d("screen type: " + screenType);
        double pixelRatio = getResources().getInteger(R.integer.androidzoomifyviewer_pxRatio) / 100.0;
        TestLoggers.DISPLAY.d(String.format("pxRatio: %.2f", pixelRatio));
    }

    @SuppressLint("NewApi")
    private void logHwAcceleration() {
        if (Build.VERSION.SDK_INT >= 11) {
            TestLoggers.DISPLAY.d("(Window) HW accelerated: " + isHardwareAccelerated());
        }
    }

    @Override
    public ViewMode getViewMode() {
        return mViewMode;
    }

    @Override
    public void setViewMode(ViewMode viewMode) {
        if (viewMode == null) {
            throw new NullPointerException();
        }
        this.mViewMode = viewMode;
    }

    @Override
    public void setImageInitializationHandler(ImageInitializationHandler imageInitializationHandler) {
        this.mImageInitializationHandler = imageInitializationHandler;
    }

    @Override
    public void setTileDownloadHandler(TileDownloadHandler tileDownloadHandler) {
        this.mTileDownloadHandler = tileDownloadHandler;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancelAllTasks();
        //better remove other object, especially those with context in order to prevent memory leaks
        mGestureListener = null;
        mSingleTapListener = null;
        mDevTools = null;
        mImageManager = null;
        mImageInitializationHandler = null;
        mTileDownloadHandler = null;
    }

    private void cancelAllTasks() {
        if (mImageManager != null) {
            mImageManager.cancelAllTasks();
        }
        if (CacheManager.getTilesCache() != null) {
            CacheManager.getTilesCache().cancelAllTasks();
        }
        if (mGestureListener != null) {
            mGestureListener.stopAllAnimations();
        }
    }

    @Override
    public void loadImage(String zoomifyBaseUrl) {
        LOGGER.d("loading new image, base url: " + zoomifyBaseUrl);
        mViewmodeScaleFactorsInitialized = false;
        mViewmodeShiftInitialized = false;
        mMinZoomCanvasImagePaddingInitialized = false;
        mZoomifyBaseUrl = zoomifyBaseUrl;
        cancelAllTasks();
        mGestureListener.reset();
        double pxRatio = getResources().getInteger(R.integer.androidzoomifyviewer_pxRatio) / 100.0;
        mImageManager = new ZoomifyImageManager(mZoomifyBaseUrl, pxRatio);
        initTilesDownloaderAsync();
    }

    @Override
    public void setFramingRectangles(List<FramingRectangle> framingRectangles) {
        mFramingRectDrawer.setFrameRectangles(framingRectangles);
        invalidate();
    }

    private void initTilesDownloaderAsync() {
        mImageManager.initImageMetadataAsync(new MetadataInitializationHandler() {

            @Override
            public void onSuccess(ImageManager imgManager) {
                LOGGER.d("ImageManager initialized");
                if (imgManager.equals(mImageManager)) {
                    if (DEV_MODE) {
                        mTestPoints = new ImageCoordsPoints(mImageManager.getImageWidth(), mImageManager.getImageHeight());
                    }

                    if (mImageInitializationHandler != null) {
                        mImageInitializationHandler.onImagePropertiesProcessed();
                    }
                    invalidate();
                } else {
                    //ImageManager has changed since, so this one is ignored.
                }
            }

            @Override
            public void onUnhandableResponseCode(String imagePropertiesUrl, int responseCode) {
                if (mImageInitializationHandler != null) {
                    mImageInitializationHandler.onImagePropertiesUnhandableResponseCodeError(
                            imagePropertiesUrl, responseCode);
                }
            }

            @Override
            public void onRedirectionLoop(String imagePropertiesUrl, int redirections) {
                if (mImageInitializationHandler != null) {
                    mImageInitializationHandler.onImagePropertiesRedirectionLoopError(imagePropertiesUrl,
                            redirections);
                }
            }

            @Override
            public void onDataTransferError(String imagePropertiesUrl, String errorMessage) {
                if (mImageInitializationHandler != null) {
                    mImageInitializationHandler.onImagePropertiesDataTransferError(imagePropertiesUrl,
                            errorMessage);
                }
            }

            @Override
            public void onInvalidData(String imagePropertiesUrl, String errorMessage) {
                if (mImageInitializationHandler != null) {
                    mImageInitializationHandler.onImagePropertiesInvalidDataError(imagePropertiesUrl,
                            errorMessage);
                }
            }
        });
    }

    @Override
    public void onDraw(final Canvas canv) {
        // TestLoggers.THREADS.d("ui: " + Thread.currentThread().getPriority());
        // Debug.startMethodTracing("default");
        // long start = System.currentTimeMillis();

        if (mDevTools != null) {
            mDevTools.setCanvas(canv);
            mDevTools.fillWholeCanvasWithColor(mDevTools.getPaintYellow());
        }
        if (mImageManager.isInitialized()) {
            if (mDevTools != null) {
                mDevTools.fillWholeCanvasWithColor(mDevTools.getPaintBlue());
            }
            if (!mViewmodeScaleFactorsInitialized) {
                initViewmodeScaleFactors(canv);
                mViewmodeScaleFactorsInitialized = true;
            }
            if (!mViewmodeShiftInitialized) {
                initViewmodeShift(canv);
                mViewmodeShiftInitialized = true;
            }
            if (!mMinZoomCanvasImagePaddingInitialized) {
                initMinZoomPadding(canv);
                mMinZoomCanvasImagePaddingInitialized = true;
            }

            // whole image area
            mWholeImageAreaInCanvasCoords = computeWholeImageAreaInCanvasCoords(getTotalScaleFactor(), getTotalShift());
            if (mDevTools != null) {
                mDevTools.fillRectAreaWithColor(mWholeImageAreaInCanvasCoords, mDevTools.getPaintRedTrans());
            }
            //visible image area
            mVisibleImageAreaInCanvas = computeVisibleImageAreaInCanvas(canv);
            if (mDevTools != null) {
                mDevTools.fillRectAreaWithColor(mVisibleImageAreaInCanvas, mDevTools.getPaintGreenTrans());
            }

            int bestLayerId = mImageManager.computeBestLayerId(mWholeImageAreaInCanvasCoords);
            // Log.d(TestTags.TEST, "best layer: " + bestLayerId);

            //draw tiles
            drawLayers(canv, bestLayerId, true, calculateVisibleAreaInImageCoords());
            //draw framing rectangles
            if (mFramingRectDrawer != null) {
                mFramingRectDrawer.setCanvas(canv);
                mFramingRectDrawer.draw(getTotalScaleFactor(), getTotalShift());
            }
            //draw dev rectangles, points
            if (mDevTools != null) {
                double totalScaleFactor = getTotalScaleFactor();
                VectorD totalShift = getTotalShift();
                // test points
                mDevTools.drawImageCoordPoints(mTestPoints, totalScaleFactor, totalShift);
                mDevTools.drawTileRectStack();
                // zoom centers
                //mDevTools.drawDoubletapZoomCenters(getTotalScaleFactor(), getTotalShift());
                //mDevTools.drawPinchZoomCenters(getTotalScaleFactor(), getTotalShift());
            }
        }
        // Debug.stopMethodTracing();
    }

    private void initViewmodeScaleFactors(Canvas canv) {
        int imgWidth = mImageManager.getImageWidth();
        int imgHeight = mImageManager.getImageHeight();
        double scaleFactorFitToScreen = computeScaleFactorFitToScreen(canv.getWidth(), canv.getHeight(), imgWidth, imgHeight);
        double scaleFactorNoFreeSpace = computeScaleFactorNoFreeSpace(canv.getWidth(), canv.getHeight(), imgWidth, imgHeight);
        switch (mViewMode) {
            case FIT_TO_SCREEN:
                mInitialScaleFactor = scaleFactorFitToScreen;
                break;
            default:
                mInitialScaleFactor = scaleFactorNoFreeSpace;
                break;
        }
        // LOGGER.d( "fit to screen factor: " + mInitialResizeFactor);
        mMinScaleFactor = Math.min(scaleFactorFitToScreen, scaleFactorNoFreeSpace);
        // TestLoggers.PINCH_ZOOM.d("minScale: " + mMinScaleFactor);
        // TODO: spis DP, nez PX
        // double maxWidthScale = (double) imageProperties.getWidth() / (double)
        // canv.getWidth();
        // double maxHeightScale = (double) imageProperties.getHeight() /
        // (double) canv.getHeight();

        // imageProperties.getNumtiles()*imageProperties.get

        // int necoWidthPx = imageProperties.getWidth();
        // int necoHeightPx = imageProperties.getHeight();

        int mustFitInCanvasObjectWidthPx = mImageManager.getTileTypicalSize();
        int mustFitInCanvasObjectHeightPx = mImageManager.getTileTypicalSize();

        // TestLoggers.PINCH_ZOOM.d("canvas px: [" + canv.getWidth() + "," + canv.getHeight() + "]");
        // TestLoggers.PINCH_ZOOM.d("canvas dp: [" + Utils.pxToDp(canv.getWidth()) + "," + Utils.pxToDp(canv.getHeight())
        // + "]");
        // TestLoggers.PINCH_ZOOM.d("image px: [" + imageProperties.getWidth() + "," + imageProperties.getHeight() + "]");
        // TestLoggers.PINCH_ZOOM.d("tile size: " + imageProperties.getTileSize());

        double maxWidthScalePx = (double) canv.getWidth() / (double) mustFitInCanvasObjectWidthPx;
        double maxHeightScalePx = (double) canv.getHeight() / (double) mustFitInCanvasObjectHeightPx;
        // double maxWidthScaleDp = (double) Utils.pxToDp(canv.getWidth()) / (double) mustFitInCanvasObjectWidthPx;
        // double maxHeightScaleDp = (double) Utils.pxToDp(canv.getHeight()) / (double) mustFitInCanvasObjectHeightPx;
        // TestLoggers.PINCH_ZOOM.d("px: maxWidthScale: " + maxWidthScalePx + ", maxHeightScale: " + maxHeightScalePx);
        // TestLoggers.PINCH_ZOOM.d("dp: maxWidthScale: " + maxWidthScaleDp + ", maxHeightScale: " + maxHeightScaleDp);
        mMaxScaleFactor = Math.min(maxWidthScalePx, maxHeightScalePx);
    }

    private PointD computeVisibleImageCenter() {
        float x = (mVisibleImageAreaInCanvas.width() / 2 + mVisibleImageAreaInCanvas.left);
        float y = (mVisibleImageAreaInCanvas.height() / 2 + mVisibleImageAreaInCanvas.top);
        return new PointD(x, y);
    }

    @Override
    public double getCanvasImagePaddingHorizontal() {
        return mCanvasImagePaddingHorizontal;
    }

    @Override
    public double getCanvasImagePaddingVertical() {
        return mCanvasImagePaddingVertical;
    }

    @Override
    public VectorD getTotalShift() {
        VectorD gestureShifts = mGestureListener.getTotalShift();
        return mViewmodeShift.plus(gestureShifts);
    }

    @Override
    public double getTotalScaleFactor() {
        return mInitialScaleFactor * mGestureListener.getTotalScaleFactor();
    }

    @Override
    public double getMinScaleFactor() {
        return mMinScaleFactor;
    }

    @Override
    public double getMaxScaleFactor() {
        return mMaxScaleFactor;
    }


    private void drawLayers(Canvas canv, int highestLayer, boolean isIdealLayer, RectD visibleAreaInImageCoords) {
        // long start = System.currentTimeMillis();
        List<TilePositionInPyramid> visibleTiles = mImageManager.getVisibleTilesForLayer(highestLayer, visibleAreaInImageCoords);
        // cancel downloading/saving of not visible tiles within layer
        mImageManager.cancelFetchingATilesForLayerExeptForThese(highestLayer, visibleTiles);
        // TODO: 4.12.15 Vyresit zruseni stahovani pro ty vrstvy, ktere uz nejsou relevantni. Treba kdyz rychle odzumuju
        if (isIdealLayer) {
            // possibly increase memory cache
            if (CacheManager.getTilesCache() != null) {
                // TODO: 4.12.15 jeste projit zvetsovani cache. Nemela by se vubec zmensovat a i nad tim faktorem pouvazovat
                int minCacheSize = (visibleTiles.size() * 2);
                // int maxCacheSize = (int) (visibleTiles.size() * 5.5);
                // CacheManager.getTilesCache().updateMemoryCacheSizeInItems(minCacheSize, maxCacheSize);
                CacheManager.getTilesCache().updateMemoryCacheSizeInItems(minCacheSize);
            }
        }
        // check if all visible tiles within layer are available
        boolean allTilesAvailable = true;
        for (TilePositionInPyramid visibleTile : visibleTiles) {
            boolean tileAccessible = CacheManager.getTilesCache().containsTileInMemory(mZoomifyBaseUrl, visibleTile);
            if (!tileAccessible) {
                allTilesAvailable = false;
                break;
            }
        }
        // if not all visible tiles available, draw smaller layer with worse resolution under it
        // TODO: disable, just for testing
        // mDrawLayerWithWorseResolution = false;
        if (!allTilesAvailable && highestLayer != 0 && mDrawLayerWithWorseResolution) {
            drawLayers(canv, highestLayer - 1, false, visibleAreaInImageCoords);
        }
        // draw visible tiles if available, start downloading otherwise
        for (TilePositionInPyramid visibleTile : visibleTiles) {
            fetchTileWithoutBlockingOnDiskRead(canv, visibleTile);
        }
        // long end = System.currentTimeMillis();
        // LOGGER.d( "drawLayers (layer=" + highestLayer + "): " + (end - start) +
        // " ms");
    }

    private void fetchTileWithoutBlockingOnDiskRead(Canvas canv, TilePositionInPyramid visibleTileId) {
        TileBitmap tile = CacheManager.getTilesCache().getTileAsync(mZoomifyBaseUrl, visibleTileId, new FetchingBitmapFromDiskHandler() {

            @Override
            public void onFetched() {
                invalidate();
            }
        });
        switch (tile.getState()) {
            case IN_MEMORY:
                drawTile(canv, visibleTileId, tile.getBitmap());
                break;
            case IN_DISK:
                // nothing, wait for it to be fetched into memory
                break;
            case NOT_FOUND:
                enqueTileDownload(visibleTileId);
        }
    }

    private void enqueTileDownload(TilePositionInPyramid visibleTileId) {
        mImageManager.enqueTileDownload(visibleTileId, new cz.mzk.androidzoomifyviewer.tiles.TileDownloadHandler() {

            @Override
            public void onUnhandableResponseCode(TilePositionInPyramid tilePositionInPyramid, String tileUrl, int responseCode) {
                if (mTileDownloadHandler != null) {
                    mTileDownloadHandler.onTileUnhandableResponseError(tilePositionInPyramid, tileUrl, responseCode);
                }
            }

            @Override
            public void onSuccess(TilePositionInPyramid tilePositionInPyramid, Bitmap bitmap) {
                invalidate();
                if (mTileDownloadHandler != null) {
                    mTileDownloadHandler.onTileProcessed(tilePositionInPyramid);
                }
            }

            @Override
            public void onRedirectionLoop(TilePositionInPyramid tilePositionInPyramid, String tileUrl, int redirections) {
                if (mTileDownloadHandler != null) {
                    mTileDownloadHandler.onTileRedirectionLoopError(tilePositionInPyramid, tileUrl, redirections);
                }
            }

            @Override
            public void onInvalidData(TilePositionInPyramid tilePositionInPyramid, String tileUrl, String errorMessage) {
                if (mTileDownloadHandler != null) {
                    mTileDownloadHandler.onTileInvalidDataError(tilePositionInPyramid, tileUrl, errorMessage);
                }
            }

            @Override
            public void onDataTransferError(TilePositionInPyramid tilePositionInPyramid, String tileUrl, String errorMessage) {
                if (mTileDownloadHandler != null) {
                    mTileDownloadHandler.onTileDataTransferError(tilePositionInPyramid, tileUrl, errorMessage);
                }
            }
        });
    }

    private void drawTile(Canvas canv, TilePositionInPyramid tileId, Bitmap tileBmp) {
        Rect tileInCanvas = toTileAreaInCanvas(tileId, tileBmp).toRect();
        // Log.d(TestTags.TEST, "drawing tile: " + tileId + " to: " + tileInCanvas.toShortString());
        canv.drawBitmap(tileBmp, null, tileInCanvas, null);
        if (mDevTools != null) {
            // mDevTools.highlightTile(tileInCanvas, mDevTools.getPaintBlack());
            // mDevTools.highlightTile(tileInCanvas, mDevTools.getPaintWhiteTrans());
            mDevTools.highlightTile(tileInCanvas, mDevTools.getPaintRed());
        }
    }

    private RectD calculateVisibleAreaInImageCoords() {
        double resizeFactor = getTotalScaleFactor();
        VectorD totalShift = getTotalShift();
        return Utils.toImageCoords(new RectD(mVisibleImageAreaInCanvas), resizeFactor, totalShift);
    }


    private void setDrawLayerWithWorseResolution(boolean show) {
        mDrawLayerWithWorseResolution = show;
    }


    private RectD toTileAreaInCanvas(TilePositionInPyramid tilePositionInPyramid, Bitmap tile) {
        Rect tileAreaInImageCoords = mImageManager.getTileAreaInImageCoords(tilePositionInPyramid);
        return Utils.toCanvasCoords(tileAreaInImageCoords, getTotalScaleFactor(), getTotalShift());
    }

    private double computeScaleFactorFitToScreen(double canvasWidth, double canvasHeight, double imgOriginalWidth,
                                                 double imgOriginalHeight) {
        double widthRatio = canvasWidth / imgOriginalWidth;
        double heightRatio = canvasHeight / imgOriginalHeight;
        // LOGGER.d( "widthRatio=" + widthRatio + ", heightRatio=" +
        // heightRatio);
        // preferuj zmenseni
        if (widthRatio < 1 && heightRatio < 1) {
            return widthRatio < heightRatio ? widthRatio : heightRatio;
            // return widthRatio < heightRatio ? heightRatio : widthRatio;
        } else if (widthRatio < 1) {// heightRatio > 1
            return widthRatio;
        } else if (heightRatio < 1) {// widthRatio > 1
            return heightRatio;
        } else { // widthRatio > 1 && heightRatio > 1
            // mensi zvetseni
            return widthRatio < heightRatio ? widthRatio : heightRatio;
        }
    }

    private double computeScaleFactorNoFreeSpace(double canvasWidth, double canvasHeight, double imgOriginalWidth,
                                                 double imgOriginalHeight) {
        double widthRatio = canvasWidth / imgOriginalWidth;
        double heightRatio = canvasHeight / imgOriginalHeight;
        // LOGGER.d( "widthRatio=" + widthRatio + ", heightRatio=" +
        // heightRatio);

        // preferuj zmenseni
        if (widthRatio < 1 && heightRatio < 1) {
            // mensi zmenseni
            return widthRatio < heightRatio ? heightRatio : widthRatio;
        } else if (heightRatio > 1) {// widthRatio < 1
            return heightRatio;
        } else if (widthRatio > 1) {// heightRatio < 1
            return widthRatio;
        } else { // widthRatio > 1 && heightRatio > 1
            // vetsi zvetseni
            return widthRatio > heightRatio ? widthRatio : heightRatio;
        }
    }

    private void initViewmodeShift(Canvas canv) {
        double canvasWidth = canv.getWidth();
        double canvasHeight = canv.getHeight();
        double imageOriginalWidth = mImageManager.getImageWidth();
        double imageOriginalHeight = mImageManager.getImageHeight();
        double actualWidth = imageOriginalWidth * mInitialScaleFactor;
        double actualHeight = imageOriginalHeight * mInitialScaleFactor;
        double extraSpaceWidthCanv = canvasWidth - actualWidth;
        double extraSpaceHeightCanv = canvasHeight - actualHeight;

        double xLeft = 0;
        double xCenter = extraSpaceWidthCanv / 2.0;
        double xRight = extraSpaceWidthCanv;
        double yTop = 0;
        double yCenter = extraSpaceHeightCanv / 2.0;
        double yBottom = extraSpaceHeightCanv;

        switch (mViewMode) {
            case FIT_TO_SCREEN:
                mViewmodeShift = new VectorD(xCenter, yCenter);
                break;
            case NO_FREE_SPACE_ALIGN_HORIZONTAL_LEFT_VERTICAL_TOP:
                mViewmodeShift = new VectorD(xLeft, yTop);
                break;
            case NO_FREE_SPACE_ALIGN_HORIZONTAL_LEFT_VERTICAL_CENTER:
                mViewmodeShift = new VectorD(xLeft, yCenter);
                break;
            case NO_FREE_SPACE_ALIGN_HORIZONTAL_LEFT_VERTICAL_BOTTOM:
                mViewmodeShift = new VectorD(xLeft, yBottom);
                break;
            case NO_FREE_SPACE_ALIGN_HORIZONTAL_CENTER_VERTICAL_TOP:
                mViewmodeShift = new VectorD(xCenter, yTop);
                break;
            case NO_FREE_SPACE_ALIGN_HORIZONTAL_CENTER_VERTICAL_CENTER:
                mViewmodeShift = new VectorD(xCenter, yCenter);
                break;
            case NO_FREE_SPACE_ALIGN_HORIZONTAL_CENTER_VERTICAL_BOTTOM:
                mViewmodeShift = new VectorD(xCenter, yBottom);
                break;
            case NO_FREE_SPACE_ALIGN_HORIZONTAL_RIGHT_VERTICAL_TOP:
                mViewmodeShift = new VectorD(xRight, yTop);
                break;
            case NO_FREE_SPACE_ALIGN_HORIZONTAL_RIGHT_VERTICAL_CENTER:
                mViewmodeShift = new VectorD(xRight, yCenter);
                break;
            case NO_FREE_SPACE_ALIGN_HORIZONTAL_RIGHT_VERTICAL_BOTTOM:
                mViewmodeShift = new VectorD(xRight, yBottom);
                break;
        }
        TestLoggers.CENTERS.d("initial shift:" + mViewmodeShift);
    }

    private void initMinZoomPadding(Canvas canv) {
        PointD imgBottomRight = new PointD(mImageManager.getImageWidth(), mImageManager.getImageHeight());
        PointD imgInCanvasBottomRight = Utils.toCanvasCoords(imgBottomRight, mMinScaleFactor, VectorD.ZERO_VECTOR);
        double freeWidth = (canv.getWidth() - imgInCanvasBottomRight.x) * 0.5;
        double freeHeight = (canv.getHeight() - imgInCanvasBottomRight.y) * 0.5;
        mCanvasImagePaddingHorizontal = Utils.toXInImageCoords(freeWidth, mMinScaleFactor, 0);
        mCanvasImagePaddingVertical = Utils.toYInImageCoords(freeHeight, mMinScaleFactor, 0);
        // Log.d(TestTags.CORNERS, "initMinZoomBorders: width: " +
        // mCanvasImagePaddingHorizontal + ", height: "
        // + mCanvasImagePaddingVertical);
    }

    private Rect computeWholeImageAreaInCanvasCoords(double scaleFactor, VectorD shift) {
        Rect imgArea = new Rect(0, 0, mImageManager.getImageWidth(), mImageManager.getImageHeight());
        return Utils.toCanvasCoords(imgArea, scaleFactor, shift).toRect();
    }

    private Rect computeVisibleImageAreaInCanvas(Canvas canv) {
        int left = mapNumberToInterval(mWholeImageAreaInCanvasCoords.left, 0, canv.getWidth());
        int right = mapNumberToInterval(mWholeImageAreaInCanvasCoords.right, 0, canv.getWidth());
        int top = mapNumberToInterval(mWholeImageAreaInCanvasCoords.top, 0, canv.getHeight());
        int bottom = mapNumberToInterval(mWholeImageAreaInCanvasCoords.bottom, 0, canv.getHeight());
        return new Rect(left, top, right, bottom);
    }

    private int mapNumberToInterval(int number, int min, int max) {
        if (number <= min) {
            return min;
        } else if (number >= max) {
            return max;
        } else {
            return number;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return mGestureListener.onTouchEvent(event);
    }

    @Override
    public SingleTapListener getSingleTapListener() {
        return mSingleTapListener;
    }

    @Override
    public void setSingleTapListener(SingleTapListener singleTapListener) {
        this.mSingleTapListener = singleTapListener;
    }

    @Override
    public Rect getVisibleImageAreaInCanvas() {
        return mVisibleImageAreaInCanvas;
    }

    @Override
    public double getInitialScaleFactor() {
        return mInitialScaleFactor;
    }

    @Override
    public int getImageWidth() {
        return mImageManager.getImageWidth();
    }

    @Override
    public int getImageHeight() {
        return mImageManager.getImageHeight();
    }


    public enum ViewMode {
        FIT_TO_SCREEN, //

        NO_FREE_SPACE_ALIGN_HORIZONTAL_LEFT_VERTICAL_TOP, //
        NO_FREE_SPACE_ALIGN_HORIZONTAL_LEFT_VERTICAL_CENTER, //
        NO_FREE_SPACE_ALIGN_HORIZONTAL_LEFT_VERTICAL_BOTTOM, //

        NO_FREE_SPACE_ALIGN_HORIZONTAL_CENTER_VERTICAL_TOP, //
        NO_FREE_SPACE_ALIGN_HORIZONTAL_CENTER_VERTICAL_CENTER, //
        NO_FREE_SPACE_ALIGN_HORIZONTAL_CENTER_VERTICAL_BOTTOM, //

        NO_FREE_SPACE_ALIGN_HORIZONTAL_RIGHT_VERTICAL_TOP, //
        NO_FREE_SPACE_ALIGN_HORIZONTAL_RIGHT_VERTICAL_CENTER, //
        NO_FREE_SPACE_ALIGN_HORIZONTAL_RIGHT_VERTICAL_BOTTOM, //
    }

    public interface SingleTapListener {
        /**
         * This method is called after single tap, that is confirmed not to be double tap and also has not been used internally by
         * this view. I.e. for zooming, swiping etc.
         *
         * @param x           x coordinate of the tap
         * @param y           y coordinate of the tap
         * @param boundingBox area containing the image
         */
        public void onSingleTap(float x, float y, Rect boundingBox);
    }

    /**
     * Exactly one of these methods is called eventually after loadImage(). Either onImagePropertiesProcessed() if
     * ImageProperties.xml is found, downloaded and processed or one of the other methods in case of some error.
     *
     * @author martin
     */
    public interface ImageInitializationHandler {

        /**
         * ImageProperties.xml downloaded and processed properly.
         */
        public void onImagePropertiesProcessed();

        /**
         * Response to HTTP request for ImageProperties.xml returned code that cannot be handled here. That means almost
         * everything except for some 2xx codes and some 3xx codes for which redirection is applied.
         *
         * @param imagePropertiesUrl
         * @param responseCode
         */
        public void onImagePropertiesUnhandableResponseCodeError(String imagePropertiesUrl, int responseCode);

        /**
         * Too many redirections to ImageProperties.xml, probably loop.
         *
         * @param imagePropertiesUrl
         * @param redirections
         */
        public void onImagePropertiesRedirectionLoopError(String imagePropertiesUrl, int redirections);

        /**
         * Other errors in transfering ImageProperties.xml - timeouts etc.
         *
         * @param imagePropertiesUrl
         * @param errorMessage
         */
        public void onImagePropertiesDataTransferError(String imagePropertiesUrl, String errorMessage);

        /**
         * Invalid content in ImageProperties.xml. Particulary erroneous xml.
         *
         * @param imagePropertiesUrl
         * @param errorMessage
         */
        public void onImagePropertiesInvalidDataError(String imagePropertiesUrl, String errorMessage);
    }

    /**
     * Exactly one of these methods is called after tile is downloaded and stored to cache or something goes wrong in this
     * process.
     *
     * @author martin
     */
    public interface TileDownloadHandler {

        /**
         * Tile downloaded and processed properly.
         *
         * @param tilePositionInPyramid Tile id.
         */
        public void onTileProcessed(TilePositionInPyramid tilePositionInPyramid);

        /**
         * Response to HTTP request for tile returned code that cannot be handled here. That means almost everything except for
         * some 2xx codes and some 3xx codes for which redirection is applied.
         *
         * @param tilePositionInPyramid Tile id.
         * @param tileUrl               Tile jpeg url.
         * @param responseCode          Http response code recieved.
         */

        public void onTileUnhandableResponseError(TilePositionInPyramid tilePositionInPyramid, String tileUrl, int responseCode);

        /**
         * Too many redirections for tile, probably loop.
         *
         * @param tilePositionInPyramid Tile id.
         * @param tileUrl               Tile jpeg url.
         * @param redirections          Total redirections.
         */
        public void onTileRedirectionLoopError(TilePositionInPyramid tilePositionInPyramid, String tileUrl, int redirections);

        /**
         * Other errors in transfering tile - timeouts etc.
         *
         * @param tilePositionInPyramid Tile id.
         * @param tileUrl               Tile jpeg url.
         * @param errorMessage          Error message.
         */
        public void onTileDataTransferError(TilePositionInPyramid tilePositionInPyramid, String tileUrl, String errorMessage);

        /**
         * Invalid tile content.
         *
         * @param tilePositionInPyramid Tile id.
         * @param tileUrl               Tile jpeg url.
         * @param errorMessage          Error message.
         */
        public void onTileInvalidDataError(TilePositionInPyramid tilePositionInPyramid, String tileUrl, String errorMessage);
    }

}
