package com.example.transfer

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

class TransferService : Service() {
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var serverSocket: ServerSocket? = null
    private val serverPort = 9000 // Fixed port for this example to discover

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("TransferService", "Service created, opening server socket on $serverPort")
        startServer()
    }

    private fun startServer() {
        serviceScope.launch {
            try {
                serverSocket = ServerSocket(serverPort)
                while (true) {
                    val client = serverSocket!!.accept()
                    Log.d("TransferService", "Accepted connection from ${client.inetAddress}")
                    handleClient(client)
                }
            } catch (e: Exception) {
                Log.e("TransferService", "Server socket failed", e)
            }
        }
    }

    private fun handleClient(socket: Socket) {
        serviceScope.launch {
            try {
                val input: InputStream = socket.getInputStream()
                // In a production app, we would read the stream chunk by chunk, 
                // parse file markers, and write to a FileOutputStream using MediaStore APIs.
                Log.d("TransferService", "Reading from input stream...")
                val buffer = ByteArray(4096)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    // Simulating data received
                }
                Log.d("TransferService", "Transfer complete.")
            } catch (e: Exception) {
                Log.e("TransferService", "Client handling failed", e)
            } finally {
                socket.close()
            }
        }
    }

    fun sendFile(targetIp: String, port: Int, data: ByteArray) {
        serviceScope.launch {
            try {
                val socket = Socket(targetIp, port)
                val output: OutputStream = socket.getOutputStream()
                output.write(data)
                output.flush()
                socket.close()
                Log.d("TransferService", "Sent chunk successfully to $targetIp")
            } catch (e: Exception) {
               Log.e("TransferService", "Failed to send file", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serverSocket?.close()
        serviceJob.cancel()
    }
}
