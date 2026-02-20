package se.rfab.luckywheel

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import se.rfab.luckywheel.ui.theme.LuckywheelTheme
import kotlin.math.cos
import kotlin.math.sin

data class WheelOption(val name: String, val color: Color)

// Convenience alias for the standard palette – used in previews
val optionColors: List<Color> get() = ThemeManager.getById("standard").colors

// Linear deceleration easing: f(u) = 2u – u²
// Derived from integrating v(t) = Vs·(1 – t/T) and normalising to [0, 1]
private val linearDecelerationEasing = Easing { t -> 2f * t - t * t }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LuckywheelTheme {
                LuckyWheelApp()
            }
        }
    }
}

@Composable
fun LuckyWheelApp() {
    var showWheel    by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val wheelOptions = remember { mutableStateListOf<WheelOption>() }
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    val themeId      by remember(context) { AppSettings.themeId(context) }
                        .collectAsState(initial = "standard")
    val soundEnabled by remember(context) { AppSettings.soundEnabled(context) }
                        .collectAsState(initial = true)

    val currentTheme = ThemeManager.getById(themeId)

    // BillingManager lives for the lifetime of this composition
    val billingManager = remember { BillingManager(context, scope) }
    DisposableEffect(Unit) { onDispose { billingManager.destroy() } }

    val hasExtraOptions by billingManager.hasExtraOptions.collectAsState()
    val sessionPrice    by billingManager.sessionPrice.collectAsState()
    val lifetimePrice   by billingManager.lifetimePrice.collectAsState()

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        when {
            showSettings -> SettingsScreen(
                currentThemeId = themeId,
                soundEnabled   = soundEnabled,
                onNavigateBack = { showSettings = false },
                modifier       = Modifier.padding(innerPadding)
            )
            showWheel -> WheelScreen(
                options        = wheelOptions,
                soundEnabled   = soundEnabled,
                onNavigateBack = {
                    billingManager.resetSession()
                    showWheel = false
                },
                onOpenSettings = { showSettings = true },
                modifier       = Modifier.padding(innerPadding)
            )
            else -> InputScreen(
                themeColors     = currentTheme.colors,
                hasExtraOptions = hasExtraOptions,
                sessionPrice    = sessionPrice,
                lifetimePrice   = lifetimePrice,
                onRequestPurchase = { productId ->
                    billingManager.launchPurchase(context as Activity, productId)
                },
                onNavigateToWheel = { options ->
                    wheelOptions.clear()
                    wheelOptions.addAll(options)
                    showWheel = true
                },
                onOpenSettings = { showSettings = true },
                modifier       = Modifier.padding(innerPadding)
            )
        }
    }
}

