package com.example.btpriority

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.btpriority.ui.theme.BtPriorityTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class AppTab {
    DEVICES,
    ROUTINES
}

data class PairedDeviceUiState(
    val device: BluetoothDevice,
    val name: String,
    val macAddress: String,
    val isConnected: Boolean,
    val isAllowed: Boolean
)

data class AvailableDeviceUiState(
    val device: BluetoothDevice,
    val name: String,
    val macAddress: String,
    val bondState: Int = BluetoothDevice.BOND_NONE,
    val lastSeenTimestamp: Long = System.currentTimeMillis()
)

class MainActivity : ComponentActivity() {

    private var hasPermissions by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val connectGranted = permissions[Manifest.permission.BLUETOOTH_CONNECT] ?: true
        val scanGranted = permissions[Manifest.permission.BLUETOOTH_SCAN] ?: true
        hasPermissions = connectGranted && scanGranted
        Log.d("BtPriority", "Bluetooth permissions granted = $hasPermissions (no location required)")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ConnectedDeviceTracker.syncState(this)
        checkAndRequestPermissions()

        setContent {
            BtPriorityTheme {
                BluetoothScreen(
                    hasPermissions = hasPermissions,
                    onRequestPermissions = { checkAndRequestPermissions(forceRequest = true) },
                    context = this
                )
            }
        }
    }

    private fun checkAndRequestPermissions(forceRequest: Boolean = false) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val connect = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            val scan = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED

            hasPermissions = connect && scan

            if (!hasPermissions || forceRequest) {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN
                    )
                )
            }
        } else {
            hasPermissions = true
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            val bm = getSystemService(BluetoothManager::class.java)
            if (bm?.adapter?.isDiscovering == true) {
                bm.adapter.cancelDiscovery()
            }
        } catch (_: Exception) {}
    }
}

@SuppressLint("MissingPermission")
private fun queryBondedDevices(
    context: Context,
    blockManager: DeviceBlockManager
): Pair<List<PairedDeviceUiState>, List<PairedDeviceUiState>> {
    val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    val bondedDevices = bluetoothManager?.adapter?.bondedDevices ?: return Pair(emptyList(), emptyList())

    val connectedList = mutableListOf<PairedDeviceUiState>()
    val disconnectedList = mutableListOf<PairedDeviceUiState>()

    for (device in bondedDevices) {
        val isBlocked = blockManager.isBlocked(device.address)
        val isActuallyConn = BluetoothHelper.isDeviceConnected(device)

        // If user blocked it but it somehow reconnected, enforce the block.
        // Rate-limit to once every 5s per device to avoid hammering the BT stack on every 8s poll.
        if (isBlocked && isActuallyConn) {
            val now = System.currentTimeMillis()
            val lastAttempt = disconnectThrottleMap[device.address] ?: 0L
            if (now - lastAttempt > 5000L) {
                disconnectThrottleMap[device.address] = now
                BluetoothHelper.disconnectDevice(context, device)
            }
        }

        // When toggled off (blocked), the device is never displayed as active/connected
        val isConn = !isBlocked && isActuallyConn
        val isAllowed = !isBlocked

        val name = try {
            device.name?.ifBlank { "Unknown Device" } ?: "Unknown Device"
        } catch (_: SecurityException) {
            "Unknown Device"
        }

        val state = PairedDeviceUiState(
            device = device,
            name = name,
            macAddress = device.address,
            isConnected = isConn,
            isAllowed = isAllowed
        )

        if (isConn) {
            connectedList.add(state)
        } else {
            disconnectedList.add(state)
        }
    }

    return Pair(
        connectedList.sortedBy { it.name.lowercase() },
        disconnectedList.sortedBy { it.name.lowercase() }
    )
}

