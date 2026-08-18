package com.robomove.app.robot

import android.content.Context
import android.os.IBinder
import android.util.Log
import android.service.reeman.IReemanService

/**
 * Reverse-engineered from motionmanager3399.apk (com.reeman.motion.MainActivity
 * + RobotActionProvider) — same vendor demo app your head-control byte protocol
 * came from. Arm frames use the identical AA 55 [len] [payload] [xor] envelope
 * as DamanHeadControl, sent through the same reeman system service.
 */

class DamanArmControl(private val context: Context) {

    companion object {
        private const val TAG = "DamanArmControl"

        @Volatile
        var DRY_RUN = true

        @Volatile
        var TTY_PORT = 4
    }

    private var reemanService: IReemanService? = null
    @Volatile
    private var isConnected = false

    var onConnectionChanged: ((connected: Boolean) -> Unit)? = null

    // ── Protocol constants ──────────────────────────────────────────────────
    private val CMD_RIGHT_ARM: Byte = 0x8D.toByte()   // -115
    private val CMD_LEFT_ARM: Byte  = 0x8E.toByte()   // -114

    val MODE_ABSOLUTE = 2
    val MODE_RELATIVE = 4
    private val TIME_VALUE: Byte = 0x00   // 0 = sync with next queued action

    // ── Joint limits (per vendor demo's on-screen hint text) ────────────────
    val JOINT1_MIN = 0;    val JOINT1_MAX = 180   // shoulder pitch — forward/up swing
    val JOINT2_MIN = 0;    val JOINT2_MAX = 80    // shoulder lift out to side — "openness"
    val JOINT3_MIN = -80;  val JOINT3_MAX = 80    // upper-arm rotation/twist
    val JOINT4_MIN = -50;  val JOINT4_MAX = 80    // elbow

