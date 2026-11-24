package app.minemaps

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import app.minemaps.nav.GoogleMapsMirror
import app.minemaps.nav.GoogleMapsNotificationService
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GoogleMapsMirrorTest {

    private lateinit var context: Context
    private lateinit var service: GoogleMapsNotificationService
    private val firedActions = mutableListOf<String>()
    private lateinit var receiver: BroadcastReceiver

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        GoogleMapsMirror.onNotificationCleared()
        GoogleMapsMirror.latestAlertNotice.value = null
        service = Robolectric.buildService(GoogleMapsNotificationService::class.java).create().get()
        firedActions.clear()
        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                intent?.action?.let { firedActions += it }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_CANCEL_DL)
            addAction(ACTION_STOP_TIMELINE)
            addAction(ACTION_AA_EXIT)
            addAction(ACTION_NAV_EXIT)
            addAction(ACTION_SHARE_STOP)
            addAction(ACTION_MUTE)
            addAction(ACTION_ADD_STOP)
            addAction(ACTION_FR_EXIT)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    @After
    fun tearDown() {
        runCatching { context.unregisterReceiver(receiver) }
        GoogleMapsMirror.onNotificationCleared()
        GoogleMapsMirror.latestAlertNotice.value = null
    }

    private fun pi(action: String, reqCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            reqCode,
            Intent(action).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun buildNotification(
        title: String,
        text: String,
        subText: String? = null,
        category: String? = null,
        ongoing: Boolean = false,
        groupSummary: Boolean = false,
        actions: List<Pair<String, PendingIntent>> = emptyList(),
    ): Notification {
        val builder = Notification.Builder(context, "nav")
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(ongoing)
            .setGroupSummary(groupSummary)
        if (subText != null) builder.setSubText(subText)
        if (category != null) builder.setCategory(category)
        for ((label, intent) in actions) {
            @Suppress("DEPRECATION")
            builder.addAction(0, label, intent)
        }
        return builder.build()
    }

    @Suppress("DEPRECATION")
    private fun makeSbn(
        id: Int,
        notification: Notification,
        pkg: String = GoogleMapsMirror.GMAPS_PACKAGE,
    ): StatusBarNotification = StatusBarNotification(
        pkg,
        pkg,
        id,
        null,
        10001,
        1000,
        0,
        notification,
        Process.myUserHandle(),
        1_000_000L + id,
    )

    @Test
    fun nonNavOngoingNotificationDoesNotStoreExitActionOrSilenceMirror() {
        val dl = makeSbn(
            10,
            buildNotification(
                title = "Downloading offline map",
                text = "45%",
                category = Notification.CATEGORY_PROGRESS,
                ongoing = true,
                actions = listOf("Cancel" to pi(ACTION_CANCEL_DL, 10)),
            ),
        )
        val timeline = makeSbn(
            11,
            buildNotification(
                title = "Timeline",
                text = "Saving visits",
                category = null,
                ongoing = true,
                actions = listOf("Turn off" to pi(ACTION_STOP_TIMELINE, 11)),
            ),
        )
        val drivingStatus = makeSbn(
            12,
            buildNotification(
                title = "Driving with Google Maps",
                text = "Android Auto",
                category = Notification.CATEGORY_NAVIGATION,
                ongoing = true,
                actions = listOf("Exit" to pi(ACTION_AA_EXIT, 12)),
            ),
        )

        service.onNotificationPosted(dl)
        service.onNotificationPosted(timeline)
        service.onNotificationPosted(drivingStatus)

        assertNull(GoogleMapsMirror.active.value)

        GoogleMapsMirror.dismissAndExitGoogleMaps()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(firedActions.isEmpty(), "Non-navigation notifications must never arm or fire exitAction")

        // Subsequent real turn-by-turn notification must still mirror (not silenced)
        val realNav = makeSbn(
            1,
            buildNotification(
                title = "250 m",
                text = "Turn right",
                category = Notification.CATEGORY_NAVIGATION,
                ongoing = true,
            ),
        )
        service.onNotificationPosted(realNav)
        val active = assertNotNull(GoogleMapsMirror.active.value)
        assertEquals("250 m", active.distanceText)
        assertEquals("Turn right", active.instruction)
    }

    @Test
    fun nonNavOngoingNotificationDoesNotClobberActiveNavSession() {
        val navSbn = makeSbn(
            1,
            buildNotification(
                title = "250 m",
                text = "Turn right",
                category = Notification.CATEGORY_NAVIGATION,
                ongoing = true,
                actions = listOf("Exit navigation" to pi(ACTION_NAV_EXIT, 1)),
            ),
        )
        val shareSbn = makeSbn(
            2,
            buildNotification(
                title = "Location sharing",
                text = "With Alice",
                category = null,
                ongoing = true,
                actions = listOf("Stop" to pi(ACTION_SHARE_STOP, 2)),
            ),
        )
        val aaSbn = makeSbn(
            3,
            buildNotification(
                title = "Driving with Google Maps",
                text = "Android Auto",
                category = Notification.CATEGORY_NAVIGATION,
                ongoing = true,
                actions = listOf("Exit" to pi(ACTION_AA_EXIT, 3)),
            ),
        )

        service.onNotificationPosted(navSbn)
        service.onNotificationPosted(shareSbn)
        service.onNotificationPosted(aaSbn)

        val active = assertNotNull(GoogleMapsMirror.active.value)
        assertEquals("Turn right", active.instruction)

        GoogleMapsMirror.dismissAndExitGoogleMaps()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(ACTION_NAV_EXIT), firedActions)
    }

    @Test
    fun navNotificationWithoutExitKeywordDoesNotFireArbitraryFirstAction() {
        val navSbn = makeSbn(
            1,
            buildNotification(
                title = "250 m",
                text = "Turn right",
                category = Notification.CATEGORY_NAVIGATION,
                ongoing = true,
                actions = listOf(
                    "Mute" to pi(ACTION_MUTE, 21),
                    "Add stop" to pi(ACTION_ADD_STOP, 22),
                ),
            ),
        )

        service.onNotificationPosted(navSbn)
        assertNotNull(GoogleMapsMirror.active.value)

        GoogleMapsMirror.dismissAndExitGoogleMaps()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertNull(GoogleMapsMirror.active.value)
        assertTrue(firedActions.isEmpty(), "Must not fall back to first action or match 'Add stop'")
    }

    @Test
    fun multilingualNavNotificationsAndExitActionsWork() {
        val frSbn = makeSbn(
            1,
            buildNotification(
                title = "250 m",
                text = "Tourner à droite",
                category = Notification.CATEGORY_NAVIGATION,
                ongoing = true,
                actions = listOf(
                    "Couper le son" to pi(ACTION_MUTE, 31),
                    "Quitter la navigation" to pi(ACTION_FR_EXIT, 32),
                ),
            ),
        )
        service.onNotificationPosted(frSbn)
        val frActive = assertNotNull(GoogleMapsMirror.active.value)
        assertEquals("Tourner à droite", frActive.instruction)

        val noDistSbn = makeSbn(
            1,
            buildNotification(
                title = "Head north",
                text = "Toward 5th Ave",
                category = Notification.CATEGORY_NAVIGATION,
                ongoing = true,
                actions = listOf("Quitter la navigation" to pi(ACTION_FR_EXIT, 32)),
            ),
        )
        service.onNotificationPosted(noDistSbn)
        val noDistActive = assertNotNull(GoogleMapsMirror.active.value)
        assertEquals("Head north \u2013 Toward 5th Ave", noDistActive.instruction)

        GoogleMapsMirror.dismissAndExitGoogleMaps()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(ACTION_FR_EXIT), firedActions)
    }

    @Test
    fun unrelatedGmapsNotificationRemovalDoesNotResetDismissedOrKillActiveNav() {
        val navPi = pi(ACTION_NAV_EXIT, 41)
        val navSbn = makeSbn(
            1,
            buildNotification(
                title = "250 m",
                text = "Turn right",
                category = Notification.CATEGORY_NAVIGATION,
                ongoing = true,
                actions = listOf("Exit" to navPi),
            ),
        )
        val otherSbn = makeSbn(
            99,
            buildNotification(
                title = "Rate this place",
                text = "How was Cafe?",
                category = Notification.CATEGORY_STATUS,
                ongoing = false,
            ),
        )

        // While active, removing unrelated notification must leave active nav intact
        service.onNotificationPosted(navSbn)
        service.onNotificationPosted(otherSbn)
        service.onNotificationRemoved(otherSbn)
        assertNotNull(GoogleMapsMirror.active.value)

        // Dismiss active nav
        GoogleMapsMirror.dismissAndExitGoogleMaps()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertNull(GoogleMapsMirror.active.value)
        assertEquals(listOf(ACTION_NAV_EXIT), firedActions)

        // Posting and removing unrelated notification must NOT reset dismissedSession
        service.onNotificationPosted(otherSbn)
        service.onNotificationRemoved(otherSbn)
        service.onNotificationPosted(
            makeSbn(
                1,
                buildNotification(
                    title = "150 m",
                    text = "Turn right",
                    category = Notification.CATEGORY_NAVIGATION,
                    ongoing = true,
                ),
            ),
        )
        assertNull(GoogleMapsMirror.active.value, "Dismissed session must stay suppressed after unrelated removal")

        // Removing the actual dismissed nav notification resets the session cleanly
        service.onNotificationRemoved(navSbn)
        service.onNotificationPosted(
            makeSbn(
                1,
                buildNotification(
                    title = "400 m",
                    text = "Keep left",
                    category = Notification.CATEGORY_NAVIGATION,
                    ongoing = true,
                ),
            ),
        )
        val resumed = assertNotNull(GoogleMapsMirror.active.value)
        assertEquals("Keep left", resumed.instruction)
    }

    @Test
    fun inPlaceTransitionOfActiveNavToNonNavClearsSession() {
        val navSbn = makeSbn(
            1,
            buildNotification(
                title = "250 m",
                text = "Turn right",
                category = Notification.CATEGORY_NAVIGATION,
                ongoing = true,
            ),
        )
        service.onNotificationPosted(navSbn)
        assertNotNull(GoogleMapsMirror.active.value)

        val aaNotice = makeSbn(
            1,
            buildNotification(
                title = "Driving with Google Maps",
                text = "Android Auto",
                category = Notification.CATEGORY_NAVIGATION,
                ongoing = true,
            ),
        )
        service.onNotificationPosted(aaNotice)
        assertNull(GoogleMapsMirror.active.value)

        val nextNav = makeSbn(
            1,
            buildNotification(
                title = "100 m",
                text = "Turn left",
                category = Notification.CATEGORY_NAVIGATION,
                ongoing = true,
            ),
        )
        service.onNotificationPosted(nextNav)
        val active = assertNotNull(GoogleMapsMirror.active.value)
        assertEquals("Turn left", active.instruction)
    }

    @Test
    fun groupSummaryAndOtherPackageNotificationsAreIgnored_andRefreshScansAllGmapsNotifications() {
        val summary = makeSbn(
            1,
            buildNotification(
                title = "250 m",
                text = "Turn right",
                category = Notification.CATEGORY_NAVIGATION,
                ongoing = true,
                groupSummary = true,
            ),
        )
        val otherPkg = makeSbn(
            2,
            buildNotification(
                title = "250 m",
                text = "Turn right",
                category = Notification.CATEGORY_NAVIGATION,
                ongoing = true,
            ),
            pkg = "com.example.other",
        )

        service.onNotificationPosted(summary)
        service.onNotificationPosted(otherPkg)
        assertNull(GoogleMapsMirror.active.value)

        // refreshFromNotifications must scan past unrelated Google Maps notifications to find nav + alerts
        val unrelatedFirst = makeSbn(
            5,
            buildNotification(
                title = "Downloading offline map",
                text = "45%",
                category = Notification.CATEGORY_PROGRESS,
                ongoing = true,
            ),
        )
        val navSecond = makeSbn(
            6,
            buildNotification(
                title = "300 m",
                text = "Turn left",
                category = Notification.CATEGORY_NAVIGATION,
                ongoing = true,
            ),
        )
        val alertThird = makeSbn(
            7,
            buildNotification(
                title = "Speed camera ahead",
                text = "Mobile speed trap reported",
                category = Notification.CATEGORY_STATUS,
            ),
        )

        service.refreshFromNotifications(arrayOf(otherPkg, unrelatedFirst, navSecond, alertThird))
        val active = assertNotNull(GoogleMapsMirror.active.value)
        assertEquals("Turn left", active.instruction)
        assertNotNull(GoogleMapsMirror.latestAlertNotice.value)
    }

    private companion object {
        const val ACTION_CANCEL_DL = "app.minemaps.test.CANCEL_DL"
        const val ACTION_STOP_TIMELINE = "app.minemaps.test.STOP_TIMELINE"
        const val ACTION_AA_EXIT = "app.minemaps.test.AA_EXIT"
        const val ACTION_NAV_EXIT = "app.minemaps.test.NAV_EXIT"
        const val ACTION_SHARE_STOP = "app.minemaps.test.SHARE_STOP"
        const val ACTION_MUTE = "app.minemaps.test.MUTE"
        const val ACTION_ADD_STOP = "app.minemaps.test.ADD_STOP"
        const val ACTION_FR_EXIT = "app.minemaps.test.FR_EXIT"
    }
}
