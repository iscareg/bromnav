package nl.bromnav

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Pal.apply(Pal.choice(this))   // light / dark / follow the phone, before any screen opens
        Region.load(this)             // chosen city + restaurant
        Thread { PdokGeocoder.book = MapData.openAddresses(this) }.start()   // offline address book
    }
}
