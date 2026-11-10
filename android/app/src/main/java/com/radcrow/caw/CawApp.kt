package com.radcrow.caw

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.radcrow.caw.call.CallManager
import com.radcrow.caw.call.Notifications
import com.radcrow.caw.push.Push
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class CawApp : Application() {
    lateinit var prefs: Prefs
    lateinit var api: Api
    lateinit var socket: Socket
    lateinit var repo: Repo
    lateinit var calls: CallManager
    lateinit var push: Push
    lateinit var sealed: com.radcrow.caw.sealed.Sealed
    lateinit var telecom: com.radcrow.caw.call.TelecomBridge
    lateinit var media: com.radcrow.caw.media.Media
    lateinit var http: OkHttpClient

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile var visible = false
        private set

    val locked = MutableStateFlow(false)
    val theme by lazy { MutableStateFlow(prefs.theme) }
    // true once the app's own screens were opened since it last went to the background; a call screen over the lock screen does not count
    @Volatile var present = false

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        prefs.server?.let { s -> normServer(s)?.takeIf { it != s }?.let { prefs.server = it } }
        locked.value = prefs.appLock
        http = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .build()
        api = Api(prefs, http)
        socket = Socket(this, prefs, http)
        telecom = com.radcrow.caw.call.TelecomBridge(this)
        calls = CallManager(this)
        media = com.radcrow.caw.media.Media(this)
        repo = Repo(this)
        sealed = com.radcrow.caw.sealed.Sealed(this)
        push = Push(this)
        Notifications.createChannels(this)
        KeyWorker.schedule(this)

        scope.launch { socket.events.collect { repo.onEvent(it) } }
        scope.launch { socket.unauthorized.collect { signOut() } }

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                visible = true
                if (prefs.token != null) socket.start()
            }

            override fun onStop(owner: LifecycleOwner) {
                visible = false
                present = false
                if (prefs.appLock) locked.value = true
                repo.idleCheck()
            }
        })
    }

    fun onLoggedIn(server: String, res: LoginResponse, cfg: ServerConfig) {
        prefs.server = server
        prefs.token = res.token
        prefs.me = res.user
        prefs.username = res.user.username
        applyConfig(cfg)
        repo.me.value = res.user
        socket.start()
    }

    fun applyConfig(cfg: ServerConfig) {
        prefs.serverName = cfg.serverName
        prefs.vapid = cfg.vapidPublicKey
        repo.config.value = cfg
    }

    fun logout() = scope.launch {
        repo.forgetLocal()
        push.logout()
        runCatching { api.logout() }
        signOut()
    }

    // ends the server session; local data is kept for the next sign-in
    fun endSession() = scope.launch {
        push.logout()
        runCatching { api.logout() }
        signOut()
    }

    private fun signOut() {
        calls.hangup()
        socket.stop()
        prefs.clearSession()
        repo.clear()
    }
}

val Context.app: CawApp get() = applicationContext as CawApp
