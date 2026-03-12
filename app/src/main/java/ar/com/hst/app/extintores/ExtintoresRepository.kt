package ar.com.hst.app.extintores

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import ar.com.hst.app.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ExtintoresRepository(context: Context) {

    private val ctx = context.applicationContext
    private val session = SessionManager(ctx)
    private val api = ExtintoresApi.create(session)
    private val dao = HstDatabase.get(ctx).controlDao()

    companion object {
        private const val TAG = "ExtRepo"
    }

    suspend fun getClientes(query: String? = null, page: Int = 1): ClientesResponse {
        return try {
            api.getClientes(q = query, page = page)
        } catch (e: Exception) {
            ClientesResponse(ok = false, error = parseError(e))
        }
    }

    suspend fun agcLookup(url: String): AgcLookupResponse {
        return try {
            api.agcLookup(url)
        } catch (e: Exception) {
            AgcLookupResponse(ok = false, error = parseError(e))
        }
    }

    suspend fun getEstablecimientos(clienteId: Int, query: String? = null): EstablecimientosResponse {
        return try {
            api.getEstablecimientos(clienteId = clienteId, q = query)
        } catch (e: Exception) {
            EstablecimientosResponse(ok = false, error = parseError(e))
        }
    }

    sealed class ControlResult {
        data class Enviado(val totalControles: Int) : ControlResult()
        object GuardadoOffline : ControlResult()
        data class Error(val mensaje: String) : ControlResult()
    }

    suspend fun enviarControl(
        clienteId: Int,
        establecimientoId: Int,
        estadoCarga: String,
        chapaBaliza: String,
        urlQr: String? = null,
        nroExtintor: String? = null,
        comentario: String? = null,
        agente: String? = null,
        capacidad: String? = null,
        vencMantenimiento: String? = null,
        vencVidaUtil: String? = null,
        vencPh: String? = null,
        modo: String = "qr"
    ): ControlResult {
        val entity = ControlPendienteEntity(
            clienteId = clienteId,
            establecimientoId = establecimientoId,
            nroExtintor = nroExtintor,
            urlQr = urlQr,
            estadoCarga = estadoCarga,
            chapaBaliza = chapaBaliza,
            comentario = comentario,
            agente = agente,
            capacidad = capacidad,
            vencMantenimiento = vencMantenimiento,
            vencVidaUtil = vencVidaUtil,
            vencPh = vencPh,
            modo = modo
        )

        if (!isOnline()) {
            withContext(Dispatchers.IO) { dao.insert(entity) }
            return ControlResult.GuardadoOffline
        }

        return try {
            refreshCsrfIfNeeded()
            val request = ControlRequest(
                clienteId = clienteId,
                establecimientoId = establecimientoId,
                nroExtintor = nroExtintor,
                urlQr = urlQr,
                estadoCarga = estadoCarga,
                chapaBaliza = chapaBaliza,
                comentario = comentario,
                agente = agente,
                capacidad = capacidad,
                vencMantenimiento = vencMantenimiento,
                vencVidaUtil = vencVidaUtil,
                vencPh = vencPh,
                modo = modo
            )
            val response = api.enviarControl(request)
            if (response.ok) {
                ControlResult.Enviado(response.totalControles)
            } else {
                withContext(Dispatchers.IO) { dao.insert(entity) }
                ControlResult.Error(response.error ?: "Error del servidor")
            }
        } catch (e: Exception) {
            withContext(Dispatchers.IO) { dao.insert(entity) }
            ControlResult.GuardadoOffline
        }
    }

    suspend fun sincronizarPendientes(): Pair<Int, Int> = withContext(Dispatchers.IO) {
        var ok = 0
        var fail = 0
        val pendientes = dao.getPendientes()
        if (pendientes.isEmpty()) return@withContext Pair(0, 0)

        refreshCsrfIfNeeded()

        for (p in pendientes) {
            try {
                val request = ControlRequest(
                    clienteId = p.clienteId,
                    establecimientoId = p.establecimientoId,
                    nroExtintor = p.nroExtintor,
                    urlQr = p.urlQr,
                    estadoCarga = p.estadoCarga,
                    chapaBaliza = p.chapaBaliza,
                    comentario = p.comentario,
                    agente = p.agente,
                    capacidad = p.capacidad,
                    vencMantenimiento = p.vencMantenimiento,
                    vencVidaUtil = p.vencVidaUtil,
                    vencPh = p.vencPh,
                    modo = p.modo
                )
                val response = api.enviarControl(request)
                if (response.ok) {
                    dao.marcarEnviado(p.id)
                    ok++
                } else {
                    dao.marcarError(p.id)
                    fail++
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error syncing control ${p.id}: ${e.message}")
                dao.marcarError(p.id)
                fail++
            }
        }
        dao.limpiarEnviados()
        Pair(ok, fail)
    }

    suspend fun contarPendientes(): Int = withContext(Dispatchers.IO) {
        dao.contarPendientes()
    }

    private suspend fun refreshCsrfIfNeeded() {
        if (session.csrfToken.isBlank()) {
            try {
                val me = api.refreshSession()
                if (me.csrf != null) session.csrfToken = me.csrf
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error refreshing CSRF token: ${e.message}")
            }
        }
    }

    fun isOnline(): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun parseError(e: Exception): String {
        return when (e) {
            is java.net.UnknownHostException -> "Sin conexión al servidor"
            is java.net.SocketTimeoutException -> "Timeout de conexión"
            is javax.net.ssl.SSLException -> "Error de seguridad SSL"
            is java.io.IOException -> "Error de red: ${e.localizedMessage}"
            else -> "Error: ${e.localizedMessage}"
        }
    }
}
