package com.example.android.blesamplec

import android.bluetooth.BluetoothDevice
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class BleViewModel: ViewModel() {
    val deviceList = MutableLiveData<List<BluetoothDevice>>(emptyList())
    fun addDevice(device: BluetoothDevice) {
        val current = deviceList.value ?: emptyList()
        if (device.address !in current.map { it.address }) {
            deviceList.postValue(current + device)
        }
    }
}
