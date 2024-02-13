package com.example.mediacodecplayer

import android.content.Context
import android.media.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.io.IOException


class Player(
    val context: Context,
    surface: Surface,
    fileName: String
) {

    //TIMEOUT_US 值的选择并不直接取决于视频的帧率（如24fps、30fps或60fps）。
    // 通常情况下，将 TIMEOUT_US 设置为10000微秒（10毫秒）是一个合理的选择
    // 它主要影响的是解码器等待输入和输出缓冲区可用时的超时时间。设置合适的 TIMEOUT_US 值可以在保证解码性能的同时，避免过高的CPU占用。
    // 如果你发现解码性能不足，可以尝试减小 TIMEOUT_US 值，以便解码器更频繁地检查缓冲区。
    // 相反，如果你发现CPU占用率过高，可以尝试增加 TIMEOUT_US 值，以减少解码器检查缓冲区的频率
    private val TIMEOUT_US = 10000

    @Volatile
    private var videoPlayStatus: VideoPlayStatus = VideoPlayStatus.NOSTART

    @Volatile
    private var audioPlayStatus: AudioPlayStatus = AudioPlayStatus.NOSTART

    private var systemStartTime: Long = -1 // 记录系统时间对应的起始时间（微秒）
    private var isPaused = false
    private var pauseStartTime: Long = -1
    private val pauseLock = Object()

    private var isVideoSeeking = false
    private var isAudioSeeking = false
    private val seekLock = Object()

    private var videoPlayRunnable:Runnable? = null
    private var audioPlayRunnable:Runnable? = null

    private var videoExtractor = MediaExtractor()
    private var audioExtractor = MediaExtractor()

    private var videoDecoder: MediaCodec? = null
    private var audioDecoder: MediaCodec? = null
    private var audioTrack: AudioTrack? = null

    private val videoHandlerThread = HandlerThread("VideoPlayerThread")
    private lateinit var videoHandler: Handler

    private val audioHandlerThread = HandlerThread("AudioPlayerThread")
    private lateinit var audioHandler: Handler

    enum class SeekMode {
        ACCURATE, UNACCURATE
    }

    enum class VideoPlayStatus {
        NOSTART, PLAYED
    }

    enum class AudioPlayStatus {
        NOSTART, PLAYED
    }

    init {
        try {
            // 从assets文件夹中获取文件描述符
            val assetFileDescriptor = context.assets.openFd(fileName)
            // 设置视频解码器的数据源
            videoExtractor.setDataSource(
                assetFileDescriptor.fileDescriptor,
                assetFileDescriptor.startOffset,
                assetFileDescriptor.length
            )
            // 设置音频解码器的数据源
            audioExtractor.setDataSource(
                assetFileDescriptor.fileDescriptor,
                assetFileDescriptor.startOffset,
                assetFileDescriptor.length
            )

            // 遍历所有轨道，一般是两个轨道，一个是视频轨道，一个是音轨
            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                //注意，mime的取值里，"video/avc"表示H264，"video/hevc"表示h265，"video/av01"表示av1
                val mime = format.getString(MediaFormat.KEY_MIME)
                Log.d("test","format.getString format:$format")
                // 检查是否为视频轨道
                if (mime?.startsWith("video/") == true) {
                    format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 70000)
                    // 选择视频轨道
                    videoExtractor.selectTrack(i)
                    // 创建解码器，
                    // 探测优化：在知道av1的情况下，可以直接创建av1解码器，decoder = MediaCodec.createDecoderByType("video/av01")
                    // 非探测优化，不知道视频编码的是的情况下，交由内部查找  videoDecoder = MediaCodec.createDecoderByType(mime)
                    // 硬解解码器优先创建
                    videoDecoder = createHardwareFirstDecoder(mime, format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT))
                    // 配置解码器
                    // MediaCodec.configure()方法用于配置解码器。它有四个参数，分别是：
                    //format：一个MediaFormat对象，表示解码器的输入格式。这个参数是必需的，用于指定解码器的输入格式，如编码格式、分辨率、帧率等。
                    //surface：一个Surface对象，表示解码器的输出目标。这个参数是可选的，如果你需要将解码后的视频帧显示到屏幕上，你需要传入一个Surface对象。如果你不需要显示视频帧，可以传入null，音频的场景，也是传null。
                    //crypto：一个MediaCrypto对象，表示解码器的加密信息。这个参数是可选的，如果你需要解码加密的视频，你需要传入一个MediaCrypto对象。如果你不需要解码加密的视频，可以传入null。
                    //flags：一个整数，表示解码器的配置标志。这个参数是可选的，用于指定解码器的配置标志。目前，MediaCodec支持的配置标志有CONFIGURE_FLAG_ENCODE和0。CONFIGURE_FLAG_ENCODE表示配置编码器，0表示配置解码器。在大多数情况下，你可以传入0，表示默认配置。
                    videoDecoder?.configure(format, surface, null, 0)



                } else if (mime?.startsWith("audio/") == true) {
                    //选择音频轨道
                    audioExtractor.selectTrack(i)
                    audioDecoder = MediaCodec.createDecoderByType(mime)
                    audioDecoder?.configure(format, null, null, 0)

                    // 创建音频格式，设置采样率，编码格式和声道布局
                    val audioFormat = AudioFormat.Builder()
                        // 从输入格式中获取到音频的采样率，然后设置采样率
                        // 采样率是指每秒钟对声音信号的采样次数，单位是Hz（赫兹）。
                        // 常见的采样率有44100Hz、48000Hz、96000Hz等。采样率越高，音质越好，但是数据量也越大
                        .setSampleRate(format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                        // 设置解码后的音频格式为PCM 16bit
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        // 设置声道布局为立体声
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                    // 根据采样率，声道布局，编码格式获取最小的缓冲区大小
                    val minBufferSize = AudioTrack.getMinBufferSize(
                        format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                        AudioFormat.CHANNEL_OUT_STEREO,
                        AudioFormat.ENCODING_PCM_16BIT
                    )
                    audioTrack = AudioTrack.Builder()
                        // 设置音频格式
                        .setAudioFormat(audioFormat)
                        // 设置缓冲区大小
                        .setBufferSizeInBytes(minBufferSize)
                        // 设置传输模式为流模式
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                }
            }

            videoHandlerThread.start()
            audioHandlerThread.start()
            videoHandler = Handler(videoHandlerThread.looper)
            audioHandler = Handler(audioHandlerThread.looper)

            videoPlayRunnable = Runnable {
                //开始启动解码器，将开始处理输入数据并生成输出数据，如果是视频预加载的场景，可以将decoder?.start()方法放在decoder?.configure()方法后立马执行
                if (videoPlayStatus != VideoPlayStatus.PLAYED) {
                    videoDecoder?.start()
                }
                videoPlayStatus = VideoPlayStatus.PLAYED
                decodeAndPlayVideoFrames()
            }

            audioPlayRunnable = Runnable {
                if (audioPlayStatus != AudioPlayStatus.PLAYED) {
                    audioDecoder?.start()
                    audioTrack?.play()
                }
                audioPlayStatus = AudioPlayStatus.PLAYED
                decodeAndPlayAudioFrames()
            }

            //首帧预渲染
            videoHandler.post { seekTo(1) }

        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    //创建硬解优先的解码器
    fun createHardwareFirstDecoder(mimeType: String?, width: Int, height: Int): MediaCodec? {
        val numCodecs = MediaCodecList.getCodecCount()
        for (i in 0 until numCodecs) {
            val codecInfo = MediaCodecList.getCodecInfoAt(i)
            if (!codecInfo.isEncoder) {
                val types = codecInfo.supportedTypes
                for (j in types.indices) {
                    if (types[j].equals(mimeType, ignoreCase = true)) {
                        val capabilities = codecInfo.getCapabilitiesForType(mimeType)
                        val videoCapabilities = capabilities.videoCapabilities
                        // 查询是否支持特定的分辨率
                        val isSizeSupported = videoCapabilities.isSizeSupported(width, height)

                        // 查询是否支持Adaptive Playback
                        val isAdaptivePlaybackSupported = capabilities.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_AdaptivePlayback)
                        Log.d("test","createHardwareFirstDecoder createHardwareFirstDecoder isSizeSupported:$isSizeSupported,isAdaptivePlaybackSupported:$isAdaptivePlaybackSupported,width:$width,height:$height")
                        val codecName = codecInfo.name
                        return if (!codecName.startsWith("OMX.google.")) {
                            MediaCodec.createByCodecName(codecName)
                        } else {
                            MediaCodec.createByCodecName(codecName)
                        }
                    }
                }
            }
        }
        return null // No hardware decoder found for this MIME type
    }

    fun play() {
        videoPlayRunnable?.let { videoHandler.post(it) }
        audioPlayRunnable?.let { audioHandler.post(it) }
    }

    fun pause() {
        synchronized(pauseLock) {
            isPaused = true
        }
    }

    fun resume() {
        synchronized(pauseLock) {
            isPaused = false
            if (pauseStartTime != -1L) {
                val pauseEndTime = System.nanoTime() / 1000
                systemStartTime += (pauseEndTime - pauseStartTime)
                pauseStartTime = -1
            }
            pauseLock.notifyAll()
        }
    }

    fun seekTo(position: Long, seekMode: SeekMode = SeekMode.UNACCURATE) {
        if (videoPlayStatus != VideoPlayStatus.PLAYED) {
            videoDecoder?.start()
        }
        if (audioPlayStatus != AudioPlayStatus.PLAYED) {
            audioDecoder?.start()
            audioTrack?.play()
        }
        videoPlayStatus = VideoPlayStatus.PLAYED
        audioPlayStatus = AudioPlayStatus.PLAYED
        seekToPosition(position, seekMode, false)
    }


    fun seekToAndPlay(position: Long, seekMode: SeekMode = SeekMode.UNACCURATE) {
        if (videoPlayStatus != VideoPlayStatus.PLAYED) {
            videoDecoder?.start()
        }
        if (audioPlayStatus != AudioPlayStatus.PLAYED) {
            audioDecoder?.start()
            audioTrack?.play()
        }
        videoPlayStatus = VideoPlayStatus.PLAYED
        audioPlayStatus = AudioPlayStatus.PLAYED
        seekToPosition(position, seekMode, true)
    }

    // seekToPosition 函数用于执行 seek 操作
    private fun seekToPosition(
        position: Long,
        seekMode: SeekMode = SeekMode.ACCURATE,
        needPlayAfterSeek: Boolean = false
    ) {
        if (seekMode == SeekMode.ACCURATE) {
            //精准seek,需要先定位到目标值前一个帧，然后逐帧解码，直到找到目标帧
            videoExtractor.seekTo(position, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            audioExtractor.seekTo(position, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        } else {
            //非精准seek,只需要找到最近帧即可
            videoExtractor.seekTo(position, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            audioExtractor.seekTo(position, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        }
        val seekPosition = if(seekMode == SeekMode.ACCURATE)  position  else -1
        if (needPlayAfterSeek) {
            isVideoSeeking = true
            Log.d("test","decodeAndPlayVideoFrames start seek before")
            videoHandler.post {
                isVideoSeeking = false
                Log.d("test","decodeAndPlayVideoFrames start seek before 1111")
                decodeAndPlayVideoFrames(seekPosition)
            }
            audioHandler.post {
                decodeAndPlayAudioFrames(seekPosition)
            }
        } else {
            decodeAndDisplayOneVideoFrame(seekPosition)
        }
    }

    // decodeAndPlayFrames 函数用于播放视频
    private fun decodeAndPlayVideoFrames(position: Long = -1) {
        val bufferInfo = MediaCodec.BufferInfo() // 创建一个MediaCodec.BufferInfo对象，用于存储解码器输出缓冲区的信息

        var outputDone = false // 标记输出处理是否完成，当输出缓冲区处理完毕时，将其设置为true
        var inputDone = false // 标记输入处理是否完成，当输入缓冲区处理完毕时，将其设置为true
        var videoStartTime: Long = -1 // 记录视频帧的起始时间（微秒）
        systemStartTime = -1 // 记录系统时间对应的起始时间（微秒）
        val playbackSpeed = 1.0 // 设置播放速度为1倍速
        // 循环处理输入和输出缓冲区，直到输出处理完成
        while (!outputDone) {
            // 处理输入缓冲区
            if (!inputDone) {
                // 从解码器中获取可用的输入缓冲区索引，如果没有可用的输入缓冲区，将等待 TIMEOUT_US 微秒
                val inputBufferIndex = videoDecoder?.dequeueInputBuffer(TIMEOUT_US.toLong()) ?: -1
                if (inputBufferIndex >= 0) {
                    // 使用新的 API 获取输入缓冲区
                    val inputBuffer = videoDecoder?.getInputBuffer(inputBufferIndex)
                    if (inputBuffer != null) {
                        // 从媒体文件中读取一帧数据到输入缓冲区
                        val sampleSize = videoExtractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            // 读取到文件末尾，向解码器输入缓冲区发送结束标志
                            videoDecoder?.queueInputBuffer(
                                inputBufferIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            //标记输入处理完成
                            inputDone = true
                        } else {
                            // 获取当前帧的显示时间（微秒）
                            val presentationTimeUs = videoExtractor.sampleTime
                            // 将帧数据发送给解码器进行解码
                            videoDecoder?.queueInputBuffer(
                                inputBufferIndex,
                                0,
                                sampleSize,
                                presentationTimeUs,
                                0
                            )
                            // 移动到下一个帧
                            videoExtractor.advance()
                        }
                    }
                }
            }
            // 处理输出缓冲区
            // 从解码器中获取已解码的输出缓冲区索引，如果没有可用的输出缓冲区，将等待 TIMEOUT_US 微秒
            val outputBufferIndex =
                videoDecoder?.dequeueOutputBuffer(bufferInfo, TIMEOUT_US.toLong()) ?: -2
            if (outputBufferIndex >= 0) {
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    // 已经解码到了最后
                    // 标记输出处理完成
                    outputDone = true
                } else if (position >=0 && bufferInfo.presentationTimeUs < position) {
                    // 如果指定了从position的位置开始播放，则在未解码到指定位置之前，需要丢帧
                    Log.d("test","decodeAndPlayVideoFrames drop frame whith time :${bufferInfo.presentationTimeUs}")
                    videoDecoder?.releaseOutputBuffer(outputBufferIndex, false)
                } else {
                    // 根据播放速度计算帧渲染时间
                    if (videoStartTime == -1L) {
                        // 记录视频帧的起始时间
                        videoStartTime = bufferInfo.presentationTimeUs
                        // 记录系统时间对应的起始时间
                        systemStartTime = System.nanoTime() / 1000

                        Log.d("test","decodeAndPlayVideoFrames first frame time :${videoStartTime}, position:$position")
                    }
                    // 获取当前系统时间（微秒）
                    var currentTime = System.nanoTime() / 1000
                    // 计算帧的实际渲染时间，根据播放速度进行调整
                    val presentationTimeUs =
                        (bufferInfo.presentationTimeUs - videoStartTime) / playbackSpeed + systemStartTime

                    Log.d("test","decodeAndPlayVideoFrames render frame whith time :${presentationTimeUs},current time:$currentTime")
                    // 等待渲染帧的时间到了
                    while (presentationTimeUs > currentTime) {
                        try {
                            // 等待下一帧渲染时降低CPU占用,故休眠10毫秒
                            // 10这个值可以根据实际需求进行调整。如果希望进一步降低cpu的占用，可以将其设置为更大的值，例如 20 或 50。但是，增加休眠时间可能会导致渲染的精度降低，因为线程在休眠状态时不能及时响应下一帧的到来。
                            //如果你希望提高渲染的精度，可以将休眠时间设置为更小的值，例如 5 或 1。然而，这可能会导致CPU占用率增加，从而增加功耗。
                            Thread.sleep(10)
                        } catch (e: InterruptedException) {
                            e.printStackTrace()
                            break
                        }
                        // 更新当前系统时间
                        currentTime = System.nanoTime() / 1000

                        // 检查是否暂停
                        synchronized(pauseLock) {
                            if (isPaused) {
                                //记录暂停时候的时间，如果不记录的话，resume的时候会跳帧
                                pauseStartTime = System.nanoTime() / 1000
                            }
                            while (isPaused) {
                                try {
                                    pauseLock.wait()
                                } catch (e: InterruptedException) {
                                    e.printStackTrace()
                                }
                            }
                        }
                    }
                    // 渲染帧，将解码后的帧显示在Surface上
                    videoDecoder?.releaseOutputBuffer(outputBufferIndex, true)
                    // 检查是否seek
                    synchronized(seekLock) {
                        if (isVideoSeeking) {
                            // seek的时候，强制标记输出处理完成
                            outputDone = true
                        }
                    }
                }
            }
        }
    }

    // decodeAndDisplayOneFrame 函数用于解码并只显示一帧
    private fun decodeAndDisplayOneVideoFrame(position: Long = -1) {
        val bufferInfo = MediaCodec.BufferInfo()

        var outputDone = false
        var inputDone = false
        // 循环处理输入和输出缓冲区，直到输出处理完成
        while (!outputDone) {
            if (!inputDone) {
                val inputBufferIndex = videoDecoder?.dequeueInputBuffer(TIMEOUT_US.toLong()) ?: -1
                if (inputBufferIndex >= 0) {
                    val inputBuffer = videoDecoder?.getInputBuffer(inputBufferIndex)
                    if (inputBuffer != null) {
                        val sampleSize = videoExtractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            videoDecoder?.queueInputBuffer(
                                inputBufferIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            val presentationTimeUs = videoExtractor.sampleTime
                            videoDecoder?.queueInputBuffer(
                                inputBufferIndex,
                                0,
                                sampleSize,
                                presentationTimeUs,
                                0
                            )
                            videoExtractor.advance()
                        }
                    }
                }
            }

            val outputBufferIndex =
                videoDecoder?.dequeueOutputBuffer(bufferInfo, TIMEOUT_US.toLong()) ?: -1
            if (outputBufferIndex >= 0) {
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    outputDone = true
                } else if (position >=0 && bufferInfo.presentationTimeUs < position) {
                    // 如果指定了从position的位置开始播放，则在未解码到指定位置之前，需要丢帧
                    videoDecoder?.releaseOutputBuffer(outputBufferIndex, false)
                } else {
                    videoDecoder?.releaseOutputBuffer(outputBufferIndex, true)
                    outputDone = true
                }
            }
        }
    }

    // 解码并播放音频帧的函数，如果指定了position参数，则从该位置开始播放
    private fun decodeAndPlayAudioFrames(position: Long = -1) {
        // 创建一个用于存储解码后的音频帧信息的对象
        val bufferInfo = MediaCodec.BufferInfo()

        // 循环解码并播放音频帧
        while (true) {
            // 从音频解码器中获取一个可用的输入缓冲区的索引
            val index = audioDecoder?.dequeueInputBuffer(TIMEOUT_US.toLong())
            if (index != null && index >= 0) {
                // 获取输入缓冲区
                val buffer = audioDecoder?.getInputBuffer(index)
                // 从音频提取器中读取一帧音频数据，并将其存储到输入缓冲区中
                val sampleSize = audioExtractor.readSampleData(buffer!!, 0)
                if (sampleSize > 0) {
                    // 将输入缓冲区中的音频数据提交给音频解码器进行解码
                    audioDecoder?.queueInputBuffer(index, 0, sampleSize, audioExtractor.sampleTime, 0)
                    // 将音频提取器的位置前进到下一帧音频数据
                    audioExtractor.advance()
                } else {
                    // 如果已经没有更多的音频数据，则向音频解码器提交一个空的输入缓冲区，并设置结束标志
                    audioDecoder?.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
            }

            // 检查是否seek
            if (isAudioSeeking) {
                // seek的时候，强制标记输出处理完成
                break
            }

            // 从音频解码器中获取一个已经解码完成的输出缓冲区的索引
            val outIndex = audioDecoder?.dequeueOutputBuffer(bufferInfo, TIMEOUT_US.toLong())
            if (outIndex != null && outIndex >= 0) {
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    // 如果已经解码到了最后，跳出循环
                    break
                }
                else if (position >=0 && bufferInfo.presentationTimeUs < position) {
                    // 如果指定了从position的位置开始播放，则在未解码到指定位置之前，需要丢帧
                    audioDecoder?.releaseOutputBuffer(outIndex, false)
                } else {
                    // 获取输出缓冲区，该缓冲区包含了解码后的音频数据
                    val buffer = audioDecoder?.getOutputBuffer(outIndex)
                    // 将输出缓冲区的数据转换为ShortBuffer
                    val shortBuffer = buffer?.asShortBuffer()
                    // 创建一个ShortArray，用于存储从ShortBuffer中读取的数据
                    val outSamples = ShortArray(bufferInfo.size / 2)
                    // 从ShortBuffer中读取数据到ShortArray
                    shortBuffer?.get(outSamples)
                    // 将解码后的音频数据写入AudioTrack进行播放
                    audioTrack?.write(outSamples, 0, outSamples.size)
                    // 释放输出缓冲区，使其可以被音频解码器重新使用
                    audioDecoder?.releaseOutputBuffer(outIndex, false)
                }
            }
        }
    }

    public fun setNewFileAndSurface(fileName: String, surface: Surface) {
        // 从assets文件夹中获取文件描述符
        val assetFileDescriptor = context.assets.openFd(fileName)
        synchronized(seekLock) {
            isVideoSeeking = true

        }
        isAudioSeeking = true
        videoExtractor.release()
        // 重新设置视频解码器的数据源
        // 创建一个新的MediaExtractor对象
        videoExtractor = MediaExtractor()
        videoExtractor.setDataSource(
            assetFileDescriptor.fileDescriptor,
            assetFileDescriptor.startOffset,
            assetFileDescriptor.length
        )
        audioExtractor.release()
        audioExtractor = MediaExtractor()
        // 设置音频解码器的数据源
        audioExtractor.setDataSource(
            assetFileDescriptor.fileDescriptor,
            assetFileDescriptor.startOffset,
            assetFileDescriptor.length
        )

        // 遍历所有轨道，一般是两个轨道，一个是视频轨道，一个是音轨
        for (i in 0 until videoExtractor.trackCount) {
            val format = videoExtractor.getTrackFormat(i)
            //注意，mime的取值里，"video/avc"表示H264，"video/hevc"表示h265，"video/av01"表示av1
            val mime = format.getString(MediaFormat.KEY_MIME)
            Log.d("test","format.getString format:$format")
            // 检查是否为视频轨道
            if (mime?.startsWith("video/") == true) {

                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 70000)
                // 选择视频轨道
                videoExtractor.selectTrack(i)

            } else if (mime?.startsWith("audio/") == true) {
                // 选择音频轨道
                audioExtractor.selectTrack(i)

            }
        }

        videoHandler.post {
            // 清空解码器的内部缓存
            videoDecoder?.flush()
            videoDecoder?.setOutputSurface(surface)
            isVideoSeeking = false
            videoPlayRunnable?.let { videoHandler.post(it) }
        }

        audioHandler.post {
            // 清空解码器的内部缓存
            audioDecoder?.flush()
            isAudioSeeking = false
            audioPlayRunnable?.let {
                audioHandler.post(it)
            }
        }

    }


}