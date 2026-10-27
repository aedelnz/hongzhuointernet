package top.jixiejidiguan.hongzhuointernet

import android.app.Notification
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Report
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import top.jixiejidiguan.hongzhuointernet.ui.theme.HongzhuointernetTheme
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HongzhuointernetTheme {
                NotificationHome()
            }
        }
    }
}

/**
 * 通知数据模型
 */
data class AppNotification(
    val id: String,
    val packageName: String,
    val title: String,
    val text: String,
    val time: Long,
    val isMedia: Boolean = false,
    val artwork: Bitmap? = null,
    val actions: List<NotificationAction> = emptyList(),
    val sourceDevice: String? = null,
    val sourceAppName: String? = null
)

data class NotificationAction(
    val title: String,
    val pendingIntent: PendingIntent
)

/**
 * 全局共享的通知内存数据容器（供 NotificationListenerService 写入，UI 读取）
 */
object NotificationStore {
    val notifications = mutableStateListOf<AppNotification>()

    fun addNotification(notification: AppNotification) {
        upsertByTime(notification)
    }

    fun removeNotification(id: String) {
        notifications.removeIf { it.id == id }
    }

    fun addRemoteNotification(notification: AppNotification) {
        upsertByTime(notification)
    }

    fun clearLocalNotifications() {
        notifications.removeAll { it.sourceDevice == null }
    }

    private fun upsertByTime(notification: AppNotification) {
        notifications.removeAll { it.id == notification.id }
        val index = notifications.indexOfFirst { it.time <= notification.time }
        if (index == -1) {
            notifications.add(notification)
        } else {
            notifications.add(index, notification)
        }
    }
}

/**
 * 核心通知监听服务
 */
class HongZhuoNotificationListener : android.service.notification.NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        listenerInstance = this
        refreshActiveNotifications()
    }

    override fun onListenerDisconnected() {
        if (listenerInstance === this) listenerInstance = null
        super.onListenerDisconnected()
        mainHandler.postDelayed({
            if (isNotificationListenerEnabled(this)) {
                requestRebind(ComponentName(this, HongZhuoNotificationListener::class.java))
            }
        }, REBIND_DELAY_MILLIS)
    }

    override fun onDestroy() {
        if (listenerInstance === this) listenerInstance = null
        super.onDestroy()
    }

    private fun refreshActiveNotifications() {
        NotificationStore.clearLocalNotifications()
        try {
            activeNotifications
                ?.sortedByDescending { it.postTime }
                ?.forEach { processNotification(it, sync = false) }
        } catch (e: SecurityException) {
            android.util.Log.e("NotificationListener", "Unable to load active notifications", e)
        }
    }

    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn?.let { processNotification(it, sync = true) }
    }

    override fun onNotificationRemoved(sbn: android.service.notification.StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        sbn?.let {
            NotificationStore.removeNotification(it.key)
            if (it.packageName != packageName) {
                BluetoothSyncService.sendRemoval(this, it.key)
            }
        }
    }

    private fun processNotification(
        statusBarNotification: android.service.notification.StatusBarNotification,
        sync: Boolean
    ) {
        if (statusBarNotification.packageName == packageName) return
        val postedNotification = statusBarNotification.notification
        val extras = postedNotification.extras
        val isMedia = postedNotification.category == Notification.CATEGORY_TRANSPORT ||
                extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG)
            ?: postedNotification.tickerText
        val text = listOf(
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
            extras.getCharSequence(Notification.EXTRA_TEXT),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT)
        ).firstOrNull { !it.isNullOrBlank() }
        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.joinToString(separator = "\n") { it.toString() }
        val appName = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(statusBarNotification.packageName, 0)
            ).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            android.util.Log.w(
                "NotificationListener",
                "Could not resolve notification source app ${statusBarNotification.packageName}",
                e
            )
            statusBarNotification.packageName
        } catch (e: SecurityException) {
            android.util.Log.w(
                "NotificationListener",
                "Permission denied while resolving notification source app ${statusBarNotification.packageName}",
                e
            )
            statusBarNotification.packageName
        }
        val notification = AppNotification(
            id = statusBarNotification.key,
            packageName = statusBarNotification.packageName,
            title = title?.toString()?.takeIf { it.isNotBlank() } ?: appName,
            text = text?.toString()?.takeIf { it.isNotBlank() }
                ?: textLines.orEmpty(),
            time = statusBarNotification.postTime,
            isMedia = isMedia,
            artwork = if (isMedia) {
                extras.getParcelable(Notification.EXTRA_PICTURE, Bitmap::class.java)
            } else {
                null
            },
            actions = if (isMedia) {
                postedNotification.actions
                    ?.take(3)
                    ?.mapNotNull { action ->
                        action.actionIntent?.let { pendingIntent ->
                            NotificationAction(
                                title = action.title?.toString().orEmpty(),
                                pendingIntent = pendingIntent
                            )
                        }
                    }
                    .orEmpty()
            } else {
                emptyList()
            },
            sourceAppName = appName
        )
        NotificationStore.addNotification(notification)
        if (sync && statusBarNotification.packageName != packageName) {
            BluetoothSyncService.sendNotification(this, notification)
        }
    }

    companion object {
        private const val REBIND_DELAY_MILLIS = 1000L
        private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

        @Volatile
        private var listenerInstance: HongZhuoNotificationListener? = null

        fun refresh(context: Context) {
            if (!isNotificationListenerEnabled(context)) return
            val listener = listenerInstance
            if (listener != null) {
                listener.refreshActiveNotifications()
                return
            }
            try {
                requestRebind(ComponentName(context, HongZhuoNotificationListener::class.java))
            } catch (e: SecurityException) {
                android.util.Log.e(
                    "NotificationListener",
                    "Unable to rebind notification listener",
                    e
                )
            }
        }
    }
}

