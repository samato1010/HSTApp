package ar.com.hst.app.extintores

import androidx.room.*

@Dao
interface ControlDao {
    @Insert
    fun insert(control: ControlPendienteEntity): Long

    @Query("SELECT * FROM controles_pendientes WHERE estado = 'pendiente' ORDER BY fecha ASC")
    fun getPendientes(): List<ControlPendienteEntity>

    @Query("UPDATE controles_pendientes SET estado = 'enviado' WHERE id = :id")
    fun marcarEnviado(id: Long)

    @Query("UPDATE controles_pendientes SET estado = 'error', intentos = intentos + 1 WHERE id = :id")
    fun marcarError(id: Long)

    @Query("SELECT COUNT(*) FROM controles_pendientes WHERE estado = 'pendiente'")
    fun contarPendientes(): Int

    @Query("DELETE FROM controles_pendientes WHERE estado = 'enviado'")
    fun limpiarEnviados()

    @Query("SELECT COUNT(*) FROM controles_pendientes")
    fun contarTotal(): Int

    @Query("SELECT COUNT(*) FROM controles_pendientes WHERE estado = 'enviado'")
    fun contarEnviados(): Int

    @Query("DELETE FROM controles_pendientes")
    fun borrarTodo()
}
