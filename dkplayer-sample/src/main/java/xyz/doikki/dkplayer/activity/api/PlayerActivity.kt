package xyz.doikki.dkplayer.activity.api

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.text.TextUtils
import android.view.View
import android.widget.EditText
import android.widget.Toast
import android.widget.ImageView
import com.bumptech.glide.Glide
import xyz.doikki.dkplayer.R
import xyz.doikki.dkplayer.activity.BaseActivity
import xyz.doikki.dkplayer.util.IntentKeys
import xyz.doikki.dkplayer.util.Utils
import xyz.doikki.dkplayer.widget.component.PlayerMonitor
import xyz.doikki.dkplayer.widget.render.gl2.GLSurfaceRenderView2
import xyz.doikki.dkplayer.widget.render.gl2.filter.GlFilterGroup
import xyz.doikki.dkplayer.widget.render.gl2.filter.GlSepiaFilter
import xyz.doikki.dkplayer.widget.render.gl2.filter.GlSharpenFilter
import xyz.doikki.dkplayer.widget.render.gl2.filter.GlWatermarkFilter
import xyz.doikki.videocontroller.StandardVideoController
import xyz.doikki.videocontroller.component.*
import xyz.doikki.videoplayer.player.BaseVideoView
import xyz.doikki.videoplayer.player.VideoView
import xyz.doikki.videoplayer.render.IRenderView
import xyz.doikki.videoplayer.render.RenderViewFactory
import xyz.doikki.videoplayer.util.L

/**
 * 播放器演示
 * Created by Doikki on 2017/4/7.
 */
class PlayerActivity : BaseActivity<VideoView>() {

    override fun showTitleBar() = false // 去掉 dk播放器 顶栏

    private var rawUrl: String? = null
    private var cacheOn = false
    private var proxyOn = false
    private var loopOn = false
    private lateinit var mxPanel: xyz.doikki.dkplayer.widget.component.MxPanelView

