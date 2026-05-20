package com.jimtime.wear.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController

class MainActivity : ComponentActivity() {

    private val viewModel: SessionViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* permissions handled — GPS will work if granted */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        val neededPermissions = arrayOf(
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ).filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (neededPermissions.isNotEmpty()) {
            permissionLauncher.launch(neededPermissions)
        }

        setContent {
            val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()
            val heartRate    by viewModel.heartRate.collectAsStateWithLifecycle()

            val navController = rememberSwipeDismissableNavController()

            SwipeDismissableNavHost(
                navController    = navController,
                startDestination = "idle",
            ) {
                composable("idle") {
                    IdleScreen(onStart = { activityType ->
                        viewModel.startFromWatch(activityType)
                    })
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
