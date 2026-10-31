package com.maysk.camera

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.fillMaxWidth
import android.content.Intent
import com.maysk.camera.ui.theme.Accent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.first
import com.maysk.camera.camera.CameraMode
import com.maysk.camera.ui.components.PrimaryButton
import com.maysk.camera.ui.screens.AboutScreen
import com.maysk.camera.ui.screens.AiAutoScreen
import com.maysk.camera.ui.screens.AiStudioScreen
import com.maysk.camera.ui.screens.AiVideoScreen
import com.maysk.camera.ui.screens.CameraNav
import com.maysk.camera.ui.screens.CameraScreen
import com.maysk.camera.ui.screens.EditorScreen
import com.maysk.camera.ui.screens.EffectsScreen
import com.maysk.camera.ui.screens.FiltersScreen
import com.maysk.camera.ui.screens.GalleryScreen
import com.maysk.camera.ui.screens.HelpScreen
import com.maysk.camera.ui.screens.MayskBottomBar
import com.maysk.camera.ui.screens.MayskLogo
import com.maysk.camera.ui.screens.ModesScreen
import com.maysk.camera.ui.screens.ProSettingsScreen
import com.maysk.camera.ui.screens.SceneAnalysisScreen
import com.maysk.camera.ui.screens.SettingsScreen
import com.maysk.camera.ui.screens.SpecialModesScreen
import com.maysk.camera.ui.screens.Tab
import com.maysk.camera.ui.screens.VideoSettingsScreen
import com.maysk.camera.ui.screens.ViewerScreen
import com.maysk.camera.ui.screens.WelcomeScreen
import com.maysk.camera.ui.theme.MayskTheme

