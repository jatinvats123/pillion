package app.pillion.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLES20.GL_ARRAY_BUFFER
import android.opengl.GLES20.GL_BACK
import android.opengl.GLES20.GL_BLEND
import android.opengl.GLES20.GL_CCW
import android.opengl.GLES20.GL_COLOR_BUFFER_BIT
import android.opengl.GLES20.GL_COMPILE_STATUS
import android.opengl.GLES20.GL_CULL_FACE
import android.opengl.GLES20.GL_DEPTH_BUFFER_BIT
import android.opengl.GLES20.GL_DEPTH_TEST
import android.opengl.GLES20.GL_ELEMENT_ARRAY_BUFFER
import android.opengl.GLES20.GL_FLOAT
import android.opengl.GLES20.GL_FRAGMENT_SHADER
import android.opengl.GLES20.GL_FUNC_ADD
import android.opengl.GLES20.GL_LEQUAL
import android.opengl.GLES20.GL_LINK_STATUS
import android.opengl.GLES20.GL_ONE
import android.opengl.GLES20.GL_ONE_MINUS_SRC_ALPHA
import android.opengl.GLES20.GL_SRC_ALPHA
import android.opengl.GLES20.GL_STATIC_DRAW
import android.opengl.GLES20.GL_TRIANGLES
import android.opengl.GLES20.GL_UNSIGNED_SHORT
import android.opengl.GLES20.GL_VERTEX_SHADER
import android.opengl.GLES20.glAttachShader
import android.opengl.GLES20.glBindBuffer
import android.opengl.GLES20.glBlendEquation
import android.opengl.GLES20.glBlendFuncSeparate
import android.opengl.GLES20.glBufferData
import android.opengl.GLES20.glClear
import android.opengl.GLES20.glClearColor
import android.opengl.GLES20.glCompileShader
import android.opengl.GLES20.glCreateProgram
import android.opengl.GLES20.glCreateShader
import android.opengl.GLES20.glCullFace
import android.opengl.GLES20.glDepthFunc
import android.opengl.GLES20.glDrawElements
import android.opengl.GLES20.glEnable
import android.opengl.GLES20.glEnableVertexAttribArray
import android.opengl.GLES20.glFrontFace
import android.opengl.GLES20.glGenBuffers
import android.opengl.GLES20.glGetAttribLocation
import android.opengl.GLES20.glGetProgramInfoLog
import android.opengl.GLES20.glGetProgramiv
import android.opengl.GLES20.glGetShaderInfoLog
import android.opengl.GLES20.glGetShaderiv
import android.opengl.GLES20.glGetUniformLocation
import android.opengl.GLES20.glLinkProgram
import android.opengl.GLES20.glShaderSource
import android.opengl.GLES20.glUniform1f
import android.opengl.GLES20.glUniform3f
import android.opengl.GLES20.glUniformMatrix4fv
import android.opengl.GLES20.glUseProgram
import android.opengl.GLES20.glVertexAttribPointer
import android.opengl.GLES20.glViewport
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.pillion.ui.RideVoiceState
import app.pillion.ui.theme.Pillion
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.isActive

/**
 * The glow and the mic button's ring follow the globe's smoothed voice level (the design's
 * `--lvl`, 0..1). Written once per frame, read at draw time only (no recomposition).
 */
@Stable
class GlobeLevel {
    internal val state = mutableFloatStateOf(0f)
    val value: Float get() = state.floatValue
}

@Composable
fun rememberGlobeLevel(): GlobeLevel = remember { GlobeLevel() }

/**
 * Something drawn over the whole app (the intro video) is showing. The globe's surface sits above
 * the app's window (so its transparent pixels show the page behind it), so it steps aside until
 * that's gone.
 */
val LocalCoverShowing = compositionLocalOf { false }

/**
 * Pillion's swirled iridescent glass globe (the design's A7/A8 `initGlass`), OpenGL ES 2.0 in a
 * [GLSurfaceView], with the design's glow behind it (`.v5-orb::before`) drawn by Compose.
 *
 * [state] and [micLevel] (the rider's Agora volume, 0..1) drive it as in the design: listening
 * swells and speeds it with the voice, thinking shrinks and spins it, speaking pulses it. With no
 * [micLevel], listening uses the design's built-in demo voice (its no-microphone fallback). The
 * surface renders only while the screen is resumed; with "Remove animations" it's one still frame.
 */
