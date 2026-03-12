package ar.com.hst.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.webkit.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import ar.com.hst.app.databinding.ActivityToolWebviewBinding
import ar.com.hst.app.extintores.ExtintoresActivity
import java.io.File

class ToolWebViewActivity : AppCompatActivity() {

    // JavaScript bridge for WebView ↔ Native communication
    inner class HSTBridge {
        @android.webkit.JavascriptInterface
        fun openExtintores(clienteId: Int, establecimientoId: Int, clienteName: String, estName: String) {
            runOnUiThread {
                val intent = Intent(this@ToolWebViewActivity, ExtintoresActivity::class.java).apply {
                    putExtra("clienteId", clienteId)
                    putExtra("establecimientoId", establecimientoId)
                    putExtra("clienteName", clienteName)
                    putExtra("estName", estName)
                }
                startActivity(intent)
            }
        }

        @android.webkit.JavascriptInterface
        fun isApp(): Boolean = true
    }

    private lateinit var b: ActivityToolWebviewBinding
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var cameraPhotoUri: Uri? = null

    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>
    private lateinit var cameraPermLauncher: ActivityResultLauncher<String>
    private lateinit var audioPermLauncher: ActivityResultLauncher<String>
    private var pendingFileChooserParams: WebChromeClient.FileChooserParams? = null
    private var pendingPermissionRequest: PermissionRequest? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityToolWebviewBinding.inflate(layoutInflater)
        setContentView(b.root)

        val url = intent.getStringExtra("url") ?: return finish()

        fileChooserLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val cb = fileUploadCallback ?: return@registerForActivityResult
            if (result.resultCode == RESULT_OK) {
                val dataUri = result.data?.data
                val uris = when {
                    dataUri != null -> arrayOf(dataUri)
                    cameraPhotoUri != null -> arrayOf(cameraPhotoUri!!)
                    else -> arrayOf()
                }
                cb.onReceiveValue(uris)
            } else {
                cb.onReceiveValue(arrayOf())
            }
            fileUploadCallback = null
            cameraPhotoUri = null
        }

        cameraPermLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                launchFileChooser(pendingFileChooserParams)
            } else {
                launchFileChooser(pendingFileChooserParams, cameraAllowed = false)
            }
            pendingFileChooserParams = null
        }

        audioPermLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            val req = pendingPermissionRequest ?: return@registerForActivityResult
            if (granted) {
                req.grant(req.resources)
            } else {
                req.deny()
            }
            pendingPermissionRequest = null
        }

        b.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            mediaPlaybackRequiresUserGesture = false
        }

        CookieManager.getInstance().setAcceptThirdPartyCookies(b.webView, true)

        b.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val reqUrl = request.url.toString()
                return if (reqUrl.contains("hst.ar")) {
                    false
                } else if (reqUrl.startsWith("mailto:")) {
                    try {
                        val intent = Intent(Intent.ACTION_SENDTO, request.url)
                        startActivity(intent)
                    } catch (_: Exception) {}
                    true
                } else {
                    false
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                // Only detect asociados session expiry, not extintores/other login pages
                if (url.contains("/asociados/login.php")) {
                    SessionManager(this@ToolWebViewActivity).logout()
                    startActivity(Intent(this@ToolWebViewActivity, LoginActivity::class.java))
                    finish()
                }
            }
        }

        b.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, progress: Int) {
                if (progress < 100) {
                    b.webProgress.visibility = View.VISIBLE
                    b.webProgress.progress = progress
                } else {
                    b.webProgress.visibility = View.GONE
                }
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                val resources = request.resources
                val needsAudio = resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)

                if (needsAudio) {
                    if (ContextCompat.checkSelfPermission(
                            this@ToolWebViewActivity, Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        request.grant(resources)
                    } else {
                        pendingPermissionRequest = request
                        audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                } else {
                    request.grant(resources)
                }
            }

            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileUploadCallback?.onReceiveValue(arrayOf())
                fileUploadCallback = callback

                val accept = params.acceptTypes?.firstOrNull() ?: "*/*"
                val needsCamera = accept.startsWith("image/")

                if (needsCamera && ContextCompat.checkSelfPermission(
                        this@ToolWebViewActivity, Manifest.permission.CAMERA
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    pendingFileChooserParams = params
                    cameraPermLauncher.launch(Manifest.permission.CAMERA)
                    return true
                }

                launchFileChooser(params, cameraAllowed = needsCamera)
                return true
            }
        }

        b.webView.setDownloadListener { downloadUrl, _, contentDisposition, mimeType, _ ->
            try {
                val request = android.app.DownloadManager.Request(Uri.parse(downloadUrl))
                request.setMimeType(mimeType)
                val fileName = URLUtil.guessFileName(downloadUrl, contentDisposition, mimeType)
                request.setTitle(fileName)
                request.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                request.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, fileName)
                val dm = getSystemService(DOWNLOAD_SERVICE) as android.app.DownloadManager
                dm.enqueue(request)
                android.widget.Toast.makeText(this, "Descargando: $fileName", android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(this, "Error al descargar", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        b.webView.addJavascriptInterface(HSTBridge(), "HSTApp")
        b.webView.loadUrl(url)
    }

    private fun launchFileChooser(
        params: WebChromeClient.FileChooserParams?,
        cameraAllowed: Boolean = true
    ) {
        val intents = mutableListOf<Intent>()

        if (cameraAllowed) {
            try {
                val photoFile = File(cacheDir, "camera_${System.currentTimeMillis()}.jpg")
                cameraPhotoUri = FileProvider.getUriForFile(
                    this, "${packageName}.fileprovider", photoFile
                )
                val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    putExtra(MediaStore.EXTRA_OUTPUT, cameraPhotoUri)
                }
                if (cameraIntent.resolveActivity(packageManager) != null) {
                    intents.add(cameraIntent)
                }
            } catch (_: Exception) {
                cameraPhotoUri = null
            }
        }

        val accept = params?.acceptTypes?.firstOrNull() ?: "*/*"
        val galleryIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (accept.isNotEmpty() && accept != "*/*") accept else "image/*"
        }

        val chooser = Intent.createChooser(galleryIntent, "Seleccionar imagen")
        if (intents.isNotEmpty()) {
            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, intents.toTypedArray())
        }

        fileChooserLauncher.launch(chooser)
    }

    @Deprecated("Use OnBackPressedCallback")
    override fun onBackPressed() {
        if (b.webView.canGoBack()) {
            b.webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }
}