// Per-MAC rate-limiter for blocked-device disconnect enforcement in queryBondedDevices
private val disconnectThrottleMap = mutableMapOf<String, Long>()

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
fun BluetoothScreen(
    hasPermissions: Boolean,
    onRequestPermissions: () -> Unit,
    context: Context
) {
    val blockManager = remember { DeviceBlockManager(context) }
    val profileManager = remember { BluetoothHelper.getManager(context) }
    val bluetoothAdapter = remember { profileManager.adapter }
    val coroutineScope = rememberCoroutineScope()
    var scanTimeoutJob by remember { mutableStateOf<Job?>(null) }
    var selectedTab by remember { mutableStateOf(AppTab.DEVICES) }

    var connectedDevices by remember { mutableStateOf<List<PairedDeviceUiState>>(emptyList()) }
    var pairedDevices by remember { mutableStateOf<List<PairedDeviceUiState>>(emptyList()) }
    val availableDevices = remember { mutableStateMapOf<String, AvailableDeviceUiState>() }
    var isScanning by remember { mutableStateOf(false) }
    val toggleDebounceMap = remember { mutableStateMapOf<String, Long>() }
    val connectingDevices = remember { mutableStateMapOf<String, Boolean>() }

    val leScanCallback = remember {
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                if (device.bondState == BluetoothDevice.BOND_BONDED) return
                if (blockManager.isBlocked(device.address)) return

                // Strictly ignore unnamed devices / anonymous beacons
                val recordName = result.scanRecord?.deviceName?.takeIf { it.isNotBlank() }
                val devName = try { device.name?.takeIf { it.isNotBlank() } } catch (_: SecurityException) { null }
                val name = recordName ?: devName ?: return

                val address = device.address
                availableDevices[address] = AvailableDeviceUiState(
                    device = device,
                    name = name,
                    macAddress = address,
                    bondState = device.bondState,
                    lastSeenTimestamp = System.currentTimeMillis()
                )
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                for (result in results) {
                    onScanResult(0, result)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.d("BtPriority", "BLE scan failed with code: $errorCode")
            }
        }
    }

    fun refreshLists() {
        if (!hasPermissions) return
        val (connected, paired) = queryBondedDevices(context, blockManager)
        connectedDevices = connected
        pairedDevices = paired
        ConnectedDeviceTracker.syncState(context)
        BouncerAppWidgetProvider.updateAllWidgets(context)
    }

    fun stopScan() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        isScanning = false
        try {
            if (bluetoothAdapter?.isDiscovering == true) {
                bluetoothAdapter.cancelDiscovery()
            }
        } catch (_: Exception) {}
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(leScanCallback)
        } catch (_: Exception) {}
    }

    fun startScan() {
        if (!hasPermissions) return
        stopScan()
        availableDevices.clear()
        isScanning = true

        // 1. Classic discovery (BR/EDR)
        try {
            bluetoothAdapter?.startDiscovery()
        } catch (e: Exception) {
            Log.d("BtPriority", "Error starting classic discovery: ${e.message}")
        }

        // 2. BLE low-latency scanning
        try {
            val leScanner = bluetoothAdapter?.bluetoothLeScanner
            if (leScanner != null) {
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setReportDelay(0)
                    .build()
                leScanner.startScan(null, settings, leScanCallback)
            }
        } catch (e: Exception) {
            Log.d("BtPriority", "Error starting BLE scan: ${e.message}")
        }

        // Auto-stop after 12s to save power (standard Bluetooth discovery window)
        scanTimeoutJob = coroutineScope.launch {
            delay(12000L)
            stopScan()
        }
    }

    // Periodic poll every 8s as fallback (real-time changes handled by BroadcastReceiver)
    LaunchedEffect(hasPermissions) {
        while (isActive) {
            if (hasPermissions) {
                refreshLists()
            }
            delay(8000L)
        }
    }

    // Dynamic pruning: remove devices that stopped advertising (turned off / closed case / moved away)
    LaunchedEffect(isScanning) {
        while (isScanning && isActive) {
            delay(1500L)
            val now = System.currentTimeMillis()
            val staleCutoff = now - 6000L // 6s without advertisement = turned off or closed
            val staleKeys = availableDevices.filterValues { it.lastSeenTimestamp < staleCutoff }.keys
            if (staleKeys.isNotEmpty()) {
                staleKeys.forEach { availableDevices.remove(it) }
            }
        }
    }

    // Receiver for Bluetooth broadcasts
    DisposableEffect(hasPermissions) {
        profileManager.bindProxies()
        refreshLists()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val action = intent.action ?: return
                when {
                    action == BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                        if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                            stopScan()
                            availableDevices.clear()
                            refreshLists()
                        } else if (state == BluetoothAdapter.STATE_ON) {
                            profileManager.bindProxies()
                            refreshLists()
                        }
                    }
                    action == BluetoothDevice.ACTION_FOUND -> {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                        if (device != null && device.bondState != BluetoothDevice.BOND_BONDED && !blockManager.isBlocked(device.address)) {
                            val extraName = intent.getStringExtra(BluetoothDevice.EXTRA_NAME)?.takeIf { it.isNotBlank() }
                            val devName = try { device.name?.takeIf { it.isNotBlank() } } catch (_: SecurityException) { null }
                            val name = extraName ?: devName
                            if (name != null) {
                                availableDevices[device.address] = AvailableDeviceUiState(
                                    device = device,
                                    name = name,
                                    macAddress = device.address,
                                    bondState = device.bondState,
                                    lastSeenTimestamp = System.currentTimeMillis()
                                )
                            }
                        }
                    }
                    action == BluetoothDevice.ACTION_NAME_CHANGED -> {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                        val extraName = intent.getStringExtra(BluetoothDevice.EXTRA_NAME)?.takeIf { it.isNotBlank() }
                        val devName = try { device?.name?.takeIf { it.isNotBlank() } } catch (_: SecurityException) { null }
                        val name = extraName ?: devName
                        if (device != null && name != null && device.bondState != BluetoothDevice.BOND_BONDED && !blockManager.isBlocked(device.address)) {
                            availableDevices[device.address] = AvailableDeviceUiState(
                                device = device,
                                name = name,
                                macAddress = device.address,
                                bondState = device.bondState,
                                lastSeenTimestamp = System.currentTimeMillis()
                            )
                        }
                    }
                    action == BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                        isScanning = true
                    }
                    action == BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        if (scanTimeoutJob == null || scanTimeoutJob?.isActive == false) {
                            isScanning = false
                        }
                    }
                    action == BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                        if (device != null && device.bondState == BluetoothDevice.BOND_BONDED) {
                            availableDevices.remove(device.address)
                        }
                        refreshLists()
                    }
                    action == BluetoothDevice.ACTION_ACL_CONNECTED ||
                    action == BluetoothDevice.ACTION_ACL_DISCONNECTED ||
                    action == BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED ||
                    action.contains("CONNECTION_STATE_CHANGED") -> {
                        refreshLists()
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_NAME_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction("android.bluetooth.pan.profile.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.hidhost.profile.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }

        onDispose {
            stopScan()
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {}
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFF152A20),
                            modifier = Modifier.size(42.dp),
                            border = BorderStroke(1.2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.8f))
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_bt_bouncer),
                                contentDescription = "BT Bouncer Logo",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(2.dp),
                                contentScale = ContentScale.Fit
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "BT Bouncer",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1
                            )
                            Text(
                                text = "VIP access only",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 11.sp,
                                maxLines = 1
                            )
                            Text(
                                text = "Choose who gets in",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                                maxLines = 1
                            )
                        }
                    }
                },
                actions = {
                    if (selectedTab == AppTab.DEVICES && hasPermissions) {
                        if (isScanning) {
                            OutlinedButton(
                                onClick = { stopScan() },
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier.padding(end = 8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(12.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Stop", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        } else {
                            Button(
                                onClick = { startScan() },
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier.padding(end = 8.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
                            ) {
                                Text("Scan", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == AppTab.DEVICES,
                    onClick = { selectedTab = AppTab.DEVICES },
                    icon = { Icon(Icons.Default.Bluetooth, contentDescription = "Devices") },
                    label = { Text("Devices", fontWeight = if (selectedTab == AppTab.DEVICES) FontWeight.Bold else FontWeight.Normal) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primary
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == AppTab.ROUTINES,
                    onClick = {
                        stopScan()
                        selectedTab = AppTab.ROUTINES
                    },
                    icon = { Icon(Icons.Default.AutoMode, contentDescription = "Routines") },
                    label = { Text("Routines", fontWeight = if (selectedTab == AppTab.ROUTINES) FontWeight.Bold else FontWeight.Normal) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (selectedTab) {
                AppTab.DEVICES -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 18.dp)
                    ) {
            if (!hasPermissions) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(
                            text = "Bluetooth Permission Required",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "This app requires Bluetooth permissions (BLUETOOTH_CONNECT & BLUETOOTH_SCAN) without requesting your location.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = onRequestPermissions,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Grant Permission")
                        }
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 10.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // SECTION 1: CONNECTED DEVICES
                item {
                    SectionHeader(
                        title = "Connected Devices",
                        count = connectedDevices.size,
                        badgeColor = MaterialTheme.colorScheme.primary
                    )
                }

                if (connectedDevices.isEmpty()) {
                    item {
                        EmptySectionText("No connected devices currently.")
                    }
                } else {
                    items(connectedDevices, key = { "conn_${it.macAddress}" }) { item ->
                        ConnectedDeviceCard(
                            item = item,
                            onToggleChanged = { isTurnedOn ->
                                val now = System.currentTimeMillis()
                                val lastTime = toggleDebounceMap[item.macAddress] ?: 0L
                                if (now - lastTime < 500L) return@ConnectedDeviceCard
                                toggleDebounceMap[item.macAddress] = now

                                if (!isTurnedOn) {
                                    // User turned OFF: block device, disconnect profiles without unpairing
                                    blockManager.setBlocked(item.macAddress, true)
                                    BluetoothHelper.disconnectDevice(context, item.device) {
                                        refreshLists()
                                    }
                                    refreshLists()
                                } else {
                                    blockManager.setBlocked(item.macAddress, false)
                                    refreshLists()
                                }
                            },
                            onUnpairClick = {
                                blockManager.setBlocked(item.macAddress, false)
                                BluetoothHelper.disconnectDevice(context, item.device)
                                BluetoothHelper.unpairDevice(context, item.device)
                                refreshLists()
                            }
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(12.dp)) }

                // SECTION 2: PAIRED DEVICES (DISCONNECTED / BLOCKED)
                item {
                    SectionHeader(
                        title = "Paired Devices",
                        count = pairedDevices.size,
                        badgeColor = MaterialTheme.colorScheme.secondary
                    )
                }

                if (pairedDevices.isEmpty()) {
                    item {
                        EmptySectionText("No other paired devices.")
                    }
                } else {
                    items(pairedDevices, key = { "paired_${it.macAddress}" }) { item ->
                        val isConnecting = connectingDevices[item.macAddress] == true
                        PairedDeviceCard(
                            item = item,
                            isConnecting = isConnecting,
                            onToggleChanged = { isTurnedOn ->
                                val now = System.currentTimeMillis()
                                val lastTime = toggleDebounceMap[item.macAddress] ?: 0L
                                if (now - lastTime < 500L) return@PairedDeviceCard
                                toggleDebounceMap[item.macAddress] = now

                                if (isTurnedOn) {
                                    // Turn ON: Allow device and attempt reconnecting
                                    blockManager.setBlocked(item.macAddress, false)
                                    BluetoothHelper.connectDevice(context, item.device)
                                    refreshLists()
                                } else {
                                    // Turn OFF: Block device and disconnect without unpairing
                                    blockManager.setBlocked(item.macAddress, true)
                                    BluetoothHelper.disconnectDevice(context, item.device) {
                                        refreshLists()
                                    }
                                    refreshLists()
                                }
                            },
                            onConnectClick = {
                                if (connectingDevices[item.macAddress] == true) return@PairedDeviceCard
                                connectingDevices[item.macAddress] = true
                                blockManager.setBlocked(item.macAddress, false)
                                BluetoothHelper.connectDevice(context, item.device)
                                refreshLists()
                                coroutineScope.launch {
                                    delay(3500L)
                                    connectingDevices.remove(item.macAddress)
                                    val isConn = BluetoothHelper.isDeviceConnected(item.device)
                                    if (!isConn) {
                                        snackbarHostState.showSnackbar(
                                            message = "${item.name} could not be reached (may be offline or out of range)",
                                            duration = SnackbarDuration.Short
                                        )
                                    }
                                    refreshLists()
                                }
                            },
                            onUnpairClick = {
                                blockManager.setBlocked(item.macAddress, false)
                                BluetoothHelper.disconnectDevice(context, item.device)
                                BluetoothHelper.unpairDevice(context, item.device)
                                refreshLists()
                            }
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(12.dp)) }

                // SECTION 3: AVAILABLE DEVICES (DISCOVERED NEARBY)
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        SectionHeader(
                            title = "Available Devices",
                            count = availableDevices.size,
                            badgeColor = MaterialTheme.colorScheme.tertiary
                        )
                        if (isScanning) {
                            Text(
                                text = "Scanning nearby...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                val availableList = availableDevices.values.toList().sortedBy { it.name.lowercase() }
                if (availableList.isEmpty()) {
                    item {
                        EmptySectionText(
                            if (isScanning) "Searching for nearby Bluetooth devices (no location used)..."
                            else "No available devices found. Tap 'Scan' to discover nearby devices."
                        )
                    }
                } else {
                    items(availableList, key = { "avail_${it.macAddress}" }) { item ->
                        AvailableDeviceCard(
                            item = item,
                            onPairClick = {
                                BluetoothHelper.pairDevice(context, item.device)
                                refreshLists()
                            }
                        )
                    }
                }
            }
        }
    }
    AppTab.ROUTINES -> {
        RoutinesScreen(
            context = context,
            onRoutineChanged = {
                refreshLists()
            },
            onShowMessage = { message ->
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(
                        message = message,
                        duration = SnackbarDuration.Short
                    )
                }
            }
        )
    }
}
}
}
}

@Composable
fun SectionHeader(title: String, count: Int, badgeColor: Color) {
    Row(
        modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.width(10.dp))
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = badgeColor.copy(alpha = 0.18f),
            modifier = Modifier.padding(vertical = 2.dp)
        ) {
            Text(
                text = "$count",
                style = MaterialTheme.typography.labelSmall,
                color = badgeColor,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
            )
        }
    }
}

@Composable
fun EmptySectionText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp)
    )
}

@Composable
fun ConnectedDeviceCard(
    item: PairedDeviceUiState,
    onToggleChanged: (Boolean) -> Unit,
    onUnpairClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        border = BorderStroke(1.2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = item.macAddress,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primary
                ) {
                    Text(
                        text = "CONNECTED",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Action row: Unpair on left, Active Switch on right
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OutlinedButton(
                    onClick = onUnpairClick,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f))
                ) {
                    Text("Unpair", fontSize = 12.sp)
                }

                Switch(
                    checked = item.isAllowed,
                    onCheckedChange = onToggleChanged,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        }
    }
}

