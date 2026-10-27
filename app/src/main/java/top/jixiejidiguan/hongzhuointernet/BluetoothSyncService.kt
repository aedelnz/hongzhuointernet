package top.jixiejidiguan.hongzhuointernet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

data class BluetoothPeer(val address: String, val name: String)

object BluetoothSyncPrefs {
    private const val PREFERENCES = "bluetooth_sync"
    private const val PEER_ADDRESS = "peer_address"
    private const val PEER_ADDRESSES = "peer_addresses"
    private const val LOCAL_DEVICE_ID = "local_device_id"

    fun boundAddresses(context: Context): Set<String> {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val saved = preferences.getStringSet(PEER_ADDRESSES, null)
        if (saved != null) return saved.toSet()
        val legacyAddress = preferences.getString(PEER_ADDRESS, null)
        val migrated = legacyAddress?.let(::setOf).orEmpty()
        preferences.edit {
            putStringSet(PEER_ADDRESSES, migrated)
            remove(PEER_ADDRESS)
        }
        return migrated
    }

    fun setBound(context: Context, address: String, bound: Boolean) {
        val addresses = boundAddresses(context).toMutableSet()
        if (bound) addresses.add(address) else addresses.remove(address)
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit {
            putStringSet(PEER_ADDRESSES, addresses)
            remove(PEER_ADDRESS)
        }
    }

    fun localDeviceId(context: Context): String {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val existingId = preferences.getString(LOCAL_DEVICE_ID, null)
        if (existingId != null) return existingId
        return UUID.randomUUID().toString().also { generatedId ->
            preferences.edit { putString(LOCAL_DEVICE_ID, generatedId) }
        }
    }
}

