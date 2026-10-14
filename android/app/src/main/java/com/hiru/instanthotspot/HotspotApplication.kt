package com.hiru.instanthotspot

import android.app.Application
import com.hiru.instanthotspot.ble.BleEngine
import com.hiru.instanthotspot.data.AppStore

class HotspotApplication : Application() {
    val store by lazy { AppStore(this) }
    val ble by lazy { BleEngine(this, store) }
}
