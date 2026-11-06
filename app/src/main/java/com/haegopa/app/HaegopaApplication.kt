package com.haegopa.app

import android.app.Application
import com.haegopa.app.di.AppContainer

class HaegopaApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
