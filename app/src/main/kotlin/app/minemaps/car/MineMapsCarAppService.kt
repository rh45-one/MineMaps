package app.minemaps.car

import android.content.Context
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import app.minemaps.BuildConfig

class MineMapsCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator = hostValidator(applicationContext, BuildConfig.DEBUG)

    override fun onCreateSession(): Session = MapSession()

    companion object {
        /** Debug builds accept any host (DHU, sideloaded Android Auto); release builds only the allowlisted hosts. */
        fun hostValidator(context: Context, debug: Boolean): HostValidator =
            if (debug) {
                HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
            } else {
                HostValidator.Builder(context)
                    .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                    .build()
            }
    }
}
