package app.minemaps.car

import androidx.car.app.CarContext
import androidx.car.app.constraints.ConstraintManager

/** Use the negotiated host protocol, not the phone manufacturer or Android version. */
object CarHostCapabilities {
    fun apiLevel(context: CarContext): Int = try {
        context.carAppApiLevel.coerceAtLeast(1)
    } catch (_: IllegalStateException) {
        // No completed handshake: never optimistically send newer protocol models.
        1
    }

    fun listLimit(context: CarContext): Int =
        if (apiLevel(context) >= 2) {
            context.getCarService(ConstraintManager::class.java)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        } else {
            // Car App Library's baseline list limit; its resource is private API.
            6
        }
}
