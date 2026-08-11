package com.coxy.ghost.perception

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * GHOST's perception layer. Buckets raw sensor + GMS data into a [ContextState] held in the
 * process-wide [ContextStore], so [com.coxy.ghost.MainActivity] can read it cross-component
 * without binding to the service.
 *
 * "Best of both" (per the Phase 4 design): GMS [ActivityRecognitionClient] +
 * [FusedLocationProviderClient] for activity + fine location; framework [SensorManager] for
 * light, proximity, and step counter. If GMS is unavailable (ColorOS throttles it, or Play
 * Services missing), activity + location degrade to framework motion sensors + [LocationManager];
 * the `gmsAvailable` flag surfaces the degradation to the UI and the [CONTEXT] block.
 *
 * Threading: runs on its own [HandlerThread] ("ghost-sensor") for sensor/location callbacks and a
 * [Dispatchers.Default] coroutine scope for the step-rate loop. It NEVER touches the
 * InferenceEngine or its single-threaded `llamaDispatcher` — it only writes [ContextState].
 */
class PerceptionManager(private val ctx: Context) {

    companion object {
        private const val TAG = "PerceptionManager"

        @Volatile
        @JvmStatic
        var isRunning: Boolean = false
            private set

        private const val ACTION_ACTIVITY = "com.coxy.ghost.PERCEPTION_ACTIVITY"
        private const val LIGHT_WRITE_THROTTLE_MS = 2000L
        private const val STEP_RATE_INTERVAL_MS = 5000L
        private const val AR_INTERVAL_MS = 30_000L
        private const val LOC_INTERVAL_MS = 60_000L
        private const val LOC_FASTEST_MS = 30_000L
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val sensorThread = HandlerThread("ghost-sensor").apply { start() }
    private val sensorHandler = Handler(sensorThread.looper)

    private val sensorManager =
        ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    // GMS clients (nullable when GMS unavailable / degraded).
    private var activityClient: com.google.android.gms.location.ActivityRecognitionClient? = null
    private var fusedClient: FusedLocationProviderClient? = null
    private var gmsOk: Boolean = false

    // Framework fallback location.
    private var frameworkLocManager: LocationManager? = null

    private var activityPendingIntent: PendingIntent? = null
    private var activityReceiver: BroadcastReceiver? = null
    private var fusedCallback: LocationCallback? = null

    private var stepJob: Job? = null

    @Volatile private var lastLightWriteMs: Long = 0L
    @Volatile private var lastStepCount: Float = -1f
    @Volatile private var lastStepTs: Long = 0L
    @Volatile private var currentStepRate: Int = 0

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_LIGHT -> {
                    val lux = event.values.firstOrNull() ?: return
                    val now = System.currentTimeMillis()
                    // Throttle writes: light fires ~5Hz; state only needs to update every ~2s.
                    if (now - lastLightWriteMs < LIGHT_WRITE_THROTTLE_MS) return
                    lastLightWriteMs = now
                    val bucket = bucketLight(lux)
                    update { st ->
                        // If the phone is in-pocket (proximity NEAR), force DARK regardless of lux.
                        val effective = if (st.proximity == Proximity.NEAR) LightBucket.DARK else bucket
                        st.copy(lightBucket = effective, lightLux = lux, timestampMs = now)
                    }
                }
                Sensor.TYPE_PROXIMITY -> {
                    val near = (event.values.firstOrNull() ?: return) < (event.sensor.maximumRange.coerceAtMost(1f))
                    val prox = if (near) Proximity.NEAR else Proximity.FAR
                    update { st ->
                        // NEAR (in-pocket) overrides light → DARK.
                        val light = if (prox == Proximity.NEAR) LightBucket.DARK else st.lightBucket
                        st.copy(proximity = prox, lightBucket = light, timestampMs = System.currentTimeMillis())
                    }
                }
                Sensor.TYPE_STEP_COUNTER -> {
                    val count = event.values.firstOrNull() ?: return
                    val now = System.currentTimeMillis()
                    if (lastStepCount >= 0f && now > lastStepTs) {
                        val dt = now - lastStepTs
                        val dSteps = count - lastStepCount
                        if (dt > 0 && dSteps >= 0) {
                            // steps/min = (delta / dt_ms) * 60000
                            val rate = ((dSteps / dt) * 60_000f).toInt().coerceAtLeast(0)
                            currentStepRate = rate
                            update { it.copy(stepRatePerMin = rate, timestampMs = now) }
                        }
                    }
                    lastStepCount = count
                    lastStepTs = now
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        Log.i(TAG, "starting perception")

        // --- Framework sensors (always available; no GMS needed) ---
        val light = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        val prox = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        val step = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (light != null) sensorManager.registerListener(sensorListener, light, SensorManager.SENSOR_DELAY_NORMAL, sensorHandler)
        if (prox != null) sensorManager.registerListener(sensorListener, prox, SensorManager.SENSOR_DELAY_NORMAL, sensorHandler)
        if (step != null && hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)) {
            sensorManager.registerListener(sensorListener, step, SensorManager.SENSOR_DELAY_NORMAL, sensorHandler)
            stepJob = scope.launch {
                while (isRunning) {
                    delay(STEP_RATE_INTERVAL_MS)
                    // If no new step events in this interval, decay the rate toward 0.
                    if (System.currentTimeMillis() - lastStepTs > STEP_RATE_INTERVAL_MS * 2 && currentStepRate > 0) {
                        currentStepRate = 0
                        update { it.copy(stepRatePerMin = 0) }
                    }
                }
            }
        }

        // --- GMS availability ---
        gmsOk = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx) ==
            com.google.android.gms.common.ConnectionResult.SUCCESS
        update { it.copy(gmsAvailable = gmsOk) }

