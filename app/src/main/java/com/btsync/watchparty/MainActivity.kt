package com.btsync.watchparty

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.btsync.watchparty.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), BluetoothSyncManager.BluetoothListener {

    private lateinit var binding: ActivityMainBinding
    private var exoPlayer: ExoPlayer? = null
    private lateinit var btManager: BluetoothSyncManager
    private var isSyncingEvent = false

    override fun onCreate(savedInstanceState: Bundle()) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        btManager = BluetoothSyncManager(this, "BTSyncPartyVideos", this)
        
        setupPlayer()
        setupUI()
        requestPermissions()
    }

    private fun setupPlayer() {
        exoPlayer = ExoPlayer.Builder(this).build()
        binding.playerView.player = exoPlayer

        exoPlayer?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isSyncingEvent) return
                val currentPos = exoPlayer?.currentPosition ?: 0L
                if (isPlaying) {
                    btManager.sendSyncPacket("PLAY", currentPos)
                } else {
                    btManager.sendSyncPacket("PAUSE", currentPos)
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (isSyncingEvent) return
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    btManager.sendSyncPacket("SEEK", newPosition.positionMs)
                }
            }
        })
    }

    private fun setupUI() {
        binding.btnHostServer.setOnClickListener { btManager.startServer() }
        binding.btnConnectPeer.setOnClickListener { btManager.showDevicePickerDialog() }
        binding.btnTransferFile.setOnClickListener { selectAndSendVideo() }

        binding.btnSendChat.setOnClickListener {
            val text = binding.etChatMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                btManager.sendChatMessage(text)
                binding.chatOverlay.addMessage("Me", text, true)
                binding.etChatMessage.setText("")
            }
        }
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            Toast.makeText(this, "Transferring video file over Bluetooth...", Toast.LENGTH_LONG).show()
            btManager.sendVideoFile(it)
        }
    }

    private fun selectAndSendVideo() {
        filePickerLauncher.launch("video/*")
    }

    override fun onSyncPacketReceived(action: String, positionMs: Long) {
        runOnUiThread {
            isSyncingEvent = true
            when (action) {
                "PLAY" -> {
                    exoPlayer?.seekTo(positionMs)
                    exoPlayer?.play()
                }
                "PAUSE" -> {
                    exoPlayer?.seekTo(positionMs)
                    exoPlayer?.pause()
                }
                "SEEK" -> {
                    exoPlayer?.seekTo(positionMs)
                }
            }
            binding.root.postDelayed({ isSyncingEvent = false }, 200)
        }
    }

    override fun onChatMessageReceived(sender: String, message: String) {
        runOnUiThread {
            binding.chatOverlay.addMessage(sender, message, false)
        }
    }

    override fun onVideoFileReceived(fileUri: Uri) {
        runOnUiThread {
            Toast.makeText(this, "Video File Received & Saved!", Toast.LENGTH_SHORT).show()
            exoPlayer?.setMediaItem(MediaItem.fromUri(fileUri))
            exoPlayer?.prepare()
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}.launch(missing.toTypedArray())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
        btManager.cleanup()
    }
}