package ar.com.hst.app.extintores

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.*
import android.text.InputType
import android.util.Log
import android.view.*
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ar.com.hst.app.R
import ar.com.hst.app.databinding.ActivityExtintoresBinding
import ar.com.hst.app.databinding.ItemClienteRowBinding
import ar.com.hst.app.databinding.ItemEstablecimientoRowBinding
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.launch

class ExtintoresActivity : AppCompatActivity() {

    private lateinit var b: ActivityExtintoresBinding
    private lateinit var repo: ExtintoresRepository

    // Selection state
    private var selectedCliente: ClienteItem? = null
    private var selectedEstablecimiento: EstablecimientoItem? = null
    private var controlCount = 0

    // Camera
    private var cameraProvider: ProcessCameraProvider? = null
    private val barcodeScanner = BarcodeScanning.getClient()
    private var lastQrUrl: String = ""
    private var lastQrTime: Long = 0
    // Set de QRs ya controlados en esta sesión para evitar duplicados
    private val controlledQrUrls = mutableSetOf<String>()

    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else showMsg("Permiso de cámara requerido")
    }

    // Search debounce
    private val searchHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityExtintoresBinding.inflate(layoutInflater)
        setContentView(b.root)

        repo = ExtintoresRepository(this)

        setupClienteView()
        setupEstablecimientoView()
        setupControlView()
        setupCameraView()

        // Check if launched from Relevamiento with pre-selected client/establishment
        val extraClienteId = intent.getIntExtra("clienteId", -1)
        val extraEstId = intent.getIntExtra("establecimientoId", -1)
        val extraClienteName = intent.getStringExtra("clienteName")
        val extraEstName = intent.getStringExtra("estName")

        if (extraClienteId > 0 && extraEstId > 0) {
            // Pre-select client and establishment, skip to control view
            selectedCliente = ClienteItem(
                itemId = extraClienteId,
                razonSocial = extraClienteName,
                nombreCorto = null, cuit = null, localidad = null,
                provincia = null, activo = null
            )
            selectedEstablecimiento = EstablecimientoItem(
                itemId = extraEstId,
                denominacion = extraEstName,
                nroSucursal = null, domicilio = null, localidad = null,
                estado = null
            )
            controlCount = 0
            updateControlInfo()
            b.viewFlipper.displayedChild = 2
        } else {
            // Normal flow: start on view 0 (select cliente)
            b.viewFlipper.displayedChild = 0
            loadClientes("")
        }
    }

    // ===================== VIEW 0: SELECT CLIENTE =====================

    private fun setupClienteView() {
        b.recyclerClientes.layoutManager = LinearLayoutManager(this)

        b.searchCliente.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                loadClientes(b.searchCliente.text.toString().trim())
                true
            } else false
        }

        // Debounced search on text change
        b.searchCliente.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                searchHandler.removeCallbacksAndMessages(null)
                searchHandler.postDelayed({
                    loadClientes(s?.toString()?.trim() ?: "")
                }, 400)
            }
        })
    }

    private fun loadClientes(query: String) {
        if (!repo.isOnline()) {
            b.progressClientes.visibility = View.GONE
            b.emptyClientes.visibility = View.VISIBLE
            b.emptyClientes.text = "Sin conexión a internet"
            b.recyclerClientes.adapter = null
            return
        }

        b.progressClientes.visibility = View.VISIBLE
        b.emptyClientes.visibility = View.GONE

        lifecycleScope.launch {
            val res = repo.getClientes(query = query.ifBlank { null })
            b.progressClientes.visibility = View.GONE

            if (!res.ok || res.records.isNullOrEmpty()) {
                b.emptyClientes.visibility = View.VISIBLE
                b.emptyClientes.text = res.error ?: "No se encontraron clientes"
                b.recyclerClientes.adapter = null
                return@launch
            }

            b.recyclerClientes.adapter = ClienteAdapter(res.records) { cliente ->
                selectedCliente = cliente
                b.selectedClienteName.text = cliente.razonSocial ?: "Sin nombre"
                b.searchEstablecimiento.text?.clear()
                b.viewFlipper.displayedChild = 1
                loadEstablecimientos(cliente.itemId, "")
            }
        }
    }

    // ===================== VIEW 1: SELECT ESTABLECIMIENTO =====================

    private fun setupEstablecimientoView() {
        b.recyclerEstablecimientos.layoutManager = LinearLayoutManager(this)
        b.btnBackCliente.setOnClickListener {
            b.viewFlipper.displayedChild = 0
        }

        b.searchEstablecimiento.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                searchHandler.removeCallbacksAndMessages(null)
                searchHandler.postDelayed({
                    val cliente = selectedCliente ?: return@postDelayed
                    loadEstablecimientos(cliente.itemId, s?.toString()?.trim() ?: "")
                }, 400)
            }
        })

        b.searchEstablecimiento.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val cliente = selectedCliente ?: return@setOnEditorActionListener false
                loadEstablecimientos(cliente.itemId, b.searchEstablecimiento.text.toString().trim())
                true
            } else false
        }
    }

    private fun loadEstablecimientos(clienteId: Int, query: String) {
        if (!repo.isOnline()) {
            b.progressEst.visibility = View.GONE
            b.emptyEst.visibility = View.VISIBLE
            b.emptyEst.text = "Sin conexión a internet"
            b.recyclerEstablecimientos.adapter = null
            return
        }

        b.progressEst.visibility = View.VISIBLE
        b.emptyEst.visibility = View.GONE

        lifecycleScope.launch {
            val res = repo.getEstablecimientos(clienteId, query = query.ifBlank { null })
            b.progressEst.visibility = View.GONE

            if (!res.ok || res.records.isNullOrEmpty()) {
                b.emptyEst.visibility = View.VISIBLE
                b.emptyEst.text = res.error ?: "Sin establecimientos para este cliente"
                b.recyclerEstablecimientos.adapter = null
                return@launch
            }

            b.recyclerEstablecimientos.adapter = EstablecimientoAdapter(res.records) { est ->
                selectedEstablecimiento = est
                controlCount = 0
                updateControlInfo()
                b.viewFlipper.displayedChild = 2
            }
        }
    }

    // ===================== VIEW 2: CONTROL MODE =====================

    private fun setupControlView() {
        b.btnBackEst.setOnClickListener {
            b.viewFlipper.displayedChild = 1
        }

        b.btnScanQR.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
                b.viewFlipper.displayedChild = 3
                startCamera()
            } else {
                cameraPermLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        b.btnManual.setOnClickListener {
            showManualEntryDialog()
        }

        b.btnFinalizar.setOnClickListener {
            finish()
        }
    }

    private fun updateControlInfo() {
        b.controlClienteInfo.text = selectedCliente?.razonSocial ?: ""
        b.controlEstInfo.text = selectedEstablecimiento?.denominacion ?: ""
        b.controlCount.text = "$controlCount controles realizados"

        lifecycleScope.launch {
            val pending = repo.contarPendientes()
            if (pending > 0) {
                b.pendingBadge.visibility = View.VISIBLE
                b.pendingBadge.text = "\u23F3 $pending pendientes de sincronizar"
            } else {
                b.pendingBadge.visibility = View.GONE
            }
        }
    }

    // ===================== VIEW 3: QR CAMERA =====================

    private fun setupCameraView() {
        b.btnCancelScan.setOnClickListener {
            stopCamera()
            b.viewFlipper.displayedChild = 2
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(b.cameraPreview.surfaceProvider)
            }

            val analyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analyzer.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
                processImage(imageProxy)
            }

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analyzer
                )
            } catch (e: Exception) {
                Log.e("ExtCam", "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        cameraProvider?.unbindAll()
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun processImage(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        barcodeScanner.process(image)
            .addOnSuccessListener { barcodes ->
                for (barcode in barcodes) {
                    val rawValue = barcode.rawValue ?: continue
                    Log.d("ExtQR", "QR detectado: $rawValue (type=${barcode.valueType})")
                    if (isValidExtintorQR(rawValue)) {
                        handleQrDetected(rawValue)
                        break
                    } else {
                        runOnUiThread {
                            b.scanStatus.text = "QR no v\u00e1lido (no es extintor AGC)"
                        }
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e("ExtQR", "ML Kit error: ${e.message}")
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun isValidExtintorQR(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("agcontrol.gob.ar/matafuegos") ||
                lower.contains("dghpsh.agcontrol.gob.ar/matafuegos") ||
                lower.contains("datosestampilla.jsp")
    }

    private fun handleQrDetected(rawUrl: String) {
        val now = System.currentTimeMillis()
        // Debounce: ignorar mismo QR dentro de 3 segundos
        if (rawUrl == lastQrUrl && now - lastQrTime < 3000) return

        lastQrUrl = rawUrl
        lastQrTime = now

        // Normalize URL
        var url = rawUrl.replace(Regex("[\\x00-\\x1f\\x7f]"), "")
        if (url.startsWith("http://")) url = url.replaceFirst("http://", "https://")

        // Verificar duplicado en esta sesión
        if (controlledQrUrls.contains(url)) {
            runOnUiThread {
                showMsg("Este extintor ya fue controlado en esta sesión")
                b.scanStatus.text = "QR duplicado - ya controlado"
            }
            return
        }

        // Vibrate usando VibrationEffect para API 26+
        val vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
        vibrator?.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))

        // Stop camera and fetch AGC data
        stopCamera()
        b.scanStatus.text = "QR detectado, consultando datos..."

        // Fetch extintor info from AGC via backend
        lifecycleScope.launch {
            val lookup = repo.agcLookup(url)
            showControlFormDialog(
                urlQr = url,
                nroExtintor = lookup.data?.nroExtintor,
                modo = "qr",
                agcData = lookup.data
            )
        }
    }

    // ===================== CONTROL FORM DIALOG =====================

    private fun showControlFormDialog(
        urlQr: String?,
        nroExtintor: String?,
        modo: String,
        agcData: AgcExtintorData? = null
    ) {
        val view = layoutInflater.inflate(R.layout.dialog_control_form, null)
        val container = view.findViewById<LinearLayout>(R.id.formContainer)

        // If we have AGC data, show info card at top
        if (agcData != null) {
            val infoCard = buildAgcInfoView(agcData)
            container.addView(infoCard, 0)
        }

        val spEstado = view.findViewById<Spinner>(R.id.spEstadoCarga)
        val spChapa = view.findViewById<Spinner>(R.id.spChapaBaliza)
        val etOtraChapa = view.findViewById<EditText>(R.id.etOtraChapa)
        val etComentario = view.findViewById<EditText>(R.id.etComentario)

        // Estado de carga spinner
        val estados = arrayOf("Cargado", "Descargado", "Sobrecargado")
        spEstado.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, estados)

        // Chapa/baliza spinner
        val chapas = arrayOf("A", "ABC", "BC", "No tiene", "Otra")
        spChapa.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, chapas)
        spChapa.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                etOtraChapa.visibility = if (chapas[pos] == "Otra") View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val title = if (agcData?.nroExtintor != null) {
            "Control QR \u2014 Ext. ${agcData.nroExtintor}"
        } else if (modo == "manual") "Control Manual" else "Control por QR"

        val dialog = AlertDialog.Builder(this, R.style.Theme_HSTApp)
            .setTitle(title)
            .setView(view)
            .setPositiveButton("Guardar", null)
            .setNegativeButton("Cancelar") { d, _ ->
                d.dismiss()
                if (modo == "qr") b.viewFlipper.displayedChild = 2
            }
            .setCancelable(false)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val estadoCarga = estados[spEstado.selectedItemPosition]
                var chapaBaliza = chapas[spChapa.selectedItemPosition]
                if (chapaBaliza == "Otra") {
                    chapaBaliza = etOtraChapa.text.toString().trim()
                    if (chapaBaliza.isBlank()) {
                        etOtraChapa.error = "Especifique"
                        return@setOnClickListener
                    }
                }
                val comentario = etComentario.text.toString().trim().ifBlank { null }

                dialog.dismiss()
                submitControl(
                    urlQr = urlQr,
                    nroExtintor = nroExtintor,
                    estadoCarga = estadoCarga,
                    chapaBaliza = chapaBaliza,
                    comentario = comentario,
                    agente = agcData?.agenteExtintor,
                    capacidad = agcData?.capacidad,
                    vencMantenimiento = agcData?.vencMantenimiento,
                    vencVidaUtil = agcData?.vencVidaUtil,
                    vencPh = agcData?.vencPh,
                    modo = modo
                )
            }
        }

        dialog.show()
    }

    private fun buildAgcInfoView(data: AgcExtintorData): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1A2332.toInt())
            setPadding(dp(14), dp(12), dp(14), dp(12))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.bottomMargin = dp(16)
            layoutParams = params
        }

        val titleTv = TextView(this).apply {
            text = "Datos del extintor (AGC)"
            setTextColor(0xFF3B82F6.toInt())
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        }
        card.addView(titleTv)

        val fields = listOfNotNull(
            data.nroExtintor?.let { "Nro. Extintor" to it },
            data.agenteExtintor?.let { "Agente" to it },
            data.capacidad?.let { "Capacidad" to it },
            data.fabricante?.let { "Fabricante" to it },
            data.recargadora?.let { "Recargadora" to it },
            data.fechaFabricacion?.let { "Fabricaci\u00f3n" to it },
            data.fechaMantenimiento?.let { "Mantenimiento" to it },
            data.vencMantenimiento?.let { "Venc. Mant." to it },
            data.vencVidaUtil?.let { "Venc. Vida \u00datil" to it },
            data.vencPh?.let { "Venc. PH" to it },
            data.domicilio?.let { "Domicilio" to it },
            data.uso?.let { "Uso" to it }
        )

        for ((label, value) in fields) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(2), 0, dp(2))
            }
            val labelTv = TextView(this).apply {
                text = "$label: "
                setTextColor(0xFF94A3B8.toInt())
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(dp(100), LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val valueTv = TextView(this).apply {
                text = value
                setTextColor(0xFFE2E8F0.toInt())
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(labelTv)
            row.addView(valueTv)
            card.addView(row)
        }

        if (fields.isEmpty()) {
            val noData = TextView(this).apply {
                text = "No se pudieron obtener datos del extintor"
                setTextColor(0xFFEF4444.toInt())
                textSize = 12f
            }
            card.addView(noData)
        }

        return card
    }

    // ===================== MANUAL ENTRY DIALOG =====================

    private fun showManualEntryDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_control_form, null)

        // Add Nro Extintor field at top
        val container = view.findViewById<LinearLayout>(R.id.formContainer)
        val etNro = EditText(this).apply {
            hint = "Nro. Extintor *"
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.text_muted))
            background = ContextCompat.getDrawable(context, R.drawable.input_bg)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            textSize = 14f
        }
        container.addView(etNro, 0)

        val spEstado = view.findViewById<Spinner>(R.id.spEstadoCarga)
        val spChapa = view.findViewById<Spinner>(R.id.spChapaBaliza)
        val etOtraChapa = view.findViewById<EditText>(R.id.etOtraChapa)
        val etComentario = view.findViewById<EditText>(R.id.etComentario)

        val estados = arrayOf("Cargado", "Descargado", "Sobrecargado")
        spEstado.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, estados)

        val chapas = arrayOf("A", "ABC", "BC", "No tiene", "Otra")
        spChapa.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, chapas)
        spChapa.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                etOtraChapa.visibility = if (chapas[pos] == "Otra") View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val dialog = AlertDialog.Builder(this, R.style.Theme_HSTApp)
            .setTitle("Carga Manual")
            .setView(view)
            .setPositiveButton("Guardar", null)
            .setNegativeButton("Cancelar", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val nro = etNro.text.toString().trim()
                if (nro.isBlank()) {
                    etNro.error = "Requerido"
                    return@setOnClickListener
                }
                val estadoCarga = estados[spEstado.selectedItemPosition]
                var chapaBaliza = chapas[spChapa.selectedItemPosition]
                if (chapaBaliza == "Otra") {
                    chapaBaliza = etOtraChapa.text.toString().trim()
                    if (chapaBaliza.isBlank()) {
                        etOtraChapa.error = "Especifique"
                        return@setOnClickListener
                    }
                }
                val comentario = etComentario.text.toString().trim().ifBlank { null }

                dialog.dismiss()
                submitControl(
                    urlQr = null,
                    nroExtintor = nro,
                    estadoCarga = estadoCarga,
                    chapaBaliza = chapaBaliza,
                    comentario = comentario,
                    modo = "manual"
                )
            }
        }

        dialog.show()
    }

    // ===================== SUBMIT CONTROL =====================

    private fun submitControl(
        urlQr: String?,
        nroExtintor: String?,
        estadoCarga: String,
        chapaBaliza: String,
        comentario: String?,
        agente: String? = null,
        capacidad: String? = null,
        vencMantenimiento: String? = null,
        vencVidaUtil: String? = null,
        vencPh: String? = null,
        modo: String
    ) {
        val cliente = selectedCliente
        val est = selectedEstablecimiento
        if (cliente == null || est == null) {
            showMsg("Error: seleccione cliente y establecimiento")
            Log.e("ExtCtrl", "submitControl sin cliente/est seleccionado")
            return
        }

        lifecycleScope.launch {
            val result = repo.enviarControl(
                clienteId = cliente.itemId,
                establecimientoId = est.itemId,
                estadoCarga = estadoCarga,
                chapaBaliza = chapaBaliza,
                urlQr = urlQr,
                nroExtintor = nroExtintor,
                comentario = comentario,
                agente = agente,
                capacidad = capacidad,
                vencMantenimiento = vencMantenimiento,
                vencVidaUtil = vencVidaUtil,
                vencPh = vencPh,
                modo = modo
            )

            controlCount++
            // Registrar QR como ya controlado para evitar duplicados
            if (urlQr != null) controlledQrUrls.add(urlQr)
            updateControlInfo()

            when (result) {
                is ExtintoresRepository.ControlResult.Enviado -> {
                    showSiguienteDialog("Control enviado correctamente (${result.totalControles} total)")
                }
                is ExtintoresRepository.ControlResult.GuardadoOffline -> {
                    showSiguienteDialog("Control guardado offline (se sincronizar\u00e1 luego)")
                }
                is ExtintoresRepository.ControlResult.Error -> {
                    showSiguienteDialog("Guardado offline. Error: ${result.mensaje}")
                }
            }
        }
    }

    // ===================== SIGUIENTE / FINALIZAR DIALOG =====================

    private fun showSiguienteDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("\u2705 Control registrado")
            .setMessage("$message\n\n\u00bfDesea controlar otro extintor?")
            .setPositiveButton("Siguiente") { _, _ ->
                b.viewFlipper.displayedChild = 2
            }
            .setNegativeButton("Finalizar") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    // ===================== UTILS =====================

    private fun showMsg(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    @Deprecated("Use OnBackPressedCallback")
    override fun onBackPressed() {
        when (b.viewFlipper.displayedChild) {
            3 -> { stopCamera(); b.viewFlipper.displayedChild = 2 }
            2 -> b.viewFlipper.displayedChild = 1
            1 -> b.viewFlipper.displayedChild = 0
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onDestroy() {
        searchHandler.removeCallbacksAndMessages(null)
        stopCamera()
        barcodeScanner.close()
        super.onDestroy()
    }
}

// ===================== ADAPTERS =====================

class ClienteAdapter(
    private val items: List<ClienteItem>,
    private val onClick: (ClienteItem) -> Unit
) : RecyclerView.Adapter<ClienteAdapter.VH>() {

    class VH(val b: ItemClienteRowBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemClienteRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.b.clienteRazonSocial.text = item.razonSocial ?: "Sin nombre"
        holder.b.clienteCuit.text = item.cuit ?: ""
        holder.b.clienteEstCount.text = "${item.estCount} est."
        val loc = listOfNotNull(item.localidad, item.provincia).joinToString(", ")
        holder.b.clienteLocalidad.text = loc
        holder.b.root.setOnClickListener { onClick(item) }
    }

    override fun getItemCount() = items.size
}

class EstablecimientoAdapter(
    private val items: List<EstablecimientoItem>,
    private val onClick: (EstablecimientoItem) -> Unit
) : RecyclerView.Adapter<EstablecimientoAdapter.VH>() {

    class VH(val b: ItemEstablecimientoRowBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemEstablecimientoRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.b.estDenominacion.text = item.denominacion ?: "Sin denominaci\u00f3n"
        val dir = listOfNotNull(item.domicilio, item.localidad).joinToString(", ")
        holder.b.estDomicilio.text = dir
        if (!item.nroSucursal.isNullOrBlank()) {
            holder.b.estSucursal.visibility = View.VISIBLE
            holder.b.estSucursal.text = "Sucursal: ${item.nroSucursal}"
        }
        holder.b.root.setOnClickListener { onClick(item) }
    }

    override fun getItemCount() = items.size
}
