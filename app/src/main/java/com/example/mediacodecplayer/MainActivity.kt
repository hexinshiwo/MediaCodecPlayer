package com.example.mediacodecplayer

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import androidx.annotation.RequiresApi

class MainActivity : Activity() {
    private lateinit var player: Player

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val surfaceView = findViewById<SurfaceView>(R.id.surfaceView)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                player = Player(applicationContext, holder.surface, "sample2_h265.mp4")
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                // Handle surface changed
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                // Handle surface destroyed
            }
        })

        val surfaceView2 = findViewById<SurfaceView>(R.id.surfaceView2)
        surfaceView2.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {

            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                // Handle surface changed
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                // Handle surface destroyed
            }
        })

        val playButton = findViewById<Button>(R.id.playButton)
        playButton.setOnClickListener {
            player.play() // Start playing from the beginning
        }

        val pauseButton = findViewById<Button>(R.id.pauseButton)
        pauseButton.setOnClickListener {
            player.pause() // Start playing from the beginning
        }

        val resumeButton = findViewById<Button>(R.id.resumeButton)
        resumeButton.setOnClickListener {
            player.resume() // Start playing from the beginning
        }

        val seekButton = findViewById<Button>(R.id.seekButton)
        seekButton.setOnClickListener {
            player.seekToAndPlay(11000000, Player.SeekMode.ACCURATE) // Seek to the specified position
        }

        val reuseCodec = findViewById<Button>(R.id.reuseCodec)
        reuseCodec.setOnClickListener {
            player.setNewFileAndSurface(
                "sample_h265.mp4",
                surfaceView2.holder.surface
            ) // Seek to the specified position }
        }
    }
}