@Composable
fun PairedDeviceCard(
    item: PairedDeviceUiState,
    isConnecting: Boolean = false,
    onToggleChanged: (Boolean) -> Unit,
    onConnectClick: () -> Unit,
    onUnpairClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (item.isAllowed) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
            }
        ),
        border = BorderStroke(
            1.dp,
            if (item.isAllowed) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.error.copy(alpha = 0.35f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (item.isAllowed) "Allowed • Disconnected" else "Blocked • Connection Disallowed",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.isAllowed) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        fontWeight = if (item.isAllowed) FontWeight.Normal else FontWeight.SemiBold
                    )
                    Text(
                        text = item.macAddress,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Switch(
                    checked = item.isAllowed,
                    onCheckedChange = onToggleChanged,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surface
                    )
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Action row: Connect & Unpair buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (item.isAllowed) {
                    FilledTonalButton(
                        onClick = onConnectClick,
                        enabled = !isConnecting,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 10.dp)
                    ) {
                        if (isConnecting) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Connecting...", fontSize = 13.sp)
                            }
                        } else {
                            Text("Connect", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                OutlinedButton(
                    onClick = onUnpairClick,
                    shape = RoundedCornerShape(12.dp),
                    modifier = if (item.isAllowed) Modifier.weight(0.7f) else Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f))
                ) {
                    Text("Unpair", fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
fun AvailableDeviceCard(
    item: AvailableDeviceUiState,
    onPairClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.macAddress,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Button(
                onClick = onPairClick,
                enabled = item.bondState != BluetoothDevice.BOND_BONDING,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)
            ) {
                Text(
                    text = if (item.bondState == BluetoothDevice.BOND_BONDING) "Pairing..." else "Pair",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

