package com.example.btpriority

import android.annotation.SuppressLint
import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MapLocationPickerDialog(
    context: Context,
    initialLat: Double?,
    initialLng: Double?,
    initialRadius: Float,
    initialName: String,
    onDismiss: () -> Unit,
    onLocationConfirmed: (lat: Double, lng: Double, radius: Float, name: String) -> Unit
) {
    // Default to last known or San Francisco if null
    val lastKnown = remember { LocationHelper.getLastKnownLocation(context) }
    var currentLat by remember { mutableDoubleStateOf(initialLat ?: lastKnown?.latitude ?: 37.7749) }
    var currentLng by remember { mutableDoubleStateOf(initialLng ?: lastKnown?.longitude ?: -122.4194) }
    var currentRadius by remember { mutableFloatStateOf(initialRadius.coerceIn(50f, 1000f)) }
    var locationName by remember { mutableStateOf(initialName.ifBlank { "Selected Place" }) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val coroutineScope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        onDispose {
            try {
                webViewRef?.stopLoading()
                webViewRef?.destroy()
                webViewRef = null
            } catch (_: Exception) {}
        }
    }

    fun updateMapPosition(lat: Double, lng: Double) {
        currentLat = lat
        currentLng = lng
        webViewRef?.evaluateJavascript(
            "if (typeof updateLocation === 'function') { updateLocation($lat, $lng); }",
            null
        )
    }

    fun updateMapRadius(radius: Float) {
        currentRadius = radius
        webViewRef?.evaluateJavascript(
            "if (typeof setRadius === 'function') { setRadius($radius); }",
            null
        )
    }

    fun searchLocation(query: String) {
        if (query.isBlank()) return
        isSearching = true

        coroutineScope.launch {
            var foundLat: Double? = null
            var foundLng: Double? = null
            var foundName: String? = null

            // 1. Try Android Geocoder first
            try {
                val geocoder = Geocoder(context, Locale.getDefault())
                @Suppress("DEPRECATION")
                val results: List<Address>? = withContext(Dispatchers.IO) {
                    try {
                        geocoder.getFromLocationName(query, 1)
                    } catch (_: Exception) {
                        null
                    }
                }
                if (!results.isNullOrEmpty()) {
                    val addr = results[0]
                    foundLat = addr.latitude
                    foundLng = addr.longitude
                    foundName = addr.featureName ?: addr.locality ?: query
                }
            } catch (_: Exception) {}

            // 2. Fallback to OpenStreetMap Nominatim API if Geocoder has no backend
            if (foundLat == null) {
                try {
                    withContext(Dispatchers.IO) {
                        val encoded = URLEncoder.encode(query, "UTF-8")
                        val url = URL("https://nominatim.openstreetmap.org/search?q=$encoded&format=json&limit=1")
                        val conn = url.openConnection() as HttpURLConnection
                        conn.setRequestProperty("User-Agent", "BluetoothBouncerApp/1.0")
                        conn.connectTimeout = 5000
                        conn.readTimeout = 5000
                        if (conn.responseCode == 200) {
                            val response = conn.inputStream.bufferedReader().use { it.readText() }
                            val jsonArray = JSONArray(response)
                            if (jsonArray.length() > 0) {
                                val first = jsonArray.getJSONObject(0)
                                foundLat = first.getDouble("lat")
                                foundLng = first.getDouble("lon")
                                foundName = first.optString("display_name").split(",").firstOrNull() ?: query
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            isSearching = false

            if (foundLat != null && foundLng != null) {
                locationName = foundName ?: query
                updateMapPosition(foundLat!!, foundLng!!)
                Toast.makeText(context, "Found: $locationName", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Location not found. Try dragging the pin on the map.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Select Location on Map",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Tap or drag pin to choose blocking geofence",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Search Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Search city, address or place...") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { searchLocation(searchQuery) })
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = { searchLocation(searchQuery) },
                        enabled = !isSearching,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
                    ) {
                        if (isSearching) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Search, contentDescription = "Search", modifier = Modifier.size(18.dp))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Interactive Leaflet Map in WebView
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                webViewRef = this
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.cacheMode = WebSettings.LOAD_DEFAULT

                                addJavascriptInterface(object {
                                    @JavascriptInterface
                                    fun onLocationSelected(lat: Double, lng: Double) {
                                        coroutineScope.launch(Dispatchers.Main) {
                                            currentLat = lat
                                            currentLng = lng
                                        }
                                    }
                                }, "Android")

                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        updateMapPosition(currentLat, currentLng)
                                        updateMapRadius(currentRadius)
                                    }
                                }

                                val html = """
                                <!DOCTYPE html>
                                <html>
                                <head>
                                  <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
                                  <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
                                  <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
                                  <style>
                                    html, body, #map { margin: 0; padding: 0; width: 100%; height: 100%; background: #222; }
                                    .leaflet-control-attribution { font-size: 8px !important; }
                                  </style>
                                </head>
                                <body>
                                  <div id="map"></div>
                                  <script>
                                    var lat = $currentLat;
                                    var lng = $currentLng;
                                    var radius = $currentRadius;
                                    var map = L.map('map', { zoomControl: false }).setView([lat, lng], 15);
                                    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
                                      maxZoom: 19
                                    }).addTo(map);
                                    
                                    var marker = L.marker([lat, lng], { draggable: true }).addTo(map);
                                    var circle = L.circle([lat, lng], {
                                      radius: radius,
                                      color: '#00bf63',
                                      fillColor: '#33cc82',
                                      fillOpacity: 0.25,
                                      weight: 2
                                    }).addTo(map);

                                    function updateLocation(newLat, newLng) {
                                      lat = newLat;
                                      lng = newLng;
                                      marker.setLatLng([lat, lng]);
                                      circle.setLatLng([lat, lng]);
                                      map.panTo([lat, lng]);
                                      if (window.Android && window.Android.onLocationSelected) {
                                        window.Android.onLocationSelected(lat, lng);
                                      }
                                    }

                                    function setRadius(newRadius) {
                                      radius = newRadius;
                                      circle.setRadius(newRadius);
                                    }

                                    marker.on('dragend', function() {
                                      var pos = marker.getLatLng();
                                      updateLocation(pos.lat, pos.lng);
                                    });

                                    map.on('click', function(e) {
                                      updateLocation(e.latlng.lat, e.latlng.lng);
                                    });
                                  </script>
                                </body>
                                </html>
                                """.trimIndent()

                                loadDataWithBaseURL("https://openstreetmap.org", html, "text/html", "UTF-8", null)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // GPS Button overlay
                    FilledTonalIconButton(
                        onClick = {
                            LocationHelper.captureCurrentLocation(context) { loc ->
                                if (loc != null) {
                                    updateMapPosition(loc.latitude, loc.longitude)
                                    locationName = "My GPS Location"
                                    Toast.makeText(context, "Centered on current GPS!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Unable to get GPS location.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(12.dp)
                    ) {
                        Icon(Icons.Default.MyLocation, contentDescription = "My GPS Location")
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Geofence Radius Slider
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Geofence Radius: ${currentRadius.toInt()}m",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Lat: ${"%.4f".format(currentLat)}, Lng: ${"%.4f".format(currentLng)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }

                    Slider(
                        value = currentRadius,
                        onValueChange = {
                            updateMapRadius(it)
                        },
                        valueRange = 50f..800f,
                        steps = 14
                    )
                }

                // Place Name input
                OutlinedTextField(
                    value = locationName,
                    onValueChange = { locationName = it },
                    label = { Text("Location Label") },
                    placeholder = { Text("e.g. Home, Office, Gym") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Bottom Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(onClick = onDismiss) {
                        Text("Cancel")
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = {
                            onLocationConfirmed(
                                currentLat,
                                currentLng,
                                currentRadius,
                                locationName.ifBlank { "Selected Place" }
                            )
                        }
                    ) {
                        Text("Confirm Location")
                    }
                }
            }
        }
    }
}