        if (gmsOk) {
            startGmsActivity()
            startGmsLocation()
        } else {
            Log.w(TAG, "GMS unavailable — degrading to framework LocationManager + motion sensors")
            startFrameworkLocation()
            // Activity degrades: leave UNKNOWN unless step/motion sensors indicate otherwise.
            // (StationaryDetect/MotionDetect could refine STILL here — optional, deferred.)
        }
    }

    private fun startGmsActivity() {
        if (!hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)) {
            Log.i(TAG, "ACTIVITY_RECOGNITION not granted — activity recognition disabled")
            return
        }
        runCatching {
            activityClient = ActivityRecognition.getClient(ctx)
            activityReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val result = ActivityRecognitionResult.extractResult(intent)
                    if (result == null) {
                        Log.w(TAG, "AR broadcast received but extractResult=null")
                        return
                    }
                    val best = result.probableActivities.maxByOrNull { it.confidence }
                    if (best == null) {
                        Log.w(TAG, "AR broadcast received but no probable activities")
                        return
                    }
                    Log.i(TAG, "AR update: ${activityName(best.type)} conf=${best.confidence}")
                    if (best.confidence < 50) return // keep previous state; low-confidence
                    val mapped = mapDetectedActivity(best.type, currentStepRate)
                    update {
                        it.copy(
                            activity = mapped,
                            activityConfidence = best.confidence,
                            timestampMs = System.currentTimeMillis(),
                        )
                    }
                }
            }
            val filter = IntentFilter(ACTION_ACTIVITY)
            // Android 13+ requires the explicit exported flag on runtime receivers.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(activityReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                ctx.registerReceiver(activityReceiver, filter)
            }
            val intent = Intent(ACTION_ACTIVITY).setPackage(ctx.packageName)
            // FLAG_MUTABLE is required: GMS attaches the ActivityRecognitionResult as extras to
            // the PendingIntent when it fires (an immutable PendingIntent would arrive with
            // extras=null and extractResult would fail). The intent is explicit + package-scoped,
            // so the mutable broadcast can only be delivered to this app.
            val pi = PendingIntent.getBroadcast(
                ctx, 0, intent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            activityPendingIntent = pi
            activityClient!!.requestActivityUpdates(AR_INTERVAL_MS, pi)
                .addOnSuccessListener { Log.i(TAG, "requestActivityUpdates OK") }
                .addOnFailureListener { Log.w(TAG, "requestActivityUpdates failed", it) }
        }.onFailure { Log.w(TAG, "GMS activity recognition init failed", it) }
    }

    private fun startGmsLocation() {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            Log.i(TAG, "ACCESS_FINE_LOCATION not granted — GMS location disabled")
            return
        }
        runCatching {
            fusedClient = LocationServices.getFusedLocationProviderClient(ctx)
            fusedCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val loc = result.lastLocation
                    if (loc == null) { Log.w(TAG, "fused onLocationResult: null location"); return }
                    Log.i(TAG, "fused location fix: lat=${loc.latitude} lon=${loc.longitude} speed=${loc.speed}m/s hasSpeed=${loc.hasSpeed()}")
                    handleLocation(loc)
                }
            }
            val request = LocationRequest.Builder(LOC_INTERVAL_MS)
                .setMinUpdateIntervalMillis(LOC_FASTEST_MS)
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .build()
            fusedClient!!.requestLocationUpdates(request, fusedCallback!!, sensorThread.looper)
                .addOnSuccessListener { Log.i(TAG, "requestLocationUpdates (fused) OK") }
                .addOnFailureListener { Log.w(TAG, "requestLocationUpdates (fused) failed", it) }
        }.onFailure { Log.w(TAG, "GMS fused location init failed — falling back to framework", it) }
            // If fused init threw, try the framework location manager.
            .also { if (fusedClient == null) startFrameworkLocation() }
    }

    /** Framework fallback when GMS is unavailable. */
    private fun startFrameworkLocation() {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) &&
            !hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) return
        runCatching {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            frameworkLocManager = lm
            val provider = if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) &&
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER
            else LocationManager.NETWORK_PROVIDER
            if (!lm.isProviderEnabled(provider)) {
                Log.w(TAG, "framework location provider $provider disabled")
                return@runCatching
            }
            // The (provider, minTime, minDist, listener, Looper) overload still works on API 34;
            // the Executor variant is the non-deprecated replacement but needs an Executor.
            @Suppress("DEPRECATION")
            lm.requestLocationUpdates(provider, LOC_INTERVAL_MS, 0f, frameworkLocListener, sensorThread.looper)
        }.onFailure { Log.w(TAG, "framework location init failed", it) }
    }

    private val frameworkLocListener = object : android.location.LocationListener {
        override fun onLocationChanged(location: Location) { handleLocation(location) }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("kept for compat")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    }

    private fun handleLocation(loc: Location) {
        val speedKmh = if (loc.hasSpeed()) loc.speed * 3.6f else 0f
        // Home/away learning is deferred past Phase 4; report UNKNOWN for the bucket.
        update { st ->
            // Speed-based activity inference — a backstop when AR hasn't reported (or is delayed).
            // Only fills UNKNOWN/STILL; never overrides a confident AR reading (WALKING/RUNNING/
            // IN_VEHICLE/ON_BICYCLE). >8 km/h with no positive activity → IN_VEHICLE.
            val activity = when {
                speedKmh > 8f && (st.activity == Activity.UNKNOWN || st.activity == Activity.STILL) ->
                    Activity.IN_VEHICLE
                speedKmh <= 1f && st.activity == Activity.UNKNOWN -> Activity.STILL
                else -> st.activity
            }
            val conf = if (activity != st.activity && activity != Activity.UNKNOWN) 0 else st.activityConfidence
            st.copy(
                locationBucket = LocationBucket.UNKNOWN,
                speedKmh = speedKmh,
                activity = activity,
                activityConfidence = conf,
                timestampMs = System.currentTimeMillis(),
            )
        }
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        Log.i(TAG, "stopping perception")
        runCatching { sensorManager.unregisterListener(sensorListener) }
        stepJob?.cancel()
        runCatching {
            activityPendingIntent?.let { activityClient?.removeActivityUpdates(it) }
        }
        activityClient = null
        activityPendingIntent = null
        runCatching { activityReceiver?.let { ctx.unregisterReceiver(it) } }
        activityReceiver = null
        runCatching { fusedCallback?.let { fusedClient?.removeLocationUpdates(it) } }
        fusedClient = null
        fusedCallback = null
        runCatching { frameworkLocManager?.removeUpdates(frameworkLocListener) }
        frameworkLocManager = null
        runCatching { scope.cancel() }
        runCatching { sensorThread.quitSafely() }
        // Reset only the sensor/GMS fields we own — leave mic/camera state (owned by MicListener /
        // CameraCapture) intact. On a full service stop those components reset their own fields.
        update { cs ->
            cs.copy(
                activity = Activity.UNKNOWN,
                activityConfidence = 0,
                locationBucket = LocationBucket.UNKNOWN,
                speedKmh = 0f,
                lightBucket = LightBucket.UNKNOWN,
                lightLux = -1f,
                proximity = Proximity.UNKNOWN,
                stepRatePerMin = 0,
                gmsAvailable = true,
                timestampMs = 0L,
            )
        }
    }

    // --- helpers ---

    private fun update(transform: (ContextState) -> ContextState) {
        ContextStore.update(transform)
    }

    private fun hasPermission(perm: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    private fun bucketLight(lux: Float): LightBucket = when {
        lux < 10f -> LightBucket.DARK
        lux < 50f -> LightBucket.DIM
        lux < 1000f -> LightBucket.NORMAL
        else -> LightBucket.BRIGHT
    }

    private fun mapDetectedActivity(type: Int, stepRate: Int): Activity = when (type) {
        DetectedActivity.STILL -> Activity.STILL
        DetectedActivity.IN_VEHICLE -> Activity.IN_VEHICLE
        DetectedActivity.ON_BICYCLE -> Activity.ON_BICYCLE
        DetectedActivity.RUNNING -> Activity.RUNNING
        DetectedActivity.WALKING -> Activity.WALKING
        DetectedActivity.ON_FOOT -> if (stepRate > 130) Activity.RUNNING else Activity.WALKING
        DetectedActivity.TILTING -> Activity.TILTING
        else -> Activity.UNKNOWN
    }

    private fun activityName(type: Int): String = when (type) {
        DetectedActivity.STILL -> "STILL"
        DetectedActivity.IN_VEHICLE -> "IN_VEHICLE"
        DetectedActivity.ON_BICYCLE -> "ON_BICYCLE"
        DetectedActivity.RUNNING -> "RUNNING"
        DetectedActivity.WALKING -> "WALKING"
        DetectedActivity.ON_FOOT -> "ON_FOOT"
        DetectedActivity.TILTING -> "TILTING"
        else -> "type=$type"
    }
}