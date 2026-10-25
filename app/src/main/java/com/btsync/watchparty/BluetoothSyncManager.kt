package com.btsync.watchparty

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

class BluetoothSyncManager(
    private val context: Context,
    private val storageDirName: String,
    private val listener: BluetoothListener
) {
    interface BluetoothListener {
        fun onSyncPacketReceived(action: String, positionMs: Long)
        fun onChatMessageReceived(sender: String, message: String)
        fun onVideoFileReceived(fileUri: Uri)
    }

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private var isConnected = false

    companion object {
        val APP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    fun startServer() {
        Thread {
            try {
                val serverSocket: BluetoothServerSocket? =
                    bluetoothAdapter?.listenUsingRfcommWithServiceRecord("BTSyncParty", APP_UUID)
                socket = serverSocket?.accept()
                serverSocket?.close()
                setupStreams()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun setupStreams() {
        socket?.let {
            outputStream = it.outputStream
            inputStream = it.inputStream
            isConnected = true
            listenForIncomingData()
        }
    }

    fun sendSyncPacket(action: String, positionMs: Long) {
        if (!isConnected) return
        Thread {
            try {
                val json = JSONObject().apply {
                    put("type", "SYNC")
                    put("action", action)
                    put("pos", positionMs)
                }
                val data = (json.toString() + "
").toByteArray(StandardCharsets.UTF_8)
                outputStream?.write(data)
                outputStream?.flush()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    fun sendChatMessage(message: String) {
        if (!isConnected) return
        Thread {
            try {
                val json = JSONObject().apply {
                    put("type", "CHAT")
                    put("msg", message)
                }
                val data = (json.toString() + "
").toByteArray(StandardCharsets.UTF_8)
                outputStream?.write(data)
                outputStream?.flush()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    fun sendVideoFile(fileUri: Uri) {
        if (!isConnected) return
        Thread {
            try {
                val fileStream = context.contentResolver.openInputStream(fileUri) ?: return@Thread
                val header = JSONObject().apply {
                    put("type", "FILE_START")
                    put("size", fileStream.available())
                }
                val headerData = (header.toString() + "
").toByteArray(StandardCharsets.UTF_8)
                outputStream?.write(headerData)

                val buffer = ByteArray(16384)
                var bytesRead: Int
                while (fileStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream?.write(buffer, 0, bytesRead)
                }
                fileStream.close()
                outputStream?.flush()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun listenForIncomingData() {
        Thread {
            val reader = inputStream?.bufferedReader()
            while (isConnected) {
                try {
                    val line = reader?.readLine() ?: break
                    if (line.startsWith("{")) {
                        val json = JSONObject(line)
                        when (json.optString("type")) {
                            "SYNC" -> listener.onSyncPacketReceived(
                                json.getString("action"),
                                json.getLong("pos")
                            )
                            "CHAT" -> listener.onChatMessageReceived("Peer", json.getString("msg"))
                        }
                    }
                } catch (e: Exception) {
                    break
                }
            }
        }.start()
    }

    fun cleanup() {
        isConnected = false
        socket?.close()
    }
}