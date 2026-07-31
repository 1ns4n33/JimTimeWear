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
            val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()
            val heartRate    by viewModel.heartRate.collectAsStateWithLifecycle()
            val planDays     by viewModel.planDays.collectAsStateWithLifecycle()
            val planName     by viewModel.planName.collectAsStateWithLifecycle()

            val navController = rememberSwipeDismissableNavController()

            SwipeDismissableNavHost(
                navController    = navController,
                startDestination = "idle",
            ) {
                composable("idle") {
                    var phoneNeeded by remember { mutableStateOf(false) }
                    IdleScreen(
                        onStart = { activityType ->
                            viewModel.startFromWatch(activityType)
                        },
                        planName = planName,
                        planDays = planDays,
                        showPhoneNeeded = phoneNeeded,
                        onStartInterval = { spec ->
                            viewModel.startIntervalStandalone(spec)
                        },
                        onStartPlanDay = { day ->
                            viewModel.startPlanDayFromWatch(day.week, day.day) { ok ->
                                phoneNeeded = !ok
                            }
                        },
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
}
