package com.robomove.app.robot

import android.content.Context
import android.os.IBinder
import android.util.Log
import android.service.reeman.IReemanService

class DamanHeadControl(private val context: Context) {

    companion object {
        private const val TAG = "DamanHeadControl"

        @Volatile
        var DRY_RUN = true

        // Kept for testing - but with the ServiceManager fix below this
        // probably won't matter anymore since we're using the real binder now.
        @Volatile
        var TTY_PORT = 4
    }

    private var reemanService: IReemanService? = null
    @Volatile
    private var isConnected = false

    var onConnectionChanged: ((connected: Boolean) -> Unit)? = null

    // ── Protocol constants ──────────────────────────────────────────────────
    private val CMD_HEAD: Byte = 0x84.toByte()
    private val SUB_HORI: Byte = 0x02
    private val SUB_VERTI: Byte = 0x01
    private val MODE_ABSOLUTE: Byte = 0x02
    private val TIME_VALUE: Byte = 0x02

    // ── Angle limits ────────────────────────────────────────────────────────
    val HORI_MIN = -80;  val HORI_MAX = 80
    val VERTI_MIN = -3;  val VERTI_MAX = 20

    // ── Connection ─────────────────────────────────────────────────────────
    // FIX: The reeman binder is NOT a regular bindable app Service reachable
    // via Context.bindService() + Intent. On this DAMAN build it's registered
    // directly as a native Android system service (like "wifi" or "power"),
    // only reachable through the hidden ServiceManager.getService() API via
    // reflection. This matches your friend's confirmed-working connect().
    // It's synchronous - no waiting for onServiceConnected(), no race window
    // where a button tap can fire before the service is ready.
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
                Log.e(TAG, "ServiceManager.getService(\"reeman\") returned null - " +
                        "service may not be registered on this build/process yet")
            }
        } catch (e: Exception) {
            reemanService = null
            isConnected = false
            Log.e(TAG, "Failed to connect to reeman service: ${e.message}")
        }
        onConnectionChanged?.invoke(isConnected)
    }

    fun disconnect() {
        // No bindService() was used, so there's no unbindService() needed -
        // just drop our reference.
        reemanService = null
        isConnected = false
    }

    fun isReady() = isConnected

    // ── Public movement commands ────────────────────────────────────────────

    fun moveHorizontal(angle: Int, speedPercent: Int): Int {
        val a = angle.coerceIn(HORI_MIN, HORI_MAX)
        val s = speedPercent.coerceIn(1, 100)
        return sendHeadCommand(SUB_HORI, a, s)
    }

    fun moveVertical(angle: Int, speedPercent: Int): Int {
        val a = angle.coerceIn(VERTI_MIN, VERTI_MAX)
        val s = speedPercent.coerceIn(1, 100)
        return sendHeadCommand(SUB_VERTI, a, s)
    }

    fun resetHorizontal() = sendHeadCommand(SUB_HORI, 0, 50)
    fun resetVertical()   = sendHeadCommand(SUB_VERTI, 0, 50)
    fun resetToCenter() {
        Log.d(TAG, "Resetting head to center position")
        sendHeadCommand(SUB_HORI, 0, 50)
        sendHeadCommand(SUB_VERTI, 0, 50)
    }

    // ── Internal protocol builder ───────────────────────────────────────────

    private fun sendHeadCommand(subId: Byte, angle: Int, speed: Int): Int {
        val payload = byteArrayOf(
            0x06,
            CMD_HEAD,
            subId,
            MODE_ABSOLUTE,
            angle.toByte(),
            speed.toByte(),
            TIME_VALUE
        )

        val hex = payload.joinToString(" ") { String.format("%02X", it) }
        Log.d(TAG, "HEAD CMD payload: $hex  (angle=$angle speed=$speed subId=${String.format("%02X", subId)})")

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