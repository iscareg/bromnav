package nl.bromnav

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Pal.apply(Pal.choice(this))   // light / dark / follow the phone, before any screen opens
    }
}
