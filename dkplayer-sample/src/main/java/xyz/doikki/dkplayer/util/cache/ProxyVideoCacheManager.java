package xyz.doikki.dkplayer.util.cache;

import android.content.Context;

import com.danikula.videocache.HttpProxyCacheServer;
import com.danikula.videocache.StorageUtils;

import java.io.File;

public class ProxyVideoCacheManager {

    private static HttpProxyCacheServer sharedProxy;

    private ProxyVideoCacheManager() {
    }

    public static HttpProxyCacheServer getProxy(Context context) {
        return sharedProxy == null ? (sharedProxy = newProxy(context)) : sharedProxy;
    }

    private static HttpProxyCacheServer newProxy(Context context) {
        return new HttpProxyCacheServer.Builder(context)
                .maxCacheSize(512 * 1024 * 1024)       // 512MB for cache
                //缓存固定到 downloads 目录（下载页从这里取）
                .cacheDirectory(new File(context.getExternalFilesDir(null), "downloads"))
                .build();
    }

    /** 下载页读取的缓存目录 */
    public static File getCacheDir(Context context) {
        getProxy(context);
        return sharedProxy.getCacheRoot();
    }


    /**
     * 删除所有缓存文件
     * @return 返回缓存是否删除成功
     */
    public static boolean clearAllCache(Context context) {
        getProxy(context);
        return StorageUtils.deleteFiles(sharedProxy.getCacheRoot());
    }

    /**
     * 删除url对应默认缓存文件
     * @return 返回缓存是否删除成功
     */
    public static boolean clearDefaultCache(Context context, String url) {
        getProxy(context);
        File pathTmp = sharedProxy.getTempCacheFile(url);
        File path = sharedProxy.getCacheFile(url);
        return StorageUtils.deleteFile(pathTmp.getAbsolutePath()) &&
                StorageUtils.deleteFile(path.getAbsolutePath());

    }
}