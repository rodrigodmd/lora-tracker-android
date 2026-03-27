package com.rodri.gpstracker

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.MapsInitializer
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import org.json.JSONObject
import java.util.*

class MainActivity : ComponentActivity() {

    private val SERVICE_UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
    private val CHARACTERISTIC_UUID = UUID.fromString("be152a26-9821-4a34-a63e-60d540217030")

    private var bluetoothGatt: BluetoothGatt? = null
    private val trackerLocation = mutableStateOf(LatLng(-32.8901, -68.8440))
    private val trackerBattery = mutableStateOf(0)
    private val connectionStatus = mutableStateOf("Desconectado")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Inicializar Maps SDK de inmediato
        MapsInitializer.initialize(applicationContext)
        
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}.launch(arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ))

        setContent {
            MaterialTheme {
                TrackerApp()
            }
        }

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            startBleScan()
        }, 2000)
    }

    private fun showToast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun bitmapDescriptorFromVector(context: Context, vectorResId: Int): BitmapDescriptor? {
        return try {
            val drawable = androidx.core.content.ContextCompat.getDrawable(context, vectorResId) ?: return null
            drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
            val bitmap = android.graphics.Bitmap.createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            drawable.draw(canvas)
            BitmapDescriptorFactory.fromBitmap(bitmap)
        } catch (e: Exception) {
            Log.e("MAPS", "Error creando icono", e)
            null
        }
    }

    @Composable
    fun TrackerApp() {
        val context = androidx.compose.ui.platform.LocalContext.current
        // El icono se guarda en un estado para evitar recrearlo constantemente
        var trackerIcon by remember { mutableStateOf<BitmapDescriptor?>(null) }
        
        LaunchedEffect(Unit) {
            trackerIcon = bitmapDescriptorFromVector(context, R.drawable.ic_tracker)
        }

        val cameraPositionState = rememberCameraPositionState {
            position = CameraPosition.fromLatLngZoom(trackerLocation.value, 15f)
        }

        // Efecto para centrar la cámara cuando cambia la posición del tracker significativamente
        LaunchedEffect(trackerLocation.value) {
            cameraPositionState.animate(
                com.google.android.gms.maps.CameraUpdateFactory.newLatLng(trackerLocation.value)
            )
        }

        Scaffold(
            topBar = {
                Surface(tonalElevation = 4.dp) {
                    Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                        Text("Estado: ${connectionStatus.value}", style = MaterialTheme.typography.titleMedium)
                        Text("Batería Tracker: ${trackerBattery.value}%", style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { startBleScan() }, modifier = Modifier.padding(top = 8.dp)) {
                            Text("Reconectar Bluetooth")
                        }
                    }
                }
            }
        ) { padding ->
            GoogleMap(
                modifier = Modifier.fillMaxSize().padding(padding),
                cameraPositionState = cameraPositionState,
                properties = MapProperties(isMyLocationEnabled = true)
            ) {
                Marker(
                    state = MarkerState(position = trackerLocation.value),
                    title = "Tracker",
                    icon = trackerIcon,
                    snippet = "Batería: ${trackerBattery.value}%"
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            showToast("Encendé el Bluetooth")
            return
        }
        val scanner = adapter.bluetoothLeScanner
        connectionStatus.value = "Escaneando..."
        
        scanner.startScan(object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name
                if (name == "Gateway-Repetidor") {
                    scanner.stopScan(this)
                    connectToDevice(result.device)
                }
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        connectionStatus.value = "Conectando..."
        bluetoothGatt = device.connectGatt(this, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connectionStatus.value = "Conectado"
                    Log.i("BLE", "Conectado. Pidiendo aumento de MTU...")
                    gatt.requestMtu(512)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    connectionStatus.value = "Desconectado"
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                Log.i("BLE", "MTU cambiado a $mtu. Descubriendo servicios...")
                gatt.discoverServices()
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val service = gatt.getService(SERVICE_UUID)
                val characteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)
                
                if (characteristic != null) {
                    Log.i("BLE", "Servicio encontrado. Suscribiendo...")
                    gatt.setCharacteristicNotification(characteristic, true)
                    val desc = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                    if (desc != null) {
                        desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(desc)
                    }
                }
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, char: BluetoothGattCharacteristic) {
                val data = String(char.value)
                Log.i("BLE_DATA", "Recibido: $data")
                try {
                    val json = JSONObject(data)
                    // Actualizar batería siempre
                    if (json.has("batt")) {
                        trackerBattery.value = json.getInt("batt")
                    }
                    
                    // Actualizar ubicación solo si es válida
                    if (json.has("lat") && json.has("lng")) {
                        val lat = json.getDouble("lat")
                        val lng = json.getDouble("lng")
                        if (lat != 0.0) {
                            trackerLocation.value = LatLng(lat, lng)
                        }
                    }
                } catch (e: Exception) { 
                    Log.e("BLE", "Error procesando JSON: $data", e) 
                }
            }
        })
    }
}