class BluetoothSyncService : Service() {
    private val connectionLock = Any()
    private val activeSockets = ConcurrentHashMap<String, BluetoothSocket>()
    private val activeOutputs = ConcurrentHashMap<String, DataOutputStream>()
    private val pendingMessages =
        ConcurrentHashMap<String, ConcurrentLinkedQueue<ByteArray>>()
    private val outgoingExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "BluetoothSyncOutgoing")
    }
    private val clientThreads = ConcurrentHashMap<String, Thread>()
    private val connectionDirections = ConcurrentHashMap<String, Boolean>()

    @Volatile
    private var running = false

    @Volatile
    private var workerGeneration = 0

    @Volatile
    private var boundPeers: Set<String> = emptySet()

    @Volatile
    private var serverSocket: BluetoothServerSocket? = null

    private var serverThread: Thread? = null

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_TURNING_OFF,
                BluetoothAdapter.STATE_OFF -> stopWorkers()
                BluetoothAdapter.STATE_ON -> {
                    if (running) {
                        stopWorkers()
                        startWorkers(BluetoothSyncPrefs.boundAddresses(this@BluetoothSyncService))
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        registerReceiver(
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            Context.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val message = intent
            ?.takeIf { it.action == ACTION_SEND }
            ?.getStringExtra(EXTRA_MESSAGE)

        startInForeground()
        if (!hasBluetoothConnectPermission()) {
            stopSelf()
            return START_NOT_STICKY
        }

        running = true
        val currentPeers = BluetoothSyncPrefs.boundAddresses(this)
        if (currentPeers.isEmpty()) {
            running = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (currentPeers != boundPeers || serverThread == null) {
            stopWorkers()
            startWorkers(currentPeers)
        }
        if (message != null) broadcastMessage(message)
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        stopWorkers()
        outgoingExecutor.shutdownNow()
        unregisterReceiver(bluetoothStateReceiver)
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "蓝牙通知同步", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("蓝牙通知同步")
            .setContentText("正在连接已绑定设备")
            .setOngoing(true)
            .build()
        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
    }

    @Synchronized
    private fun startWorkers(peers: Set<String>) {
        boundPeers = peers.toSet()
        pendingMessages.keys.retainAll(boundPeers)
        if (peers.isEmpty() || !running) return
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "Bluetooth is disabled; waiting for the user to enable it")
            return
        }
        val generation = ++workerGeneration
        serverThread = Thread(
            { acceptConnections(adapter, generation) },
            "BluetoothSyncServer"
        ).also(Thread::start)
        peers.forEach { address ->
            val thread = Thread(
                { connectToPeer(adapter, address, generation) },
                "BluetoothSyncClient-${address.takeLast(5)}"
            )
            clientThreads[address] = thread
            thread.start()
        }
    }

    @Synchronized
    private fun stopWorkers() {
        workerGeneration++
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            Log.w(TAG, "Could not close Bluetooth server socket", e)
        }
        serverSocket = null
        activeSockets.values.forEach(::closeSocket)
        activeSockets.clear()
        activeOutputs.clear()
        clientThreads.values.forEach(Thread::interrupt)
        clientThreads.clear()
        connectionDirections.clear()
        serverThread?.interrupt()
        serverThread = null
        pendingMessages.keys.retainAll(boundPeers)
    }

    private fun acceptConnections(adapter: BluetoothAdapter, generation: Int) {
        while (running && generation == workerGeneration) {
            var server: BluetoothServerSocket? = null
            try {
                server = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
                if (!running || generation != workerGeneration) {
                    server.close()
                    return
                }
                serverSocket = server
                val socket = server.accept()
                server.close()
                if (serverSocket === server) serverSocket = null
                val address = socket.remoteDevice.address
                if (address in boundPeers) {
                    Thread(
                        { handleConnection(socket, address, generation, initiatedLocally = false) },
                        "BluetoothSyncIncoming-${address.takeLast(5)}"
                    ).start()
                } else {
                    socket.close()
                }
            } catch (e: IOException) {
                closeServerSocket(server)
                if (running && generation == workerGeneration) {
                    Log.w(TAG, "Bluetooth server connection failed", e)
                    sleepBeforeRetry()
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Bluetooth connect permission was revoked", e)
                stopSelf()
                return
            }
        }
    }

    private fun connectToPeer(
        adapter: BluetoothAdapter,
        peerAddress: String,
        generation: Int
    ) {
        while (running &&
            generation == workerGeneration &&
            peerAddress in boundPeers &&
            connectionDirections[peerAddress] != false
        ) {
            var socket: BluetoothSocket? = null
            try {
                socket = adapter.getRemoteDevice(peerAddress)
                    .createRfcommSocketToServiceRecord(SERVICE_UUID)
                socket.connect()
                handleConnection(socket, peerAddress, generation, initiatedLocally = true)
            } catch (e: IOException) {
                closeSocket(socket)
                if (running && generation == workerGeneration) {
                    Log.i(TAG, "Retrying Bluetooth connection to $peerAddress", e)
                    sleepBeforeRetry()
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Bluetooth connect permission was revoked", e)
                closeSocket(socket)
                stopSelf()
                return
            }
        }
    }

    private fun handleConnection(
        socket: BluetoothSocket,
        peerAddress: String,
        generation: Int,
        initiatedLocally: Boolean
    ) {
        socket.use { connectedSocket ->
            try {
                val input = DataInputStream(BufferedInputStream(connectedSocket.inputStream))
                val output = DataOutputStream(BufferedOutputStream(connectedSocket.outputStream))
                val localDeviceId = BluetoothSyncPrefs.localDeviceId(this)
                writeMessage(
                    output,
                    JSONObject().put("type", "hello").put("deviceId", localDeviceId)
                        .toString().toByteArray(StandardCharsets.UTF_8)
                )
                val handshake = JSONObject(String(readMessage(input), StandardCharsets.UTF_8))
                val remoteDeviceId = handshake.optString("deviceId")
                if (handshake.optString("type") != "hello" ||
                    remoteDeviceId.isBlank() ||
                    remoteDeviceId == localDeviceId
                ) {
                    throw IOException("Bluetooth peer identity is invalid")
                }
                val shouldInitiate = localDeviceId < remoteDeviceId
                connectionDirections[peerAddress] = shouldInitiate
                if (initiatedLocally != shouldInitiate ||
                    !running ||
                    generation != workerGeneration ||
                    peerAddress !in boundPeers
                ) return

                synchronized(connectionLock) {
                    activeSockets[peerAddress]?.let(::closeSocket)
                    activeSockets[peerAddress] = connectedSocket
                    activeOutputs[peerAddress] = output
                    flushPendingMessages(peerAddress, output)
                }

                while (running && generation == workerGeneration && peerAddress in boundPeers) {
                    receiveMessage(
                        peerAddress,
                        readMessage(input)
                    )
                }
            } catch (e: EOFException) {
                Log.i(TAG, "Bluetooth peer disconnected: $peerAddress")
            } catch (e: JSONException) {
                Log.w(TAG, "Bluetooth peer sent an invalid handshake: $peerAddress", e)
            } catch (e: IOException) {
                if (running && generation == workerGeneration) {
                    Log.i(TAG, "Bluetooth connection ended: $peerAddress", e)
                }
            } finally {
                synchronized(connectionLock) {
                    if (activeSockets[peerAddress] === connectedSocket) {
                        activeSockets.remove(peerAddress)
                        activeOutputs.remove(peerAddress)
                    }
                }
            }
        }
    }

    private fun receiveMessage(address: String, bytes: ByteArray) {
        try {
            val json = JSONObject(String(bytes, StandardCharsets.UTF_8))
            val id = json.getString("id").take(MAX_FIELD_LENGTH)
            when (json.getString("type")) {
                "remove" -> mainHandler.post {
                    NotificationStore.removeNotification("bluetooth:$address:$id")
                }
                "message" -> {
                    val title = json.getString("title").take(MAX_FIELD_LENGTH).ifBlank {
                        "来自已绑定设备的消息"
                    }
                    val text = json.getString("text").take(MAX_FIELD_LENGTH)
                    mainHandler.post { postMessageNotification(address, id, title, text) }
                }
                "upsert" -> {
                    val title = json.getString("title").take(MAX_FIELD_LENGTH)
                    val packageName = json.getString("packageName").take(MAX_FIELD_LENGTH)
                    val text = json.getString("text").take(MAX_FIELD_LENGTH)
                    val time = json.optLong("time", System.currentTimeMillis())
                    val isMedia = json.optBoolean("isMedia", false)
                    val name = try {
                        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager)
                            .adapter?.getRemoteDevice(address)?.name
                    } catch (e: SecurityException) {
                        Log.w(TAG, "Unable to read the paired device name", e)
                        null
                    }
                    mainHandler.post {
                        NotificationStore.addRemoteNotification(
                            AppNotification(
                                id = "bluetooth:$address:$id",
                                packageName = packageName,
                                title = title,
                                text = text,
                                time = time,
                                isMedia = isMedia,
                                sourceDevice = name ?: address
                            )
                        )
                    }
                }
                else -> Log.w(TAG, "Ignoring unknown Bluetooth message type")
            }
        } catch (e: JSONException) {
            Log.w(TAG, "Ignoring malformed Bluetooth notification message", e)
        }
    }

    private fun postMessageNotification(address: String, id: String, title: String, text: String) {
        val deviceName = try {
            (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager)
                .adapter?.getRemoteDevice(address)?.name
        } catch (e: SecurityException) {
            Log.w(TAG, "Unable to read the sending device name", e)
            null
        }
        NotificationStore.addRemoteNotification(
            AppNotification(
                id = "bluetooth:$address:$id",
                packageName = packageName,
                title = title,
                text = text,
                time = System.currentTimeMillis(),
                sourceDevice = deviceName ?: address,
                sourceAppName = "同步消息"
            )
        )
        if (ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Cannot display received message: notification permission is denied")
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(MESSAGE_CHANNEL_ID, "同步设备消息", NotificationManager.IMPORTANCE_DEFAULT)
        )
        manager.notify(
            "bluetooth:$address:$id",
            id.hashCode(),
            Notification.Builder(this, MESSAGE_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build()
        )
    }

    private fun broadcastMessage(message: String) {
        boundPeers.toList().forEach { peerAddress -> queueMessage(message, peerAddress) }
    }

    private fun queueMessage(message: String, peerAddress: String) {
        val bytes = message.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > MAX_MESSAGE_SIZE || peerAddress !in boundPeers) return
        try {
            outgoingExecutor.execute {
                synchronized(connectionLock) {
                    if (peerAddress !in boundPeers) return@synchronized
                    val output = activeOutputs[peerAddress]
                    if (output != null) {
                        try {
                            writeMessage(output, bytes)
                        } catch (e: IOException) {
                            Log.w(TAG, "Could not send notification to $peerAddress", e)
                            enqueuePendingMessage(peerAddress, bytes)
                            closeSocket(activeSockets[peerAddress])
                        }
                    } else {
                        enqueuePendingMessage(peerAddress, bytes)
                    }
                }
            }
        } catch (e: RejectedExecutionException) {
            Log.w(TAG, "Skipping message because Bluetooth sync is stopping", e)
        }
    }

    private fun enqueuePendingMessage(peerAddress: String, bytes: ByteArray) {
        val queue = pendingMessages.getOrPut(peerAddress) { ConcurrentLinkedQueue() }
        if (queue.size >= MAX_PENDING_MESSAGES) queue.poll()
        queue.add(bytes)
    }

    private fun flushPendingMessages(peerAddress: String, output: DataOutputStream) {
        val queue = pendingMessages[peerAddress] ?: return
        while (true) {
            val message = queue.peek() ?: return
            try {
                writeMessage(output, message)
                queue.poll()
            } catch (e: IOException) {
                Log.w(TAG, "Could not flush queued messages to $peerAddress", e)
                return
            }
        }
    }

    private fun writeMessage(output: DataOutputStream, bytes: ByteArray) {
        output.writeInt(bytes.size)
        output.write(bytes)
        output.flush()
    }

    private fun readMessage(input: DataInputStream): ByteArray {
        val size = input.readInt()
        if (size !in 1..MAX_MESSAGE_SIZE) throw IOException("Invalid message size: $size")
        return ByteArray(size).also(input::readFully)
    }

    private fun hasBluetoothConnectPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED

    private fun closeServerSocket(socket: BluetoothServerSocket?) {
        try {
            socket?.close()
        } catch (e: IOException) {
            Log.w(TAG, "Could not close Bluetooth server socket", e)
        }
    }

    private fun closeSocket(socket: BluetoothSocket?) {
        try {
            socket?.close()
        } catch (e: IOException) {
            Log.w(TAG, "Could not close Bluetooth socket", e)
        }
    }

    private fun sleepBeforeRetry() {
        try {
            Thread.sleep(RETRY_DELAY_MILLIS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private const val TAG = "BluetoothSync"
        private const val CHANNEL_ID = "bluetooth_sync"
        private const val MESSAGE_CHANNEL_ID = "synced_messages"
        private const val NOTIFICATION_ID = 4101
        private const val ACTION_START = "top.jixiejidiguan.hongzhuointernet.action.START_SYNC"
        private const val ACTION_SEND = "top.jixiejidiguan.hongzhuointernet.action.SEND_NOTIFICATION"
        private const val EXTRA_MESSAGE = "message"
        private const val SERVICE_NAME = "HongZhuoNotificationSync"
        private const val MAX_MESSAGE_SIZE = 64 * 1024
        private const val MAX_FIELD_LENGTH = 4096
        private const val MAX_PENDING_MESSAGES = 100
        private const val RETRY_DELAY_MILLIS = 3000L
        private val SERVICE_UUID: UUID =
            UUID.fromString("46b1f0c2-32b7-4b44-8b85-8ac1970a51ad")
        private val mainHandler = Handler(Looper.getMainLooper())

        @Volatile
        private var instance: BluetoothSyncService? = null

        fun start(context: Context) {
            if (BluetoothSyncPrefs.boundAddresses(context).isEmpty()) return
            val intent = Intent(context, BluetoothSyncService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BluetoothSyncService::class.java))
        }

        fun sendNotification(context: Context, notification: AppNotification) {
            if (notification.packageName == context.packageName || !canSync(context)) return
            broadcast(
                context,
                JSONObject()
                    .put("type", "upsert")
                    .put("id", notification.id)
                    .put("packageName", notification.packageName)
                    .put("title", notification.title)
                    .put("text", notification.text)
                    .put("time", notification.time)
                    .put("isMedia", notification.isMedia)
                    .toString()
            )
        }

        fun sendRemoval(context: Context, id: String) {
            if (!canSync(context)) return
            broadcast(context, JSONObject().put("type", "remove").put("id", id).toString())
        }

        fun sendCustomMessage(context: Context, title: String, text: String) {
            if (!canSync(context)) return
            broadcast(
                context,
                JSONObject()
                    .put("type", "message")
                    .put("id", UUID.randomUUID().toString())
                    .put("title", title.take(MAX_FIELD_LENGTH))
                    .put("text", text.take(MAX_FIELD_LENGTH))
                    .toString()
            )
        }

        private fun canSync(context: Context): Boolean =
            BluetoothSyncPrefs.boundAddresses(context).isNotEmpty() &&
                    ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED

        private fun broadcast(context: Context, message: String) {
            val service = instance
            if (service != null) {
                service.broadcastMessage(message)
                return
            }
            val intent = Intent(context, BluetoothSyncService::class.java)
                .setAction(ACTION_SEND)
                .putExtra(EXTRA_MESSAGE, message)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: android.app.ForegroundServiceStartNotAllowedException) {
                Log.e(TAG, "Unable to start Bluetooth sync from the background", e)
            }
        }
    }
}