private enum class Destination(val title: String) {
    Home("通知列表"),
    Settings("设置"),
    About("关于")
}

private val bluetoothPermissions = arrayOf(
    android.Manifest.permission.BLUETOOTH_CONNECT
)
private val notificationPermissions = arrayOf(
    android.Manifest.permission.POST_NOTIFICATIONS
)

@Preview
@Composable
private fun NotificationHome() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var currentPage by remember { mutableStateOf(Destination.Home) }
    var isPermissionGranted by remember {
        mutableStateOf(isNotificationListenerEnabled(context))
    }
    var isBluetoothPermissionGranted by remember {
        mutableStateOf(hasBluetoothPermissions(context))
    }
    var isPostNotificationPermissionGranted by remember {
        mutableStateOf(hasPostNotificationPermission(context))
    }
    var pairedBluetoothDevices by remember {
        mutableStateOf(getPairedBluetoothDevices(context))
    }
    var boundBluetoothDeviceAddresses by remember {
        mutableStateOf(BluetoothSyncPrefs.boundAddresses(context))
    }
    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        isBluetoothPermissionGranted = hasBluetoothPermissions(context)
        pairedBluetoothDevices = getPairedBluetoothDevices(context)
        if (!isBluetoothPermissionGranted) {
            BluetoothSyncService.stop(context)
        }
    }
    val postNotificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        isPostNotificationPermissionGranted = hasPostNotificationPermission(context)
    }

    val snackbarHostState = remember { SnackbarHostState() }

    // 监听生命周期，当用户从系统设置返回时自动更新通知监听权限状态
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isPermissionGranted = isNotificationListenerEnabled(context)
                if (isPermissionGranted) {
                    HongZhuoNotificationListener.refresh(context)
                }
                isBluetoothPermissionGranted = hasBluetoothPermissions(context)
                isPostNotificationPermissionGranted = hasPostNotificationPermission(context)
                pairedBluetoothDevices = getPairedBluetoothDevices(context)
                val bluetoothEnabled = isBluetoothEnabled(context)
                val boundAddresses = BluetoothSyncPrefs.boundAddresses(context)
                boundBluetoothDeviceAddresses =
                    if (!bluetoothEnabled) boundAddresses else boundAddresses.intersect(
                        pairedBluetoothDevices.mapTo(mutableSetOf()) { it.address }
                    )
                (boundAddresses - boundBluetoothDeviceAddresses).forEach { address ->
                    BluetoothSyncPrefs.setBound(context, address, false)
                }
                if (isBluetoothPermissionGranted && boundBluetoothDeviceAddresses.isNotEmpty()) {
                    BluetoothSyncService.start(context)
                } else {
                    BluetoothSyncService.stop(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler(enabled = currentPage != Destination.Home) {
        currentPage = Destination.Home
    }

    val notifications = NotificationStore.notifications

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            MediumTopAppBar(
                title = { Text(currentPage.title) },
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                NavigationBarItem(
                    selected = currentPage == Destination.Home,
                    onClick = { currentPage = Destination.Home },
                    icon = { Icon(Icons.Rounded.Home, contentDescription = null) },
                    label = { Text("首页") }
                )
                NavigationBarItem(
                    selected = currentPage == Destination.Settings,
                    onClick = { currentPage = Destination.Settings },
                    icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                    label = { Text("设置") }
                )
                NavigationBarItem(
                    selected = currentPage == Destination.About,
                    onClick = { currentPage = Destination.About },
                    icon = { Icon(Icons.Rounded.Report, contentDescription = null) },
                    label = { Text("关于") }
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { contentPadding ->
        AnimatedContent(
            targetState = currentPage,
            modifier = Modifier.padding(contentPadding),
            transitionSpec = {
                if (targetState.ordinal > initialState.ordinal) {
                    (slideInHorizontally { it / 4 } + fadeIn()) togetherWith
                            (slideOutHorizontally { -it / 4 } + fadeOut())
                } else {
                    (slideInHorizontally { -it / 4 } + fadeIn()) togetherWith
                            (slideOutHorizontally { it / 4 } + fadeOut())
                }
            },
            label = "page-transition"
        ) { destination ->
            when (destination) {
                Destination.Home -> {
                    if (!isPermissionGranted) {
                        PermissionRequiredScreen(
                            onRequestPermission = {
                                openNotificationListenerSettings(context)
                            }
                        )
                    } else {
                        HomePage(notifications = notifications)
                    }
                }

                Destination.Settings -> SettingsPage(
                    isPermissionGranted = isPermissionGranted,
                    onTogglePermission = { openNotificationListenerSettings(context) },
                    isBluetoothPermissionGranted = isBluetoothPermissionGranted,
                    onBluetoothPermissionChange = { enabled ->
                        if (enabled) {
                            bluetoothPermissionLauncher.launch(bluetoothPermissions)
                        } else {
                            openAppPermissionSettings(context)
                        }
                    },
                    pairedBluetoothDevices = pairedBluetoothDevices,
                    boundBluetoothDeviceAddresses = boundBluetoothDeviceAddresses,
                    onToggleBluetoothDevice = { address ->
                        val shouldBind = address !in boundBluetoothDeviceAddresses
                        BluetoothSyncPrefs.setBound(context, address, shouldBind)
                        boundBluetoothDeviceAddresses = BluetoothSyncPrefs.boundAddresses(context)
                        if (boundBluetoothDeviceAddresses.isEmpty()) {
                            BluetoothSyncService.stop(context)
                        } else if (isBluetoothPermissionGranted) {
                            BluetoothSyncService.start(context)
                        }
                    },
                    onOpenBluetoothSettings = { openBluetoothSettings(context) },
                    isPostNotificationPermissionGranted = isPostNotificationPermissionGranted,
                    onRequestPostNotificationPermission = {
                        postNotificationPermissionLauncher.launch(notificationPermissions)
                    },
                    onSendCustomMessage = { title, text ->
                        BluetoothSyncService.sendCustomMessage(context, title, text)
                    }
                )

                Destination.About -> AboutPage()
            }
        }
    }
}

/**
 * 检查当前应用是否拥有“通知监听权限”
 */
private fun isNotificationListenerEnabled(context: Context): Boolean {
    val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(context)
    return enabledPackages.contains(context.packageName)
}

/**
 * 跳转到系统设置页面的“通知访问权限”列表
 */
private fun openNotificationListenerSettings(context: Context) {
    val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

private fun hasBluetoothPermissions(context: Context): Boolean =
    bluetoothPermissions.all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

private fun hasPostNotificationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        android.Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED

private fun getPairedBluetoothDevices(context: Context): List<BluetoothPeer> {
    if (!hasBluetoothPermissions(context)) return emptyList()
    return try {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null) {
            emptyList()
        } else {
            adapter.bondedDevices
                .map { device -> BluetoothPeer(device.address, device.name ?: device.address) }
                .sortedBy { it.name.lowercase() }
        }
    } catch (e: SecurityException) {
        android.util.Log.w("BluetoothSync", "Unable to read paired Bluetooth devices", e)
        emptyList()
    }
}

private fun isBluetoothEnabled(context: Context): Boolean {
    if (!hasBluetoothPermissions(context)) return false
    return try {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
            .adapter?.isEnabled == true
    } catch (e: SecurityException) {
        android.util.Log.w("BluetoothSync", "Unable to read Bluetooth adapter state", e)
        false
    }
}

private fun openBluetoothSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

private fun openAppPermissionSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

@Composable
private fun PermissionRequiredScreen(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier.size(88.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.errorContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.Notifications,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = "需要「通知访问权限」",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "为了能够实时捕获并展示手机收到的通知信息，请授予本应用通知监听权限。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequestPermission) {
            Text("前往系统设置授权")
        }
    }
}

@Composable
private fun HomePage(notifications: List<AppNotification>) {
    val localNotifications = notifications
        .filter { it.sourceDevice == null }
        .sortedByDescending { it.isMedia }
    val syncedNotifications = notifications
        .filter { it.sourceDevice != null }
        .groupBy { it.sourceDevice.orEmpty() }
        .toSortedMap()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 8.dp,
            end = 16.dp,
            bottom = 96.dp
        ),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        if (notifications.isEmpty()) {
            item(key = "empty") {
                EmptyNotificationState()
            }
        } else {
            if (localNotifications.isNotEmpty()) {
                item(key = "local-header") {
                    NotificationSectionHeader(
                        title = "本机通知",
                        count = localNotifications.size
                    )
                }
                itemsIndexed(localNotifications, key = { _, item -> item.id }) { index, item ->
                    NotificationListItem(item, index, localNotifications.size)
                }
            }
            syncedNotifications.forEach { (deviceName, deviceNotifications) ->
                val orderedDeviceNotifications =
                    deviceNotifications.sortedByDescending { it.isMedia }
                item(key = "synced-header-$deviceName") {
                    NotificationSectionHeader(
                        title = "同步设备 · $deviceName",
                        count = orderedDeviceNotifications.size
                    )
                }
                itemsIndexed(
                    orderedDeviceNotifications,
                    key = { _, item -> item.id }
                ) { index, item ->
                    NotificationListItem(item, index, orderedDeviceNotifications.size)
                }
            }
        }
    }
}

