package com.example.android.blesamplec

import android.Manifest
import android.app.Dialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.ParcelUuid
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.nio.charset.Charset

const val PREFKEY_FIRST_LAUNCH = "PREFKEY_FIRST_LAUNCH"
class MainActivity : AppCompatActivity() {
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var viewModel: BleViewModel
    private lateinit var adapter: BleDeviceAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        /* String → ByteArray */
        val text = "義経ああ"
        val byteArray = text.toByteArray(Charset.forName("Shift_JIS"))
        Log.d("aaaaa", "text(${text}) -> byteArray=${byteArray.joinToString(","){it.toString()}}")
        /* ByteArray → String */
        val text2 = byteArray.toString(Charset.forName("Shift_JIS"))
        Log.d("aaaaa", "-> text2=${text2}")

        viewModel = ViewModelProvider(this)[BleViewModel::class.java]
        bluetoothAdapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter

        val checkOK = checkPermissions()
        if(checkOK) {
            startScan()
            startAdvertise()
        }

        adapter = BleDeviceAdapter(emptyList()) { device ->
            connectToDevice(this, device)
        }

        findViewById<RecyclerView>(R.id.deviceList).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        viewModel.deviceList.observe(this) { devices ->
            adapter = BleDeviceAdapter(devices) { device -> connectToDevice(this, device) }
            findViewById<RecyclerView>(R.id.deviceList).adapter = adapter
        }
    }

    /* 権限(s)チェック */
    private fun checkPermissions(): Boolean {
        /* 複数の権限チェック */
        val permissions = arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE,
                                  Manifest.permission.BLUETOOTH_SCAN,
                                  Manifest.permission.BLUETOOTH_CONNECT,
                                  Manifest.permission.ACCESS_FINE_LOCATION )
        /* 未許可権限を取得 */
        val deniedPermissions: MutableList<String> = ArrayList()
        for (permission in permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED)
                deniedPermissions.add(permission)
        }

        /* 未許可権限チェック */
        if(deniedPermissions.isEmpty())
            return true  /* 権限付与済 何もする必要ない */

        val fstLaunch = isFirstLaunch(this)
        Log.d("aaaaa", "(110) fstLaunch=${fstLaunch}")
        if(fstLaunch) {
            Log.d("aaaaa", "(112) fstLaunch=${fstLaunch}")
            /* 未許可権限があれば許可要求 */
            permissionLauncher.launch(deniedPermissions.toTypedArray())
        }
        else {
            Log.d("aaaaa", "(117) fstLaunch=${fstLaunch}")
            val rationaleNeeded = deniedPermissions.any { permission ->
                ActivityCompat.shouldShowRequestPermissionRationale(this, permission)
            }

            if(rationaleNeeded) {
                Log.d("aaaaa", "(123) fstLaunch=${fstLaunch} rationaleNeeded=${rationaleNeeded}")
                /* 以前に拒否った */
                permissionLauncher.launch(deniedPermissions.toTypedArray())
            }
            else {
                Log.d("aaaaa", "(128) fstLaunch=${fstLaunch} rationaleNeeded=${rationaleNeeded}")
                /* 以前に"非表示にした"ならアラートダイアログ → Shutdown */
                PermissionDialogFragment.show(this, R.string.wording_permission_2times)
            }
        }
        return false
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            isGranted: Map<String, Boolean> ->
        Log.d("aaaaa", "isGranted=$isGranted")
        /* 権限チェック */
        if (isGranted.all { it.value == true }) {
            startScan()
            startAdvertise()
            return@registerForActivityResult    /* 全て許可済 問題なし */
        }
        else {
            /* ひとつでも権限不足ありならアラートダイアログ→Shutdown */
            PermissionDialogFragment.show(this, R.string.wording_permission)
        }
    }

    private fun startScan() {
        val scanner = bluetoothAdapter.bluetoothLeScanner
        val callback = object : ScanCallback() {
            override fun onScanFailed(errorCode: Int) {
                /* エラーログ出力 */
                when (errorCode) {
                    SCAN_FAILED_ALREADY_STARTED                -> Log.e("aaaaa", "SCAN FAILED! ${getString(R.string.failed_scan_already_started)}")
                    SCAN_FAILED_APPLICATION_REGISTRATION_FAILED-> Log.e("aaaaa", "SCAN FAILED! ${getString(R.string.failed_scan_application_registration_failed)}")
                    SCAN_FAILED_FEATURE_UNSUPPORTED            -> Log.e("aaaaa", "SCAN FAILED! ${getString(R.string.failed_scan_feature_unsupported)}")
                    SCAN_FAILED_INTERNAL_ERROR                 -> Log.e("aaaaa", "SCAN FAILED! ${getString(R.string.failed_scan_internal_error)}")
                    SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES      -> Log.e("aaaaa", "SCAN FAILED! ${getString(R.string.failed_scan_out_of_hardware_resources)}")
                    else -> Log.e("aaaaa", "SCAN FAILED! Unknown error")
                }
            }

            override fun onScanResult(type: Int, result: ScanResult) {
                if(result.scanRecord==null) Log.d("aaaaa", "ScanRecord is null")
                val scanResult = result.scanRecord ?: return
                /* ServiceUUIDの確認 */
                val serviceUUIDs = scanResult.serviceUuids
                if(serviceUUIDs==null) Log.d("aaaaa", "serviceUUIDs is null")
                if(serviceUUIDs?.contains(ParcelUuid.fromString("0000180A-0000-1000-8000-00805F9B34FB"))==false) {
                    Log.d("aaaaa", "out of UUID=${serviceUUIDs}")
                    return
                }
                Log.d("aaaaa", "Device info UUID found!")
                /* manufacturerDataの確認 */
                val manufacturerData = scanResult.getManufacturerSpecificData(0xFFFF)
                if(manufacturerData == null) {
                    Log.d("aaaaa", "out of manufacturerData=${scanResult.manufacturerSpecificData}")
                    return
                }
                val decoded = manufacturerData.decodeToString().map { it.code.toChar() }.joinToString("")
                Log.d("aaaaa", "decoded=${decoded}")
                viewModel.addDevice(result.device)
            }
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
            return
        scanner.startScan(callback)
    }
    private fun startAdvertise() {
        val serviceUuid = ParcelUuid.fromString("0000180A-0000-1000-8000-00805F9B34FB")
        val manufacturerID = 0xFFFF
        val payload = byteArrayOf('a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), 'd'.code.toByte(),
                                  '1'.code.toByte(), '2'.code.toByte(), '3'.code.toByte(), '4'.code.toByte())
        val advertiser = getSystemService(BluetoothManager::class.java).adapter.bluetoothLeAdvertiser

        val advertiseSettings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        val advertiseData = AdvertiseData.Builder()
            .addServiceUuid(serviceUuid)
            .addManufacturerData(manufacturerID, payload)
            .setIncludeTxPowerLevel(true)
            .setIncludeDeviceName(false)
            .build()

        advertiser.startAdvertising(advertiseSettings, advertiseData, object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                /* エラーログ出力 */
                when (errorCode) {
                    ADVERTISE_FAILED_ALREADY_STARTED     -> Log.e("aaaaa", "FAILED! ${getString(R.string.failed_already_started)}")
                    ADVERTISE_FAILED_DATA_TOO_LARGE      -> Log.e("aaaaa", "FAILED! ${getString(R.string.failed_data_too_large)}")
                    ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> Log.e("aaaaa", "FAILED! ${getString(R.string.failed_feature_unsupported)}")
                    ADVERTISE_FAILED_INTERNAL_ERROR      -> Log.e("aaaaa", "FAILED! ${getString(R.string.failed_internal_error)}")
                    ADVERTISE_FAILED_TOO_MANY_ADVERTISERS-> Log.e("aaaaa", "FAILED! ${getString(R.string.failed_too_many_advertisers)}")
                    else -> Log.e("aaaaa", "FAILED! Unknown error")
                }
            }
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                Log.d("aaaaa", "Advertising started settings=${settingsInEffect}")
            }
        })
    }

    private fun connectToDevice(context: Context, device: BluetoothDevice) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            return

        device.connectGatt(this, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                        return
                    gatt.discoverServices()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                gatt.services.forEach {
                    Log.d("BLE", "Service: ${it.uuid}")
                    it.characteristics.forEach { it ->
                        val props = it.properties
                        val isRead = props and BluetoothGattCharacteristic.PROPERTY_READ != 0
                        val isWrite = props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
                        val isNotify = props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                        val isWriteNoRes = props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
                        val isIndicate = props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
                        Log.d("aaaaa", "    Characteristic: ${it.uuid} READ:$isRead WRITE:$isWrite NOTIFY:$isNotify WRITE_NO_RESPONSE:$isWriteNoRes INDICATE:$isIndicate")
                    }
                }
            }
        })
    }
}

