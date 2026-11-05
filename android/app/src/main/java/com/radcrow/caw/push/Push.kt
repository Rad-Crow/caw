package com.radcrow.caw.push

import android.util.Log
import com.radcrow.caw.CawApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint

class Push(private val app: CawApp) {
    val distributor = MutableStateFlow(UnifiedPush.getAckDistributor(app))
    val endpoint = MutableStateFlow(app.prefs.pushEndpoint)

    fun distributors(): List<String> = UnifiedPush.getDistributors(app)

    fun use(pkg: String) {
        UnifiedPush.saveDistributor(app, pkg)
        register()
    }

    // re-registering is cheap and re-sends the endpoint and keys to the server
    fun ensure() {
        if (UnifiedPush.getSavedDistributor(app) != null) register() else autoPick()
    }

    // auto-selects when only one push app is installed; retries until an endpoint arrives, since an unopened push app ignores registrations
    fun autoPick() {
        val saved = UnifiedPush.getSavedDistributor(app)
        if (saved != null && saved in distributors()) {
            if (app.prefs.pushEndpoint == null) register()
            return
        }
        if (saved != null) {
            UnifiedPush.removeDistributor(app)
            onUnregistered()
        }
        distributors().singleOrNull()?.let { use(it) }
    }

    // null if none is chosen or the chosen app is uninstalled
    fun chosen(): String? = UnifiedPush.getSavedDistributor(app)?.takeIf { it in distributors() }

    // a distributor that switched servers keeps returning the stale endpoint until unregistered
    fun reregister() {
        val d = UnifiedPush.getSavedDistributor(app) ?: return
        UnifiedPush.unregister(app)
        UnifiedPush.saveDistributor(app, d)
        register()
    }

    private fun register() {
        UnifiedPush.register(app, vapid = app.prefs.vapid)
    }

    fun onEndpoint(ep: PushEndpoint) {
        val keys = ep.pubKeySet
        if (keys == null) {
            Log.w(TAG, "distributor gave no encryption keys, ignoring")
            return
        }
        app.prefs.pushEndpoint = ep.url
        endpoint.value = ep.url
        distributor.value = UnifiedPush.getAckDistributor(app)
        app.scope.launch {
            runCatching { app.api.registerPush(ep.url, keys.pubKey, keys.auth) }
                .onFailure { Log.w(TAG, "push register failed", it) }
        }
    }

    fun onUnregistered() {
        val ep = app.prefs.pushEndpoint
        app.prefs.pushEndpoint = null
        endpoint.value = null
        distributor.value = null
        if (ep != null) app.scope.launch { runCatching { app.api.unregisterPush(ep) } }
    }

    suspend fun logout() {
        app.prefs.pushEndpoint?.let { runCatching { app.api.unregisterPush(it) } }
        UnifiedPush.unregister(app)
        app.prefs.pushEndpoint = null
        endpoint.value = null
        distributor.value = null
    }

    companion object {
        private const val TAG = "caw.push"
    }
}
