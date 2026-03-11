package ar.com.hst.app.extintores

import ar.com.hst.app.SessionManager
import com.google.gson.annotations.SerializedName
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

interface ExtintoresApi {

    @GET("api/clientes_list.php")
    suspend fun getClientes(
        @Query("q") q: String? = null,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 50
    ): ClientesResponse

    @GET("api/establecimientos_list.php")
    suspend fun getEstablecimientos(
        @Query("cliente_id") clienteId: Int,
        @Query("q") q: String? = null,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 100
    ): EstablecimientosResponse

    @POST("api/control_periodico_save.php")
    suspend fun enviarControl(@Body control: ControlRequest): ControlResponse

    @GET("api/agc_lookup.php")
    suspend fun agcLookup(@Query("url") url: String): AgcLookupResponse

    @GET("api/me.php")
    suspend fun refreshSession(): MeResponse

    companion object {
        private const val BASE_URL = "https://hst.ar/asociados/"

        fun create(session: SessionManager): ExtintoresApi {
            val authInterceptor = Interceptor { chain ->
                val original = chain.request()
                val builder = original.newBuilder()
                if (session.sessionCookie.isNotBlank()) {
                    builder.header("Cookie", session.sessionCookie)
                }
                if (original.method == "POST" && session.csrfToken.isNotBlank()) {
                    builder.header("X-CSRF-Token", session.csrfToken)
                }
                chain.proceed(builder.build())
            }

            val client = OkHttpClient.Builder()
                .addInterceptor(authInterceptor)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()

            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(ExtintoresApi::class.java)
        }
    }
}

// --- Request/Response models ---

data class ClientesResponse(
    val ok: Boolean,
    val records: List<ClienteItem>? = null,
    val total: Int = 0,
    val page: Int = 1,
    val pages: Int = 1,
    val error: String? = null
)

data class ClienteItem(
    @SerializedName("item_id") val itemId: Int,
    @SerializedName("razon_social") val razonSocial: String?,
    @SerializedName("nombre_corto") val nombreCorto: String?,
    val cuit: String?,
    val localidad: String?,
    val provincia: String?,
    val activo: String?,
    @SerializedName("est_count") val estCount: Int = 0
)

data class EstablecimientosResponse(
    val ok: Boolean,
    val records: List<EstablecimientoItem>? = null,
    val total: Int = 0,
    val error: String? = null
)

data class EstablecimientoItem(
    @SerializedName("item_id") val itemId: Int,
    val denominacion: String?,
    @SerializedName("nro_sucursal") val nroSucursal: String?,
    val domicilio: String?,
    val localidad: String?,
    val estado: String?
)

data class ControlRequest(
    @SerializedName("cliente_id") val clienteId: Int,
    @SerializedName("establecimiento_id") val establecimientoId: Int,
    @SerializedName("nro_extintor") val nroExtintor: String? = null,
    @SerializedName("url_qr") val urlQr: String? = null,
    @SerializedName("estado_carga") val estadoCarga: String,
    @SerializedName("chapa_baliza") val chapaBaliza: String,
    val comentario: String? = null,
    val agente: String? = null,
    val capacidad: String? = null,
    @SerializedName("venc_mantenimiento") val vencMantenimiento: String? = null,
    @SerializedName("venc_vida_util") val vencVidaUtil: String? = null,
    @SerializedName("venc_ph") val vencPh: String? = null,
    val modo: String = "qr"
)

data class ControlResponse(
    val ok: Boolean,
    val id: Int? = null,
    @SerializedName("total_controles") val totalControles: Int = 0,
    val error: String? = null
)

data class MeResponse(
    val ok: Boolean,
    val logged: Boolean = false,
    val csrf: String? = null,
    val error: String? = null
)

data class AgcLookupResponse(
    val ok: Boolean,
    val data: AgcExtintorData? = null,
    val error: String? = null
)

data class AgcExtintorData(
    @SerializedName("nro_extintor") val nroExtintor: String? = null,
    @SerializedName("agente_extintor") val agenteExtintor: String? = null,
    val capacidad: String? = null,
    val fabricante: String? = null,
    val recargadora: String? = null,
    @SerializedName("fecha_fabricacion") val fechaFabricacion: String? = null,
    @SerializedName("fecha_mantenimiento") val fechaMantenimiento: String? = null,
    @SerializedName("venc_mantenimiento") val vencMantenimiento: String? = null,
    @SerializedName("venc_vida_util") val vencVidaUtil: String? = null,
    @SerializedName("venc_ph") val vencPh: String? = null,
    @SerializedName("nro_tarjeta") val nroTarjeta: String? = null,
    val domicilio: String? = null,
    val uso: String? = null
)
