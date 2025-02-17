package levi.lin.wifianalyser.data

import android.content.Context
import android.net.wifi.ScanResult
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.BufferedReader
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.time.Instant

class DataStorage(private val context: Context) {
    data class WiFiNetwork(
        val levels: MutableMap<String, Int> = mutableMapOf(),
        var ssid: String,
        var frequency: Int,
        var capabilities: String,
        var lastUpdateTime: Long,
    )

    private val networks = mutableMapOf<String, WiFiNetwork>()
    private val fileName = "wifi_map_data.json"

    init {
        loadNetworks()
    }

    @Suppress("DEPRECATION")
    fun addOrUpdateCluster(latitude: Double, longitude: Double, scanResults: List<ScanResult>) {
        val geoLocation = "$latitude,$longitude"
        for (result in scanResults)
        {
            val key = result.BSSID
            if (networks.containsKey(key))
            {
                networks[key]!!.levels[geoLocation] = result.level
                networks[key]!!.capabilities
                networks[key]!!.lastUpdateTime = Instant.now().toEpochMilli()
                networks[key]!!.ssid = result.SSID
            }
            else
            {
                val newNetwork = WiFiNetwork(
                    capabilities = result.capabilities,
                    lastUpdateTime = Instant.now().toEpochMilli(),
                    ssid = result.SSID,
                    frequency = result.frequency)
                    .apply {
                    levels[geoLocation] = result.level
                }
                networks[key] = newNetwork
            }
        }

        saveNetworks()
    }

    fun toJson() : String {
        val json = Gson().toJson(networks)
        return json
    }

    private fun saveNetworks() {
        val json = toJson()

        try {
            val outputStream: FileOutputStream =
                context.openFileOutput(fileName, Context.MODE_PRIVATE)
            outputStream.write(json.toByteArray())
            outputStream.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun loadNetworks() {
        try {
            context.openFileInput(fileName).use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    val json = reader.readText()

                    val typeToken = object : TypeToken<Map<String, WiFiNetwork>>() {}.type
                    val loadedClusters: Map<String, WiFiNetwork> = Gson().fromJson(json, typeToken)

                    networks.putAll(loadedClusters)
                }
            }
        } catch (_: FileNotFoundException) {
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    fun getNetworksCount(): Int {
        return networks.size
    }
}