@Composable
fun GlassGlobe(
    state: RideVoiceState,
    micLevel: (() -> Float)?,
    description: String,
    modifier: Modifier = Modifier,
    level: GlobeLevel = rememberGlobeLevel(),
) {
    val colors = Pillion.colors
    val animate = !Pillion.reducedMotion
    val renderer = remember(animate) { GlassGlobeRenderer(animate) }
    SideEffect {
        renderer.voice = state
        renderer.demoVoice = micLevel == null
    }
    val currentMicLevel by rememberUpdatedState(micLevel)
    LaunchedEffect(renderer) {
        snapshotFlow { currentMicLevel?.invoke() ?: 0f }.collect { renderer.micLevel = it }
    }
    LaunchedEffect(renderer) {
        if (!animate) return@LaunchedEffect
        // As the design's `--lvl` (cur.level.toFixed(3)): the easing never quite reaches 0, and
        // rounding lets it settle instead of redrawing the glow every frame.
        while (isActive) withFrameNanos { level.state.floatValue = (renderer.level * 1000f).roundToInt() / 1000f }
    }

    BoxWithConstraints(
        modifier
            .semantics {
                contentDescription = description
                role = Role.Image
            }
            // Its own layer: the glow's per-frame redraw doesn't re-record the rest of the screen.
            .graphicsLayer()
            .drawBehind {
                // .v5-orb::before: a 230 px glow in a 262 px box, scale(1 + lvl·0.45), opacity 0.75 + lvl·0.9.
                val lvl = level.value
                val radius = size.height * (115f / 262f) * (1f + lvl * 0.45f)
                val alpha = min(1f, 0.75f + lvl * 0.9f)
                drawCircle(glowBrush(colors.orbGlow, center, radius), radius, center, alpha = alpha)
            },
        contentAlignment = Alignment.Center,
    ) {
        // The sphere's size follows the view's height (a fixed vertical field of view), so a square
        // of that height holds it at its largest; the sides stay free for the icons beside it.
        val side = min(maxHeight.value, maxWidth.value).dp
        if (!LocalCoverShowing.current) {
            var view by remember { mutableStateOf<GlassGlobeView?>(null) }
            AndroidView(
                factory = { GlassGlobeView(it, renderer, animate).also { created -> view = created } },
                modifier = Modifier.size(side),
            )
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            DisposableEffect(lifecycle, view) {
                val target = view ?: return@DisposableEffect onDispose {}
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_RESUME -> target.onResume()
                        Lifecycle.Event.ON_PAUSE -> target.onPause()
                        else -> Unit
                    }
                }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }
        }
    }
}

/**
 * Transparent, above the app's window (so the Compose glow and page show through), rendering at
 * most 2 device pixels per dp like the design (`setPixelRatio(min(devicePixelRatio, 2))`).
 */
@SuppressLint("ViewConstructor")
private class GlassGlobeView(context: Context, renderer: GlassGlobeRenderer, animate: Boolean) : GLSurfaceView(context) {
    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(MultisampleConfigChooser)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderOnTop(true)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = if (animate) RENDERMODE_CONTINUOUSLY else RENDERMODE_WHEN_DIRTY
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val density = resources.displayMetrics.density
        if (density > MAX_PIXEL_RATIO && w > 0 && h > 0) {
            holder.setFixedSize((w * MAX_PIXEL_RATIO / density).roundToInt(), (h * MAX_PIXEL_RATIO / density).roundToInt())
        }
    }
}

/** RGBA 8888 with a 16-bit depth buffer; 4× multisampling (the design's `antialias: true`) when the GPU has it. */
private object MultisampleConfigChooser : GLSurfaceView.EGLConfigChooser {
    private const val EGL_OPENGL_ES2_BIT = 4

    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        for (samples in intArrayOf(4, 0)) {
            val attributes = intArrayOf(
                EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8,
                EGL10.EGL_DEPTH_SIZE, 16, EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                EGL10.EGL_SAMPLE_BUFFERS, if (samples > 0) 1 else 0, EGL10.EGL_SAMPLES, samples,
                EGL10.EGL_NONE,
            )
            val count = IntArray(1)
            if (!egl.eglChooseConfig(display, attributes, null, 0, count) || count[0] == 0) continue
            val configs = arrayOfNulls<EGLConfig>(count[0])
            egl.eglChooseConfig(display, attributes, configs, count[0], count)
            val value = IntArray(1)
            fun EGLConfig.get(attribute: Int) = if (egl.eglGetConfigAttrib(display, this, attribute, value)) value[0] else -1
            configs.filterNotNull().firstOrNull { config ->
                config.get(EGL10.EGL_RED_SIZE) == 8 && config.get(EGL10.EGL_GREEN_SIZE) == 8 &&
                    config.get(EGL10.EGL_BLUE_SIZE) == 8 && config.get(EGL10.EGL_ALPHA_SIZE) == 8
            }?.let { return it }
        }
        throw IllegalStateException("No RGBA 8888 OpenGL ES 2 configuration")
    }
}

