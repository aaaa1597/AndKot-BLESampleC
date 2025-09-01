package com.example.android.blesamplec

import android.Manifest
import android.app.Dialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.SharedPreferences
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
import java.util.UUID

const val PREFKEY_FIRST_LAUNCH = "PREFKEY_FIRST_LAUNCH"
class MainActivity : AppCompatActivity() {
    private lateinit var sharedPref: SharedPreferences

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

        viewModel = ViewModelProvider(this)[BleViewModel::class.java]
        sharedPref = getPreferences(MODE_PRIVATE)
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

        val fstLaunch = sharedPref.getBoolean(PREFKEY_FIRST_LAUNCH, true)
        if(fstLaunch) {
            /* 未許可権限があれば許可要求 */
            permissionLauncher.launch(deniedPermissions.toTypedArray())
            sharedPref.edit(commit=true) { putBoolean(PREFKEY_FIRST_LAUNCH, false)}
        }
        else {
            val rationaleNeeded = deniedPermissions.any { permission ->
                ActivityCompat.shouldShowRequestPermissionRationale(this, permission)
            }

            if(rationaleNeeded) {
                /* 以前に拒否った */
                permissionLauncher.launch(deniedPermissions.toTypedArray())
            }
            else {
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
            override fun onScanResult(type: Int, result: ScanResult) {
                viewModel.addDevice(result.device)
            }
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
            return
        scanner.startScan(callback)
    }
    private fun startAdvertise() {
        val bluetoothManager: BluetoothManager = getSystemService(BluetoothManager::class.java)
        val advertiser = bluetoothManager.adapter.bluetoothLeAdvertiser
        val advertiseSettings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .setIncludeTxPowerLevel(true)
            .addServiceUuid(ParcelUuid(UUID.fromString("f000aa00-0451-4000-b000-333322221111"))) // 例: Heart Rate Service
            .build()

        advertiser.startAdvertising(advertiseSettings, advertiseData, object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                Log.d("aaaaa", "Advertising started")
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