class BleDeviceAdapter(
    private val devices: List<BluetoothDevice>,
    private val onClick: (BluetoothDevice) -> Unit
) : RecyclerView.Adapter<BleDeviceAdapter.DeviceViewHolder>() {
    private lateinit var context: Context

    class DeviceViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(android.R.id.text1)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        context = parent.context
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_1, parent, false)
        return DeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            return
        val device = devices[position]
        holder.name.text = device.name ?: "Unknown Device"
        holder.view.setOnClickListener { onClick(device) }
    }

    override fun getItemCount(): Int = devices.size
}

class PermissionDialogFragment(@StringRes private val redid: Int) : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val activity = requireActivity()
        return AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.req_permission))
            .setMessage(activity.getString(redid, activity.getString(R.string.app_name)))
            .setPositiveButton("OK") { _, _ ->
                activity.finish()
            }
            .create()
    }

    companion object {
        fun show(activity: AppCompatActivity, @StringRes redid: Int) {
            val fragment = PermissionDialogFragment(redid)
            fragment.show(activity.supportFragmentManager, "PermissionDialog")
        }
    }
}

private fun isFirstLaunch(context: Context): Boolean {
    val markerFile = File(context.noBackupFilesDir, "first_launch_marker")
    return if(markerFile.exists()) {
                false   /* Already launched */
            }
            else {
                markerFile.createNewFile()  /* First launch, so create marker */
                true
            }
}