/**
 * The design's glass globe. The GLSL is GLASS_COMMON + GLASS_VERT + GLASS_FRAG from the design file,
 * unchanged; only what Three.js declares for a ShaderMaterial (precision, `projectionMatrix`,
 * `modelViewMatrix`, `cameraPosition`, the `position` attribute) is declared here. Scene: sphere
 * radius 1.55, 160 × 160 segments; camera FOV 40°, z = 6; transparent (normal) blending.
 */
private class GlassGlobeRenderer(private val animate: Boolean) : GLSurfaceView.Renderer {
    /** Set from the UI thread; read once per frame. */
    @Volatile var voice = RideVoiceState.Idle
    @Volatile var micLevel = 0f
    @Volatile var demoVoice = false

    /** The smoothed level (`cur.level`), for the glow and the mic ring. */
    @Volatile var level = 0f
        private set

    private var program = 0
    private var aPosition = 0
    private var uTime = 0
    private var uAmp = 0
    private var uProjection = 0
    private var uModelView = 0
    private var uCamera = 0
    private var vertexBuffer = 0
    private var indexBuffer = 0
    private var indexCount = 0
    private val projection = FloatArray(16)
    private val modelView = FloatArray(16)

    // initGlass's voice-reactive state, in the design's names.
    private var lastNanos = 0L
    private var elapsed = 0f
    private var phase = 0f
    private var curLevel = 0f
    private var curSpeed = 1f
    private var curScale = 1f
    private var targetLevel = 0f
    private var targetSpeed = 1f
    private var targetScale = 1f

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = link(compile(GL_VERTEX_SHADER, VERTEX_SHADER), compile(GL_FRAGMENT_SHADER, FRAGMENT_SHADER))
        aPosition = glGetAttribLocation(program, "position")
        uTime = glGetUniformLocation(program, "uTime")
        uAmp = glGetUniformLocation(program, "uAmp")
        uProjection = glGetUniformLocation(program, "projectionMatrix")
        uModelView = glGetUniformLocation(program, "modelViewMatrix")
        uCamera = glGetUniformLocation(program, "cameraPosition")
        uploadSphere()

