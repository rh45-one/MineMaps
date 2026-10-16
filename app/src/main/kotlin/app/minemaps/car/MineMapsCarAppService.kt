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
        /**
         * Debug builds accept any host (DHU); release builds allow Google's Android Auto (`com.google.android.projection.gearhead`)
         * and AAOS template host certificates, including the newer Android Auto 14+ release signing key (`1ca8dcc0...`) that is
         * missing from `androidx.car.app`'s bundled `hosts_allowlist_sample`.
         */
        fun hostValidator(context: Context, debug: Boolean): HostValidator =
            if (debug) {
                HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
            } else {
                HostValidator.Builder(context)
                    .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                    .addAllowedHost("com.google.android.projection.gearhead", "1ca8dcc0bed3cbd872d2cb791200c0292ca9975768a82d676b8b424fb65b5295")
                    .addAllowedHost("com.google.android.projection.gearhead", "ee39411b93c116f8e0a15f7c2c13f88f31f0e2519b47142b1c1f40254b8b57d7")
                    .build()
            }
    }
}
