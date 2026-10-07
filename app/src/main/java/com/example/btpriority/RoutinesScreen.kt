package com.example.btpriority

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.btpriority.data.AppDatabase
import com.example.btpriority.data.RoutineRuleEntity
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
fun RoutinesScreen(
    context: Context,
    onRoutineChanged: () -> Unit,
    onShowMessage: (String) -> Unit
) {
    val database = remember { AppDatabase.getDatabase(context) }
    val dao = remember { database.routineRuleDao() }
    val rules by dao.getAllRulesFlow().collectAsState(initial = emptyList())
    val connectedAddresses by ConnectedDeviceTracker.connectedFlow.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    var showAddDialog by remember { mutableStateOf(false) }
    var routineToEdit by remember { mutableStateOf<RoutineRuleEntity?>(null) }

    val bluetoothManager = remember { context.getSystemService(BluetoothManager::class.java) }
    val bondedDevices = remember(showAddDialog, routineToEdit) {
        bluetoothManager?.adapter?.bondedDevices?.toList()?.sortedBy {
            try { it.name?.lowercase() ?: "unknown" } catch (_: Exception) { "unknown" }
        } ?: emptyList()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .fillMaxSize()
            .padding(horizontal = 18.dp)
    ) {
        // Top Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = "Rule-Based Routines",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Multi-device auto-disconnect & map location automation",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }

            Button(
                onClick = { showAddDialog = true },
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Rule", modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Add Rule", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        // Summary Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val enabledCount = rules.count { it.isEnabled }
                val activeCount = rules.count { rule ->
                    if (!rule.isEnabled) return@count false
                    val triggerList = rule.getTriggerList()
                    val isTriggerConnected = triggerList.any { connectedAddresses.contains(it) }
                    isTriggerConnected || (rule.hasLocationCondition && triggerList.isEmpty())
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.AltRoute,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Total Rules: ${rules.size}",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = "$enabledCount Enabled",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    if (activeCount > 0) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = "$activeCount Active",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        // Rules List or Empty State
        if (rules.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(0.95f),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                ) {
                    Column(
                        modifier = Modifier.padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(60.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Block,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No Routines Created",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Create rules like 'If headphones connect, disconnect car & speaker' or pick a place on the map to block devices.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 20.sp
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = { showAddDialog = true },
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Create First Rule", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(rules, key = { it.id }) { rule ->
                    val triggerList = rule.getTriggerList()
                    val isTriggerConnected = triggerList.any { connectedAddresses.contains(it) }

                    RoutineRuleCard(
                        rule = rule,
                        isTriggerConnected = isTriggerConnected,
                        onEdit = {
                            routineToEdit = rule
                        },
                        onToggle = { isEnabled ->
                            coroutineScope.launch {
                                dao.setRuleEnabled(rule.id, isEnabled)
                                onRoutineChanged()
                                if (isEnabled) {
                                    RoutineRuleEvaluator.evaluateAllRules(context)
                                }
                            }
                        },
                        onDelete = {
                            coroutineScope.launch {
                                dao.deleteRuleById(rule.id)
                                onRoutineChanged()
                                onShowMessage("Routine '${rule.ruleName.ifBlank { "Routine" }}' deleted")
                            }
                        }
                    )
                }
            }
        }
    }

    // Dialog for Adding a New Rule
    if (showAddDialog) {
        AddOrEditRuleDialog(
            context = context,
            existingRule = null,
            bondedDevices = bondedDevices,
            onDismiss = { showAddDialog = false },
            onSave = { newRule ->
                coroutineScope.launch {
                    dao.insertRule(newRule)
                    showAddDialog = false
                    onRoutineChanged()
                    onShowMessage("Routine created successfully!")
                    RoutineRuleEvaluator.evaluateAllRules(context)
                }
            }
        )
    }

    // Dialog for Editing an Existing Rule
    if (routineToEdit != null) {
        AddOrEditRuleDialog(
            context = context,
            existingRule = routineToEdit,
            bondedDevices = bondedDevices,
            onDismiss = { routineToEdit = null },
            onSave = { updatedRule ->
                coroutineScope.launch {
                    dao.insertRule(updatedRule)
                    routineToEdit = null
                    onRoutineChanged()
                    onShowMessage("Routine updated successfully!")
                    RoutineRuleEvaluator.evaluateAllRules(context)
                }
            }
        )
    }
}

@Composable
fun RoutineRuleCard(
    rule: RoutineRuleEntity,
    isTriggerConnected: Boolean,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    val isActive = rule.isEnabled && (isTriggerConnected || (rule.hasLocationCondition && rule.getTriggerList().isEmpty()))
    val triggerNames = rule.getTriggerNameList()
    val blockedNames = rule.getBlockedNameList()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            } else if (rule.isEnabled) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            }
        ),
        border = BorderStroke(
            1.2.dp,
            if (isActive) MaterialTheme.colorScheme.primary
            else if (rule.isEnabled) MaterialTheme.colorScheme.outlineVariant
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: Name, Status badge, Edit, Delete, Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = rule.ruleName.ifBlank { "Auto-Disconnect Routine" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (isActive) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primary
                            ) {
                                Text(
                                    text = "ACTIVE",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        } else if (rule.isEnabled) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = "STANDBY",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        } else {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
                            ) {
                                Text(
                                    text = "DISABLED",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        if (rule.hasLocationCondition && rule.locationName != null) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Place,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = "${rule.locationName} (±${rule.radiusMeters.toInt()}m)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit Routine",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Rule",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    Switch(
                        checked = rule.isEnabled,
                        onCheckedChange = onToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                            uncheckedTrackColor = MaterialTheme.colorScheme.surface
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Body: IF [Triggers] -> DISCONNECT [Blocked]
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Triggers Box
                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isTriggerConnected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                        } else {
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
                        }
                    ),
                    border = BorderStroke(
                        1.dp,
                        if (isTriggerConnected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.BluetoothConnected,
                                contentDescription = null,
                                tint = if (isTriggerConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (triggerNames.isEmpty()) "LOCATION TRIGGER" else "WHEN CONNECTED (${triggerNames.size})",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isTriggerConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        if (triggerNames.isEmpty()) {
                            Text(
                                text = "When inside ${rule.locationName ?: "location"}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                        } else {
                            triggerNames.forEach { name ->
                                Text(
                                    text = "• $name",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                // Arrow Symbol
                Box(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Blocks",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Blocked Box
                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Block,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "DISCONNECT (${blockedNames.size})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        blockedNames.forEach { name ->
                            Text(
                                text = "• $name",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun AddOrEditRuleDialog(
    context: Context,
    existingRule: RoutineRuleEntity?,
    bondedDevices: List<BluetoothDevice>,
    onDismiss: () -> Unit,
    onSave: (RoutineRuleEntity) -> Unit
) {
    var ruleName by remember { mutableStateOf(existingRule?.ruleName ?: "") }
    val selectedTriggers = remember {
        mutableStateMapOf<String, String>().apply {
            if (existingRule != null) {
                val macs = existingRule.getTriggerList()
                val names = existingRule.getTriggerNameList()
                for (i in macs.indices) {
                    this[macs[i]] = names.getOrElse(i) { "Device" }
                }
            }
        }
    }
    val selectedBlocked = remember {
        mutableStateMapOf<String, String>().apply {
            if (existingRule != null) {
                val macs = existingRule.getBlockedList()
                val names = existingRule.getBlockedNameList()
                for (i in macs.indices) {
                    this[macs[i]] = names.getOrElse(i) { "Device" }
                }
            }
        }
    }

    var hasLocationCondition by remember { mutableStateOf(existingRule?.hasLocationCondition ?: false) }
    var locationName by remember { mutableStateOf(existingRule?.locationName ?: "") }
    var latitude by remember { mutableStateOf<Double?>(existingRule?.latitude) }
    var longitude by remember { mutableStateOf<Double?>(existingRule?.longitude) }
    var radiusMeters by remember { mutableFloatStateOf(existingRule?.radiusMeters ?: 150f) }

    var showMapPicker by remember { mutableStateOf(false) }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val granted = (perms[Manifest.permission.ACCESS_FINE_LOCATION] == true) ||
                      (perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true)
        if (granted) {
            showMapPicker = true
        } else {
            Toast.makeText(context, "Location permission required for location routines.", Toast.LENGTH_SHORT).show()
        }
    }

    if (showMapPicker) {
        MapLocationPickerDialog(
            context = context,
            initialLat = latitude,
            initialLng = longitude,
            initialRadius = radiusMeters,
            initialName = locationName,
            onDismiss = { showMapPicker = false },
            onLocationConfirmed = { lat, lng, rad, name ->
                latitude = lat
                longitude = lng
                radiusMeters = rad
                locationName = name
                showMapPicker = false
                hasLocationCondition = true
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (existingRule == null) "Create Custom Routine" else "Edit Routine",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    OutlinedTextField(
                        value = ruleName,
                        onValueChange = { ruleName = it },
                        label = { Text("Routine Name (Optional)") },
                        placeholder = { Text("e.g. Headphones First, Work Mode") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                if (bondedDevices.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "No Paired Devices Found",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Routines require at least one paired Bluetooth device. Please pair your devices first in phone Bluetooth settings.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }

                // SECTION 1: TRIGGER DEVICES (MULTI-SELECT)
                item {
                    Column {
                        Text(
                            text = "1. TRIGGER DEVICES (When ANY of these connect):",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Select 1, 2, or more triggers (e.g. Headphones, Watch)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                items(bondedDevices) { dev ->
                    val devName = try { dev.name?.ifBlank { "Unknown Device" } ?: "Unknown Device" } catch (_: Exception) { "Unknown Device" }
                    val isChecked = selectedTriggers.containsKey(dev.address)
                    val isBlockedSelected = selectedBlocked.containsKey(dev.address)

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isBlockedSelected) {
                                if (isChecked) {
                                    selectedTriggers.remove(dev.address)
                                } else {
                                    selectedTriggers[dev.address] = devName
                                }
                            },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isChecked) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            }
                        ),
                        border = if (isChecked) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isChecked,
                                enabled = !isBlockedSelected,
                                colors = CheckboxDefaults.colors(
                                    checkedColor = MaterialTheme.colorScheme.primary,
                                    checkmarkColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                onCheckedChange = { checked ->
                                    if (checked) selectedTriggers[dev.address] = devName
                                    else selectedTriggers.remove(dev.address)
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = devName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isChecked) FontWeight.Bold else FontWeight.Normal,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = dev.address,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }

                // SECTION 2: BLOCKED DEVICES (MULTI-SELECT)
                item {
                    Spacer(modifier = Modifier.height(6.dp))
                    Column {
                        Text(
                            text = "2. BLOCKED DEVICES (Disconnect & prevent):",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = "Select 1 or more devices to drop (e.g. Car Audio, Speaker)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                items(bondedDevices) { dev ->
                    val devName = try { dev.name?.ifBlank { "Unknown Device" } ?: "Unknown Device" } catch (_: Exception) { "Unknown Device" }
                    val isChecked = selectedBlocked.containsKey(dev.address)
                    val isTriggerSelected = selectedTriggers.containsKey(dev.address)

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isTriggerSelected) {
                                if (isChecked) {
                                    selectedBlocked.remove(dev.address)
                                } else {
                                    selectedBlocked[dev.address] = devName
                                }
                            },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isChecked) {
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            }
                        ),
                        border = if (isChecked) BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)) else null
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isChecked,
                                enabled = !isTriggerSelected,
                                colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.error),
                                onCheckedChange = { checked ->
                                    if (checked) selectedBlocked[dev.address] = devName
                                    else selectedBlocked.remove(dev.address)
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = devName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isChecked) FontWeight.Bold else FontWeight.Normal,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = dev.address,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }

                // SECTION 3: LOCATION RESTRICTION (MAP SELECTION)
                item {
                    Spacer(modifier = Modifier.height(6.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Place,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Location Restriction",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Switch(
                                    checked = hasLocationCondition,
                                    onCheckedChange = { checked ->
                                        hasLocationCondition = checked
                                        if (checked && latitude == null) {
                                            if (!LocationHelper.hasLocationPermission(context)) {
                                                locationPermissionLauncher.launch(
                                                    arrayOf(
                                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                                    )
                                                )
                                            } else {
                                                showMapPicker = true
                                            }
                                        }
                                    }
                                )
                            }

                            if (hasLocationCondition) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Rule only enforces when inside selected place (0% background drain, uses cached location):",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(6.dp))

                                if (latitude != null && longitude != null) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surface,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = locationName.ifBlank { "Selected Map Location" },
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    text = "±${radiusMeters.toInt()}m radius (${"%.4f".format(latitude)}, ${"%.4f".format(longitude)})",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }

                                            FilledTonalButton(
                                                onClick = { showMapPicker = true },
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                            ) {
                                                Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(14.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Change", fontSize = 11.sp)
                                            }
                                        }
                                    }
                                } else {
                                    Button(
                                        onClick = {
                                            if (!LocationHelper.hasLocationPermission(context)) {
                                                locationPermissionLauncher.launch(
                                                    arrayOf(
                                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                                    )
                                                )
                                            } else {
                                                showMapPicker = true
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Select on Map & Type Location")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val hasBlocked = selectedBlocked.isNotEmpty()
            val hasTriggerOrLocation = selectedTriggers.isNotEmpty() || (hasLocationCondition && latitude != null)
            val isValid = hasBlocked && hasTriggerOrLocation

            Button(
                onClick = {
                    val triggerMacs = selectedTriggers.keys.joinToString(",")
                    val triggerNames = selectedTriggers.values.map { it.replace(",", " ") }.joinToString(",")
                    val blockedMacs = selectedBlocked.keys.joinToString(",")
                    val blockedNames = selectedBlocked.values.map { it.replace(",", " ") }.joinToString(",")

                    val defaultName = if (ruleName.isNotBlank()) {
                        ruleName.trim()
                    } else if (selectedTriggers.isNotEmpty()) {
                        "Block ${selectedBlocked.values.joinToString(", ")}"
                    } else {
                        "Location Block (${locationName.ifBlank { "Map Area" }})"
                    }

                    val entity = RoutineRuleEntity(
                        id = existingRule?.id ?: 0L,
                        ruleName = defaultName,
                        triggerAddresses = triggerMacs,
                        triggerNames = triggerNames,
                        blockedAddresses = blockedMacs,
                        blockedNames = blockedNames,
                        hasLocationCondition = hasLocationCondition && latitude != null,
                        locationName = if (hasLocationCondition) locationName.ifBlank { "Map Location" } else null,
                        latitude = if (hasLocationCondition) latitude else null,
                        longitude = if (hasLocationCondition) longitude else null,
                        radiusMeters = radiusMeters,
                        isEnabled = existingRule?.isEnabled ?: true
                    )
                    onSave(entity)
                },
                enabled = isValid
            ) {
                Text(if (existingRule == null) "Save Routine" else "Update Routine")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
