package com.salla.push.consumercheck

import android.app.Application
import androidx.work.Configuration
import com.salla.push.SallaPush

/**
 * The consumer check's Application: SallaPush from the published AAR, started with README.md's initWithContext line.
 * Its manifest takes WorkManager's startup initializer out, as docs/REFERENCE.md shows, so it provides WorkManager's
 * configuration: WorkManager then starts the first time something uses it.
 */
class App : Application(), Configuration.Provider {

    // snippet:work-manager-provider
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()
    // end-snippet

    override fun onCreate() {
        super.onCreate()
        SallaPush.initWithContext(this, "00000000-0000-4000-8000-000000000002")
        SallaPush.logout()
    }
}
