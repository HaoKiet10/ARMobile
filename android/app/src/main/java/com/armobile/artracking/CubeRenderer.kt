package com.armobile.artracking

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Vẽ 1 khối lập phương màu tại vị trí do MVP matrix chỉ định.
 * Mục đích: verify pipeline overlay 3D bám đúng pose marker,
 * tương đương ViroBox debug trước đây — trước khi đầu tư Filament/glTF thật.
 */
class CubeRenderer {

    private var program = 0
    private var positionAttrib = 0
    private var mvpUniform = 0
    private var colorUniform = 0
    private val vertexBuffer: FloatBuffer
    private val indexBuffer: java.nio.ShortBuffer

    companion object {
        // Cube cạnh 1.0, sẽ scale nhỏ lại khi dựng model matrix (xem buildModelMatrix)
        private val VERTICES = floatArrayOf(
            -0.5f, -0.5f, -0.5f,  0.5f, -0.5f, -0.5f,  0.5f,  0.5f, -0.5f, -0.5f,  0.5f, -0.5f, // back
            -0.5f, -0.5f,  0.5f,  0.5f, -0.5f,  0.5f,  0.5f,  0.5f,  0.5f, -0.5f,  0.5f,  0.5f, // front
        )
        private val INDICES = shortArrayOf(
            0,1,2, 0,2,3, // back
            4,5,6, 4,6,7, // front
            0,4,7, 0,7,3, // left
            1,5,6, 1,6,2, // right
            3,2,6, 3,6,7, // top
            0,1,5, 0,5,4, // bottom
        )

        private const val VERTEX_SHADER = """
            uniform mat4 u_MVP;
            attribute vec4 a_Position;
            void main() {
                gl_Position = u_MVP * a_Position;
            }
        """
        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 u_Color;
            void main() {
                gl_FragColor = u_Color;
            }
        """
    }

    init {
        vertexBuffer = ByteBuffer.allocateDirect(VERTICES.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(VERTICES); position(0) }
        indexBuffer = ByteBuffer.allocateDirect(INDICES.size * 2)
            .order(ByteOrder.nativeOrder()).asShortBuffer().apply { put(INDICES); position(0) }
    }

    fun createOnGlThread() {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
        }
        positionAttrib = GLES20.glGetAttribLocation(program, "a_Position")
        mvpUniform = GLES20.glGetUniformLocation(program, "u_MVP")
        colorUniform = GLES20.glGetUniformLocation(program, "u_Color")
    }

    /** mvpMatrix = projection * view * model (model đã bao gồm translate theo pose + scale kích thước box) */
    fun draw(mvpMatrix: FloatArray) {
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(program)

        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(positionAttrib, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(positionAttrib)

        GLES20.glUniformMatrix4fv(mvpUniform, 1, false, mvpMatrix, 0)
        GLES20.glUniform4f(colorUniform, 1.0f, 0.1f, 0.1f, 1.0f) // đỏ, giống ViroBox debug cũ

        GLES20.glDrawElements(GLES20.GL_TRIANGLES, INDICES.size, GLES20.GL_UNSIGNED_SHORT, indexBuffer)

        GLES20.glDisableVertexAttribArray(positionAttrib)
    }

    private fun loadShader(type: Int, code: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, code)
        GLES20.glCompileShader(shader)
        return shader
    }
}
