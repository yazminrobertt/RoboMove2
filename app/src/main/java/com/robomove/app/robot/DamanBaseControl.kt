package com.robomove.app.robot

import android.content.Context
import android.os.IBinder
import android.util.Log
import android.service.reeman.IReemanService

/**
 * Reverse-engineered from motionmanager3399.apk (com.reeman.motion.MainActivity —
 * "底座前后"/"底座旋转" bottom move/rotate screens). Same AA 55 [len] [payload] [xor]
 * envelope and same reeman service as DamanHeadControl/DamanArmControl.
 *
 * ⚠️ UNLIKE head and arm control, this has NOT been confirmed working on the real
 * robot. Test with DRY_RUN first, in an open space, before trusting it.
 */
class DamanBaseControl(private val context: Context) {

    companion object {
        private const val TAG = "DamanBaseControl"

        @Volatile
        var DRY_RUN = false

        @Volatile
        var TTY_PORT = 4
    }

    private var reemanService: IReemanService? = null
    @Volatile
    private var isConnected = false

    var onConnectionChanged: ((connected: Boolean) -> Unit)? = null

    // ── Protocol constants ──────────────────────────────────────────────────
    private val CMD_BASE: Byte = 0x93.toByte()   // -109
    private val SUB_MOVE: Byte = 0x01             // forward(+) / backward(-)
    private val SUB_ROTATE: Byte = 0x02           // turn left(+) / turn right(-)
    private val TIME_VALUE: Byte = 0x00

    // ── Safety limits ───────────────────────────────────────────────────────
    // Conservative on purpose. Unit for "value" (cm? mm?) is unconfirmed —
    // tune upward only after confirming this even moves the robot at all.
    val MAX_MOVE_DISTANCE = 30
    val MAX_ROTATE_ANGLE  = 30
    val MAX_SPEED         = 40   // capped well under 100 for autonomous movement

    // ── Connection — identical pattern to DamanHeadControl/DamanArmControl ──
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

    fun moveForward(distance: Int, speed: Int): Int =
        sendBaseCommand(SUB_MOVE, distance.coerceIn(0, MAX_MOVE_DISTANCE), speed)

    fun moveBackward(distance: Int, speed: Int): Int =
        sendBaseCommand(SUB_MOVE, -distance.coerceIn(0, MAX_MOVE_DISTANCE), speed)

    fun turnLeft(angle: Int, speed: Int): Int =
        sendBaseCommand(SUB_ROTATE, angle.coerceIn(0, MAX_ROTATE_ANGLE), speed)

    fun turnRight(angle: Int, speed: Int): Int =
        sendBaseCommand(SUB_ROTATE, -angle.coerceIn(0, MAX_ROTATE_ANGLE), speed)

    /**
     * Best-effort stop — sends a zero-distance move command. NOT a confirmed
     * emergency stop. Test this specifically, on its own, before relying on it.
     */
    fun stop(): Int = sendBaseCommand(SUB_MOVE, 0, 1)

    // ── Internal protocol builder ───────────────────────────────────────────

    private fun sendBaseCommand(subId: Byte, value: Int, speed: Int): Int {
        val s = speed.coerceIn(1, MAX_SPEED)

        val payload = byteArrayOf(
            0x07,
            CMD_BASE,
            subId,
            highByte(value), lowByte(value),
            highByte(s), lowByte(s),
            TIME_VALUE
        )

        val hex = payload.joinToString(" ") { String.format("%02X", it) }
        Log.d(TAG, "BASE CMD payload: $hex (sub=${String.format("%02X", subId)} value=$value speed=$s)")

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