package io.homeassistant.companion.android.sensors

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.TrafficStats
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.homeassistant.companion.android.BuildConfig
import io.homeassistant.companion.android.common.data.authentication.AuthenticationRepository
import io.homeassistant.companion.android.common.data.integration.IntegrationRepository
import io.homeassistant.companion.android.common.R as commonR
import io.homeassistant.companion.android.common.sensors.SensorManager
import java.math.RoundingMode
import java.util.Calendar
import java.util.GregorianCalendar
import kotlinx.coroutines.runBlocking

class AppSensorManager : BroadcastReceiver(), SensorManager {
    companion object {
        private const val TAG = "AppSensor"
        private const val GB = 1000000000

        const val ACTION_APP_LOCK_UPDATE =
            "io.homeassistant.companion.android.background.APPLOCK_UPDATE"

        const val ACTION_APP_LOCK_UPDATE_EXTRA =
            "io.homeassistant.companion.android.background.applock_update.LOCK"

        // Or create exposed function here to cause lock sensor update...
        // (Search 'setHighAccuracyModeSetting')

        val currentVersion = SensorManager.BasicSensor(
            "current_version",
            "sensor",
            commonR.string.basic_sensor_name_current_version,
            commonR.string.sensor_description_current_version,
            "mdi:android",
            docsLink = "https://companion.home-assistant.io/docs/core/sensors#current-version-sensor",
            entityCategory = SensorManager.ENTITY_CATEGORY_DIAGNOSTIC
        )

        val app_rx_gb = SensorManager.BasicSensor(
            "app_rx_gb",
            "sensor",
            commonR.string.basic_sensor_name_app_rx_gb,
            commonR.string.sensor_description_app_rx_gb,
            "mdi:radio-tower",
            unitOfMeasurement = "GB",
            docsLink = "https://companion.home-assistant.io/docs/core/sensors#app-data-sensors",
            stateClass = SensorManager.STATE_CLASS_TOTAL_INCREASING,
            entityCategory = SensorManager.ENTITY_CATEGORY_DIAGNOSTIC
        )

        val app_tx_gb = SensorManager.BasicSensor(
            "app_tx_gb",
            "sensor",
            commonR.string.basic_sensor_name_app_tx_gb,
            commonR.string.sensor_description_app_tx_gb,
            "mdi:radio-tower",
            unitOfMeasurement = "GB",
            docsLink = "https://companion.home-assistant.io/docs/core/sensors#app-data-sensors",
            stateClass = SensorManager.STATE_CLASS_TOTAL_INCREASING,
            entityCategory = SensorManager.ENTITY_CATEGORY_DIAGNOSTIC
        )

        val app_memory = SensorManager.BasicSensor(
            "app_memory",
            "sensor",
            commonR.string.basic_sensor_name_app_memory,
            commonR.string.sensor_description_app_memory,
            "mdi:memory",
            unitOfMeasurement = "GB",
            docsLink = "https://companion.home-assistant.io/docs/core/sensors#app-memory-sensor",
            stateClass = SensorManager.STATE_CLASS_MEASUREMENT,
            entityCategory = SensorManager.ENTITY_CATEGORY_DIAGNOSTIC
        )

        val app_locked = SensorManager.BasicSensor(
            "app_locked",
            "binary_sensor",
            commonR.string.basic_sensor_name_app_locked,
            commonR.string.sensor_description_app_locked,
            "mdi:lock-outline",
            docsLink = "https://companion.home-assistant.io/docs/core/sensors#app-lock-sensor",
            entityCategory = SensorManager.ENTITY_CATEGORY_DIAGNOSTIC
        )

        val app_inactive = SensorManager.BasicSensor(
            "app_inactive",
            "binary_sensor",
            commonR.string.basic_sensor_name_app_inactive,
            commonR.string.sensor_description_app_inactive,
            "mdi:timer-outline",
            docsLink = "https://companion.home-assistant.io/docs/core/sensors#app-usage-sensors",
            entityCategory = SensorManager.ENTITY_CATEGORY_DIAGNOSTIC
        )

        val app_standby_bucket = SensorManager.BasicSensor(
            "app_standby_bucket",
            "sensor",
            commonR.string.basic_sensor_name_app_standby,
            commonR.string.sensor_description_app_standby,
            "mdi:android",
            docsLink = "https://companion.home-assistant.io/docs/core/sensors#app-usage-sensors",
            entityCategory = SensorManager.ENTITY_CATEGORY_DIAGNOSTIC
        )

        val app_importance = SensorManager.BasicSensor(
            "app_importance",
            "sensor",
            commonR.string.basic_sensor_name_app_importance,
            commonR.string.sensor_description_app_importance,
            "mdi:android",
            docsLink = "https://companion.home-assistant.io/docs/core/sensors#app-importance-sensor",
            entityCategory = SensorManager.ENTITY_CATEGORY_DIAGNOSTIC
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_APP_LOCK_UPDATE) {
            Log.d(TAG, "Received app lock intent: ${intent.action}!")
            updateAppLock(context, intent)
        }
    }

