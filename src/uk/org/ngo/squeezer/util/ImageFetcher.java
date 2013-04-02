/*
 * Copyright 2012 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.org.ngo.squeezer.util;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.widget.ImageView;

/**
 * A subclass of {@link ImageWorker} that fetches images from a URL.
 */
public class ImageFetcher extends ImageWorker {
    private static final String TAG = ImageFetcher.class.getSimpleName();

    @SuppressWarnings("PointlessArithmeticExpression")
    public static final int IO_BUFFER_SIZE_BYTES = 1 * 1024; // 1KB

    private ImageFetcherParams mFetcherParams;

    // Default fetcher params
    private static final int DEFAULT_MAX_THUMBNAIL_BYTES = 70 * 1024; // 70KB
    private static final int DEFAULT_MAX_IMAGE_HEIGHT = 1024;
    private static final int DEFAULT_MAX_IMAGE_WIDTH = 1024;
    private static final int DEFAULT_HTTP_CACHE_SIZE = 5 * 1024 * 1024; // 5MB
    private static final String DEFAULT_HTTP_CACHE_DIR = "http";

    /**
     * Create an ImageFetcher specifying custom parameters.
     */
    public ImageFetcher(Context context, ImageFetcherParams params) {
        super(context);
        setParams(params);
    }

    /**
     * Create an ImageFetcher using default parameters.
     */
    public ImageFetcher(Context context) {
        super(context);
        setParams(new ImageFetcherParams());
    }

    public void loadThumbnailImage(String key, ImageView imageView, Bitmap loadingBitmap) {
        loadImage(new ImageData(key, ImageData.IMAGE_TYPE_THUMBNAIL, imageView), imageView, loadingBitmap);
    }

    public void loadThumbnailImage(String key, ImageView imageView, int resId) {
        loadImage(new ImageData(key, ImageData.IMAGE_TYPE_THUMBNAIL, imageView), imageView, resId);
    }

    public void loadThumbnailImage(String key, ImageView imageView) {
        loadImage(new ImageData(key, ImageData.IMAGE_TYPE_THUMBNAIL, imageView), imageView, mLoadingBitmap);
    }

    public void loadImage(String key, ImageView imageView, Bitmap loadingBitmap) {
        loadImage(new ImageData(key, ImageData.IMAGE_TYPE_NORMAL, imageView), imageView, loadingBitmap);
    }

    public void loadImage(String key, ImageView imageView, int resId) {
        loadImage(new ImageData(key, ImageData.IMAGE_TYPE_NORMAL, imageView), imageView, resId);
    }

    public void loadImage(String key, ImageView imageView) {
        loadImage(new ImageData(key, ImageData.IMAGE_TYPE_NORMAL, imageView), imageView, mLoadingBitmap);
    }

    public void setParams(ImageFetcherParams params) {
        mFetcherParams = params;
    }

    /**
     * Set the target image width and height.
     */
    public void setImageSize(int width, int height) {
        mFetcherParams.mImageWidth = width;
        mFetcherParams.mImageHeight = height;
    }

    /**
     * Set the target image size (width and height will be the same).
     */
    public void setImageSize(int size) {
        setImageSize(size, size);
    }

    /**
     * The main process method, which will be called by the ImageWorker in the AsyncTask background
     * thread.
     * 
     * @param key The key to load the bitmap, in this case, a regular http URL
     * @return The downloaded and resized bitmap
     */
    @Override
    protected Bitmap processBitmap(Object key) {
        final ImageData imageData = (ImageData) key;
        Log.d(TAG, "processBitmap - " + key);

        if (imageData.mType == ImageData.IMAGE_TYPE_NORMAL) {
            final File f = downloadBitmapToFile(mContext, imageData.mKey, mFetcherParams.mHttpCacheDir);
            if (f != null) {
                // Return a sampled down version
                final Bitmap bitmap = decodeSampledBitmapFromFile(
                        f.toString(), imageData.mWidth, imageData.mHeight);
                f.delete();
                return bitmap;
            }
        } else if (imageData.mType == ImageData.IMAGE_TYPE_THUMBNAIL) {
            return downloadBitmapToMemory(imageData.mKey, imageData.mWidth, imageData.mHeight);
        }
        return null;
    }

