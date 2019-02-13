package com.mixpanel.android.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.support.v4.util.LruCache;
import android.util.Base64;

import com.mixpanel.android.mpmetrics.MPConfig;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import javax.net.ssl.SSLSocketFactory;

/**
 * ABSOLUTELY NOT thread, or even process safe.
 * Writes and reads files and directories at known paths, and uses a shared instance of MessageDigest.
 */
public class ImageStore {
    public static class CantGetImageException extends Exception {
        public CantGetImageException(String message) {
            super(message);
        }

        public CantGetImageException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public ImageStore(Context context, String moduleName) {
        this(context, DEFAULT_DIRECTORY_PREFIX + moduleName, new HttpService());
    }

    public ImageStore(Context context, String directoryName, RemoteService poster) {
        // 创建文件夹
        mDirectory = context.getDir(directoryName, Context.MODE_PRIVATE);
        // 网络请求对象
        mPoster = poster;
        // 配置文件
        mConfig = MPConfig.getInstance(context);
        // sha1 摘要
        MessageDigest useDigest;
        try {
            useDigest = MessageDigest.getInstance("SHA1");
        } catch (NoSuchAlgorithmException e) {
            // 不支持sha1 那就无法保存
            MPLog.w(LOGTAG, "Images won't be stored because this platform doesn't supply a SHA1 hash function");
            useDigest = null;
        }

        mDigest = useDigest;

        // 创建LRUCache
        if (sMemoryCache == null) {
            synchronized (ImageStore.class) {
                if (sMemoryCache == null) {
                    int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
                    int cacheSize = maxMemory / mConfig.getImageCacheMaxMemoryFactor();

                    sMemoryCache = new LruCache<String, Bitmap>(cacheSize) {
                        @Override
                        protected int sizeOf(String key, Bitmap bitmap) {
                            return bitmap.getRowBytes() * bitmap.getHeight() / 1024;
                        }
                    };
                }
            }
        }
    }

    /**
     * 从网络下载图片至本地
     *
     * @param url
     * @return
     * @throws CantGetImageException
     */
    public File getImageFile(String url) throws CantGetImageException {
        final File file = storedFile(url);
        byte[] bytes = null;

        if (file == null || !file.exists()) {
            try {
                final SSLSocketFactory factory = mConfig.getSSLSocketFactory();
                bytes = mPoster.performRequest(url, null, factory);
            } catch (IOException e) {
                throw new CantGetImageException("Can't download bitmap", e);
            } catch (RemoteService.ServiceUnavailableException e) {
                throw new CantGetImageException("Couldn't download image due to service availability", e);
            }

            if (null != bytes) {
                // 图片不能过大
                if (null != file && bytes.length < MAX_BITMAP_SIZE) {
                    OutputStream out = null;
                    try {
                        out = new FileOutputStream(file);
                        out.write(bytes);
                    } catch (FileNotFoundException e) {
                        throw new CantGetImageException("It appears that ImageStore is misconfigured, or disk storage is unavailable- can't write to bitmap directory", e);
                    } catch (IOException e) {
                        throw new CantGetImageException("Can't store bitmap", e);
                    } finally {
                        if (null != out) {
                            try {
                                out.close();
                            } catch (IOException e) {
                                MPLog.w(LOGTAG, "Problem closing output file", e);
                            }
                        }
                    }
                }
            }
        }

        return file;
    }

    public Bitmap getImage(String url) throws CantGetImageException {
        // 从缓存中获取图片
        Bitmap cachedBitmap = getBitmapFromMemCache(url);
        // 缓存中没有
        if (cachedBitmap == null) {
            //  下载图片至本地
            final File imageFile = getImageFile(url);
            // 加载至内存中
            cachedBitmap = decodeImage(imageFile);
            // 添加到LRUCache
            addBitmapToMemoryCache(url, cachedBitmap);
        }

        return cachedBitmap;
    }

    /**
     * 从本地加载图片到内存中
     *
     * @param file
     * @return
     * @throws CantGetImageException
     */
    private static Bitmap decodeImage(File file) throws CantGetImageException {
        BitmapFactory.Options option = new BitmapFactory.Options();
        option.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), option);
        float imageSize = (float) option.outHeight * option.outWidth;
        // 图片大小过大,内存不足
        if (imageSize > getAvailableMemory()) {
            throw new CantGetImageException("Do not have enough memory for the image");
        }
        // 解析
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
        // 无法打开..或被删除了...
        if (null == bitmap) {
            final boolean ignored = file.delete();
            throw new CantGetImageException("Bitmap on disk can't be opened or was corrupt");
        }

        return bitmap;
    }

    private static float getAvailableMemory() {
        Runtime runtime = Runtime.getRuntime();
        float used = runtime.totalMemory() - runtime.freeMemory();  // used      = heap - free
        return runtime.maxMemory() - used;                          // available = max - used
    }

    public void clearStorage() {
        File[] files = mDirectory.listFiles();
        int length = files.length;
        for (int i = 0; i < length; i++) {
            final File file = files[i];
            final String filename = file.getName();
            if (filename.startsWith(FILE_PREFIX)) {
                final boolean ignored = file.delete();
            }
        }

        clearMemCache();
    }

    public void deleteStorage(String url) {
        final File file = storedFile(url);
        if (null != file) {
            final boolean ignored = file.delete();
            removeBitmapFromMemCache(url);
        }
    }

    private File storedFile(String url) {
        if (null == mDigest) {
            return null;
        }

        final byte[] hashed = mDigest.digest(url.getBytes());
        final String safeName = FILE_PREFIX + Base64.encodeToString(hashed, Base64.URL_SAFE | Base64.NO_WRAP);
        return new File(mDirectory, safeName);
    }

    public static void addBitmapToMemoryCache(String key, Bitmap bitmap) {
        if (getBitmapFromMemCache(key) == null) {
            synchronized (sMemoryCache) {
                sMemoryCache.put(key, bitmap);
            }
        }
    }

    public static Bitmap getBitmapFromMemCache(String key) {
        synchronized (sMemoryCache) {
            return sMemoryCache.get(key);
        }
    }

    public static void removeBitmapFromMemCache(String key) {
        synchronized (sMemoryCache) {
            sMemoryCache.remove(key);
        }
    }

    public static void clearMemCache() {
        synchronized (sMemoryCache) {
            sMemoryCache.evictAll();
        }
    }

    private final File mDirectory;
    private final RemoteService mPoster;
    private final MessageDigest mDigest;
    private final MPConfig mConfig;
    /**
     *
     */
    private static LruCache<String, Bitmap> sMemoryCache;

    private static final String DEFAULT_DIRECTORY_PREFIX = "MixpanelAPI.Images.";
    private static final int MAX_BITMAP_SIZE = 10000000; // 10 MB
    private static final String FILE_PREFIX = "MP_IMG_";

    @SuppressWarnings("unused")
    private static final String LOGTAG = "MixpanelAPI.ImageStore";
}