class MainActivity : ComponentActivity() {
    private val vm: CameraViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            val settings by vm.settings.collectAsState()
            MayskTheme(settings.theme) { AppRoot(vm) }
        }
    }

    /** Затвор кнопками громкости. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_CAMERA) &&
            vm.settings.value.volumeShutter && cameraVisible
        ) {
            if (event?.repeatCount == 0) vm.shutterEvents.tryEmit(Unit)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        @Volatile
        var cameraVisible = false
    }
}

private object Routes {
    const val WELCOME = "welcome"
    const val CAMERA = "camera"
    const val MODES = "modes"
    const val SPECIAL = "special"
    const val AI_AUTO = "aiauto"
    const val PRO = "pro"
    const val VIDEO_SETTINGS = "videosettings"
    const val AI_VIDEO = "aivideo"
    const val GALLERY = "gallery"
    const val VIEWER = "viewer?uri={uri}&video={video}"
    const val EDITOR = "editor?uri={uri}&tool={tool}"
    const val FILTERS = "filters?uri={uri}"
    const val EFFECTS = "effects?uri={uri}"
    const val ANALYSIS = "analysis?uri={uri}"
    const val STUDIO = "studio"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
    const val HELP = "help"

    fun viewer(u: Uri, video: Boolean) = "viewer?uri=${Uri.encode(u.toString())}&video=$video"
    fun editor(u: Uri, tool: String? = null) = "editor?uri=${Uri.encode(u.toString())}&tool=${tool ?: ""}"
    fun filters(u: Uri) = "filters?uri=${Uri.encode(u.toString())}"
    fun effects(u: Uri?) = "effects?uri=${u?.let { Uri.encode(it.toString()) } ?: ""}"
    fun analysis(u: Uri) = "analysis?uri=${Uri.encode(u.toString())}"
}

private fun cameraPermissions(): Array<String> = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
private fun readMediaPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

@Composable
private fun AppRoot(vm: CameraViewModel) {
    val ctx = LocalContext.current
    val settings by vm.settings.collectAsState()
    val nav = rememberNavController()
    fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    var camGranted by remember { mutableStateOf(granted(Manifest.permission.CAMERA)) }
    var readGranted by remember { mutableStateOf(readMediaPermissions().all { granted(it) }) }
    val camPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { camGranted = granted(Manifest.permission.CAMERA) }
    val readPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { readGranted = readMediaPermissions().all { granted(it) } }
    val locPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    LaunchedEffect(Unit) { if (!camGranted) camPerm.launch(cameraPermissions()) }

    var startRoute by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val s = vm.settingsRepo.flow.first()
        startRoute = if (s.onboardingDone) Routes.CAMERA else Routes.WELCOME
    }
    val start = startRoute
    if (start == null) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    MainActivity.cameraVisible = route == Routes.CAMERA
    val tab = when (route) {
        Routes.GALLERY -> Tab.GALLERY
        Routes.STUDIO -> Tab.STUDIO
        Routes.SETTINGS -> Tab.SETTINGS
        else -> null
    }

    fun go(t: Tab) {
        val r = when (t) { Tab.CAMERA -> Routes.CAMERA; Tab.GALLERY -> Routes.GALLERY; Tab.STUDIO -> Routes.STUDIO; Tab.SETTINGS -> Routes.SETTINGS }
        if (t == Tab.CAMERA) nav.popBackStack(Routes.CAMERA, false) else nav.navigate(r) { launchSingleTop = true; popUpTo(Routes.CAMERA) }
    }

    Scaffold(
        containerColor = Color.Black,
        bottomBar = { if (tab != null) MayskBottomBar(tab) { go(it) } }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(bottom = if (tab != null) pad.calculateBottomPadding() else 0.dp)) {
            NavHost(nav, startDestination = start) {
                composable(Routes.WELCOME) {
                    WelcomeScreen {
                        vm.updateSettings({ it.copy(onboardingDone = true) })
                        nav.navigate(Routes.CAMERA) { popUpTo(Routes.WELCOME) { inclusive = true } }
                    }
                }
                composable(Routes.CAMERA) {
                    if (!camGranted) PermissionScreen { camPerm.launch(cameraPermissions()) }
                    else CameraScreen(
                        vm, CameraNav(
                            openModes = { nav.navigate(Routes.MODES) },
                            openGallery = { nav.navigate(Routes.GALLERY) },
                            openViewer = { u, v -> nav.navigate(Routes.viewer(u, v)) },
                            openSettings = { nav.navigate(Routes.SETTINGS) },
                            openVideoSettings = { nav.navigate(Routes.VIDEO_SETTINGS) },
                            openAiVideo = { nav.navigate(Routes.AI_VIDEO) },
                            openProSettings = { nav.navigate(Routes.PRO) },
                        )
                    )
                }
                composable(Routes.MODES) {
                    ModesScreen(vm, onBack = { nav.popBackStack() }, onSpecial = { nav.navigate(Routes.SPECIAL) }, onAiAuto = { nav.navigate(Routes.AI_AUTO) })
                }
                composable(Routes.SPECIAL) {
                    SpecialModesScreen(vm, onBack = { nav.popBackStack() }, onDone = { nav.popBackStack(Routes.CAMERA, false) })
                }
                composable(Routes.AI_AUTO) {
                    AiAutoScreen(vm, onBack = { nav.popBackStack() }, onShoot = { nav.popBackStack(Routes.CAMERA, false) })
                }
                composable(Routes.PRO) { ProSettingsScreen(vm) { nav.popBackStack() } }
                composable(Routes.VIDEO_SETTINGS) { VideoSettingsScreen(vm) { nav.popBackStack() } }
                composable(Routes.AI_VIDEO) { AiVideoScreen(vm) { nav.popBackStack() } }
                composable(Routes.GALLERY) {
                    GalleryScreen(
                        onBack = { go(Tab.CAMERA) },
                        onOpen = { m -> nav.navigate(Routes.viewer(m.uri, m.isVideo)) },
                        hasReadPermission = readGranted,
                        requestPermission = { readPerm.launch(readMediaPermissions()) }
                    )
                }
                composable(
                    Routes.VIEWER,
                    arguments = listOf(
                        navArgument("uri") { type = NavType.StringType; defaultValue = "" },
                        navArgument("video") { type = NavType.BoolType; defaultValue = false }
                    )
                ) { e ->
                    val u = Uri.parse(e.arguments?.getString("uri") ?: "")
                    ViewerScreen(
                        u, e.arguments?.getBoolean("video") ?: false, onBack = { nav.popBackStack() },
                        onEdit = { nav.navigate(Routes.editor(u)) },
                        onFilters = { nav.navigate(Routes.filters(u)) },
                        onEffects = { nav.navigate(Routes.effects(u)) },
                        onAnalyze = { nav.navigate(Routes.analysis(u)) },
                    )
                }
                composable(
                    Routes.EDITOR,
                    arguments = listOf(
                        navArgument("uri") { type = NavType.StringType; defaultValue = "" },
                        navArgument("tool") { type = NavType.StringType; defaultValue = "" }
                    )
                ) { e ->
                    val u = Uri.parse(e.arguments?.getString("uri") ?: "")
                    EditorScreen(u, e.arguments?.getString("tool")?.takeIf { it.isNotBlank() }, onBack = { nav.popBackStack() }) { saved ->
                        nav.navigate(Routes.viewer(saved, false)) { popUpTo(Routes.EDITOR) { inclusive = true } }
                    }
                }
                composable(Routes.FILTERS, arguments = listOf(navArgument("uri") { type = NavType.StringType; defaultValue = "" })) { e ->
                    FiltersScreen(Uri.parse(e.arguments?.getString("uri") ?: ""), onBack = { nav.popBackStack() }) { saved ->
                        nav.navigate(Routes.viewer(saved, false)) { popUpTo(Routes.FILTERS) { inclusive = true } }
                    }
                }
                composable(Routes.EFFECTS, arguments = listOf(navArgument("uri") { type = NavType.StringType; defaultValue = "" })) { e ->
                    val s = e.arguments?.getString("uri").orEmpty()
                    EffectsScreen(
                        if (s.isBlank()) null else Uri.parse(s), onBack = { nav.popBackStack() },
                        onSaved = { saved -> nav.navigate(Routes.viewer(saved, false)) { popUpTo(Routes.EFFECTS) { inclusive = true } } },
                        onUseInCamera = { fx ->
                            vm.setMode(CameraMode.VIDEO); vm.setEffect(fx); nav.popBackStack(Routes.CAMERA, false)
                        }
                    )
                }
                composable(Routes.ANALYSIS, arguments = listOf(navArgument("uri") { type = NavType.StringType; defaultValue = "" })) { e ->
                    val u = Uri.parse(e.arguments?.getString("uri") ?: "")
                    SceneAnalysisScreen(
                        u, onBack = { nav.popBackStack() },
                        onSaved = { saved -> nav.navigate(Routes.viewer(saved, false)) { popUpTo(Routes.ANALYSIS) { inclusive = true } } },
                        onEdit = { nav.navigate(Routes.editor(u, "enhance")) }
                    )
                }
                composable(Routes.STUDIO) {
                    AiStudioScreen(
                        onBack = { go(Tab.CAMERA) },
                        openEditor = { u, t -> nav.navigate(Routes.editor(u, t)) },
                        openFilters = { u -> nav.navigate(Routes.filters(u)) }
                    )
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        vm, onBack = { go(Tab.CAMERA) },
                        onVideo = { nav.navigate(Routes.VIDEO_SETTINGS) },
                        onAbout = { nav.navigate(Routes.ABOUT) },
                        onHelp = { nav.navigate(Routes.HELP) },
                        requestLocation = { locPerm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
                    )
                }
                composable(Routes.ABOUT) { AboutScreen(onBack = { nav.popBackStack() }, onTry = { nav.popBackStack(Routes.CAMERA, false) }) }
                composable(Routes.HELP) { HelpScreen { nav.popBackStack() } }
            }
        }
    }
    GlobalDialogs(vm)
}

/** Диалоги поверх всех экранов: обновление и отчёт о сбое. */
@Composable
private fun GlobalDialogs(vm: CameraViewModel) {
    val ctx = LocalContext.current
    val ui by vm.ui.collectAsState()
    val info = ui.updateAvailable
    val progress = ui.updateProgress
    if (progress != null) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("Загрузка обновления") },
            text = {
                Column {
                    LinearProgressIndicator(progress = { progress }, color = Accent, modifier = Modifier.fillMaxWidth())
                    Text("${(progress * 100).toInt()}%", modifier = Modifier.padding(top = 8.dp))
                }
            }
        )
    } else if (info != null) {
        AlertDialog(
            onDismissRequest = { vm.dismissUpdate() },
            title = { Text("Доступно обновление ${info.versionName}") },
            text = {
                Text(
                    "Сейчас: ${BuildConfig.VERSION_NAME}\n\n" +
                        (info.notes.ifBlank { "Улучшения и исправления" }).take(600)
                )
            },
            confirmButton = { TextButton(onClick = { vm.installUpdate() }) { Text("Обновить", color = Accent) } },
            dismissButton = { TextButton(onClick = { vm.dismissUpdate() }) { Text("Позже") } }
        )
    }

    // Отчёт о прошлом сбое
    var crash by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val f = MayskApp.crashFile(ctx)
        if (f.exists()) crash = runCatching { f.readText() }.getOrNull().also { f.delete() }
    }
    crash?.let { report ->
        AlertDialog(
            onDismissRequest = { crash = null },
            title = { Text("Приложение закрылось с ошибкой") },
            text = { Text("Отправьте отчёт разработчику — это поможет быстро исправить проблему. В отчёте только техническая информация, без фото.") },
            confirmButton = {
                TextButton(onClick = {
                    val i = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Maysk Camera — отчёт о сбое")
                        putExtra(Intent.EXTRA_TEXT, report)
                    }
                    ctx.startActivity(Intent.createChooser(i, "Отправить отчёт").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    crash = null
                }) { Text("Отправить", color = Accent) }
            },
            dismissButton = { TextButton(onClick = { crash = null }) { Text("Не надо") } }
        )
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MayskLogo(90)
        Spacer(Modifier.height(20.dp))
        Text(
            "Maysk Camera нужен доступ к камере и микрофону, чтобы снимать фото и видео.\nВся обработка выполняется на телефоне.",
            color = Color.White, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        PrimaryButton("Разрешить доступ", onClick = onRequest)
    }
}

@Suppress("unused")
private fun NavHostController.safePop() = popBackStack()
