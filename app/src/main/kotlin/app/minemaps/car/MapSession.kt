package app.minemaps.car

import android.content.Intent
import android.content.res.Configuration
import androidx.car.app.AppManager
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.minemaps.MineMapsApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MapSession : Session() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: CarMapController? = null

    override fun onCreateScreen(intent: Intent): Screen {
        val container = (carContext.applicationContext as MineMapsApp).container
        val c = CarMapController(carContext, container, scope)
        controller = c
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(c)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = c.onSessionStarted()

            override fun onStop(owner: LifecycleOwner) = c.onSessionStopped()

            override fun onDestroy(owner: LifecycleOwner) {
                c.release()
                scope.cancel()
                controller = null
            }
        })
        val icons = AtlasItemIcons({ container.engine.await() }, container.settings.settings.map { it.texturePack }.distinctUntilChanged())
        scope.launch { icons.run() }
        return MapScreen.create(carContext, c, container, icons)
    }

    override fun onCarConfigurationChanged(newConfiguration: Configuration) {
        controller?.onNightChanged(carContext.isDarkMode)
    }
}
