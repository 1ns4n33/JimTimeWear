package com.jimtime.wear.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.app.ActivityCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController

class MainActivity : ComponentActivity() {

    private val viewModel: SessionViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BODY_SENSORS), 1)
        }

        setContent {
            val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()
            val heartRate by viewModel.heartRate.collectAsStateWithLifecycle()

            val navController = rememberSwipeDismissableNavController()

            SwipeDismissableNavHost(
                navController = navController,
                startDestination = "idle",
            ) {
                composable("idle") {
                    IdleScreen()
                }
                composable("session") {
                    SessionScreen(
                        sessionState = sessionState,
                        heartRate = heartRate,
                        onStop = viewModel::stopFromWatch,
                        onPause = viewModel::pauseFromWatch,
                        onResume = viewModel::resumeFromWatch,
                    )
                }
            }

            // Navigate based on session state from phone
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