    /**
     * Download a bitmap from a URL and decode it to the requested size.
     * 
     * @param urlString The URL to fetch
     * @param reqWidth Requested width of the decoded bitmap
     * @param reqHeight Requested height of the decoded bitmap
     * @return The decoded bitmap
     */
    private static Bitmap downloadBitmapToMemory(String urlString, int reqWidth, int reqHeight) {
        disableConnectionReuseIfNecessary();
        InputStream in = null;

        try {
            final URL url = new URL(urlString);

            // First decode with inJustDecodeBounds=true to check dimensions
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            in = (InputStream) url.getContent();
            BitmapFactory.decodeStream(in, null, options);
            in.close();

            // Calculate inSampleSize
            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);

            // Decode with inSampleSize set
            in = (InputStream) url.getContent();
            options.inJustDecodeBounds = false;
            return BitmapFactory.decodeStream(in, null, options);
        } catch (final IOException e) {
            Log.e(TAG, "Error in downloadBitmapToMemory - " + e);
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (final IOException e) {
                Log.e(TAG, "Error in downloadBitmapToMemory - " + e);
            }
        }
        return null;
    }


    /**
     * Download a bitmap from a URL, write it to a disk and return the File pointer. This
     * implementation uses a simple disk cache.
     * 
     * @param context The context to use
     * @param urlString The URL to fetch
     * @return A File pointing to the fetched bitmap
     */
    public static File downloadBitmapToFile(Context context, String urlString, String uniqueName) {
        final File cacheDir = ImageCache.getDiskCacheDir(context, uniqueName);

        if (!cacheDir.exists()) {
            cacheDir.mkdir();
        }

        Log.v(TAG, "downloadBitmap - downloading - " + urlString);

        disableConnectionReuseIfNecessary();
        HttpURLConnection urlConnection = null;
        BufferedOutputStream out = null;

        try {
            Log.v(TAG, "cacheDir:" + cacheDir);
            final File tempFile = File.createTempFile("bitmap", null, cacheDir);

            final URL url = new URL(urlString);
            urlConnection = (HttpURLConnection) url.openConnection();
            if (urlConnection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }
            final InputStream in =
                    new BufferedInputStream(urlConnection.getInputStream(), IO_BUFFER_SIZE_BYTES);
            out = new BufferedOutputStream(new FileOutputStream(tempFile), IO_BUFFER_SIZE_BYTES);

            int b;
            while ((b = in.read()) != -1) {
                out.write(b);
            }

            return tempFile;

        } catch (final IOException e) {
            Log.e(TAG, "Error in downloadBitmap - " + e);
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
            if (out != null) {
                try {
                    out.close();
                } catch (final IOException e) {
                    Log.e(TAG, "Error in downloadBitmap - " + e);
                }
            }
        }

        return null;
    }

    /**
     * Decode and sample down a bitmap from a file to the requested width and height.
     * 
     * @param filename The full path of the file to decode
     * @param reqWidth The requested width of the resulting bitmap
     * @param reqHeight The requested height of the resulting bitmap
     * @return A bitmap sampled down from the original with the same aspect ratio and dimensions
     *         that are equal to or greater than the requested width and height
     */
    public static synchronized Bitmap decodeSampledBitmapFromFile(String filename,
            int reqWidth, int reqHeight) {

        // First decode with inJustDecodeBounds=true to check dimensions
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(filename, options);

        // Calculate inSampleSize
        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);

        // Decode bitmap with inSampleSize set
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(filename, options);
    }

    /**
     * Calculate an inSampleSize for use in a {@link android.graphics.BitmapFactory.Options} object
     * when decoding bitmaps using the decode* methods from {@link BitmapFactory}. This
     * implementation calculates the closest inSampleSize that will result in the final decoded
     * bitmap having a width and height equal to or larger than the requested width and height. This
     * implementation does not ensure a power of 2 is returned for inSampleSize which can be faster
     * when decoding but results in a larger bitmap which isn't as useful for caching purposes.
     * 
     * @param options An options object with out* params already populated (run through a decode*
     *            method with inJustDecodeBounds==true
     * @param reqWidth The requested width of the resulting bitmap
     * @param reqHeight The requested height of the resulting bitmap
     * @return The value to be used for inSampleSize
     */
    public static int calculateInSampleSize(BitmapFactory.Options options,
            int reqWidth, int reqHeight) {
        // Raw height and width of image
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            if (width > height) {
                inSampleSize = Math.round((float) height / (float) reqHeight);
            } else {
                inSampleSize = Math.round((float) width / (float) reqWidth);
            }

            // This offers some additional logic in case the image has a strange
            // aspect ratio. For example, a panorama may have a much larger
            // width than height. In these cases the total pixels might still
            // end up being too large to fit comfortably in memory, so we should
            // be more aggressive with sample down the image (=larger
            // inSampleSize).

            final float totalPixels = width * height;

            // Anything more than 2x the requested pixels we'll sample down
            // further.
            final float totalReqPixelsCap = reqWidth * reqHeight * 2;

            while (totalPixels / (inSampleSize * inSampleSize) > totalReqPixelsCap) {
                inSampleSize++;
            }
        }
        return inSampleSize;
    }

    /**
     * Workaround for bug pre-Froyo, see here for more info:
     * http://android-developers.blogspot.com/2011/09/androids-http-clients.html
     */
    public static void disableConnectionReuseIfNecessary() {
        // HTTP connection reuse which was buggy pre-froyo
        if (hasHttpConnectionBug()) {
            System.setProperty("http.keepAlive", "false");
        }
    }

    /**
     * Check if OS version has a http URLConnection bug. See here for more information:
     * http://android-developers.blogspot.com/2011/09/androids-http-clients.html
     * 
     * @return true if this OS version is affected, false otherwise
     */
    public static boolean hasHttpConnectionBug() {
        return !UIUtils.hasFroyo();
    }

    public static class ImageFetcherParams {
        public int mImageWidth = DEFAULT_MAX_IMAGE_WIDTH;
        public int mImageHeight = DEFAULT_MAX_IMAGE_HEIGHT;
        public int mMaxThumbnailBytes = DEFAULT_MAX_THUMBNAIL_BYTES;
        public int mHttpCacheSize = DEFAULT_HTTP_CACHE_SIZE;
        public String mHttpCacheDir = DEFAULT_HTTP_CACHE_DIR;
    }

    private static class ImageData {
        public static final int IMAGE_TYPE_THUMBNAIL = 0;
        public static final int IMAGE_TYPE_NORMAL = 1;
        public String mKey;
        public int mType;
        public int mWidth;
        public int mHeight;

        public ImageData(String key, int type, ImageView imageView) {
            mKey = key;
            mType = type;
            mWidth = imageView.getWidth();
            mHeight = imageView.getHeight();
        }

        @Override
        public String toString() {
            return mKey;
        }
    }
}