        // Three.js for a transparent ShaderMaterial: depth test (LessEqual) and write, back faces
        // culled, normal blending (SRC_ALPHA, ONE_MINUS_SRC_ALPHA; alpha ONE, ONE_MINUS_SRC_ALPHA).
        glEnable(GL_DEPTH_TEST)
        glDepthFunc(GL_LEQUAL)
        glEnable(GL_CULL_FACE)
        glCullFace(GL_BACK)
        glFrontFace(GL_CCW)
        glEnable(GL_BLEND)
        glBlendEquation(GL_FUNC_ADD)
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
        glClearColor(0f, 0f, 0f, 0f)
        lastNanos = 0L
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        glViewport(0, 0, width, height)
        Matrix.perspectiveM(projection, 0, FOV_DEGREES, width.toFloat() / height, 0.1f, 100f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        // clock.getDelta(), capped at 0.1 s (also after a pause); frozen at t = 0 without animations.
        val dt = if (!animate || lastNanos == 0L) 0f else min((now - lastNanos) / 1e9f, 0.1f)
        lastNanos = now
        step(dt)

        Matrix.setIdentityM(modelView, 0)
        Matrix.translateM(modelView, 0, 0f, 0f, -CAMERA_Z)
        Matrix.scaleM(modelView, 0, curScale, curScale, curScale)

        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        glUseProgram(program)
        glUniform1f(uTime, phase)
        glUniform1f(uAmp, curLevel)
        glUniformMatrix4fv(uProjection, 1, false, projection, 0)
        glUniformMatrix4fv(uModelView, 1, false, modelView, 0)
        glUniform3f(uCamera, 0f, 0f, CAMERA_Z)
        glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer)
        glEnableVertexAttribArray(aPosition)
        glVertexAttribPointer(aPosition, 3, GL_FLOAT, false, 0, 0)
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indexBuffer)
        glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_SHORT, 0)
    }

    /** initGlass's render(): targets(), then cur.level / speed / scale eased toward them. */
    private fun step(dt: Float) {
        elapsed += dt
        targets(elapsed)
        val fast = min(dt * 10f, 1f)
        val slow = min(dt * 3f, 1f)
        curLevel += (targetLevel - curLevel) * (if (targetLevel > curLevel) fast else slow)
        curSpeed += (targetSpeed - curSpeed) * slow
        curScale += (targetScale - curScale) * min(dt * 6f, 1f)
        phase += dt * curSpeed
        level = curLevel
    }

    private fun targets(t: Float) {
        var lvl = 0f
        var speed = 1f
        var scale = 1f
        when (voice) {
            RideVoiceState.Listening -> {
                lvl = if (demoVoice) {
                    val syl = max(0f, sin(t * 9.0f)) * (0.55f + 0.45f * sin(t * 2.3f))
                    val gap = if (sin(t * 0.8f) > -0.55f) 1f else 0.1f
                    min(1f, syl * gap * 1.1f)
                } else {
                    micLevel.coerceIn(0f, 1f)
                }
                speed = 1.4f + lvl * 3.2f
                scale = 1.03f + lvl * 0.14f
            }
            RideVoiceState.Thinking -> {
                lvl = 0.18f
                speed = 3.2f
                scale = 0.95f
            }
            RideVoiceState.Speaking -> {
                lvl = 0.35f + 0.35f * max(0f, sin(t * 6.5f)) * (0.6f + 0.4f * sin(t * 1.7f))
                speed = 1.6f + lvl * 1.5f
                scale = 1.0f + lvl * 0.08f
            }
            RideVoiceState.Idle -> Unit
        }
        targetLevel = lvl
        targetSpeed = speed
        targetScale = scale
    }

    /** Three.js SphereGeometry(1.55, 160, 160): the same vertices and triangle winding. */
    private fun uploadSphere() {
        val columns = SEGMENTS + 1
        val vertices = ByteBuffer.allocateDirect(columns * columns * 3 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (iy in 0..SEGMENTS) {
            val theta = iy.toFloat() / SEGMENTS * Math.PI.toFloat()
            for (ix in 0..SEGMENTS) {
                val phi = ix.toFloat() / SEGMENTS * 2f * Math.PI.toFloat()
                vertices.put(-RADIUS * cos(phi) * sin(theta))
                vertices.put(RADIUS * cos(theta))
                vertices.put(RADIUS * sin(phi) * sin(theta))
            }
        }
        vertices.position(0)
        val indices = ByteBuffer.allocateDirect(SEGMENTS * SEGMENTS * 6 * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        for (iy in 0 until SEGMENTS) {
            for (ix in 0 until SEGMENTS) {
                val a = (iy * columns + ix + 1).toShort()
                val b = (iy * columns + ix).toShort()
                val c = ((iy + 1) * columns + ix).toShort()
                val d = ((iy + 1) * columns + ix + 1).toShort()
                if (iy != 0) indices.put(a).put(b).put(d)
                if (iy != SEGMENTS - 1) indices.put(b).put(c).put(d)
            }
        }
        indexCount = indices.position()
        indices.position(0)

        val buffers = IntArray(2)
        glGenBuffers(2, buffers, 0)
        vertexBuffer = buffers[0]
        indexBuffer = buffers[1]
        glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer)
        glBufferData(GL_ARRAY_BUFFER, vertices.capacity() * 4, vertices, GL_STATIC_DRAW)
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indexBuffer)
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indexCount * 2, indices, GL_STATIC_DRAW)
    }

    private fun compile(type: Int, source: String): Int {
        val shader = glCreateShader(type)
        glShaderSource(shader, source)
        glCompileShader(shader)
        val ok = IntArray(1)
        glGetShaderiv(shader, GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) Log.e(TAG, "Shader compile failed: ${glGetShaderInfoLog(shader)}")
        return shader
    }

    private fun link(vertex: Int, fragment: Int): Int {
        val program = glCreateProgram()
        glAttachShader(program, vertex)
        glAttachShader(program, fragment)
        glLinkProgram(program)
        val ok = IntArray(1)
        glGetProgramiv(program, GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) Log.e(TAG, "Program link failed: ${glGetProgramInfoLog(program)}")
        return program
    }
}

