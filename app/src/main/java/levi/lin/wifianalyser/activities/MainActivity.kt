package levi.lin.wifianalyser.activities

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import levi.lin.wifianalyser.R
import levi.lin.wifianalyser.data.DataStorage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity(), LocationListener {
    private lateinit var dataStorage: DataStorage
    private lateinit var wifiManager: WifiManager
    private lateinit var locationManager: LocationManager
    private lateinit var exportLauncher: ActivityResultLauncher<String>

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            startLocationUpdates()
        } else {
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        dataStorage = DataStorage(this)
        wifiManager = getSystemService(WIFI_SERVICE) as WifiManager
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
            uri?.let {
                val jsonRecords = dataStorage.toJson()
                jsonRecords.let {
                    contentResolver.openOutputStream(uri)?.bufferedWriter().use { writer -> writer?.write(jsonRecords) }
                    Toast.makeText(this, R.string.data_exported, Toast.LENGTH_LONG).show()
                }
            }
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions()
        } else {
            startLocationUpdates()
        }

        setContent {
            WifiAnalyzerApp(
                context = this,
                dataStorage = dataStorage,
                exportLauncher = exportLauncher,
                wifiManager = wifiManager,
                locationManager = locationManager
            )
        }
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_WIFI_STATE),
            100
        )
    }


    private fun startLocationUpdates() {
        val providers = locationManager.allProviders
        if (providers.contains(LocationManager.GPS_PROVIDER)) {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            } else {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 5f, this)
            }

            val lastKnownLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            lastKnownLocation?.let { location ->
                scanWifi(location.latitude, location.longitude)
            }
        } else {
            Toast.makeText(this, getString(R.string.no_gps_location_provider_available), Toast.LENGTH_LONG).show()
        }
    }

    override fun onLocationChanged(location: Location) {
        scanWifi(location.latitude, location.longitude)
    }

    private fun scanWifi(latitude: Double, longitude: Double) {
        val wifiScanResults = if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionRequest.launch("No Permission")
            return
        } else {
            wifiManager.scanResults
        }

        dataStorage.addOrUpdateCluster(latitude, longitude, wifiScanResults)
    }
}

@Composable
fun WifiAnalyzerApp(
    context: Context,
    dataStorage: DataStorage,
    exportLauncher: ActivityResultLauncher<String>,
    wifiManager: WifiManager,
    locationManager: LocationManager
) {
    var consoleText by remember { mutableStateOf("") }

    LaunchedEffect(key1 = dataStorage) {
        withContext(Dispatchers.IO) {
            val wifiScanResults = if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return@withContext
            } else {
                wifiManager.scanResults
            }

            val lastKnownLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            lastKnownLocation?.let { location ->

                dataStorage.addOrUpdateCluster(location.latitude, location.longitude, wifiScanResults)
                consoleText = buildConsoleText(dataStorage, location.latitude, location.longitude, wifiScanResults)
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            modifier = Modifier.padding(start = 15.dp),
            text = consoleText,
            textAlign = TextAlign.Start
        )

        Button(
            onClick = {
                exportLauncher.launch("wifi_map_data.json")
            },
            content = {
                Text(text = stringResource(id = R.string.export_data))
            }
        )
    }
}

private fun buildConsoleText(dataStorage: DataStorage, lat: Double, lon: Double, scanResults: List<ScanResult>): String {
    val sb = StringBuilder()
    sb.append("--------------------------------------------------------------------------\n")
    sb.append("[Networks Captured]: ${dataStorage.getNetworksCount()}\n")
    sb.append("[Last Update Time]: ${DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.now())}\n")
    sb.append("[GPS]\n$lat, $lon\n")
    sb.append("[Networks (${scanResults.size})]\n")
    sb.append(String.format("%-20s %-15s %-15s %-15s\n", "SSID", "Level", "SecurityType", "Frequency"))
    sb.append("--------------------------------------------------------------------------\n")

    for (result in scanResults.sortedByDescending { it.level }) {
        val securityType = when {
            result.capabilities.contains("WEP") -> "WEP"
            result.capabilities.contains("WPA2") -> "WPA2"
            result.capabilities.contains("WPA") -> "WPA"
            else -> "Open"
        }

        sb.append(String.format("%-20s %-15s %-15s %-15s\n", result.SSID, result.level.toString().trim(), securityType.toString().trim(), result.frequency.toString().trim()))
    }

    return sb.toString()
}
