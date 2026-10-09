package com.talktomyfinances.android.data.injection

import android.app.Application
import android.content.Intent
import android.util.Log
import com.google.android.gms.security.ProviderInstaller
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class TalkToMyFinancesHiltApp : Application(), ProviderInstaller.ProviderInstallListener {

    override fun onCreate() {
        super.onCreate()
        initProviderInstaller()
    }

    private fun initProviderInstaller() {
        ProviderInstaller.installIfNeededAsync(this, this)
    }

    override fun onProviderInstalled() {
        Log.i(TAG, "ProviderInstaller installed successfully.")
    }

    override fun onProviderInstallFailed(errorCode: Int, recoveryIntent: Intent?) {
        Log.w(TAG, "ProviderInstaller installation failed with error code: $errorCode")
    }

    companion object {
        private const val TAG = "TalkToMyFinancesHiltApp"
    }
}