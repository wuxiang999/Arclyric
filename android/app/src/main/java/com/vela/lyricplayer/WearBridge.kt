package com.vela.lyricplayer

import android.content.Context
import android.os.SystemClock
import com.xiaomi.xms.wearable.Wearable
import com.xiaomi.xms.wearable.auth.Permission
import com.xiaomi.xms.wearable.message.OnMessageReceivedListener
import org.json.JSONObject

class WearBridge(
    context: Context,
    private val onMessage: (String) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val nodeApi = Wearable.getNodeApi(appContext)
    private val authApi = Wearable.getAuthApi(appContext)
    private val messageApi = Wearable.getMessageApi(appContext)

    @Volatile
    private var nodeId: String? = null

    @Volatile
    private var registered = false

    @Volatile
    private var connecting = false

    private var lastAttempt = 0L

    // 连续发送失败通常意味着节点已经失效（手环断连 / 通道被系统回收），
    // 这时必须重新走一遍发现 + 注册，否则会永远卡在「发送失败」。
    @Volatile
    private var failures = 0L

    private val listener = OnMessageReceivedListener { _, bytes ->
        val text = String(bytes, Charsets.UTF_8).trim()
        if (text.isNotEmpty()) {
            onMessage(text)
        }
    }

    fun ensureConnected() {
        if (registered || connecting) {
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastAttempt < 10000L) {
            return
        }
        lastAttempt = now
        connect()
    }

    fun connect() {
        if (connecting || registered) {
            return
        }
        connecting = true
        nodeApi.getConnectedNodes()
            .addOnSuccessListener { nodes ->
                connecting = false
                val node = nodes.firstOrNull()
                if (node == null) {
                    onStatus("未发现已连接的设备，请先在小米运动健康中连接手环")
                    return@addOnSuccessListener
                }
                nodeId = node.id
                onStatus("发现设备：${node.name ?: node.id}")
                checkPermission(node.id)
            }
            .addOnFailureListener { error ->
                connecting = false
                onStatus("获取设备失败：${error.message ?: error}")
            }
    }

    private fun checkPermission(id: String) {
        authApi.checkPermission(id, Permission.DEVICE_MANAGER)
            .addOnSuccessListener { granted ->
                if (granted == true) {
                    register(id)
                } else {
                    requestPermission(id)
                }
            }
            .addOnFailureListener { error ->
                onStatus("权限检查失败：${error.message ?: error}")
            }
    }

    private fun requestPermission(id: String) {
        authApi.requestPermission(id, Permission.DEVICE_MANAGER, Permission.NOTIFY)
            .addOnSuccessListener {
                onStatus("设备权限已授予")
                register(id)
            }
            .addOnFailureListener { error ->
                onStatus("权限申请失败：${error.message ?: error}")
            }
    }

    private fun register(id: String) {
        if (registered) {
            return
        }
        messageApi.addListener(id, listener)
            .addOnSuccessListener {
                registered = true
                onStatus("消息通道已就绪")
            }
            .addOnFailureListener { error ->
                onStatus("注册消息监听失败：${error.message ?: error}")
            }
    }

    fun send(envelope: JSONObject) {
        val id = nodeId
        if (id == null) {
            onStatus("尚未发现设备")
            return
        }
        if (!registered) {
            onStatus("消息通道尚未就绪")
            return
        }
        // 与官方样例保持一致：整包用 {"data":"<json>"} 包一层，
        // 手环侧取 data 再 JSON.parse。直接发裸 JSON 在部分固件上会被丢掉。
        val payload = JSONObject().put("data", envelope.toString())
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        messageApi.sendMessage(id, bytes)
            .addOnSuccessListener {
                failures = 0
            }
            .addOnFailureListener { error ->
                failures++
                // 失败会每秒来一次，只在前几次和每 20 次提醒，避免刷屏
                if (failures <= 2 || failures % 20L == 0L) {
                    onStatus("发送失败：${error.message ?: error}")
                }
                if (failures >= 3L) {
                    reset("连接疑似失效，重新建立通道")
                }
            }
    }

    // 主动作废当前节点，让 ensureConnected 在一个周期后重新发现并注册
    private fun reset(reason: String) {
        val id = nodeId
        registered = false
        nodeId = null
        connecting = false
        failures = 0
        lastAttempt = 0L
        if (id != null) {
            try {
                messageApi.removeListener(id)
            } catch (e: Exception) {
            }
        }
        onStatus(reason)
    }

    fun isReady(): Boolean {
        return registered && nodeId != null
    }

    fun close() {
        val id = nodeId
        if (id != null && registered) {
            try {
                messageApi.removeListener(id)
            } catch (e: Exception) {
            }
        }
        registered = false
        nodeId = null
    }
}
