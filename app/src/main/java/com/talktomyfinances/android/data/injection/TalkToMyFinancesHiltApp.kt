package com.talktomyfinances.android.data.injection

import android.app.Application
import android.content.Intent
import android.util.Log
import com.google.android.gms.security.ProviderInstaller
import com.google.firebase.Firebase
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.talktomyfinances.android.BuildConfig
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class TalkToMyFinancesHiltApp : Application(), ProviderInstaller.ProviderInstallListener {

    override fun onCreate() {
        initializeAppCheck()
        super.onCreate()
        initProviderInstaller()
    }

    private fun initializeAppCheck() {
        if (BuildConfig.DEBUG) {
            Firebase.appCheck.installAppCheckProviderFactory(
                DebugAppCheckProviderFactory.getInstance()
            )
            Log.i(TAG, "App Check initialized with DebugAppCheckProviderFactory.")
        } else {
            Firebase.appCheck.installAppCheckProviderFactory(
                PlayIntegrityAppCheckProviderFactory.getInstance()
            )
            Log.i(TAG, "App Check initialized with PlayIntegrityAppCheckProviderFactory.")
        }
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