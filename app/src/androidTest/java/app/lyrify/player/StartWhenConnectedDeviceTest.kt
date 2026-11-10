package app.lyrify.player

import android.Manifest
import android.companion.CompanionDeviceService
import android.content.Intent
import android.content.pm.FeatureInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Start when connected's manifest entries, as the phone reads them (debug build): the companion service resolves for the
 * system's action, exported and guarded by the system-only permission; the two companion permissions are granted at
 * install; the feature is declared, not required. Nothing is paired: the Galaxy Buds checks are done by hand.
 */
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
@RunWith(AndroidJUnit4::class)
class StartWhenConnectedDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val packages = context.packageManager

    @Test
    fun theCompanionServiceResolvesForTheSystemOnly() {
        val intent = Intent(CompanionDeviceService.SERVICE_INTERFACE).setPackage(context.packageName)
        val found = packages.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0)).map { it.serviceInfo }

        assertEquals(listOf(ConnectService::class.java.name), found.map { it.name })
        val service = found.single()
        assertTrue(service.exported)
        assertTrue(service.isEnabled)
        assertEquals(Manifest.permission.BIND_COMPANION_DEVICE_SERVICE, service.permission)
    }

    @Test
    fun theCompanionPermissionsAreGrantedAtInstall() {
        listOf(Manifest.permission.REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE, Manifest.permission.REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND).forEach {
            assertEquals(it, PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(it))
        }
        val asked = packages.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())).requestedPermissions.orEmpty()
        // The heavier one stays out: it would also lift KJB's battery limits while a device is near.
        assertFalse(Manifest.permission.REQUEST_COMPANION_RUN_IN_BACKGROUND in asked)
    }

    @Test
    fun companionDevicesAreDeclaredNotRequired() {
        val features = packages.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_CONFIGURATIONS.toLong())).reqFeatures.orEmpty()
        val companion = features.single { it.name == PackageManager.FEATURE_COMPANION_DEVICE_SETUP }

        assertEquals(0, companion.flags and FeatureInfo.FLAG_REQUIRED)
    }

    @Test
    fun theRowShowsOnAPhoneWithCompanionDevices() {
        val companion = packages.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)

        assertEquals(companion, StartWhenConnected.supported(context))
    }
}