/** Maximum number of options available without a purchase. */
private const val FREE_OPTION_LIMIT = 3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InputScreen(
    themeColors: List<Color>,
    hasExtraOptions: Boolean,
    sessionPrice: String,
    lifetimePrice: String,
    onRequestPurchase: (productId: String) -> Unit,
    onNavigateToWheel: (List<WheelOption>) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val options = remember { mutableStateListOf("", "") }
    var showPurchaseDialog by remember { mutableStateOf(false) }

    // Effective cap: 3 for free users, 6 once unlocked
    val maxOptions = if (hasExtraOptions) 6 else FREE_OPTION_LIMIT

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TopAppBar(
            title = {
                Text(
                    text = "Create Your Wheel",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            },
            actions = {
                IconButton(onClick = onOpenSettings) {
                    Text("⚙", style = MaterialTheme.typography.titleLarge)
                }
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            options.forEachIndexed { index, value ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(themeColors[index], RoundedCornerShape(8.dp))
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    OutlinedTextField(
                        value = value,
                        onValueChange = { options[index] = it },
                        label = { Text("Option ${index + 1}") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            Spacer(modifier = Modifier.height(8.dp))

            // "Add option" button – shows purchase dialog when the free limit is reached
            val atFreeLimit   = options.size >= FREE_OPTION_LIMIT && !hasExtraOptions
            val atAbsoluteMax = options.size >= 6

            Button(
                onClick = {
                    when {
                        atAbsoluteMax -> { /* button is disabled */ }
                        atFreeLimit   -> showPurchaseDialog = true
                        else          -> options.add("")
                    }
                },
                enabled = !atAbsoluteMax,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (atFreeLimit) Color(0xFF9C27B0) else Color(0xFF6200EE),
                    disabledContainerColor = Color.Gray
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = when {
                        atAbsoluteMax -> "Max 6 alternativ"
                        atFreeLimit   -> "🔒 Fler alternativ (4–6)"
                        else          -> "Lägg till alternativ"
                    },
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            val filledOptions = options.filter { it.isNotBlank() }
            val canNavigate   = filledOptions.size >= 2

            Button(
                onClick = {
                    val result = filledOptions.mapIndexed { index, name ->
                        WheelOption(name = name, color = themeColors[index])
                    }
                    onNavigateToWheel(result)
                },
                enabled = canNavigate,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFF4444),
                    disabledContainerColor = Color.Gray
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(
                    text = if (canNavigate) "To the Wheel" else "Fill in at least 2 options",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
            }
        }
    }

    // ── Purchase dialog ───────────────────────────────────────────────────
    if (showPurchaseDialog) {
        AlertDialog(
            onDismissRequest = { showPurchaseDialog = false },
            title = {
                Text(
                    text = "Fler alternativ",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Gratisversionen tillåter upp till 3 alternativ.\nVälj ett av alternativen nedan för att låsa upp 4–6 alternativ.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(20.dp))

                    // Session purchase
                    Button(
                        onClick = {
                            onRequestPurchase(PRODUCT_SESSION)
                            showPurchaseDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6200EE)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "En session  –  $sessionPrice",
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "Gäller tills du stänger appen",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Lifetime purchase
                    Button(
                        onClick = {
                            onRequestPurchase(PRODUCT_LIFETIME)
                            showPurchaseDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4444)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Livstid  –  $lifetimePrice",
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "Låst upp för alltid",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showPurchaseDialog = false }) {
                    Text("Avbryt")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WheelScreen(
    options: List<WheelOption>,
    soundEnabled: Boolean = true,
    onNavigateBack: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rotation    = remember { Animatable(0f) }
    var isSpinning  by remember { mutableStateOf(false) }
    var winnerIndex by remember { mutableStateOf<Int?>(null) }
    val scope       = rememberCoroutineScope()

    val sliceAngle = 360f / options.size
    val nitCount   = 180
    val nitAngleDeg = 360f / nitCount  // 2.0° per nit

    // Flärp bend derived from which nit is currently at the 12-o'clock position
    val flärpBend = if (isSpinning || winnerIndex != null) {
        val phase = ((rotation.value % nitAngleDeg) + nitAngleDeg) % nitAngleDeg / nitAngleDeg
        28f * (1f - phase)
    } else 0f

    // ── Ljud ─────────────────────────────────────────────────────────────────
    val context = LocalContext.current
    val soundEngine = remember { WheelSoundEngine(context) }
    val currentSoundEnabled by rememberUpdatedState(soundEnabled)

    // Initierar SoundPool när composable mountas; frigör när den lämnar.
    DisposableEffect(Unit) {
        soundEngine.init()
        onDispose { soundEngine.release() }
    }

    // Spelar ett klick varje gång en nit passerar flärpen (rotation ökar med nitAngleDeg).
    // snapshotFlow observerar rotation.value och emittar vid varje animationsbild.
    LaunchedEffect(Unit) {
        var prevNitCount = (rotation.value / nitAngleDeg).toInt()
        snapshotFlow { rotation.value }.collect { currentRotation ->
            val currentNitCount = (currentRotation / nitAngleDeg).toInt()
            val delta = currentNitCount - prevNitCount
            if (delta > 0 && currentSoundEnabled) {
                // Spela max 4 klick per frame vid snabb snurrning (undviker ljudstörningar)
                repeat(delta.coerceAtMost(4)) { soundEngine.playClick() }
            }
            prevNitCount = currentNitCount
        }
    }
    // ─────────────────────────────────────────────────────────────────────────

    BackHandler(onBack = onNavigateBack)

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TopAppBar(
            title = {
                Text(
                    text = "Lucky Wheel",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            },
            navigationIcon = {
                IconButton(onClick = onNavigateBack) {
                    Text("←", style = MaterialTheme.typography.titleLarge)
                }
            },
            actions = {
                IconButton(onClick = onOpenSettings) {
                    Text("⚙", style = MaterialTheme.typography.titleLarge)
                }
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            val wheelDp = 280.dp
            val flärpDp = 40.dp

            Box(modifier = Modifier.size(wheelDp, wheelDp + flärpDp)) {

                // ── Rotating wheel ───────────────────────────────────────────
                Canvas(
                    modifier = Modifier
                        .size(wheelDp)
                        .align(Alignment.BottomCenter)
                        .rotate(rotation.value)
                ) {
                    val radius = size.minDimension / 2f

                    val textPaint = android.graphics.Paint().apply {
                        color     = android.graphics.Color.WHITE
                        textSize  = radius * 0.15f
                        textAlign = android.graphics.Paint.Align.LEFT
                        isAntiAlias = true
                        typeface  = android.graphics.Typeface.DEFAULT_BOLD
                    }

                    // Pie slices + dividers + labels
                    options.forEachIndexed { index, option ->
                        val startAngle = -90f + index * sliceAngle

                        drawArc(
                            color = option.color,
                            startAngle = startAngle,
                            sweepAngle = sliceAngle,
                            useCenter  = true
                        )

                        val lineRad = Math.toRadians(startAngle.toDouble())
                        drawLine(
                            color       = Color.White,
                            start       = center,
                            end         = Offset(
                                center.x + radius * cos(lineRad).toFloat(),
                                center.y + radius * sin(lineRad).toFloat()
                            ),
                            strokeWidth = 3f
                        )

                        val midAngle = startAngle + sliceAngle / 2f
                        val nc: android.graphics.Canvas = drawContext.canvas.nativeCanvas
                        nc.save()
                        nc.rotate(midAngle, center.x, center.y)
                        nc.drawText(
                            option.name.take(10),
                            center.x + radius * 0.22f,
                            center.y + textPaint.textSize / 3f,
                            textPaint
                        )
                        nc.restore()
                    }

                    // 180 nitar – small dark triangles at the rim
                    val nitLen      = radius * 0.045f
                    val nitHalfBase = radius * 0.013f
                    for (i in 0 until nitCount) {
                        val angleDeg = -90f + i * nitAngleDeg
                        val angleRad = Math.toRadians(angleDeg.toDouble())
                        val cosA = cos(angleRad).toFloat()
                        val sinA = sin(angleRad).toFloat()

                        val tipX  = center.x + radius * cosA
                        val tipY  = center.y + radius * sinA
                        val baseX = center.x + (radius - nitLen) * cosA
                        val baseY = center.y + (radius - nitLen) * sinA

                        val path = Path()
                        path.moveTo(tipX, tipY)
                        path.lineTo(baseX - sinA * nitHalfBase, baseY + cosA * nitHalfBase)
                        path.lineTo(baseX + sinA * nitHalfBase, baseY - cosA * nitHalfBase)
                        path.close()
                        drawPath(path, Color(0xFF444444))
                    }

                    drawCircle(
                        color  = Color(0xFF333333),
                        radius = radius,
                        style  = Stroke(width = 5f)
                    )
                    drawCircle(color = Color.White,       radius = 20f)
                    drawCircle(color = Color(0xFFCCCCCC), radius = 13f)
                }

                // ── Fixed flärp ──────────────────────────────────────────────
                Canvas(
                    modifier = Modifier
                        .size(wheelDp, flärpDp)
                        .align(Alignment.TopCenter)
                ) {
                    val cx      = size.width / 2f
                    val halfBase = 13f
                    val bendRad = Math.toRadians(flärpBend.toDouble())
                    val cosB = cos(bendRad).toFloat()
                    val sinB = sin(bendRad).toFloat()

                    val leftX  = cx - halfBase * cosB
                    val leftY  = halfBase * sinB
                    val rightX = cx + halfBase * cosB
                    val rightY = -halfBase * sinB
                    val tipX   = cx + size.height * sinB
                    val tipY   = size.height * cosB

                    val path = Path()
                    path.moveTo(leftX, leftY)
                    path.lineTo(rightX, rightY)
                    path.lineTo(tipX, tipY)
                    path.close()
                    drawPath(path, Color(0xFF222222))
                    drawCircle(color = Color(0xFF555555), radius = 7f, center = Offset(cx, 0f))
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Winner banner
            val winner = winnerIndex
            if (winner != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(options[winner].color, RoundedCornerShape(12.dp))
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = options[winner].name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = {
                    isSpinning  = true
                    winnerIndex = null

                    val vs     = (45..90).random().toFloat()
                    val tSpinn = (4..12).random().toFloat()
                    val totalNits    = vs * tSpinn / 2f
                    val totalDegrees = totalNits * nitAngleDeg
                    val targetAngle  = rotation.value + totalDegrees

                    scope.launch {
                        rotation.animateTo(
                            targetValue    = targetAngle,
                            animationSpec  = tween(
                                durationMillis = (tSpinn * 1000f).toInt(),
                                easing         = linearDecelerationEasing
                            )
                        )
                        val r = rotation.value % 360f
                        val effectiveAngle = ((-90f - r) % 360f + 360f) % 360f
                        val offset = (effectiveAngle + 90f) % 360f
                        winnerIndex = (offset / sliceAngle).toInt() % options.size
                        isSpinning  = false
                    }
                },
                enabled = !isSpinning,
                colors = ButtonDefaults.buttonColors(
                    containerColor         = Color(0xFFFF4444),
                    disabledContainerColor = Color.Gray
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(
                    text  = if (isSpinning) "Snurrar..." else "Snurra!",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
            }
        } // inner Column
    } // outer Column
}

@Preview(showBackground = true)
@Composable
fun InputScreenPreview() {
    LuckywheelTheme {
        InputScreen(
            themeColors       = ThemeManager.getById("standard").colors,
            hasExtraOptions   = false,
            sessionPrice      = "1 USD",
            lifetimePrice     = "15 USD",
            onRequestPurchase = {},
            onNavigateToWheel = {},
            onOpenSettings    = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
fun WheelScreenPreview() {
    LuckywheelTheme {
        WheelScreen(
            options = listOf(
                WheelOption("Pizza",  optionColors[0]),
                WheelOption("Pasta",  optionColors[1]),
                WheelOption("Sushi",  optionColors[2]),
                WheelOption("Burger", optionColors[3]),
                WheelOption("Tacos",  optionColors[4]),
                WheelOption("Salad",  optionColors[5])
            ),
            onNavigateBack = {},
            onOpenSettings = {}
        )
    }
}
