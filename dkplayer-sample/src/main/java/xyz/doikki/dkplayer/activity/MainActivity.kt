package xyz.doikki.dkplayer.activity

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import xyz.doikki.dkplayer.R
import xyz.doikki.dkplayer.activity.api.ParallelPlayActivity
import xyz.doikki.dkplayer.activity.api.PlayerActivity
import xyz.doikki.dkplayer.activity.api.PlayRawAssetsActivity
import xyz.doikki.dkplayer.activity.extend.ADActivity
import xyz.doikki.dkplayer.activity.extend.CacheActivity
import xyz.doikki.dkplayer.activity.extend.CustomExoPlayerActivity
import xyz.doikki.dkplayer.activity.extend.CustomIjkPlayerActivity
import xyz.doikki.dkplayer.activity.extend.DefinitionPlayerActivity
import xyz.doikki.dkplayer.activity.extend.FullScreenActivity
import xyz.doikki.dkplayer.activity.extend.PadActivity
import xyz.doikki.dkplayer.activity.extend.PlayListActivity
import xyz.doikki.dkplayer.activity.pip.PIPListActivity
import xyz.doikki.dkplayer.activity.CpuInfoActivity

class MainActivity : AppCompatActivity() {

    private lateinit var etUrl: EditText
    private lateinit var mediaPanel: View
    private lateinit var listMedia: ListView
    private val mediaItems = ArrayList<String>()
    private val mediaPaths = ArrayList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        etUrl = findViewById(R.id.et_url)
        mediaPanel = findViewById(R.id.media_panel)
        listMedia = findViewById(R.id.list_media)

        findViewById<View>(R.id.btn_go).setOnClickListener { playInput() }
        etUrl.setOnEditorActionListener { _, _, _ ->
            playInput(); true
        }
        findViewById<View>(R.id.btn_features).setOnClickListener { showFeatures() }
        findViewById<View>(R.id.btn_media).setOnClickListener { toggleMedia() }

        requestStorage()
        listMedia.setOnItemClickListener { _, _, pos, _ ->
            mediaPanel.visibility = View.GONE
            PlayerActivity.start(this, "file://" + mediaPaths[pos], mediaItems[pos], false, false)
        }
    }

    private fun playInput() {
        val url = etUrl.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(this, "请输入视频链接", Toast.LENGTH_SHORT).show()
            return
        }
        PlayerActivity.start(this, url, url, false, false)
    }

    private fun showFeatures() {
        val names = arrayOf(
            "全屏播放", "播放列表", "抖音上下滑", "画中画列表",
            "自定义控制器(Exo)", "自定义控制器(IJK)", "多清晰度", "广告示例",
            "缓存管理", "平板适配", "并行播放", "Raw资源播放", "CPU信息"
        )
        val acts = arrayOf(
            FullScreenActivity::class.java, PlayListActivity::class.java,
            xyz.doikki.dkplayer.activity.list.tiktok.TikTokActivity::class.java,
            PIPListActivity::class.java, CustomExoPlayerActivity::class.java,
            CustomIjkPlayerActivity::class.java, DefinitionPlayerActivity::class.java,
            ADActivity::class.java, CacheActivity::class.java, PadActivity::class.java,
            ParallelPlayActivity::class.java, PlayRawAssetsActivity::class.java,
            CpuInfoActivity::class.java
        )
        AlertDialog.Builder(this)
            .setTitle("功能大全")
            .setItems(names) { _, which -> startActivity(android.content.Intent(this, acts[which])) }
            .show()
    }

    private fun toggleMedia() {
        if (mediaPanel.visibility == View.VISIBLE) {
            mediaPanel.visibility = View.GONE
            return
        }
        loadMedia()
        if (mediaItems.isEmpty()) {
            Toast.makeText(this, "未找到本机视频", Toast.LENGTH_SHORT).show()
            return
        }
        listMedia.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mediaItems)
        mediaPanel.visibility = View.VISIBLE
    }

    private fun loadMedia() {
        mediaItems.clear()
        mediaPaths.clear()
        try {
            val cur: Cursor? = contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DATA),
                null, null, MediaStore.Video.Media.DATE_ADDED + " DESC")
            cur?.use { c ->
                while (c.moveToNext()) {
                    mediaItems.add(c.getString(0) ?: "video")
                    mediaPaths.add(c.getString(1) ?: "")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun requestStorage() {
        if (Build.VERSION.SDK_INT in 23..32) {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 10001)
            }
        }
    }
}
