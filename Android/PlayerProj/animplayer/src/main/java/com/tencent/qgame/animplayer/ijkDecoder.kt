package com.tencent.qgame.animplayer

import android.graphics.SurfaceTexture
import android.view.Surface
import com.tencent.qgame.animplayer.file.IFileContainer
import com.tencent.qgame.animplayer.util.ALog
import tv.danmaku.ijk.media.player.IMediaPlayer
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import java.lang.RuntimeException

class ijkDecoder(player: AnimPlayer) : Decoder(player), SurfaceTexture.OnFrameAvailableListener{

    companion object{
        private const val TAG = "${Constant.TAG}.IjkDecoder"
    }
    private var glTexture: SurfaceTexture? = null
    private var needDestroy = false
    private var frameIndex =0

    var decoder: IjkMediaPlayer? = null

    internal var mSizeChangedListener: IMediaPlayer.OnVideoSizeChangedListener =
        IMediaPlayer.OnVideoSizeChangedListener { mp, width, height, sarNum, sarDen ->


        }


    internal var mPreparedListener: IMediaPlayer.OnPreparedListener = IMediaPlayer.OnPreparedListener{it

        var width = it.videoWidth
        var height = it.videoHeight
        this?.renderThread?.handler?.post {

            try {
                if (!prepareRender()){
                    throw  RuntimeException("render create fail")
                }
            }catch (t: Throwable){
                onFailed(Constant.REPORT_ERROR_TYPE_CREATE_RENDER,errorMsg = "${Constant.ERROR_MSG_CREATE_RENDER} e = $t")
            }

            preparePlay(width, height)
            try {

                render?.apply {
                    glTexture = SurfaceTexture(getExternalTexture()).apply {
                        setOnFrameAvailableListener(this@ijkDecoder)
                        setDefaultBufferSize(width, height)

                    }
                    clearFrame()
                }

                it.setSurface(Surface(glTexture))
                it.start()

            }catch (e: Throwable){
                ALog.e(
                    TAG,
                    "MediaExtractor exception e=$e",
                    e
                )
                onFailed(Constant.REPORT_ERROR_TYPE_EXTRACTOR_EXC, "${Constant.ERROR_MSG_EXTRACTOR_EXC} e=$e")
                release(it as IjkMediaPlayer?)
            }

        }


    }

    internal var mCompletionListener: IMediaPlayer.OnCompletionListener = IMediaPlayer.OnCompletionListener {
        try {
            decodeThread.handler?.post{
                release(it as IjkMediaPlayer?)
            }
        }catch (e: Throwable){

        }
    }
    override fun start(fileContainer: IFileContainer) {
        isStopReq = false
        needDestroy = false
        isRunning = true
        renderThread.handler?.post{
            // 开始播放
//            decoder?.release()
            startPlay(fileContainer)

        }
    }


    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        if (isStopReq) {
            return
        }
        ALog.d(
            TAG,
            "onFrameAvailable"
        )
        player.pluginManager.onDecoding(frameIndex)
        onVideoRender(frameIndex, player.configManager.config)

        frameIndex++

        renderThread.handler?.post {
            try {
                glTexture?.apply {
                    updateTexImage()
                    render?.renderFrame(player.configManager.config)
                    player.pluginManager.onRendering()
                    render?.swapBuffers()
                }
            } catch (e: Throwable) {
                ALog.e(
                    TAG,
                    "render exception=$e",
                    e
                )
            }
        }
    }

    private fun startPlay(fileContainer: IFileContainer){

        try {
            decoder?.apply {
                release(decoder)
            }
            decoder = null
            decoder = IjkMediaPlayer()
            decoder?.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER,"mediacodec-all-videos",0)
            fileContainer.setDataSource(decoder!!)
            decoder?.setOnVideoSizeChangedListener(mSizeChangedListener)
            decoder?.setOnPreparedListener(mPreparedListener)
            decoder?.setOnCompletionListener(mCompletionListener)
            IjkMediaPlayer.native_setLogLevel(IjkMediaPlayer.IJK_LOG_INFO)

            decoder?.prepareAsync()
            frameIndex =0

        }catch (e: Throwable){
            onFailed(Constant.REPORT_ERROR_TYPE_DECODE_EXC, "${Constant.ERROR_MSG_DECODE_EXC} e=$e")
            release(decoder)
        }

    }



    private fun release(dec: IjkMediaPlayer?) {
        renderThread.handler?.post {
            render?.clearFrame()
            try {
                ALog.i(
                    TAG,
                    "release"
                )
                dec?.apply {
                    stop()
                    release()
                }
                decoder =null

                glTexture?.release()
                glTexture = null
                speedControlUtil.reset()
                player.pluginManager.onRelease()
                render?.releaseTexture()
            } catch (e: Throwable) {
                ALog.e(
                    TAG,
                    "release e=$e",
                    e
                )
            }
            isRunning = false
            onVideoComplete()
            if (needDestroy) {
                destroyInner()
            }
        }
    }

    override fun stop(){
        isStopReq =true
        if (isStopReq)
            decoder?.apply { release(this) }

    }

    override fun destroy() {
        needDestroy = true
        if (isRunning) {
            stop()
        } else {
            destroyInner()
        }
    }

    private fun destroyInner() {
        renderThread.handler?.post {
            player.pluginManager.onDestroy()
            render?.destroy()
            render = null
            onVideoDestroy()
            destroyThread()
        }
    }
}