package top.jixiejidiguan.hongzhuointernet

import android.app.Notification
import android.app.PendingIntent
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Report
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
    val actions: List<NotificationAction> = emptyList()
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
        // 避免重复或直接在头部插入最新通知
        notifications.removeIf { it.id == notification.id }
        notifications.add(0, notification)
    }

    fun removeNotification(id: String) {
        notifications.removeIf { it.id == id }
    }
}

/**
 * 核心通知监听服务
 */
class HongZhuoNotificationListener : android.service.notification.NotificationListenerService() {
    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn?.let {
            val pkg = it.packageName
            val postedNotification = it.notification
            val extras = postedNotification.extras
            val isMedia = postedNotification.category == Notification.CATEGORY_TRANSPORT ||
                    extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "新通知"
            val text = if (isMedia) {
                extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                    ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
            } else {
                extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            }

            val notification = AppNotification(
                id = it.key,
                packageName = pkg,
                title = title,
                text = text,
                time = it.postTime,
                isMedia = isMedia,
                artwork = if (isMedia) {
                   extras.getParcelable(Notification.EXTRA_PICTURE, Bitmap::class.java)
                } else {
                    null
                },
                actions = if (isMedia) {
                    postedNotification.actions
                        ?.take(3)
                        ?.map { action ->
                            NotificationAction(
                                title = action.title?.toString().orEmpty(),
                                pendingIntent = action.actionIntent
                            )
                        }
                        .orEmpty()
                } else {
                    emptyList()
                }
            )
            NotificationStore.addNotification(notification)
        }
    }

    override fun onNotificationRemoved(sbn: android.service.notification.StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        sbn?.let {
            NotificationStore.removeNotification(it.key)
        }
    }
}

private enum class Destination(val title: String) {
    Home("通知列表"),
    Settings("设置"),
    About("关于")
}

private val bluetoothPermissions = arrayOf(
    android.Manifest.permission.BLUETOOTH_SCAN,
    android.Manifest.permission.BLUETOOTH_CONNECT,
    android.Manifest.permission.BLUETOOTH_ADVERTISE
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
    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        isBluetoothPermissionGranted = hasBluetoothPermissions(context)
    }

    val snackbarHostState = remember { SnackbarHostState() }

    // 监听生命周期，当用户从系统设置返回时自动更新通知监听权限状态
    LaunchedEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isPermissionGranted = isNotificationListenerEnabled(context)
                isBluetoothPermissionGranted = hasBluetoothPermissions(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
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
    val orderedNotifications = notifications.sortedByDescending { it.isMedia }
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
        if (orderedNotifications.isEmpty()) {
            item(key = "empty") {
                EmptyNotificationState()
            }
        } else {
            itemsIndexed(orderedNotifications, key = { _, item -> item.id }) { index, item ->
                NotificationListItem(
                    notification = item,
                    index = index,
                    itemCount = orderedNotifications.size
                )
            }
        }
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
                            text = "媒体播放器",
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
                        text = "来源: ${notification.packageName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1
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
    onBluetoothPermissionChange: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = "权限",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
        )
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
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(88.dp)
        ) {
            Text("通知访问权限")
        }
        ListItem(
            supportingContent = {
                Text(
                    if (isBluetoothPermissionGranted) "已授权，可使用蓝牙进行设备通信"
                    else "未授权，点击开关申请蓝牙权限"
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
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(88.dp)
        ) {
            Text("蓝牙权限")
        }
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )
        Text(
            text = "通知数据仅在应用运行时实时捕获并保存在内存中。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
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