private const val TAG = "GlassGlobe"
private const val MAX_PIXEL_RATIO = 2f
private const val RADIUS = 1.55f
/** The design's 160; 96 is the agreed fallback if a phone can't keep 60 fps (looks the same, fewer triangles). */
private const val SEGMENTS = 160
private const val FOV_DEGREES = 40f
private const val CAMERA_Z = 6f

// ---- GLSL from design/pillion-glass-orb-handoff.html, character for character. ----

private const val GLASS_COMMON = """
uniform float uTime;
uniform float uAmp;
float ridge(vec3 p) {
  float a = atan(p.z, p.x) + p.y * 2.1 + 0.55 * sin(p.x * 1.6 + p.z * 0.9 + uTime * 0.3) + uTime * 0.18;
  float depth = 0.55 + 0.45 * sin(p.y * 1.4 - p.x * 0.8 + uTime * 0.22);
  return sin(a * 6.0 + sin(p.y * 2.3 + uTime * 0.4) * 1.3) * depth;
}
"""

private const val GLASS_VERT = """
$GLASS_COMMON
varying vec3 vPos;
varying vec3 vN;
void main() {
  vec3 n = normalize(position);
  vec3 p = position + n * ridge(position) * 0.035 * (1.0 + uAmp * 2.2);
  vPos = p;
  vN = n;
  gl_Position = projectionMatrix * modelViewMatrix * vec4(p, 1.0);
}
"""

private const val GLASS_FRAG = """
$GLASS_COMMON
varying vec3 vPos;
varying vec3 vN;
void main() {
  vec3 n = normalize(vN);
  float e = 0.02;
  float r0 = ridge(vPos);
  vec3 g = vec3(ridge(vPos + vec3(e,0,0)) - r0, ridge(vPos + vec3(0,e,0)) - r0, ridge(vPos + vec3(0,0,e)) - r0) / e;
  vec3 N = normalize(n - 0.09 * (g - dot(g, n) * n));
  vec3 V = normalize(cameraPosition - vPos);
  float ndv = max(dot(N, V), 0.0);
  float fres = pow(1.0 - ndv, 2.2);
  vec3 R = reflect(-V, N);
  vec3 core = vec3(0.24, 0.23, 0.52);
  vec3 rim = vec3(0.86, 0.84, 1.00);
  vec3 col = mix(core, rim, clamp(fres * 1.2 + r0 * 0.10 + 0.10, 0.0, 1.0));
  float sky = smoothstep(-0.1, 0.9, R.y);
  float band = smoothstep(0.55, 0.95, sin(R.x * 3.0 + R.y * 2.0) * 0.5 + 0.5);
  col += vec3(0.75, 0.74, 0.95) * sky * 0.35 + vec3(1.0) * band * 0.18;
  float film = fres * 1.6 + r0 * 0.35 + dot(N, vec3(0.3, 0.6, 0.2)) * 0.8 + uTime * 0.03;
  vec3 irid = 0.5 + 0.5 * cos(6.28318 * (film + vec3(0.0, 0.33, 0.67)));
  float streak = smoothstep(0.35, 0.95, r0) * 0.6 + fres * 0.5;
  col = mix(col, irid * vec3(1.0, 0.92, 1.05), clamp(streak * (0.42 + uAmp * 0.25), 0.0, 0.62));
  vec3 L1 = normalize(vec3(-0.5, 0.7, 0.6));
  vec3 L2 = normalize(vec3(0.6, -0.2, 0.7));
  float spec = pow(max(dot(R, L1), 0.0), 70.0) * 1.3 + pow(max(dot(R, L2), 0.0), 18.0) * 0.28;
  col += vec3(spec);
  col += vec3(0.9, 0.9, 1.0) * pow(fres, 3.0) * 0.35;
  gl_FragColor = vec4(min(col, vec3(1.0)), 0.96);
}
"""

// What Three.js puts in front of a ShaderMaterial's code (the parts these shaders use).
private const val VERTEX_SHADER = """precision highp float;
precision highp int;
uniform mat4 modelViewMatrix;
uniform mat4 projectionMatrix;
attribute vec3 position;
$GLASS_VERT"""

private const val FRAGMENT_SHADER = """precision highp float;
precision highp int;
uniform vec3 cameraPosition;
$GLASS_FRAG"""
