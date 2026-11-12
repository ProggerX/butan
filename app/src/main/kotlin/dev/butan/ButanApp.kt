package dev.butan

import android.app.Application
import dev.butan.data.ButanStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class ButanApp : Application() {
    lateinit var store: ButanStore
        private set

    override fun onCreate() {
        super.onCreate()
        store = ButanStore(
            context = this,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
    }
}