    override val enabledByDefault: Boolean
        get() = false
    override val name: Int
        get() = commonR.string.sensor_name_app_sensor

    override fun getAvailableSensors(context: Context): List<SensorManager.BasicSensor> {
        return when {
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) ->
                listOf(
                    currentVersion, app_rx_gb, app_tx_gb, app_memory, app_inactive, app_locked,
                    app_standby_bucket, app_importance
                )
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) ->
                listOf(
                    currentVersion, app_rx_gb, app_tx_gb, app_memory, app_inactive, app_locked,
                    app_importance
                )
            else -> listOf(currentVersion, app_rx_gb, app_tx_gb, app_memory, app_importance, app_locked)
        }
    }

    override fun requiredPermissions(sensorId: String): Array<String> {
        return emptyArray()
    }

    override fun requestSensorUpdate(
        context: Context
    ) {
        val myUid = Process.myUid()
        updateCurrentVersion(context)
        updateAppMemory(context)
        updateAppRxGb(context, myUid)
        updateAppTxGb(context, myUid)
        updateImportanceCheck(context)
        updateAppLock(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val usageStatsManager = context.getSystemService<UsageStatsManager>()!!
            updateAppInactive(context, usageStatsManager)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                updateAppStandbyBucket(context, usageStatsManager)
        }
    }

    private fun updateCurrentVersion(context: Context) {

        if (!isEnabled(context, currentVersion.id))
            return

        val state = BuildConfig.VERSION_NAME

        onSensorUpdated(
            context,
            currentVersion,
            state,
            currentVersion.statelessIcon,
            mapOf()
        )
    }

    private fun updateAppRxGb(context: Context, appUid: Int) {

        if (!isEnabled(context, app_rx_gb.id))
            return

        val appRx = try {
            TrafficStats.getUidRxBytes(appUid).toFloat() / GB
        } catch (e: Exception) {
            Log.e(TAG, "Error getting app rx bytes", e)
            return
        }

        onSensorUpdated(
            context,
            app_rx_gb,
            appRx.toBigDecimal().setScale(4, RoundingMode.HALF_EVEN),
            app_rx_gb.statelessIcon,
            mapOf()
        )
    }

    private fun updateAppTxGb(context: Context, appUid: Int) {

        if (!isEnabled(context, app_tx_gb.id))
            return

        val appTx = try {
            TrafficStats.getUidTxBytes(appUid).toFloat() / GB
        } catch (e: Exception) {
            Log.e(TAG, "Error getting app tx bytes", e)
            return
        }

        onSensorUpdated(
            context,
            app_tx_gb,
            appTx.toBigDecimal().setScale(4, RoundingMode.HALF_EVEN),
            app_tx_gb.statelessIcon,
            mapOf()
        )
    }

    private fun updateAppMemory(context: Context) {

        if (!isEnabled(context, app_memory.id))
            return

        val runTime = Runtime.getRuntime()
        val freeSize = runTime.freeMemory().toFloat() / GB
        val totalSize = runTime.totalMemory().toFloat() / GB
        val usedSize = totalSize - freeSize

        onSensorUpdated(
            context,
            app_memory,
            usedSize.toBigDecimal().setScale(3, RoundingMode.HALF_EVEN),
            app_memory.statelessIcon,
            mapOf(
                "free_memory" to freeSize.toBigDecimal().setScale(3, RoundingMode.HALF_EVEN),
                "total_memory" to totalSize.toBigDecimal().setScale(3, RoundingMode.HALF_EVEN)
            )
        )
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AppSensorManagerEntryPoint {
        fun integrationRepository(): IntegrationRepository
        fun authenticationRepository(): AuthenticationRepository
    }

    private fun getIntegrationUseCase(context: Context): IntegrationRepository {
        return EntryPointAccessors.fromApplication(
            context,
            AppSensorManagerEntryPoint::class.java
        ).integrationRepository()
    }

    private fun getAuthenticationUseCase(context: Context): AuthenticationRepository {
        return EntryPointAccessors.fromApplication(
            context,
            AppSensorManagerEntryPoint::class.java
        ).authenticationRepository()
    }

    fun updateAppLock(context: Context, intent: Intent? = null) {
        if (!isEnabled(context, app_locked.id))
            return

        // if (intent != null && intent.extras != null && intent.hasExtra(ACTION_APP_LOCK_UPDATE_EXTRA)) {
        //     isAppLocked = intent.getBooleanExtra(ACTION_APP_LOCK_UPDATE_EXTRA, isAppLocked)
        // }

        val isAppLocked = runBlocking {
            getIntegrationUseCase(context).isAppLocked()
        }

        val icon = if (isAppLocked) "mdi:lock-outline" else "mdi:lock-open-outline"

        val timeout = runBlocking {
            getIntegrationUseCase(context).getSessionTimeOut()
        }
        val lock_app = runBlocking {
            getAuthenticationUseCase(context).isLockEnabledRaw()
        }
        val home_network_bypass = runBlocking {
            getAuthenticationUseCase(context).isLockHomeBypassEnabled()
        }

        val session_expire_millis: Long = runBlocking {
            getIntegrationUseCase(context).getSessionExpireMillis()
        }
        val cal: Calendar = GregorianCalendar()
        cal.timeInMillis = session_expire_millis
        val session_expire_dt = cal.time.toString()

        val session_expire_millis_report = if (isAppLocked) "" else session_expire_millis
        val session_expire_datetime_report = if (isAppLocked) "" else session_expire_dt

        Log.d(TAG, "updateAppLock(): isAppLocked: $isAppLocked, timeout: $timeout, lock_app: $lock_app, home_network_bypass: $home_network_bypass, session_expire: $session_expire_dt")

        onSensorUpdated(
            context,
            app_locked,
            isAppLocked,
            icon,
            mapOf(
                "lock_app" to lock_app,
                "unlock_on_home_network" to home_network_bypass,
                "timeout" to timeout,
                "session_expire_millis" to session_expire_millis_report,
                "session_expire" to session_expire_datetime_report
            )
        )
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun updateAppInactive(context: Context, usageStatsManager: UsageStatsManager) {
        if (!isEnabled(context, app_inactive.id))
            return

        val isAppInactive = usageStatsManager.isAppInactive(context.packageName)

        val icon = if (isAppInactive) "mdi:timer-off-outline" else "mdi:timer-outline"

        onSensorUpdated(
            context,
            app_inactive,
            isAppInactive,
            icon,
            mapOf()
        )
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun updateAppStandbyBucket(context: Context, usageStatsManager: UsageStatsManager) {
        if (!isEnabled(context, app_standby_bucket.id))
            return

        val appStandbyBucket = when (usageStatsManager.appStandbyBucket) {
            UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "active"
            UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "frequent"
            UsageStatsManager.STANDBY_BUCKET_RARE -> "rare"
            UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "restricted"
            UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "working_set"
            else -> "never"
        }

        onSensorUpdated(
            context,
            app_standby_bucket,
            appStandbyBucket,
            app_standby_bucket.statelessIcon,
            mapOf()
        )
    }

    private fun updateImportanceCheck(context: Context) {
        if (!isEnabled(context, app_importance.id))
            return

        val appManager = context.getSystemService<ActivityManager>()!!
        val currentProcess = appManager.runningAppProcesses
        var importance = "not_running"
        if (currentProcess != null) {
            for (item in currentProcess) {
                if (context.applicationInfo.processName == item.processName) {
                    importance = when (item.importance) {
                        ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> {
                            "cached"
                        }
                        ActivityManager.RunningAppProcessInfo.IMPORTANCE_CANT_SAVE_STATE -> {
                            "cant_save_state"
                        }
                        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> {
                            "foreground"
                        }
                        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> {
                            "foreground_service"
                        }
                        ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> {
                            "gone"
                        }
                        ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> {
                            "perceptible"
                        }
                        ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> {
                            "service"
                        }
                        ActivityManager.RunningAppProcessInfo.IMPORTANCE_TOP_SLEEPING -> {
                            "top_sleeping"
                        }
                        ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> {
                            "visible"
                        }
                        else -> "not_running"
                    }
                }
            }
        }

        onSensorUpdated(
            context,
            app_importance,
            importance,
            app_importance.statelessIcon,
            mapOf()
        )
    }
}
