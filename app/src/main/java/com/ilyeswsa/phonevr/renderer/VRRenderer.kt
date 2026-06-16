package com.ilyeswsa.phonevr.renderer

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * OpenGL ES 2.0 renderer that applies barrel distortion to simulate VR lenses.
 *
 * The video frame is rendered twice (left and right eye) with:
 * - Barrel distortion (k1, k2 coefficients) to counteract physical lens pincushion
 * - Chromatic aberration correction (RGB channel offset)
 * - Vignette effect at edges
 *
 * This is the same principle Meta Quest uses, just software-only.
 */
class VRRenderer(private val context: Context) : GLSurfaceView.Renderer {

    var decoderSurface: Surface? = null
        private set

    private var programId = 0
    private var textureId = 0
    private var quadVBO = 0
    private var surfaceTexture: android.graphics.SurfaceTexture? = null

    // Lens distortion coefficients (tune these for your cardboard/lens)
    private var k1 = 0.22f
    private var k2 = 0.24f

    private val vertexShader = """
        attribute vec2 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = vec4(aPosition, 0.0, 1.0);
            vTexCoord = aTexCoord;
        }
    """.trimIndent()

    // Barrel distortion + chromatic aberration fragment shader
    private val fragmentShader = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        uniform samplerExternalOES uTexture;
        uniform float uK1;
        uniform float uK2;
        uniform vec2 uCenter;    // eye center in UV space (0.25,0.5) or (0.75,0.5)
        varying vec2 vTexCoord;
        
        vec2 distort(vec2 uv, vec2 center) {
            vec2 d = uv - center;
            // Scale to [-1,1] relative to eye half
            d *= 4.0;
            float r2 = dot(d, d);
            float distFactor = 1.0 + uK1 * r2 + uK2 * r2 * r2;
            return center + (d / distFactor) * 0.25;
        }
        
        void main() {
            // Determine which eye we're in
            vec2 center = vTexCoord.x < 0.5 ? vec2(0.25, 0.5) : vec2(0.75, 0.5);
            
            // Barrel distortion per channel (chromatic aberration)
            vec2 uvR = distort(vTexCoord, center);
            vec2 uvG = mix(vTexCoord, distort(vTexCoord, center), 0.98);
            vec2 uvB = mix(vTexCoord, distort(vTexCoord, center), 0.96);
            
            float r = texture2D(uTexture, uvR).r;
            float g = texture2D(uTexture, uvG).g;
            float b = texture2D(uTexture, uvB).b;
            
            // Vignette
            vec2 d = vTexCoord - center;
            float vignette = 1.0 - smoothstep(0.15, 0.5, length(d * 2.0));
            
            gl_FragColor = vec4(r, g, b, 1.0) * vignette;
        }
    """.trimIndent()

    // Full-screen quad vertices: position (x,y) + texcoord (u,v)
    private val quadVertices = floatArrayOf(
        -1f, -1f,  0f, 0f,
         1f, -1f,  1f, 0f,
        -1f,  1f,  0f, 1f,
         1f,  1f,  1f, 1f,
    )

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        // Create external OES texture for SurfaceTexture (video input)
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]

        surfaceTexture = android.graphics.SurfaceTexture(textureId).also {
            it.setDefaultBufferSize(2160, 1200)
            decoderSurface = Surface(it)
        }

        // Compile shaders
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexShader)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShader)
        programId = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vs)
            GLES20.glAttachShader(it, fs)
            GLES20.glLinkProgram(it)
        }

        // Upload quad VBO
        val vbos = IntArray(1)
        GLES20.glGenBuffers(1, vbos, 0)
        quadVBO = vbos[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVBO)
        val vBuf: FloatBuffer = ByteBuffer.allocateDirect(quadVertices.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().also {
                it.put(quadVertices); it.position(0)
            }
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, quadVertices.size * 4, vBuf, GLES20.GL_STATIC_DRAW)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        surfaceTexture?.updateTexImage()

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(programId)

        // Bind texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(0x8D65 /*GL_TEXTURE_EXTERNAL_OES*/, textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "uTexture"), 0)

        // Distortion uniforms
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, "uK1"), k1)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(programId, "uK2"), k2)

        // Bind VBO and draw
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVBO)
        val stride = 4 * 4 // 4 floats * 4 bytes
        val posLoc = GLES20.glGetAttribLocation(programId, "aPosition")
        val texLoc = GLES20.glGetAttribLocation(programId, "aTexCoord")
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glEnableVertexAttribArray(texLoc)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, stride, 0)
        GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, stride, 2 * 4)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    fun setDistortionCoeffs(k1: Float, k2: Float) {
        this.k1 = k1; this.k2 = k2
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        return shader
    }
}
