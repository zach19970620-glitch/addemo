package com.haokai.addemo.media

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ImageView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.haokai.addemo.config.ConfigRepository
import com.haokai.addemo.config.DemoConfig
import com.haokai.addemo.config.TagMediaItem
import kotlin.random.Random

class MediaDirector(
    private val context: Context,
    private val configRepository: ConfigRepository,
    private val onMediaEnded: () -> Unit,
) {
    companion object {
        private const val TAG = "MediaDirector"
        private const val STATIC_SHOW_MS = 4_000L
        private const val MAX_BITMAP_EDGE = 2048
        private const val MAX_REMOTE_IMAGE_BYTES = 8 * 1024 * 1024
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var config: DemoConfig = configRepository.load()

    private var playerView: PlayerView? = null
    private var posterView: ImageView? = null
    private var player: ExoPlayer? = null

    private var loopingIdle: Boolean = false
    private var awaitingEndCallback: Boolean = false
    private var staticEndRunnable: Runnable? = null
    /** 上次随机海报，尽量避免连续两次相同 */
    private var lastRandomPoster: String = ""
    /** 海报异步加载代数；丢弃过期回调，避免快速切态时旧图覆盖新图 */
    private var posterLoadGeneration: Int = 0
    /**
     * 可选：拦截本次非 idle 播片结束。返回 true 表示已消费，不再回调 [onMediaEnded]。
     * 用于服务端指令串行（视频后还有后续步骤）。
     */
    private var endInterceptor: (() -> Boolean)? = null

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED && awaitingEndCallback) {
                awaitingEndCallback = false
                if (loopingIdle) {
                    // 正常 idle 用 REPEAT_MODE_* 循环，不应走到 ENDED；若落到此则重启列表
                    playIdlePlaylist()
                } else {
                    dispatchMediaEnded()
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "player error: ${error.message}")
            awaitingEndCallback = false
            if (loopingIdle) {
                playIdlePlaylist()
            } else {
                dispatchMediaEnded()
            }
        }
    }

    private fun dispatchMediaEnded() {
        val interceptor = endInterceptor
        endInterceptor = null
        if (interceptor?.invoke() == true) return
        onMediaEnded()
    }

    /** 设置/清除下一次播片结束拦截器（playVideoUri 前调用）。 */
    fun setEndInterceptor(interceptor: (() -> Boolean)?) {
        endInterceptor = interceptor
    }

    fun attach(playerView: PlayerView, posterView: ImageView) {
        this.playerView = playerView
        this.posterView = posterView
        ensurePlayer()
        this.playerView?.player = player
        this.playerView?.useController = false
        // 换片/重置时尽量保留最后一帧，减轻黑闪
        this.playerView?.setKeepContentOnPlayerReset(true)
    }

    fun reloadConfig() {
        config = configRepository.load(forceReload = true)
    }

    /** 服务端意图：图片 URI（https / file / asset；禁止明文 http） */
    fun showImageUri(uriString: String) {
        if (uriString.isBlank()) return
        if (!isAllowedMediaUri(uriString)) {
            Log.w(TAG, "blocked image uri: $uriString")
            return
        }
        cancelStaticEnd()
        loopingIdle = false
        endInterceptor = null
        pausePlayer()
        Thread({
            val bitmap = decodeBitmapFromUri(uriString)
            mainHandler.post {
                val view = posterView ?: return@post
                if (bitmap == null) {
                    Log.w(TAG, "image failed: $uriString")
                    return@post
                }
                playerView?.visibility = View.INVISIBLE
                view.setImageDrawable(BitmapDrawable(context.resources, bitmap))
                view.visibility = View.VISIBLE
                view.animate().cancel()
                view.alpha = 1f
            }
        }, "media-img-uri").also { it.isDaemon = true }.start()
    }

    /** 服务端意图：视频 URI（https / file / asset；禁止明文 http） */
    fun playVideoUri(uriString: String) {
        if (uriString.isBlank()) return
        if (!isAllowedMediaUri(uriString)) {
            Log.w(TAG, "blocked video uri: $uriString")
            loopingIdle = false
            awaitingEndCallback = false
            // 保留 interceptor，便于串行链跳过坏链视频
            dispatchMediaEnded()
            return
        }
        Log.i(TAG, "playVideoUri: ${uriString.take(200)}")
        cancelStaticEnd()
        hidePoster()
        loopingIdle = false
        awaitingEndCallback = true
        ensurePlayer()
        val p = player!!
        playerView?.player = p
        playerView?.visibility = View.VISIBLE
        p.repeatMode = Player.REPEAT_MODE_OFF
        p.setMediaItem(MediaItem.fromUri(Uri.parse(uriString)))
        p.prepare()
        p.playWhenReady = true
    }

    /** 仅允许 https / file / asset / 相对 assets 路径，拒绝 cleartext http。 */
    fun isAllowedMediaUri(uriString: String): Boolean {
        val u = uriString.trim()
        return when {
            u.startsWith("https://", ignoreCase = true) -> true
            u.startsWith("file:", ignoreCase = true) -> true
            u.startsWith("asset:///", ignoreCase = true) -> true
            u.startsWith("http://", ignoreCase = true) -> false
            u.contains("://") -> false
            else -> true // 相对路径走本地 assets / externalRoot
        }
    }

    @Deprecated("use showImageUri", ReplaceWith("showImageUri(url)"))
    fun showRemoteImage(url: String) = showImageUri(url)

    @Deprecated("use playVideoUri", ReplaceWith("playVideoUri(url)"))
    fun playRemoteVideo(url: String) = playVideoUri(url)

    private fun decodeBitmapFromUri(uriString: String): android.graphics.Bitmap? {
        return runCatching {
            when {
                uriString.startsWith("https://", ignoreCase = true) -> {
                    decodeBoundedBitmap {
                        java.net.URL(uriString).openStream().use { it.readBytes() }
                    }
                }
                uriString.startsWith("file:", ignoreCase = true) -> {
                    val path = Uri.parse(uriString).path ?: return@runCatching null
                    decodeBoundedBitmapFile(path)
                }
                uriString.startsWith("asset:///", ignoreCase = true) -> {
                    val assetPath = uriString.removePrefix("asset:///")
                    decodeBoundedBitmap {
                        context.assets.open(assetPath).use { it.readBytes() }
                    }
                }
                else -> {
                    // 相对路径走 demo assets
                    loadBitmap(uriString)
                }
            }
        }.getOrNull()
    }

    private fun decodeBoundedBitmapFile(path: String): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val sample = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        return BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }

    private fun decodeBoundedBitmap(load: () -> ByteArray): android.graphics.Bitmap? {
        val bytes = load()
        if (bytes.size > MAX_REMOTE_IMAGE_BYTES) {
            Log.w(TAG, "image too large: ${bytes.size} bytes")
            return null
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sample = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }

    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (w > MAX_BITMAP_EDGE || h > MAX_BITMAP_EDGE) {
            sample *= 2
            w /= 2
            h /= 2
        }
        return sample.coerceAtLeast(1)
    }

    fun playIdleLoop() {
        cancelStaticEnd()
        endInterceptor = null
        hidePoster()
        loopingIdle = true
        // 用 ExoPlayer 原生循环，避免 ENDED 后再 setMediaItem/prepare 黑闪
        awaitingEndCallback = false
        playIdlePlaylist()
    }

    /** 按年龄×性别选打招呼片；未知属性回退年轻男性 / greetings 列表 */
    fun playGreetingForAudience(age: Int, gender: Int) {
        cancelStaticEnd()
        endInterceptor = null
        hidePoster()
        loopingIdle = false
        awaitingEndCallback = true
        playVideoPath(resolveGreetingPath(age, gender))
    }

    fun playMood(good: Boolean) {
        cancelStaticEnd()
        endInterceptor = null
        hidePoster()
        loopingIdle = false
        awaitingEndCallback = true
        val path = if (good) {
            config.yuki.mood_good
        } else {
            config.yuki.mood_bad
        }.ifBlank {
            config.yuki.greetings.firstOrNull().orEmpty()
        }
        playVideoPath(path)
    }

    private fun resolveGreetingPath(age: Int, gender: Int): String {
        val yuki = config.yuki
        val older = age >= 0 && age >= yuki.older_age_min
        val woman = gender == com.haokai.hk5mh210.inference.FaceResult.GENDER_WOMAN
        val man = gender == com.haokai.hk5mh210.inference.FaceResult.GENDER_MAN
        val path = when {
            older && woman -> yuki.greet_older_female
            older && man -> yuki.greet_older_male
            !older && woman -> yuki.greet_young_female
            !older && man -> yuki.greet_young_male
            older -> yuki.greet_older_male.ifBlank { yuki.greet_older_female }
            woman -> yuki.greet_young_female
            else -> yuki.greet_young_male
        }
        return path.ifBlank { yuki.greetings.firstOrNull().orEmpty() }
    }

    fun showUnicoPoster() {
        cancelStaticEnd()
        endInterceptor = null
        loopingIdle = false
        awaitingEndCallback = false
        pausePlayer()
        showPoster(pickPoster(fallback = config.posters.unico), fade = false)
    }

    fun showDiningPoster() {
        cancelStaticEnd()
        endInterceptor = null
        loopingIdle = false
        awaitingEndCallback = false
        pausePlayer()
        showPoster(pickPoster(fallback = config.posters.dining), fade = true)
    }

    /** posters.random 非空则随机；否则用 fallback */
    private fun pickPoster(fallback: String): String {
        val pool = config.posters.random.filter { it.isNotBlank() }
        if (pool.isEmpty()) return fallback
        val candidates = if (pool.size > 1) {
            pool.filter { it != lastRandomPoster }.ifEmpty { pool }
        } else {
            pool
        }
        val picked = candidates[Random.nextInt(candidates.size)]
        lastRandomPoster = picked
        Log.i(TAG, "random poster=$picked")
        return picked
    }

    fun playProduct(item: TagMediaItem) = playTagMedia(item)

    fun playDish(item: TagMediaItem) = playTagMedia(item)

    fun stopAll() {
        cancelStaticEnd()
        loopingIdle = false
        awaitingEndCallback = false
        endInterceptor = null
        pausePlayer()
        hidePoster()
    }

    fun release() {
        stopAll()
        player?.removeListener(playerListener)
        player?.release()
        player = null
        playerView?.player = null
        playerView = null
        posterView = null
    }

    private fun playTagMedia(item: TagMediaItem) {
        cancelStaticEnd()
        endInterceptor = null
        hidePoster()
        loopingIdle = false
        awaitingEndCallback = true
        when {
            item.video.isNotBlank() -> playVideoPath(item.video)
            item.image.isNotBlank() -> showStaticThenEnd(item.image)
            else -> onMediaEnded()
        }
    }

    private fun ensurePlayer() {
        if (player == null) {
            player = ExoPlayer.Builder(context).build().also {
                it.addListener(playerListener)
                it.repeatMode = Player.REPEAT_MODE_OFF
            }
        }
    }

    private fun playIdlePlaylist() {
        val list = config.yuki.idle.map { it.trim() }.filter { it.isNotBlank() }
        if (list.isEmpty()) {
            Log.w(TAG, "idle list empty")
            return
        }
        val uris = list.mapNotNull { relative ->
            configRepository.resolveMediaUri(relative).also { uri ->
                if (uri == null) Log.w(TAG, "missing idle media: $relative")
            }
        }
        if (uris.isEmpty()) {
            Log.w(TAG, "idle media all missing")
            return
        }
        ensurePlayer()
        val p = player!!
        playerView?.player = p
        playerView?.visibility = View.VISIBLE
        val items = uris.map { MediaItem.fromUri(Uri.parse(it)) }
        p.repeatMode = if (items.size == 1) {
            Player.REPEAT_MODE_ONE
        } else {
            Player.REPEAT_MODE_ALL
        }
        p.setMediaItems(items, /* resetPosition= */ true)
        p.prepare()
        p.playWhenReady = true
        awaitingEndCallback = false
    }

    private fun playVideoPath(relative: String) {
        if (relative.isBlank()) {
            Log.w(TAG, "blank video path")
            if (!loopingIdle) onMediaEnded()
            return
        }
        val uriString = configRepository.resolveMediaUri(relative)
        if (uriString == null) {
            Log.w(TAG, "missing media: $relative")
            if (!loopingIdle) onMediaEnded()
            return
        }
        ensurePlayer()
        val p = player!!
        playerView?.player = p
        playerView?.visibility = View.VISIBLE
        p.repeatMode = Player.REPEAT_MODE_OFF
        p.setMediaItem(MediaItem.fromUri(Uri.parse(uriString)))
        p.prepare()
        p.playWhenReady = true
    }

    private fun pausePlayer() {
        player?.playWhenReady = false
        player?.stop()
        playerView?.visibility = View.INVISIBLE
    }

    private fun showPoster(
        relative: String,
        fade: Boolean,
        onShown: (() -> Unit)? = null,
    ) {
        val view = posterView ?: return
        if (relative.isBlank()) {
            hidePoster()
            return
        }
        val generation = ++posterLoadGeneration
        Thread({
            val bitmap = loadBitmap(relative)
            mainHandler.post {
                if (generation != posterLoadGeneration || posterView !== view) {
                    bitmap?.recycle()
                    return@post
                }
                if (bitmap == null) {
                    Log.w(TAG, "poster missing: $relative")
                    hidePoster()
                    return@post
                }
                playerView?.visibility = View.INVISIBLE
                view.setImageDrawable(BitmapDrawable(context.resources, bitmap))
                view.visibility = View.VISIBLE
                if (fade) {
                    view.alpha = 0f
                    view.animate()
                        .alpha(1f)
                        .setDuration(config.timeouts.poster_fade_ms)
                        .start()
                } else {
                    view.animate().cancel()
                    view.alpha = 1f
                }
                onShown?.invoke()
            }
        }, "media-poster").also { it.isDaemon = true }.start()
    }

    private fun showStaticThenEnd(relative: String) {
        pausePlayer()
        showPoster(relative, fade = false) {
            awaitingEndCallback = true
            val runnable = Runnable {
                if (awaitingEndCallback) {
                    awaitingEndCallback = false
                    dispatchMediaEnded()
                }
            }
            staticEndRunnable = runnable
            mainHandler.postDelayed(runnable, STATIC_SHOW_MS)
        }
    }

    private fun hidePoster() {
        posterLoadGeneration++
        val view = posterView ?: return
        view.animate().cancel()
        view.alpha = 1f
        view.setImageDrawable(null)
        view.visibility = View.GONE
    }

    private fun cancelStaticEnd() {
        staticEndRunnable?.let { mainHandler.removeCallbacks(it) }
        staticEndRunnable = null
    }

    private fun loadBitmap(relative: String): android.graphics.Bitmap? {
        if (relative.isBlank() || relative.contains("..")) return null
        val root = configRepository.externalRoot().canonicalFile
        val external = java.io.File(root, relative).canonicalFile
        if (
            (external.path.startsWith(root.path + java.io.File.separator) ||
                external.path == root.path) &&
            external.isFile
        ) {
            return decodeBoundedBitmapFile(external.absolutePath)
        }
        return decodeBoundedBitmapAsset(configRepository.assetPath(relative))
    }

    private fun decodeBoundedBitmapAsset(assetPath: String): android.graphics.Bitmap? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.assets.open(assetPath).use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            }
            val sample = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            context.assets.open(assetPath).use { input ->
                BitmapFactory.decodeStream(
                    input,
                    null,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                )
            }
        }.onFailure {
            Log.w(TAG, "asset decode failed: $assetPath (${it.message})")
        }.getOrNull()
    }
}