    /** 防盗链请求头：B站/抖音直连播放用 */
    private fun headersFor(u: String): MutableMap<String, String>? {
        val l = u.toLowerCase()
        if (l.contains("bilibili") || l.contains("bilivideo") || l.contains("upos-")) {
            return mutableMapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 13) Chrome/120 Mobile",
                "Referer" to "https://www.bilibili.com/")
        }
        if (l.contains("douyin") || l.contains("douyinvod") || l.contains("aweme")) {
            return mutableMapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 13) Chrome/120 Mobile",
                "Referer" to "https://live.douyin.com/")
        }
        return null
    }

    /** 点「代理」：切回本地代理流播放（直连被拒时用） */
    private fun replayWithProxy() {
        val u = rawUrl ?: return
        val l = u.toLowerCase()
        if (l.contains("127.0.0.1")) {
            Toast.makeText(this, "已在代理播放", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val wrapped: String = when {
                l.contains("bilibili") || l.contains("bilivideo") || l.contains("upos-") ->
                    "http://127.0.0.1:8123/bili?u=" + java.net.URLEncoder.encode(u, "UTF-8")
                l.contains("douyin") || l.contains("douyinvod") ->
                    "http://127.0.0.1:8123/dy?u=" + java.net.URLEncoder.encode(u, "UTF-8")
                else -> u
            }
            if (wrapped == u) {
                Toast.makeText(this, "该链接无需代理", Toast.LENGTH_SHORT).show()
                return
            }
            val pos = mVideoView!!.currentPosition.toInt()
            proxyOn = true
            mVideoView!!.release()
            mVideoView!!.skipPositionWhenPlay(pos)
            mVideoView!!.setUrl(wrapped)
            mVideoView!!.start()
            Toast.makeText(this, "已切换代理播放", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "代理切换失败", Toast.LENGTH_SHORT).show()
        }
    }

    /** 点「缓存」：实时抓取播放器当前流写成文件（再点一次停止），文件进下载页 */
    private var capturing = false
    private var captureThread: Thread? = null

    private fun toggleCapture() {
        if (capturing) {
            capturing = false
            Toast.makeText(this, "已停止抓取", Toast.LENGTH_SHORT).show()
            return
        }
        val u = rawUrl ?: return
        if (!u.startsWith("http")) {
            Toast.makeText(this, "当前不是网络流", Toast.LENGTH_SHORT).show()
            return
        }
        val ext = when {
            u.contains(".m3u8") -> "ts"
            u.contains(".flv") -> "flv"
            else -> "mp4"
        }
        val dir = getExternalFilesDir(null)
        val outFile = java.io.File(dir, "download_" + System.currentTimeMillis() + "." + ext)
        capturing = true
        cacheOn = true
        val hdrs = headersFor(u)
        captureThread = Thread {
            try {
                if (ext == "ts") captureM3u8(u, hdrs, outFile) else captureDirect(u, hdrs, outFile)
            } catch (ignored: Throwable) {
            }
        }
        captureThread!!.start()
        Toast.makeText(this, "开始实时抓取: download_*.$ext（再点停止）", Toast.LENGTH_LONG).show()
    }

    /** 单文件流：直接边播边写盘 */
    private fun captureDirect(u: String, hdrs: Map<String, String>?, out: java.io.File) {
        val c = java.net.URL(u).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 15000
        hdrs?.forEach { (k, v) -> c.setRequestProperty(k, v) }
        val ins = c.inputStream
        val os = java.io.FileOutputStream(out)
        val buf = ByteArray(64 * 1024)
        while (capturing) {
            val n = ins.read(buf)
            if (n <= 0) break
            os.write(buf, 0, n)
        }
        os.close(); ins.close(); c.disconnect()
    }

    /** m3u8：逐片下载拼接成 ts；直播清单会循环刷新拿新分片 */
    private fun captureM3u8(masterUrl: String, hdrs: Map<String, String>?, out: java.io.File) {
        val os = java.io.FileOutputStream(out)
        val buf = ByteArray(64 * 1024)
        val done = HashSet<String>()
        var mediaUrl: String? = null
        var rounds = 0
        while (capturing) {
            val listUrl = mediaUrl ?: masterUrl
            val body = httpGet(listUrl, hdrs) ?: break
            if (mediaUrl == null && !body.contains("#EXTINF")) {
                // 主清单：选 BANDWIDTH 最大的变体
                var bestBw = -1L; var best: String? = null; var cur = -1L
                val base = java.net.URI.create(masterUrl)
                for (ln in body.split("\n")) {
                    val t = ln.trim()
                    if (t.startsWith("#EXT-X-STREAM-INF")) {
                        val m = Regex("BANDWIDTH=(\\d+)").find(t)
                        cur = m?.groupValues?.get(1)?.toLong() ?: -1
                    } else if (t.isNotEmpty() && !t.startsWith("#") && cur > bestBw) {
                        bestBw = cur; best = base.resolve(t).toString()
                    }
                }
                mediaUrl = best ?: break
                continue
            }
            // 媒体清单：顺序抓没下过的分片
            val base = java.net.URI.create(listUrl)
            var gotNew = false
            for (ln in body.split("\n")) {
                val t = ln.trim()
                if (t.isEmpty() || t.startsWith("#")) continue
                val seg = base.resolve(t).toString()
                if (done.contains(seg)) continue
                done.add(seg)
                gotNew = true
                val c = java.net.URL(seg).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 15000
                hdrs?.forEach { (k, v) -> c.setRequestProperty(k, v) }
                val ins = c.inputStream
                while (capturing) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    os.write(buf, 0, n)
                }
                ins.close(); c.disconnect()
                if (!capturing) break
            }
            val isLive = body.contains("#EXT-X-MEDIA-SEQUENCE") && !body.contains("#EXT-X-ENDLIST")
            if (!isLive && gotNew) break           // 点播抓完即止
            if (!isLive && !gotNew) break
            if (rounds++ > 7200) break             // 直播最多约2小时
            Thread.sleep(2000)
        }
        os.close()
        capturing = false
    }

    private fun httpGet(u: String, hdrs: Map<String, String>?): String? {
        return try {
            val c = java.net.URL(u).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            hdrs?.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (c.responseCode != 200) { c.disconnect(); return null }
            val ins = c.inputStream
            val bo = java.io.ByteArrayOutputStream()
            val b = ByteArray(8192); var n: Int
            while (ins.read(b).also { n = it } > 0) bo.write(b, 0, n)
            ins.close(); c.disconnect()
            bo.toString("UTF-8")
        } catch (e: Throwable) { null }
    }

    override fun onDestroy() {
        capturing = false
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** 隐藏状态栏/导航栏，下拉临时呼出 */
    private fun hideSystemBars() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.hide(android.view.WindowInsets.Type.statusBars())
            window.insetsController?.systemBarsBehavior =
                android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }

    private val renderView by lazy {
        GLSurfaceRenderView2(this)
    }

    override fun getLayoutResId() = R.layout.activity_player

    override fun initView() {
        super.initView()
        mVideoView = findViewById(R.id.player)
        intent?.let {
            val controller = StandardVideoController(this)
            //按链接来源定方向：抖音竖屏，其余(哔哩哔哩等)横屏；不自动切换
            controller.setEnableOrientation(false)
            val prepareView = PrepareView(this) //准备播放界面
            prepareView.setClickStart()
            val thumb = prepareView.findViewById<ImageView>(R.id.thumb) //封面图
            Glide.with(this).load(THUMB).into(thumb)
            controller.addControlComponent(prepareView)
            controller.addControlComponent(CompleteView(this)) //自动完成播放界面
            controller.addControlComponent(ErrorView(this)) //错误界面
            val titleView = TitleView(this) //标题栏
            controller.addControlComponent(titleView)

            //根据是否为直播设置不同的底部控制条
            val isLive = it.getBooleanExtra(IntentKeys.IS_LIVE, false)
            if (isLive) {
                controller.addControlComponent(LiveControlView(this)) //直播控制条
            } else {
                val vodControlView = VodControlView(this) //点播控制条
                //是否显示底部进度条。默认显示
//                vodControlView.showBottomProgress(false);
                controller.addControlComponent(vodControlView)
            }
            val gestureControlView = GestureView(this) //滑动控制视图
            controller.addControlComponent(gestureControlView)
            mxPanel = xyz.doikki.dkplayer.widget.component.MxPanelView(this)
            mxPanel.onCacheClick = Runnable { toggleCapture() }
            mxPanel.onProxyClick = Runnable { replayWithProxy() }
            mxPanel.menuAction = object : xyz.doikki.dkplayer.widget.component.MxPanelView.MenuAction {
                override fun onLoop(loop: Boolean) { loopOn = loop }
            }
            controller.addControlComponent(mxPanel) //MX浮层:比例/倍速/截图/静音/旋转/缓存
            //根据是否为直播决定是否需要滑动调节进度
            controller.setCanChangePosition(!isLive)
            controller.setDismissTimeout(8000) //控件显示时长 4s→8s
            //默认进入全屏；方向按源：抖音竖屏、哔哩哔哩横屏
            mVideoView!!.startFullScreen()
            requestedOrientation = if (url != null && (url!!.contains("douyin") || url!!.contains("aweme")))
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

            //设置标题
            val title = it.getStringExtra(IntentKeys.TITLE)
            titleView.setTitle(title)

            //注意：以上组件如果你想单独定制，我推荐你把源码复制一份出来，然后改成你想要的样子。
            //改完之后再通过addControlComponent添加上去
            //你也可以通过addControlComponent添加一些你自己的组件，具体实现方式参考现有组件的实现。
            //这个组件不一定是View，请发挥你的想象力😃

            //如果你不需要单独配置各个组件，可以直接调用此方法快速添加以上组件
//            controller.addDefaultControlComponent(title, isLive)

            //竖屏也开启手势操作，默认关闭
//            controller.setEnableInNormal(true)
            //滑动调节亮度，音量，进度，默认开启
//            controller.setGestureEnabled(false)
            //适配刘海屏，默认开启
//            controller.setAdaptCutout(false)
            //双击播放暂停，默认开启
//            controller.setDoubleTapTogglePlayEnabled(false)

            //在控制器上显示调试信息
            //在LogCat显示调试信息
            controller.addControlComponent(PlayerMonitor())

            //如果你不想要UI，不要设置控制器即可
            mVideoView.setVideoController(controller)
            var url = it.getStringExtra(IntentKeys.URL)

            //点击文件管理器中的视频，选择DKPlayer打开，将会走以下代码
            if (TextUtils.isEmpty(url)
                && Intent.ACTION_VIEW == it.action
            ) {
                //获取intent中的视频地址
                url = Utils.getFileFromContentUri(this, it.data)
            }
//            val header = hashMapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/92.0.4515.131 Safari/537.36")
            mVideoView.setUrl(url, url?.let { headersFor(it) })
            rawUrl = url

            //保存播放进度
//            mVideoView.setProgressManager(ProgressManagerImpl())
            //播放状态监听
            mVideoView.addOnStateChangeListener(mOnStateChangeListener)

            // 临时切换RenderView, 如需全局请通过VideoConfig配置，详见MyApplication
            if (intent.getBooleanExtra(IntentKeys.CUSTOM_RENDER, false)) {
//                mVideoView.setRenderViewFactory(GLSurfaceRenderViewFactory.create())
                mVideoView.setRenderViewFactory(object : RenderViewFactory() {
                    override fun createRenderView(context: Context?): IRenderView {
                        return renderView
                    }
                })
                // 设置滤镜
                renderView.setGlFilter(GlFilterGroup(
                    // 水印
                    GlWatermarkFilter(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)),
                    GlSepiaFilter(),
                    GlSharpenFilter()
                ))
            }
            //临时切换播放核心，如需全局请通过VideoConfig配置，详见MyApplication
            //使用IjkPlayer解码
//            mVideoView.setPlayerFactory(IjkPlayerFactory.create())
            //使用ExoPlayer解码
//            mVideoView.setPlayerFactory(ExoMediaPlayerFactory.create())
            //使用MediaPlayer解码
//            mVideoView.setPlayerFactory(AndroidMediaPlayerFactory.create())

            //设置静音播放
//            mVideoView.setMute(true)

            //从设置的position开始播放
//            mVideoView.skipPositionWhenPlay(10000)
            mVideoView.start()
        }
    }

    private val mOnStateChangeListener: BaseVideoView.OnStateChangeListener =
        object : BaseVideoView.SimpleOnStateChangeListener() {
            override fun onPlayerStateChanged(playerState: Int) {
                when (playerState) {
                    VideoView.PLAYER_NORMAL -> {
                    }
                    VideoView.PLAYER_FULL_SCREEN -> {
                    }
                }
            }

            override fun onPlayStateChanged(playState: Int) {
                when (playState) {
                    VideoView.STATE_IDLE -> {
                    }
                    VideoView.STATE_PREPARING -> {
                    }
                    VideoView.STATE_PREPARED -> {
                    }
                    VideoView.STATE_PLAYING -> {
                        //需在此时获取视频宽高
                        val videoSize = mVideoView!!.videoSize
                        L.d("视频宽：" + videoSize[0])
                        L.d("视频高：" + videoSize[1])
                    }
                    VideoView.STATE_PAUSED -> {
                    }
                    VideoView.STATE_BUFFERING -> {
                    }
                    VideoView.STATE_BUFFERED -> {
                    }
                    VideoView.STATE_PLAYBACK_COMPLETED -> {
                        // 单集循环：播完自动重播
                        if (loopOn) mVideoView!!.replay(true)
                    }
                    VideoView.STATE_ERROR -> {
                    }
                }
            }
        }
    private var i = 0
    fun onButtonClick(view: View) {
    }

    override fun onPause() {
        super.onPause()
        //如果视频还在准备就 activity 就进入了后台，建议直接将 VideoView release
        //防止进入后台后视频还在播放
        if (mVideoView!!.currentPlayState == VideoView.STATE_PREPARING) {
            mVideoView!!.release()
        }
    }

    companion object {
        private const val THUMB =
            "https://cms-bucket.nosdn.127.net/eb411c2810f04ffa8aaafc42052b233820180418095416.jpeg"

        @JvmStatic
        fun start(context: Context, url: String, title: String, isLive: Boolean, customRender: Boolean = false) {
            val intent = Intent(context, PlayerActivity::class.java)
            intent.putExtra(IntentKeys.URL, url)
            intent.putExtra(IntentKeys.IS_LIVE, isLive)
            intent.putExtra(IntentKeys.TITLE, title)
            intent.putExtra(IntentKeys.CUSTOM_RENDER, customRender)
            context.startActivity(intent)
        }
    }
}