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
import androidx.compose.runtime.mutableIntStateOf
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
import com.jimtime.wear.BuildConfig
import com.jimtime.wear.data.DailySummaryStore
import com.jimtime.wear.data.GuidedStepMirror
import com.jimtime.wear.data.MessagePaths
import com.jimtime.wear.data.PendingRouteStore
import com.jimtime.wear.data.PendingSyncCoordinator
import com.jimtime.wear.data.PendingSyncStatusStore
import com.jimtime.wear.data.PlanDaysStore
import com.jimtime.wear.data.SessionRepository
import com.jimtime.wear.data.WristGuidedStart
import com.jimtime.wear.guided.GuidedRunRepository
import com.jimtime.wear.guided.PlanStepRole
import com.jimtime.wear.guided.ResolvedEnd
import com.jimtime.wear.guided.ResolvedEndType
import com.jimtime.wear.guided.ResolvedStep
import com.jimtime.wear.guided.ResolvedStructure
import com.jimtime.wear.guided.ResolvedStructureEntry
import com.jimtime.wear.guided.ResolvedTarget
import com.jimtime.wear.guided.ResolvedTargetKind
import com.jimtime.wear.guided.StepEvent
import com.jimtime.wear.guided.StepUi
import com.jimtime.wear.health.TrackingEngine
import com.jimtime.wear.health.TrackingService
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private val viewModel: SessionViewModel by viewModels()

    /// F7 (correzioni WP-WD) — ogni richiesta «apri la sessione»
    /// (EXTRA_OPEN_SESSION in onCreate/onNewIntent) incrementa il contatore:
    /// l'effetto di navigazione si ripete anche se `isActive` non cambia.
    private var openSessionRequests by mutableIntStateOf(0)

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
        handleLaunchExtras(intent)

        // Solo build DEBUG (mai in release, vedi le funzioni): screenshot
        // di PendingSyncScreen / GuidedSessionScreen senza dover davvero
        // registrare 4 sessioni offline o avviare una scheda guidata vera.
        if (BuildConfig.DEBUG) {
            maybeSeedFakePendingSync(intent)
            maybeSeedFakeGuided(intent)
            maybeSeedFakeSportDays(intent)
            maybeSeedFakeCompanion(intent)
        }
        val openPendingSyncOnStart = BuildConfig.DEBUG &&
            intent.getBooleanExtra("JT_OPEN_PENDING_SYNC", false)

        // F7 (J12 + correzioni) — EXTRA_OPEN_SESSION (avvio dal telefono,
        // chip OngoingActivity) NON cambia più la destinazione iniziale:
        // si parte SEMPRE da "idle" e l'effetto sotto naviga a "session",
        // così dopo lo stop c'è "idle" a cui tornare.

        setContent {
            val sessionState     by viewModel.sessionState.collectAsStateWithLifecycle()
            val heartRate        by viewModel.heartRate.collectAsStateWithLifecycle()
            val planDays         by viewModel.planDays.collectAsStateWithLifecycle()
            val planName         by viewModel.planName.collectAsStateWithLifecycle()
            // F7 (§2.9/J13) — `sportDays` sorgente `PlanDaysStore.sportDays`
            // (per il picker: l'hero della home usa `PlanDaysStore.findActivity`
            // direttamente); `guidedRun` mirror di `GuidedRunRepository.current`.
            val sportDays        by viewModel.sportDays.collectAsStateWithLifecycle()
            val guidedRun        by viewModel.guidedRun.collectAsStateWithLifecycle()
            val dailySummary     by viewModel.dailySummary.collectAsStateWithLifecycle()
            val summaryReceivedAt by viewModel.summaryReceivedAt.collectAsStateWithLifecycle()
            val pendingWaterMl   by viewModel.pendingWaterMl.collectAsStateWithLifecycle()
            val pendingWaterCount by viewModel.pendingWaterCount.collectAsStateWithLifecycle()
            val lastUsedType     by viewModel.lastUsedType.collectAsStateWithLifecycle()
            val swimHeartRate    by viewModel.swimHeartRate.collectAsStateWithLifecycle()
            val swimGuide        by viewModel.swimGuide.collectAsStateWithLifecycle()
            val swimWorkouts     by viewModel.swimWorkouts.collectAsStateWithLifecycle()
            val pendingSyncItems by viewModel.pendingSyncItems.collectAsStateWithLifecycle()

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
                startDestination = if (openPendingSyncOnStart) "pendingSync" else "idle",
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
                        pendingSyncItems   = pendingSyncItems,
                        onStartPlanDay     = { week, day ->
                            viewModel.startPlanDayFromWatch(week, day) { ok ->
                                phoneNeeded = !ok
                            }
                        },
                        onStartSportActivity = { activity -> viewModel.startSportActivityFromWatch(activity) },
                        onSportActivityNotFound = { phoneNeeded = true },
                        onQuickStart       = onTapActivity,
                        onOpenPicker       = { navController.navigate("pick") },
                        onOpenIntervals    = { navController.navigate("intervals") },
                        onAddWater         = { delta -> viewModel.addWater(delta) },
                        onRefresh          = { viewModel.requestDailySummary() },
                        onOpenPendingSync  = { navController.navigate("pendingSync") },
                    )
                }
                composable("pendingSync") {
                    PendingSyncScreen(
                        items      = pendingSyncItems,
                        onSendOne  = { syncId -> viewModel.sendPendingSyncItem(syncId) },
                        onSendAll  = { viewModel.sendAllPendingSync() },
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
                        sportDays = sportDays,
                        onStartSportActivity = { activity -> viewModel.startSportActivityFromWatch(activity) },
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
                    // Single route, multiple layouts — the phone/wrist tag
                    // the session as `workout`/`swim`/`guided`/`activity`.
                    // F7 (§2.8/D13) — `guided` ha PRIORITÀ: una corsa
                    // guidata avviata dal polso o dal telefono è sempre
                    // `ACTIVITY` di sport (mai workout/swim, D3), quindi il
                    // ramo va prima del resto per chiarezza di lettura, non
                    // perché serva davvero a disambiguare.
                    if (sessionState.guided) {
                        GuidedSessionScreen(
                            sessionState = sessionState,
                            view         = guidedRun,
                            heartRate    = heartRate,
                            onStop       = viewModel::stopFromWatch,
                            onPause      = viewModel::pauseFromWatch,
                            onResume     = viewModel::resumeFromWatch,
                            onNextStep   = viewModel::nextStepFromWatch,
                        )
                    } else if (sessionState.isWorkout()) {
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

            LaunchedEffect(sessionState.isActive, openSessionRequests) {
                if (sessionState.isActive) {
                    if (navController.currentDestination?.route != "session") {
                        navController.navigate("session") {
                            popUpTo("idle") { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                } else if (navController.currentDestination?.route == "session") {
                    // Sessione finita: "session" esce dallo stack. Se "idle"
                    // non c'è (stack nato altrove) la si crea come radice.
                    if (!navController.popBackStack("idle", inclusive = false)) {
                        navController.navigate("idle") {
                            popUpTo(navController.graph.id) { inclusive = true }
                            launchSingleTop = true
                        }
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchExtras(intent)
    }

    /// F7 (correzioni WP-WD) — EXTRA_PENDING_START: tocco sulla notifica
    /// «Tocca per iniziare», l'avvio dal telefono negato in background parte
    /// ora che l'app è in primo piano (se ancora valido). EXTRA_OPEN_SESSION:
    /// porta alla sessione in corso.
    private fun handleLaunchExtras(intent: Intent) {
        if (intent.getBooleanExtra(MessagePaths.EXTRA_PENDING_START, false)) {
            intent.removeExtra(MessagePaths.EXTRA_PENDING_START)
            WristGuidedStart.consumePending(applicationContext)
            openSessionRequests++
        }
        if (intent.getBooleanExtra(MessagePaths.EXTRA_OPEN_SESSION, false)) {
            intent.removeExtra(MessagePaths.EXTRA_OPEN_SESSION)
            openSessionRequests++
        }
    }

    /// Screenshot-only: `adb shell am start -n com.jimtime.gabrielegusmeroli/.presentation.MainActivity
    /// --ez JT_FAKE_PENDING_SYNC true --ez JT_OPEN_PENDING_SYNC true`
    /// popola PendingSyncScreen con 4 item a stato diverso (in attesa, non
    /// loggato, telefono irraggiungibile, in invio) senza registrare
    /// davvero 4 sessioni offline — mirror di JT_FAKE_PENDING_SYNC lato
    /// iOS. Il chiamante è già dietro `BuildConfig.DEBUG`: in una build
    /// release il branch è `if (false)`, R8 lo elimina dall'APK firmato.
    private fun maybeSeedFakePendingSync(intent: Intent) {
        if (!intent.getBooleanExtra("JT_FAKE_PENDING_SYNC", false)) return
        val now = System.currentTimeMillis()
        data class Fake(val type: String, val syncId: String, val reason: String?, val sending: Boolean)
        val fakes = listOf(
            Fake("run", "seed-none", reason = null, sending = false),
            Fake("bike", "seed-not-logged-in", reason = "notLoggedIn", sending = false),
            Fake("hike", "seed-unreachable", reason = "phoneUnreachable", sending = false),
            Fake("walk", "seed-sending", reason = null, sending = true),
        )
        fakes.forEachIndexed { i, fake ->
            PendingRouteStore.save(
                applicationContext,
                PendingRouteStore.PendingRoute(
                    points       = emptyList(),
                    activityType = fake.type,
                    startedAt    = now - (i + 1) * 3_600_000L,
                    endedAt      = now - (i + 1) * 3_600_000L + 32 * 60_000L,
                    syncId       = fake.syncId,
                ),
            )
            if (fake.reason != null) {
                PendingSyncStatusStore.setStatus(applicationContext, fake.syncId, fake.reason)
            }
            if (fake.sending) {
                PendingSyncCoordinator.debugMarkSending(fake.syncId)
            }
        }
    }

    /// F7 (WP-WU, §3 "Screenshot") — SOLO build DEBUG, mai in release
    /// (stesso schema di [maybeSeedFakePendingSync]: in `if (false)` R8 lo
    /// elimina dall'APK firmato). Arma [GuidedRunRepository] con una
    /// fixture 6×800 (riscaldamento a tempo + 6×(800 m a passo + 2:00
    /// recupero) + defaticamento `lap` con target FC) e fa avanzare il
    /// motore con campioni SINTETICI DETERMINISTICI (passo fisso per
    /// fase, nessuna lettura di sensori reali) fino al secondo richiesto:
    ///
    /// `adb shell am start -n com.jimtime.gabrielegusmeroli/.presentation.MainActivity
    ///   --ez JT_FAKE_GUIDED true --es JT_FAKE_GUIDED_AT 812`
    ///   (lavoro a passo, dentro banda) · `--es JT_FAKE_GUIDED_AT 2410`
    ///   (defaticamento, `lap`/HR) · `--ez JT_FAKE_GUIDED_FINISHED true`
    ///   (corsa libera) · `--ez JT_FAKE_GUIDED_PAUSED true` (in pausa).
    private fun maybeSeedFakeGuided(intent: Intent) {
        if (!intent.getBooleanExtra("JT_FAKE_GUIDED", false)) return
        val atSec = intent.getStringExtra("JT_FAKE_GUIDED_AT")?.toLongOrNull() ?: 0L
        val wantPaused = intent.getBooleanExtra("JT_FAKE_GUIDED_PAUSED", false)
        val wantFinished = intent.getBooleanExtra("JT_FAKE_GUIDED_FINISHED", false)
        // Screenshot "fuori banda" (§5.3): passo sintetico di lavoro molto
        // più veloce del target (3:20/km contro 3:55–4:05), oltre la
        // tolleranza (§2.7 Dart/Kotlin ±8 s/km) — marcatore al bordo
        // destro (passo invertito, J15), bordo rosso sulla banda.
        val wantOffBand = intent.getBooleanExtra("JT_FAKE_GUIDED_OFFBAND", false)
        // Screenshot "recupero hrBelow" (§5.3) — la 6×800 non ne ha uno
        // (la sua recovery è a TEMPO): struttura minima a parte, un solo
        // step `recovery`/`hrBelow`, FC sintetica SOPRA la soglia così
        // resta bloccato lì (mai `dt >= 10s && hr < value`, regola motore)
        // e si fotografa il sottotitolo "FC sotto 130".
        if (intent.getBooleanExtra("JT_FAKE_GUIDED_HRBELOW", false)) {
            seedFakeGuidedHrBelowDemo()
            return
        }

        val structure = fakeGuided6x800Structure()
        val expanded = structure.expand()
        val planRef = JSONObject()
            .put("planId", "debug_plan")
            .put("weekNumber", 1)
            .put("dayIndex", 1)
            .put("dayLabel", "Debug · 6×800")
            .put("activityId", "debug_6x800")

        GuidedRunRepository.start(
            structure = structure,
            planRef = planRef,
            lang = "it",
            title = "Ripetute 6×800",
            sport = "run",
            variant = null,
            hasDistanceSignal = true,
        )

        // Senza questo, `sessionState.guided` resta `false`: la rotta
        // "session" sceglie `SessionScreen` invece di `GuidedSessionScreen`
        // e la navigazione automatica (`LaunchedEffect(sessionState.isActive)`)
        // non scatta nemmeno, perché `isActive` era già `false` e lo resta.
        SessionRepository.startStandaloneSession(
            type = "run",
            planRefJson = planRef.toString(),
            guided = true,
        )

        // Tetto 20' di sicurezza: una fixture malformata non deve mai
        // bloccare l'avvio dell'app in un loop infinito.
        // 50' — copre l'intera fixture (riscaldamento 600 s + 6×(192+120) s
        // = 2472 s di ripetute) più un bel margine nel defaticamento `lap`,
        // che altrimenti (end type `lap`) non finirebbe mai da solo.
        val safetyCapSec = 50L * 60L
        val targetSec = if (wantFinished) safetyCapSec else atSec.coerceAtMost(safetyCapSec)
        var sec = 0L
        var distanceM = 0.0
        var reachedEnd = false
        while (sec < targetSec && !reachedEnd) {
            sec++
            val currentIndex = GuidedRunRepository.snapshot()?.engine?.index ?: expanded.size
            val role = expanded.getOrNull(currentIndex)?.role
            // Il defaticamento finisce con `end.type == LAP` (fino al
            // tasto, §2.8): senza un "tocco" non chiuderebbe MAI da solo e
            // `JT_FAKE_GUIDED_FINISHED` non raggiungerebbe mai `finished`.
            // `nextStep()` arma `lapPending`, consumato dal [tick] subito
            // sotto (stesso percorso del tasto «Fase ›» vero).
            if (wantFinished && currentIndex == expanded.size - 1) {
                GuidedRunRepository.nextStep()
            }
            // Passo sintetico fisso per fase — SOLO per far avanzare la
            // distanza in modo deterministico e riproducibile negli
            // screenshot, non è una simulazione realistica dei sensori.
            val paceSecPerKm = when (role) {
                PlanStepRole.WORK -> if (wantOffBand) 200.0 else 240.0 // 3:20/km (fuori) o 4:00/km (dentro banda 3:55–4:05).
                PlanStepRole.RECOVERY -> 0.0     // nessun target: distanza congelata.
                else -> 288.0                    // 4:48/km in riscaldamento/defaticamento.
            }
            if (paceSecPerKm > 0.0) distanceM += 1000.0 / paceSecPerKm
            val events = GuidedRunRepository.tick(
                nowMs = sec * 1000L,
                activeMs = sec * 1000L,
                distanceM = distanceM,
                hr = 150.0,
                paused = false,
            )
            if (wantFinished && events.any { it is StepEvent.WorkoutEnd }) reachedEnd = true
        }
        if (wantPaused) {
            GuidedRunRepository.tick(
                nowMs = sec * 1000L,
                activeMs = sec * 1000L,
                distanceM = distanceM,
                hr = 150.0,
                paused = true,
            )
            // Anche `sessionState.isPaused`, per il colore/sottotitolo del
            // rimanente e per il controllo ▶ al posto di ⏸ (§2.8).
            SessionRepository.pauseSession()
        }
    }

    /**
     * Screenshot dedicato "recupero hrBelow" (§5.3) — un solo step
     * `recovery` con fine `hrBelow` (130 bpm): la 6×800 non ne ha uno (la
     * sua recovery è a tempo). FC sintetica 150 bpm, SEMPRE sopra la
     * soglia, così lo step non chiude mai da solo (regola motore: `hr <
     * value && dt >= 10 s`) e resta fermo a mostrare il sottotitolo
     * "FC sotto 130" con il tempo trascorso.
     */
    private fun seedFakeGuidedHrBelowDemo() {
        val recovery = ResolvedStep(
            id = "st_recovery_demo", sourceId = "st_recovery_demo", role = PlanStepRole.RECOVERY,
            end = ResolvedEnd(ResolvedEndType.HR_BELOW, 130.0),
            target = ResolvedTarget(ResolvedTargetKind.NONE, low = 0.0, high = 0.0),
            ui = StepUi("Recupero attivo", "Recupero attivo", ""),
        )
        val structure = ResolvedStructure(v = 1, entries = listOf(ResolvedStructureEntry.Step(recovery)))
        val planRef = JSONObject()
            .put("planId", "debug_plan")
            .put("weekNumber", 1)
            .put("dayIndex", 1)
            .put("dayLabel", "Debug · recupero FC")
            .put("activityId", "debug_hrbelow")

        GuidedRunRepository.start(
            structure = structure,
            planRef = planRef,
            lang = "it",
            title = "Recupero attivo",
            sport = "run",
            variant = null,
            hasDistanceSignal = true,
        )
        SessionRepository.startStandaloneSession(type = "run", planRefJson = planRef.toString(), guided = true)

        // Una manciata di tick: basta per uscire da `dt < 10 s` (rule 7) e
        // mostrare un tempo trascorso non nullo, FC sempre sopra soglia.
        for (sec in 1..30L) {
            GuidedRunRepository.tick(nowMs = sec * 1000L, activeMs = sec * 1000L, distanceM = 0.0, hr = 150.0, paused = false)
        }
    }

    /** Fixture 6×800 di `$SCRATCH/F7_SPEC.md` Appendice A — SOLO per [maybeSeedFakeGuided]. */
    private fun fakeGuided6x800Structure(): ResolvedStructure {
        val warmup = ResolvedStep(
            id = "st_warmup", sourceId = "st_warmup", role = PlanStepRole.WARMUP,
            end = ResolvedEnd(ResolvedEndType.TIME, 600.0),
            target = ResolvedTarget(ResolvedTargetKind.PACE, low = 288.0, high = 324.0, zoneLabel = "easy"),
            ui = StepUi("Riscaldamento", "Riscaldamento", "4:48–5:24 /km"),
        )
        val work = ResolvedStep(
            id = "st_work", sourceId = "st_work", role = PlanStepRole.WORK,
            end = ResolvedEnd(ResolvedEndType.DISTANCE, 800.0),
            target = ResolvedTarget(ResolvedTargetKind.PACE, low = 235.0, high = 245.0, zoneLabel = "5k"),
            ui = StepUi("Ripetuta {i}/{n}", "Ripetuta {i} di {n}", "3:55–4:05 /km"),
        )
        val recovery = ResolvedStep(
            id = "st_recovery", sourceId = "st_recovery", role = PlanStepRole.RECOVERY,
            end = ResolvedEnd(ResolvedEndType.TIME, 120.0),
            target = ResolvedTarget(ResolvedTargetKind.NONE, low = 0.0, high = 0.0),
            ui = StepUi("Recupero {i}/{n}", "Recupero {i} di {n}", ""),
        )
        val cooldown = ResolvedStep(
            id = "st_cooldown", sourceId = "st_cooldown", role = PlanStepRole.COOLDOWN,
            end = ResolvedEnd(ResolvedEndType.LAP, 0.0),
            target = ResolvedTarget(ResolvedTargetKind.HR, low = 114.0, high = 133.0, zoneLabel = "2"),
            ui = StepUi("Defaticamento", "Defaticamento", "114–133 bpm"),
        )
        return ResolvedStructure(
            v = 1,
            entries = listOf(
                ResolvedStructureEntry.Step(warmup),
                ResolvedStructureEntry.RepeatBlock("rpt_6x800", 6, listOf(work, recovery)),
                ResolvedStructureEntry.Step(cooldown),
            ),
        )
    }

    /// F7 (WP-WU, fase B) — SOLO build DEBUG. Inietta `sportDays` (una
    /// SportDay guidata, come la fixture 6×800) e `nextSportDay` nel
    /// riepilogo, così l'hero della home e la sezione "Sport" del picker
    /// si fotografano senza aspettare un push reale dal telefono:
    ///
    /// `adb shell am start -n com.jimtime.gabrielegusmeroli/.presentation.MainActivity --ez JT_FAKE_SPORT_DAYS true`
    private fun maybeSeedFakeSportDays(intent: Intent) {
        if (!intent.getBooleanExtra("JT_FAKE_SPORT_DAYS", false)) return

        val activity = JSONObject()
            .put("id", "debug_6x800")
            .put("sport", "run")
            .put("title", "Ripetute 6×800")
            .put("summary", "6×800 m · ≈ 8,5 km · ≈ 45 min")
            .put("guided", true)
        val sportDay = JSONObject()
            .put("week", 1)
            .put("day", 1)
            .put("label", "Giovedì – Ripetute")
            .put("activities", org.json.JSONArray().put(activity))
        val payload = JSONObject()
            .put("v", 2)
            .put("lang", "it")
            .put("planId", "debug_plan")
            .put("planName", "Mezza in 12 settimane")
            .put("days", org.json.JSONArray())
            .put("sportDays", org.json.JSONArray().put(sportDay))
        PlanDaysStore.applyPayload(applicationContext, payload)

        val nextSportDay = JSONObject()
            .put("week", 1)
            .put("day", 1)
            .put("label", "Giovedì – Ripetute")
            .put("activityId", "debug_6x800")
            .put("sport", "run")
            .put("title", "Ripetute 6×800")
            .put("summary", "6×800 m · ≈ 8,5 km · ≈ 45 min")
            .put("guided", true)
        val summary = JSONObject()
            .put("date", DailySummaryStore.todayLocal())
            .put("sentAt", System.currentTimeMillis())
            .put("water", JSONObject().put("ml", 0).put("goalMl", 2500).put("stepMl", 250))
            .put("nextSportDay", nextSportDay)
        DailySummaryStore.apply(applicationContext, summary)
    }

    /// F7 (WP-WU, fase B) — SOLO build DEBUG. Avvia una sessione NON
    /// guidata (companion F1: il telefono guida, l'orologio mostra solo
    /// la striscia) e le pianta subito una `GuidedStepMirror` fresca, per
    /// fotografare `SessionScreen` con la striscia companion senza un vero
    /// `guidedStep` dal telefono:
    ///
    /// `adb shell am start -n com.jimtime.gabrielegusmeroli/.presentation.MainActivity --ez JT_FAKE_COMPANION true`
    private fun maybeSeedFakeCompanion(intent: Intent) {
        if (!intent.getBooleanExtra("JT_FAKE_COMPANION", false)) return
        if (SessionRepository.state.value.isActive) return
        SessionRepository.startSession("run", System.currentTimeMillis())
        SessionRepository.updateGuidedStep(
            GuidedStepMirror(
                // Base 1 come il wire reale (§2.4, `_engine.index + 1`):
                // 3 = "Ripetuta 3/6" è la terza fase.
                index = 3,
                total = 14,
                label = "Ripetuta 3/6",
                remaining = "4:32",
                target = "3:55–4:05 /km",
                next = "Recupero 3/6 · 2:00",
                endsAt = null,
                finished = false,
                receivedAtMs = System.currentTimeMillis(),
            ),
        )
    }
}
