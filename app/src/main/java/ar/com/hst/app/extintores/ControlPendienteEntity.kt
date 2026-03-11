package ar.com.hst.app.extintores

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "controles_pendientes")
data class ControlPendienteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clienteId: Int,
    val establecimientoId: Int,
    val nroExtintor: String? = null,
    val urlQr: String? = null,
    val estadoCarga: String,
    val chapaBaliza: String,
    val comentario: String? = null,
    val agente: String? = null,
    val capacidad: String? = null,
    val vencMantenimiento: String? = null,
    val vencVidaUtil: String? = null,
    val vencPh: String? = null,
    val modo: String, // "qr" or "manual"
    val fecha: Long = System.currentTimeMillis(),
    val estado: String = "pendiente", // pendiente, enviado, error
    val intentos: Int = 0
)
