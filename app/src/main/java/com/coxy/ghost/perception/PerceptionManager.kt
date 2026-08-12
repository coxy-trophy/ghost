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

        // Phase 4.5 vendor sensors. These have no Sensor.TYPE_* constant — they are Spreadtrum
        // additions resolved by string type off the full sensor list.
        private const val VENDOR_POCKET_MODE = "com.spreadtrum.pocket_mode"
        private const val VENDOR_FACE_UP_DOWN = "com.spreadtrum.face_up_down"

        /** Orientation/heading come from the fused rotation vector; writes are throttled to this. */
        private const val ORIENTATION_WRITE_THROTTLE_MS = 3000L

        // Sensor types the device reports but the public SDK does not expose as Sensor.TYPE_*
        // constants. The numbers are the platform's own, from android.hardware.Sensor.
        private const val TYPE_TILT_DETECTOR = 22
        private const val TYPE_PICK_UP_GESTURE = 25
        private val COMPASS_POINTS =
            listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
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
    @Volatile private var lastOrientationWriteMs: Long = 0L
    /** True once the vendor face-up/down sensor has reported, after which it wins over pitch/roll. */
    @Volatile private var vendorPostureSeen: Boolean = false
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
                Sensor.TYPE_STEP_DETECTOR -> {
                    // Per-step trigger. The counter above computes the rate; this just keeps
                    // lastStepTs fresh so the decay loop doesn't zero the rate mid-stride.
                    lastStepTs = System.currentTimeMillis()
                }
                Sensor.TYPE_ROTATION_VECTOR -> handleRotationVector(event)
                TYPE_PICK_UP_GESTURE, TYPE_TILT_DETECTOR -> notePickUp()
                else -> handleVendorSensor(event)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    /**
     * Vendor sensors dispatch here because their types are Spreadtrum-specific ints with no
     * platform constant, so they cannot appear as `when` branches above.
     */
    private fun handleVendorSensor(event: SensorEvent) {
        val v = event.values.firstOrNull() ?: return
        when (event.sensor.stringType) {
            VENDOR_POCKET_MODE -> {
                val pocketed = v > 0.5f
                update { st ->
                    // In a pocket it is dark regardless of what the light sensor last saw.
                    val light = if (pocketed) LightBucket.DARK else st.lightBucket
                    st.copy(inPocket = pocketed, lightBucket = light, timestampMs = System.currentTimeMillis())
                }
            }
            VENDOR_FACE_UP_DOWN -> {
                // 1 = face up, 2 = face down on this vendor HAL; anything else is inconclusive.
                val posture = when (v.toInt()) {
                    1 -> Posture.FACE_UP
                    2 -> Posture.FACE_DOWN
                    else -> return
                }
                vendorPostureSeen = true
                update { it.copy(posture = posture, timestampMs = System.currentTimeMillis()) }
            }
        }
    }

    /**
     * Orientation + compass heading from the fused rotation vector, which the HAL already computes
     * from gyroscope + accelerometer + magnetometer. Registering those three raw streams on top
     * would wake the CPU at up to 100 Hz for data we would only re-fuse ourselves.
     *
     * Writes are throttled: the vector arrives continuously, but posture and an eight-point compass
     * heading change far more slowly than the sensor reports them.
     */
    private fun handleRotationVector(event: SensorEvent) {
        val now = System.currentTimeMillis()
        if (now - lastOrientationWriteMs < ORIENTATION_WRITE_THROTTLE_MS) return
        lastOrientationWriteMs = now

        val rotation = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        val angles = FloatArray(3)
        SensorManager.getOrientation(rotation, angles)

        val azimuth = ((Math.toDegrees(angles[0].toDouble()) + 360.0) % 360.0)
        val point = COMPASS_POINTS[((azimuth + 22.5) / 45.0).toInt() % 8]

        // pitch/roll decide attitude when the vendor face-up/down sensor is absent or quiet.
        val pitchDeg = Math.toDegrees(angles[1].toDouble())
        val rollDeg = Math.toDegrees(angles[2].toDouble())
        val flat = kotlin.math.abs(pitchDeg) < 25 && kotlin.math.abs(rollDeg) < 25
        val inverted = kotlin.math.abs(rollDeg) > 155
        val derived = when {
            flat -> Posture.FACE_UP
            inverted -> Posture.FACE_DOWN
            kotlin.math.abs(pitchDeg) > 60 -> Posture.UPRIGHT
            else -> Posture.TILTED
        }

        update { st ->
            // The vendor face-up/down sensor is authoritative when it has spoken; this only fills in.
            val posture = if (vendorPostureSeen) st.posture else derived
            st.copy(posture = posture, heading = point, timestampMs = now)
        }
    }

    private fun notePickUp() {
        val now = System.currentTimeMillis()
        update { it.copy(lastPickUpMs = now, timestampMs = now) }
    }

    /**
     * One-shot sensors (stationary detect, motion detect, significant motion) do not work through
     * [SensorManager.registerListener] at all — the HAL arms them, fires once, and disarms. They
     * need [SensorManager.requestTriggerSensor] and must be re-armed by hand after every event,
     * which is exactly what makes them nearly free: silent until the state actually flips.
     */
    private val triggerListener = object : android.hardware.TriggerEventListener() {
        override fun onTrigger(event: android.hardware.TriggerEvent) {
            val now = System.currentTimeMillis()
            // Arm ONLY the opposite transition. Re-arming the sensor that just fired spins: a
            // stationary phone re-triggers stationary-detect the instant it is re-armed, which
            // woke the app every ~5s until this was fixed. The pair is a state machine — once we
            // know it is still, the only news is that it started moving.
            when (event.sensor.type) {
                Sensor.TYPE_STATIONARY_DETECT -> {
                    update { it.copy(motion = MotionState.STATIONARY, timestampMs = now) }
                    armTrigger(Sensor.TYPE_MOTION_DETECT)
                }
                Sensor.TYPE_MOTION_DETECT -> {
                    update { it.copy(motion = MotionState.MOVING, timestampMs = now) }
                    armTrigger(Sensor.TYPE_STATIONARY_DETECT)
                }
                Sensor.TYPE_SIGNIFICANT_MOTION -> {
                    // Not part of the pair: it fires only on genuine sustained motion, so
                    // re-arming itself is correct and does not spin.
                    update { it.copy(motion = MotionState.MOVING, timestampMs = now) }
                    armTrigger(Sensor.TYPE_SIGNIFICANT_MOTION)
                }
            }
        }
    }

    private fun armTrigger(type: Int) {
        if (!isRunning) return
        val s = sensorManager.getDefaultSensor(type) ?: return
        runCatching { sensorManager.requestTriggerSensor(triggerListener, s) }
            .onFailure { Log.w(TAG, "requestTriggerSensor failed for type $type", it) }
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

        registerPostureAndMotionSensors()

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

    /**
     * Phase 4.5: everything the device can tell GHOST about how it is being held and whether it is
     * moving. Deliberately biased toward on-change and one-shot sensors — they stay silent until
     * the state actually changes, which is why this whole tier costs almost nothing at idle.
     *
     * The one continuous registration is the rotation vector (throttled at write time), because
     * heading has no event-driven equivalent.
     */
    private fun registerPostureAndMotionSensors() {
        // One-shot sensors, armed via requestTriggerSensor. Only ONE of the stationary/motion pair
        // is armed here: whichever fires arms its opposite from then on. Arming both would have
        // the stationary sensor fire immediately on a resting phone while motion sat armed too,
        // and the resulting churn is what this pair exists to avoid.
        armTrigger(Sensor.TYPE_STATIONARY_DETECT)
        armTrigger(Sensor.TYPE_SIGNIFICANT_MOTION)

        // Special-trigger sensors: these DO use registerListener. Their types are not exposed as
        // public Sensor.TYPE_* constants, so they are resolved by number.
        for (type in listOf(TYPE_PICK_UP_GESTURE, TYPE_TILT_DETECTOR)) {
            val s = sensorManager.getDefaultSensor(type)
            if (s == null) { Log.i(TAG, "sensor type $type not present"); continue }
            runCatching {
                sensorManager.registerListener(sensorListener, s, SensorManager.SENSOR_DELAY_NORMAL, sensorHandler)
                Log.i(TAG, "registered ${s.stringType}")
            }.onFailure { Log.w(TAG, "failed to register sensor type $type", it) }
        }

        // Step detector needs the same permission as the counter.
        if (hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)) {
            sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let {
                runCatching {
                    sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_NORMAL, sensorHandler)
                }
            }
        }

        // Continuous, but only written every ORIENTATION_WRITE_THROTTLE_MS.
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            runCatching {
                sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI, sensorHandler)
            }.onFailure { Log.w(TAG, "rotation vector registration failed", it) }
        } ?: Log.i(TAG, "no rotation vector sensor — heading/posture will stay unknown")

        // Vendor sensors, resolved by string type off the full list.
        val vendorWanted = setOf(VENDOR_POCKET_MODE, VENDOR_FACE_UP_DOWN)
        val vendor = runCatching {
            sensorManager.getSensorList(Sensor.TYPE_ALL).filter { it.stringType in vendorWanted }
        }.getOrDefault(emptyList())
        for (s in vendor) {
            runCatching {
                sensorManager.registerListener(sensorListener, s, SensorManager.SENSOR_DELAY_NORMAL, sensorHandler)
                Log.i(TAG, "registered vendor sensor ${s.stringType}")
            }.onFailure { Log.w(TAG, "failed to register ${s.stringType}", it) }
        }
        if (vendor.none { it.stringType == VENDOR_POCKET_MODE }) {
            Log.i(TAG, "no vendor pocket-mode sensor — inPocket stays unknown (proximity still applies)")
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
        // Trigger sensors are cancelled individually, not by unregisterListener.
        for (type in listOf(
            Sensor.TYPE_STATIONARY_DETECT, Sensor.TYPE_MOTION_DETECT, Sensor.TYPE_SIGNIFICANT_MOTION
        )) {
            runCatching {
                sensorManager.getDefaultSensor(type)?.let {
                    sensorManager.cancelTriggerSensor(triggerListener, it)
                }
            }
        }
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
                // Phase 4.5 fields owned by this manager.
                posture = Posture.UNKNOWN,
                inPocket = null,
                heading = null,
                motion = MotionState.UNKNOWN,
                lastPickUpMs = 0L,
            )
        }
        vendorPostureSeen = false
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