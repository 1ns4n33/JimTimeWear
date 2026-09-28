package com.jimtime.wear.presentation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.jimtime.wear.data.SessionRepository
import com.jimtime.wear.health.TrackingEngine
import com.jimtime.wear.health.TrackingService

class MainActivity : ComponentActivity() {

    private val viewModel: SessionViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* permissions handled — GPS will work if granted */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        val neededPermissions = mutableListOf(
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            // F5b (Nuoto) — ExerciseClient (HealthServicesExerciseSource)
            // rifiuta startExercise senza questo permesso runtime.
            Manifest.permission.ACTIVITY_RECOGNITION,
        ).apply {
            // API 33+: senza, il FGS di TrackingService parte comunque ma
            // la sua notifica/chip resta invisibile all'utente.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (neededPermissions.isNotEmpty()) {
            permissionLauncher.launch(neededPermissions)
        }

        // Un crash mentre TrackingService non era stato (ancora) ri-agganciato
        // da START_STICKY deve comunque essere recuperato alla prossima
        // apertura dell'app — stessa logica idempotente usata dal restart
        // sticky del service.
        TrackingEngine.init(applicationContext)
        TrackingEngine.recoverIfNeeded(applicationContext)
        val recovered = SessionRepository.state.value
        if (recovered.isActive && recovered.isStandalone) {
            ContextCompat.startForegroundService(
                this, Intent(this, TrackingService::class.java),
            )
        }