    // ── Connection — identical pattern to DamanHeadControl ──────────────────
    fun connect() {
        try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
            val binder = getServiceMethod.invoke(null, "reeman") as? IBinder

            if (binder != null) {
                reemanService = IReemanService.Stub.asInterface(binder)
                isConnected = true
                Log.d(TAG, "Connected to reeman system service via ServiceManager")
            } else {
                reemanService = null
                isConnected = false
                Log.e(TAG, "ServiceManager.getService(\"reeman\") returned null")
            }
        } catch (e: Exception) {
            reemanService = null
            isConnected = false
            Log.e(TAG, "Failed to connect to reeman service: ${e.message}")
        }
        onConnectionChanged?.invoke(isConnected)
    }

    fun disconnect() {
        reemanService = null
        isConnected = false
    }

    fun isReady() = isConnected

    // ── Public movement commands ────────────────────────────────────────────

    fun moveRightArm(joint1: Int, joint2: Int, joint3: Int, joint4: Int, speed: Int, mode: Int = MODE_ABSOLUTE): Int =
        sendArmCommand(CMD_RIGHT_ARM, joint1, joint2, joint3, joint4, speed, mode)

    fun moveLeftArm(joint1: Int, joint2: Int, joint3: Int, joint4: Int, speed: Int, mode: Int = MODE_ABSOLUTE): Int =
        sendArmCommand(CMD_LEFT_ARM, joint1, joint2, joint3, joint4, speed, mode)

    // ── Gesture: both arms raised straight up ──────────────────────────────
    fun raiseBothArms(speed: Int = 60): Pair<Int, Int> {
        val right = moveRightArm(joint1 = 160, joint2 = 20, joint3 = 0, joint4 = 0, speed = speed)
        val left  = moveLeftArm(joint1 = 160, joint2 = 20, joint3 = 0, joint4 = 0, speed = speed)
        return Pair(right, left)
    }

    // ── Gesture: both arms open wide to the sides (less forward, more "open") ──
    // joint1 lower than raiseBothArms (less forward swing), joint2 near max
    // (more sideways lift) — gives a "ta-da / open arms" pose instead of straight up.
    fun openBothArmsWide(speed: Int = 60): Pair<Int, Int> {
        val right = moveRightArm(joint1 = 90, joint2 = 80, joint3 = 0, joint4 = 0, speed = speed)
        val left  = moveLeftArm(joint1 = 90, joint2 = 80, joint3 = 0, joint4 = 0, speed = speed)
        return Pair(right, left)
    }

    // ── Gesture: single-arm raise (used for alternating left/right wave) ───
    fun raiseRightArm(speed: Int = 60): Int =
        moveRightArm(joint1 = 160, joint2 = 20, joint3 = 0, joint4 = 0, speed = speed)

    fun raiseLeftArm(speed: Int = 60): Int =
        moveLeftArm(joint1 = 160, joint2 = 20, joint3 = 0, joint4 = 0, speed = speed)

    // ── Return to neutral rest pose ─────────────────────────────────────────
    // Same neutral (0,0,0,0) works for lowering after any gesture above,
    // since they all start from and return to the same rest position.
    fun lowerBothArms(speed: Int = 60): Pair<Int, Int> {
        val right = moveRightArm(joint1 = 0, joint2 = 0, joint3 = 0, joint4 = 0, speed = speed)
        val left  = moveLeftArm(joint1 = 0, joint2 = 0, joint3 = 0, joint4 = 0, speed = speed)
        return Pair(right, left)
    }

    fun lowerRightArm(speed: Int = 60): Int =
        moveRightArm(joint1 = 0, joint2 = 0, joint3 = 0, joint4 = 0, speed = speed)

    fun lowerLeftArm(speed: Int = 60): Int =
        moveLeftArm(joint1 = 0, joint2 = 0, joint3 = 0, joint4 = 0, speed = speed)

    // ── Internal protocol builder ───────────────────────────────────────────

    private fun sendArmCommand(cmd: Byte, joint1: Int, joint2: Int, joint3: Int, joint4: Int, speed: Int, mode: Int): Int {
        val j1 = joint1.coerceIn(JOINT1_MIN, JOINT1_MAX)
        val j2 = joint2.coerceIn(JOINT2_MIN, JOINT2_MAX)
        val j3 = joint3.coerceIn(JOINT3_MIN, JOINT3_MAX)
        val j4 = joint4.coerceIn(JOINT4_MIN, JOINT4_MAX)
        val s  = speed.coerceIn(1, 100)

        val payload = byteArrayOf(
            0x0C,
            cmd,
            mode.toByte(),
            highByte(j1), lowByte(j1),
            highByte(j2), lowByte(j2),
            highByte(j3), lowByte(j3),
            highByte(j4), lowByte(j4),
            s.toByte(),
            TIME_VALUE
        )

        val hex = payload.joinToString(" ") { String.format("%02X", it) }
        Log.d(TAG, "ARM CMD payload: $hex (cmd=${String.format("%02X", cmd)} j1=$j1 j2=$j2 j3=$j3 j4=$j4 speed=$s)")

        if (DRY_RUN) {
            Log.d(TAG, "DRY RUN — frame NOT sent to hardware")
            return -999
        }

        if (!isConnected || reemanService == null) {
            Log.w(TAG, "Not connected - attempting reconnect before sending")
            connect()
            if (!isConnected) {
                Log.w(TAG, "Reconnect failed, cannot send frame")
                return -1
            }
        }

        return sendFrame(payload)
    }

    // signed 16-bit split — matches RobotActionProvider.getHight8/getLow8 in the vendor app
    private fun highByte(value: Int): Byte {
        val s = value.toShort()
        return ((s.toInt() ushr 8) and 0xFF).toByte()
    }

    private fun lowByte(value: Int): Byte {
        val s = value.toShort()
        return (s.toInt() and 0xFF).toByte()
    }

    private fun sendFrame(payload: ByteArray): Int {
        if (reemanService == null) {
            Log.w(TAG, "Service null, cannot send frame")
            return -1
        }

        val frame = ByteArray(payload.size + 4)
        frame[0] = 0xAA.toByte()
        frame[1] = 0x55.toByte()
        frame[2] = payload.size.toByte()
        payload.copyInto(frame, 3)

        var xor = 0
        for (b in payload) xor = xor xor b.toInt()
        frame[frame.size - 1] = xor.toByte()

        val frameHex = frame.joinToString(" ") { String.format("%02X", it) }
        Log.d(TAG, "Full frame: $frameHex")

        return try {
            val result = if (TTY_PORT == 3) {
                reemanService!!.send_to_ttys3(frame)
            } else {
                reemanService!!.send_to_ttys4(frame)
            }
            Log.d(TAG, "send_to_ttys$TTY_PORT result: $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "send_to_ttys$TTY_PORT threw: ${e.message}")
            -1
        }
    }
}