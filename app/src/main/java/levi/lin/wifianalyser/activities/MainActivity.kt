package levi.lin.wifianalyser.activities

import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Bundle
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.Manifest
import android.net.Uri
import android.net.wifi.ScanResult
import androidx.core.app.ActivityCompat
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import levi.lin.wifianalyser.R
import levi.lin.wifianalyser.data.DataStorage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity(), LocationListener {
    private lateinit var dataStorage : DataStorage

    private lateinit var consoleTextView: TextView
    private lateinit var exportButton: Button

    private lateinit var wifiManager: WifiManager
    private lateinit var locationManager: LocationManager

    private lateinit var exportLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        dataStorage = DataStorage(this)

        wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        consoleTextView = findViewById(R.id.consoleTextView)

        setupExportButton()

        if (checkPermissions()) {
            startLocationUpdates()
        } else {
            requestPermissions()
        }
    }

    private fun setupExportButton() {
        exportButton = findViewById(R.id.exportButton)
        exportLauncher =
            registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
                uri?.let {
                    val jsonRecords = dataStorage.toJson()
                    jsonRecords.let {
                        contentResolver.openOutputStream(uri)?.bufferedWriter()
                            .use { writer -> writer?.write(jsonRecords) }
                        Toast.makeText(this, R.string.data_exported, Toast.LENGTH_LONG)
                            .show()
                    }
                }
            }

        exportButton.setOnClickListener {
            exportLauncher.launch("wifi_map_data.json")
        }
    }

    private fun checkPermissions(): Boolean {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_WIFI_STATE),
            100
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLocationUpdates()
            } else {
                Toast.makeText(this, getString(R.string.permission_denied), Toast.LENGTH_LONG).show()
            }
        }
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
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        } else {
            wifiManager.scanResults
        }

        dataStorage.addOrUpdateCluster(latitude, longitude, wifiScanResults)

        appendClusterToConsole(latitude, longitude, wifiScanResults)
    }

    private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    private fun appendClusterToConsole(lat: Double, lon: Double, scanResults: List<ScanResult>) {
        val sb = StringBuilder()
        sb.append("----------------------------------------------------\n")
        sb.append("[${getString(R.string.networksCaptured)}]: ${dataStorage.getNetworksCount()}\n")
        sb.append("[${getString(R.string.lastUpdateTime)}]: ${dateFormatter.format(Instant.now())}\n")
        sb.append("[${getString(R.string.gps)}]\n$lat, $lon\n")
        sb.append("[${getString(R.string.networks)} (${scanResults.size})]\n")
        sb.append(String.format("%-15s %-15s %-15s %-15s\n", getString(R.string.ssid), getString(R.string.level), getString(R.string.security), getString(R.string.frequency)))
        sb.append("----------------------------------------------------\n")

        for (result in scanResults.sortedByDescending { it.level }) {
            val securityType = when {
                result.capabilities.contains("WEP") -> "WEP"
                result.capabilities.contains("WPA2") -> "WPA2"
                result.capabilities.contains("WPA") -> "WPA"
                else -> getString(R.string.open)
            }

            sb.append(String.format("%-15s %-15s %-15s %-15s\n", result.wifiSsid.toString().trim(), result.level.toString().trim(), securityType.toString().trim(), result.frequency.toString().trim()))
        }

        consoleTextView.text = sb.toString()
    }
}