        setContent {
            val sessionState     by viewModel.sessionState.collectAsStateWithLifecycle()
            val heartRate        by viewModel.heartRate.collectAsStateWithLifecycle()
            val planDays         by viewModel.planDays.collectAsStateWithLifecycle()
            val planName         by viewModel.planName.collectAsStateWithLifecycle()
            val dailySummary     by viewModel.dailySummary.collectAsStateWithLifecycle()
            val summaryReceivedAt by viewModel.summaryReceivedAt.collectAsStateWithLifecycle()
            val pendingWaterMl   by viewModel.pendingWaterMl.collectAsStateWithLifecycle()
            val pendingWaterCount by viewModel.pendingWaterCount.collectAsStateWithLifecycle()
            val lastUsedType     by viewModel.lastUsedType.collectAsStateWithLifecycle()
            val swimHeartRate    by viewModel.swimHeartRate.collectAsStateWithLifecycle()
            val swimGuide        by viewModel.swimGuide.collectAsStateWithLifecycle()
            val swimWorkouts     by viewModel.swimWorkouts.collectAsStateWithLifecycle()

            val navController = rememberSwipeDismissableNavController()

            // Un solo countdown alla volta: due tap ravvicinati su due cerchi
            // (o sullo stesso) impilavano due destinazioni "countdown/*" e
            // "Annulla" riportava sul PRIMO countdown, che ripartiva da 3 e
            // poteva avviare un'attività che l'atleta credeva annullata.
            val openCountdown: (String) -> Unit = { type ->
                val onCountdown = navController.currentDestination?.route
                    ?.startsWith("countdown/") == true
                if (!onCountdown) navController.navigate("countdown/$type")
            }

            // F5b (Nuoto) — swim_pool/swim_open_water non passano dal
            // 3‑2‑1 generico: prima serve la lunghezza vasca (SwimStartScreen).
            // Stessa guardia "un solo flusso di avvio alla volta" di
            // openCountdown, per il tap ripetuto sulla stessa attività
            // dall'home (`lastUsedType`) o dal picker.
            val openSwimStart: (String) -> Unit = { type ->
                val onSwimStart = navController.currentDestination?.route
                    ?.startsWith("swimStart/") == true
                if (!onSwimStart) navController.navigate("swimStart/$type")
            }
            val onTapActivity: (String) -> Unit = { type ->
                if (type == "swim_pool" || type == "swim_open_water") openSwimStart(type) else openCountdown(type)
            }

            SwipeDismissableNavHost(
                navController    = navController,
                startDestination = "idle",
            ) {
                composable("idle") {
                    var phoneNeeded by remember { mutableStateOf(false) }
                    HomeScreen(
                        dailySummary       = dailySummary,
                        summaryReceivedAt  = summaryReceivedAt,
                        pendingWaterMl     = pendingWaterMl,
                        pendingWaterCount  = pendingWaterCount,
                        lastUsedType       = lastUsedType,
                        planName           = planName,
                        planDays           = planDays,
                        showPhoneNeeded    = phoneNeeded,
                        onStartPlanDay     = { week, day ->
                            viewModel.startPlanDayFromWatch(week, day) { ok ->
                                phoneNeeded = !ok
                            }
                        },
                        onQuickStart       = onTapActivity,
                        onOpenPicker       = { navController.navigate("pick") },
                        onOpenIntervals    = { navController.navigate("intervals") },
                        onAddWater         = { delta -> viewModel.addWater(delta) },
                        onRefresh          = { viewModel.requestDailySummary() },
                    )
                }
                composable("pick") {
                    var phoneNeeded by remember { mutableStateOf(false) }
                    ActivityPickerScreen(
                        planName = planName,
                        planDays = planDays,
                        onStartPlanDay = { day ->
                            viewModel.startPlanDayFromWatch(day.week, day.day) { ok ->
                                phoneNeeded = !ok
                            }
                        },
                        onTapActivity = onTapActivity,
                        showPhoneNeeded = phoneNeeded,
                        onRequestPlanDays = { viewModel.requestPlanDays() },
                    )
                }
                composable(
                    route = "swimStart/{type}",
                    arguments = listOf(navArgument("type") { type = NavType.StringType }),
                ) { backStackEntry ->
                    val activityType = backStackEntry.arguments?.getString("type") ?: "swim_pool"
                    SwimStartScreen(
                        activityType = activityType,
                        workouts = swimWorkouts,
                        onConfirm = { poolLengthM, workout ->
                            val location = if (activityType == "swim_open_water") "OPEN_WATER" else "POOL"
                            viewModel.startSwimFromWatch(activityType, location, poolLengthM, workout)
                        },
                        onCancel = { navController.popBackStack() },
                    )
                }
                composable("intervals") {
                    IntervalsScreen(
                        onStartInterval = { spec ->
                            viewModel.startIntervalStandalone(spec)
                        },
                    )
                }
                composable(
                    route = "countdown/{type}",
                    arguments = listOf(navArgument("type") { type = NavType.StringType }),
                ) { backStackEntry ->
                    val activityType = backStackEntry.arguments?.getString("type") ?: "run"
                    CountdownScreen(
                        activityType = activityType,
                        onGo = { type -> viewModel.startFromWatch(type) },
                        onCancel = { navController.popBackStack() },
                    )
                }
                composable("session") {
                    // Single route, two layouts — the phone tags the
                    // session as `workout` or `activity` via the
                    // `kind` field in the start payload.
                    if (sessionState.isWorkout()) {
                        WorkoutSessionScreen(
                            sessionState  = sessionState,
                            heartRate     = heartRate,
                            onCompleteSet = viewModel::completeSetFromWatch,
                            onSkipRest    = viewModel::skipRestFromWatch,
                            onStop        = viewModel::stopFromWatch,
                            onPause       = viewModel::pauseFromWatch,
                            onResume      = viewModel::resumeFromWatch,
                        )
                    } else if (sessionState.isSwim()) {
                        SwimSessionScreen(
                            sessionState = sessionState,
                            heartRate    = swimHeartRate,
                            guide        = swimGuide,
                            onStop       = viewModel::stopFromWatch,
                            onPause      = viewModel::pauseFromWatch,
                            onResume     = viewModel::resumeFromWatch,
                            onNextSet    = viewModel::nextSwimSet,
                        )
                    } else {
                        SessionScreen(
                            sessionState = sessionState,
                            heartRate    = heartRate,
                            isStandalone = sessionState.isStandalone,
                            onStop       = viewModel::stopFromWatch,
                            onPause      = viewModel::pauseFromWatch,
                            onResume     = viewModel::resumeFromWatch,
                        )
                    }
                }
            }

            LaunchedEffect(sessionState.isActive) {
                if (sessionState.isActive) {
                    navController.navigate("session") {
                        popUpTo("idle") { inclusive = false }
                        launchSingleTop = true
                    }
                } else {
                    if (navController.currentDestination?.route == "session") {
                        navController.popBackStack("idle", inclusive = false)
                    }
                }
            }
        }
    }

    /// Pull fresco del riepilogo "Oggi" + flush della coda acqua ad ogni
    /// ritorno in foreground (spec-contract.md regola 2 e 5 — il transport
    /// è message-only, mai applicationContext, quindi il polso deve tirare
    /// lui i dati appena torna visibile).
    override fun onResume() {
        super.onResume()
        viewModel.onForeground()
    }
}
