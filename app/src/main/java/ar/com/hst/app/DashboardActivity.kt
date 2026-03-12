package ar.com.hst.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ar.com.hst.app.databinding.ActivityDashboardBinding
import ar.com.hst.app.databinding.ItemToolCardBinding
import ar.com.hst.app.extintores.ExtintoresActivity

data class Tool(
    val id: String,
    val name: String,
    val description: String,
    val iconRes: Int,
    val url: String
)

class DashboardActivity : AppCompatActivity() {

    private lateinit var b: ActivityDashboardBinding
    private lateinit var session: SessionManager

    private val tools = listOf(
        Tool(
            id = "simulacro",
            name = "Simulacro",
            description = "Informes de simulacro de evacuación con PDF y envío por email",
            iconRes = R.drawable.ic_simulacro,
            url = "https://hst.ar/asociados/herramientas/simulacro.php"
        ),
        Tool(
            id = "extintores",
            name = "Extintores",
            description = "Control periódico de extintores con escaneo QR",
            iconRes = R.drawable.ic_extintores,
            url = ""
        ),
        Tool(
            id = "relevamiento",
            name = "Relevamiento Visual",
            description = "Relevamiento visual de desvíos con fotos y PDF",
            iconRes = R.drawable.ic_relevamiento,
            url = "https://hst.ar/asociados/herramientas/relevamiento.php"
        ),
        Tool(
            id = "pat",
            name = "Medición de PAT",
            description = "Puesta a tierra - Protocolo SRT 900/15",
            iconRes = R.drawable.ic_pat,
            url = "https://hst.ar/asociados/herramientas/medicion-pat.php"
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(b.root)

        session = SessionManager(this)

        b.userEmail.text = session.email
        b.versionText.text = "v${BuildConfig.VERSION_NAME}"

        b.logoutButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.logout))
                .setMessage("¿Cerrar sesión?")
                .setPositiveButton("Sí") { _, _ ->
                    session.logout()
                    startActivity(Intent(this, LoginActivity::class.java))
                    finish()
                }
                .setNegativeButton("No", null)
                .show()
        }

        val allowed = session.allowedTools
        val visibleTools = if (allowed.isEmpty()) tools else tools.filter { it.id in allowed }

        b.toolsRecycler.layoutManager = LinearLayoutManager(this)
        b.toolsRecycler.adapter = ToolAdapter(visibleTools) { tool ->
            if (tool.id == "extintores") {
                startActivity(Intent(this, ExtintoresActivity::class.java))
            } else {
                session.syncCookiesToWebView()
                val intent = Intent(this, ToolWebViewActivity::class.java).apply {
                    putExtra("url", tool.url)
                    putExtra("title", tool.name)
                }
                startActivity(intent)
            }
        }

        // Check for updates
        UpdateManager.checkForUpdate(this)
    }
}

class ToolAdapter(
    private val tools: List<Tool>,
    private val onClick: (Tool) -> Unit
) : RecyclerView.Adapter<ToolAdapter.VH>() {

    class VH(val b: ItemToolCardBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemToolCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val tool = tools[position]
        holder.b.toolName.text = tool.name
        holder.b.toolDesc.text = tool.description
        holder.b.toolIcon.setImageResource(tool.iconRes)
        holder.b.root.setOnClickListener { onClick(tool) }
    }

    override fun getItemCount() = tools.size
}
