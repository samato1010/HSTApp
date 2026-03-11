package ar.com.hst.app

import android.app.Application
import android.webkit.CookieManager
import ar.com.hst.app.extintores.ExtintoresSyncWorker

class HSTApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CookieManager.getInstance().setAcceptCookie(true)
        ExtintoresSyncWorker.schedule(this)
    }
}
