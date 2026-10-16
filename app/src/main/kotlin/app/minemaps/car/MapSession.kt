package app.minemaps.car

import android.content.Intent
import android.content.res.Configuration
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
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
import kotlinx.coroutines.flow.first
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
        val root = NavScreen.create(carContext, c, container) {
            carContext.getCarService(androidx.car.app.ScreenManager::class.java).push(MapScreen.create(carContext, c, container, icons))
        }
        handleIntent(intent)
        return root
    }

    override fun onNewIntent(intent: Intent) {
        handleIntent(intent)
    }

    private var navigate: NavigateHandler? = null

    private fun handleIntent(intent: Intent) {
        if (intent.action != CarContext.ACTION_NAVIGATE) return
        val c = controller ?: return
        val handler = navigate ?: run {
            val container = (carContext.applicationContext as MineMapsApp).container
            NavigateHandler(
                scope, c,
                search = { q ->
                    val settings = container.settings.settings.first()
                    val cam = container.mapState.current
                    if (settings.labelNames == app.minemaps.data.LabelNames.ENGLISH) {
                        val names = container.engine.await().nameResolver(app.minemaps.engine.labels.NameResolver.Mode.ENGLISH)
                        container.photon.search(q, cam?.lat, cam?.lon, "en", { names.latinize(it) ?: it }, cam?.zoom)
                    } else {
                        container.photon.search(q, cam?.lat, cam?.lon, java.util.Locale.getDefault().language, zoom = cam?.zoom)
                    }
                },
                toast = { CarToast.makeText(carContext, it, CarToast.LENGTH_LONG).show() },
                showResults = {
                    carContext.getCarService(androidx.car.app.ScreenManager::class.java)
                        .push(SearchResultsScreen(carContext, it, c) { container.location.lastKnown() })
                },
            ).also { navigate = it }
        }
        handler.handle(intent.dataString)
    }

    override fun onCarConfigurationChanged(newConfiguration: Configuration) {
        controller?.onNightChanged(carContext.isDarkMode)
    }
}