@Composable
private fun NotificationSectionHeader(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, top = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "$count 条",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EmptyNotificationState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 78.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier.size(88.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.Notifications,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = "暂无收到任何通知",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "当手机收到新通知时，将会实时显示在这里。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 从包名解析来源应用图标；失败时回退到 Notifications 图标
 */
@Composable
private fun AppIcon(packageName: String) {
    val context = LocalContext.current
    val bitmap = remember(packageName) {
        try {
            val bmp = when (val drawable = context.packageManager.getApplicationIcon(packageName)) {
                is BitmapDrawable -> drawable.bitmap
                else -> {
                    val w = drawable.intrinsicWidth.coerceAtLeast(1)
                    val h = drawable.intrinsicHeight.coerceAtLeast(1)
                    createBitmap(w, h).also {
                        val canvas = Canvas(it)
                        drawable.setBounds(0, 0, canvas.width, canvas.height)
                        drawable.draw(canvas)
                    }
                }
            }
            bmp.asImageBitmap()
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    Surface(
        modifier = Modifier.size(40.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.Notifications,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
private fun NotificationListItem(
    notification: AppNotification,
    index: Int,
    itemCount: Int
) {
    val top = if (index == 0) 28.dp else 8.dp
    val bottom = if (index == itemCount - 1) 28.dp else 8.dp
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale { },
        shape = RoundedCornerShape(
            topStart = top,
            topEnd = top,
            bottomStart = bottom,
            bottomEnd = bottom
        ),
        color = if (notification.isMedia) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        }
    ) {
        if (notification.isMedia) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (notification.artwork != null) {
                        Image(
                            bitmap = notification.artwork.asImageBitmap(),
                            contentDescription = "专辑封面",
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(12.dp))
                        )
                    } else {
                        AppIcon(packageName = notification.packageName)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = notification.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 1
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = notification.text.ifBlank { notification.packageName },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 1
                        )
                        Text(
                            text = notification.sourceDevice?.let { "来自 $it · 蓝牙同步" }
                                ?: "媒体播放器",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = DateFormat.getTimeInstance(DateFormat.SHORT)
                                .format(Date(notification.time)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                if (notification.actions.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        notification.actions.forEach { action ->
                            androidx.compose.material3.TextButton(
                                onClick = {
                                    try {
                                        action.pendingIntent.send()
                                    } catch (e: PendingIntent.CanceledException) {
                                        android.util.Log.w(
                                            "HongZhuoNotification",
                                            "Music notification action is no longer available",
                                            e
                                        )
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(action.title.ifBlank { "控制" }, maxLines = 1)
                            }
                        }
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppIcon(packageName = notification.packageName)
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = notification.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = notification.text.ifBlank { "无正文内容" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = notification.sourceDevice?.let {
                            "来自 $it · ${notification.sourceAppName ?: notification.packageName}"
                        } ?: "来源: ${notification.sourceAppName ?: notification.packageName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1
                    )
                    Text(
                        text = DateFormat.getTimeInstance(DateFormat.SHORT)
                            .format(Date(notification.time)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsPage(
    isPermissionGranted: Boolean,
    onTogglePermission: () -> Unit,
    isBluetoothPermissionGranted: Boolean,
    onBluetoothPermissionChange: (Boolean) -> Unit,
    pairedBluetoothDevices: List<BluetoothPeer>,
    boundBluetoothDeviceAddresses: Set<String>,
    onToggleBluetoothDevice: (String) -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    isPostNotificationPermissionGranted: Boolean,
    onRequestPostNotificationPermission: () -> Unit,
    onSendCustomMessage: (String, String) -> Unit
) {
    var isSendDialogOpen by remember { mutableStateOf(false) }
    var messageTitle by remember { mutableStateOf("") }
    var messageText by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        item {
            Text(
                text = "权限",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
            )
        }
        item {
            ListItem(
                supportingContent = {
                    Text(
                        if (isPermissionGranted) "已授权，可实时捕获通知"
                        else "未授权，无法捕获通知"
                    )
                },
                trailingContent = {
                    Switch(
                        checked = isPermissionGranted,
                        onCheckedChange = { onTogglePermission() }
                    )
                },
                colors = ListItemDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Text("通知访问权限")
            }
        }
        item {
            ListItem(
                supportingContent = {
                    Text(
                        if (isBluetoothPermissionGranted) "已授权，可连接已配对的设备"
                        else "未授权，点击开关申请蓝牙连接权限"
                    )
                },
                trailingContent = {
                    Switch(
                        checked = isBluetoothPermissionGranted,
                        onCheckedChange = onBluetoothPermissionChange
                    )
                },
                colors = ListItemDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Text("蓝牙权限")
            }
        }
        item {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
            Text(
                text = "设备绑定",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
            )
        }
        item {
            Button(
                onClick = onOpenBluetoothSettings,
                modifier = Modifier.fillMaxWidth(),
                enabled = isBluetoothPermissionGranted
            ) {
                Text("前往系统蓝牙配对设备")
            }
        }
        if (pairedBluetoothDevices.isEmpty()) {
            item {
                Text(
                    text = "没有可用的已配对设备。请先在系统蓝牙设置中完成配对。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        } else {
            items(pairedBluetoothDevices, key = { it.address }) { device ->
                val isBound = device.address in boundBluetoothDeviceAddresses
                ListItem(
                    supportingContent = {
                        Text(if (isBound) "已绑定，通知将与此设备双向同步" else "点击绑定此设备")
                    },
                    trailingContent = {
                        if (isBound) Text("已绑定", color = MaterialTheme.colorScheme.primary)
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = isBluetoothPermissionGranted) {
                            onToggleBluetoothDevice(device.address)
                        }
                ) {
                    Text(device.name)
                }
            }
        }
        if (boundBluetoothDeviceAddresses.isNotEmpty()) {
            item {
                Button(
                    onClick = { isSendDialogOpen = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = isBluetoothPermissionGranted
                ) {
                    Text("发送通知栏消息")
                }
            }
            item {
                Text(
                    text = "已绑定 ${boundBluetoothDeviceAddresses.size} 台设备；发送的消息会广播给全部绑定设备。对端需允许发布通知，才能在其通知栏看到消息。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }
        item {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
            Text(
                text = "双方需安装应用、完成系统蓝牙配对，并在此互相绑定。通知数据仅在应用运行时捕获并保存在内存中；同步内容包含通知文字和应用标识，不包含媒体封面或播放控制。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }

    if (isSendDialogOpen) {
        AlertDialog(
            onDismissRequest = { isSendDialogOpen = false },
            title = { Text("发送通知栏消息") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = messageTitle,
                        onValueChange = { messageTitle = it },
                        label = { Text("标题") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = messageText,
                        onValueChange = { messageText = it },
                        label = { Text("消息内容") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (!isPostNotificationPermissionGranted) {
                        TextButton(onClick = onRequestPostNotificationPermission) {
                            Text("授予本机接收通知权限")
                        }
                        Text(
                            text = "消息会发送到已绑定设备。接收设备需授予通知权限，才能在其通知栏显示。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = messageText.isNotBlank(),
                    onClick = {
                        onSendCustomMessage(
                            messageTitle.ifBlank { "来自同步设备的消息" },
                            messageText.trim()
                        )
                        messageTitle = ""
                        messageText = ""
                        isSendDialogOpen = false
                    }
                ) {
                    Text("发送")
                }
            },
            dismissButton = {
                TextButton(onClick = { isSendDialogOpen = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun AboutPage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ListItem(
            supportingContent = {
                Text("实时监听并展示设备上的各类应用通知。")
            },
            leadingContent = {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.Notifications,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            },
            colors = ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Text("通知列表")
        }
        Text(
            text = "版本 1.0",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp)
        )
    }
}

@Composable
private fun Modifier.pressScale(onClick: () -> Unit): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        label = "press-scale"
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }.clickable(
        interactionSource = interactionSource,
        indication = ripple(),
        onClick = onClick
    )
}