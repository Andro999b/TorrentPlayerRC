package com.example.torrentplayerrc

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.request.get
import kotlinx.coroutines.*
import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment.DIRECTORY_DOWNLOADS
import android.webkit.URLUtil
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat


class ControlActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var connectingText: TextView
    private lateinit var connectingContainer: View
    private lateinit var jsCommandListener: String
    private lateinit var controlServiceBinder: ControlService.ControlServiceBinder
    private lateinit var serviceConnection: ServiceConnection

    private var serverAddress: String? = null;
    private var loadPageJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_control)

        serviceConnection = object : ServiceConnection {
            override fun onServiceDisconnected(name: ComponentName?) {
                controlServiceBinder.jsCallback = null
            }

            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                controlServiceBinder = service as ControlService.ControlServiceBinder
                controlServiceBinder.jsCallback = this@ControlActivity::sendJSCommand
            }
        }

        startService(Intent(this, ControlService::class.java))
        bindService(Intent(this, ControlService::class.java), serviceConnection, BIND_AUTO_CREATE)

        connectingText = findViewById(R.id.connecting_text)
        connectingContainer = findViewById(R.id.connecting)

        WebView.setWebContentsDebuggingEnabled(true)

        webView = findViewById(R.id.web_view)
        webView.settings.javaScriptEnabled = true
        webView.addJavascriptInterface(this, "mobileApp")


        this.onNewIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)

        val serverAddress = intent?.extras?.getString("serverAddress")
        if(serverAddress == null) {
            cancelConnection()
        }

        if(this.serverAddress == serverAddress) return
        this.serverAddress = serverAddress

        connectingContainer.visibility = View.VISIBLE
        connectingText.text = getString(R.string.connecting, serverAddress)
        webView.loadUrl("about:blank")
        webView.visibility = View.INVISIBLE

        loadPageJob = GlobalScope.launch {
            try {
                HttpClient(Android) {
                    engine {
                        connectTimeout = 30_000
                        socketTimeout = 30_000
                    }
                } .use { client ->
                    val page = client.get<String>(serverAddress!!)
                    runOnUiThread {
                        connectingContainer.visibility = View.INVISIBLE

                        webView.loadDataWithBaseURL(serverAddress, page, "text/html", "utf-8", null)
                        webView.visibility = View.VISIBLE

                        controlServiceBinder.getService().startWebSocket(serverAddress)
                    }
                }
            } catch (ex: Exception) {
                if(isActive) {
                    runOnUiThread {
                        Toast.makeText(
                            this@ControlActivity,
                            R.string.fail_to_load_page,
                            Toast.LENGTH_LONG
                        ).show()
                        cancelConnection()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unbindService(serviceConnection)
    }

    override fun onBackPressed() {
        super.onBackPressed()
        cancelConnection()
    }

    fun onCancelConnection(view: View?) {
        cancelConnection()
    }

    private fun cancelConnection() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
        serverAddress = null
        loadPageJob?.cancel()
        loadPageJob = null
    }

    private fun sendJSCommand(command: String, data: Any?) {
        if(this::webView.isInitialized && this::jsCommandListener.isInitialized) {
            runOnUiThread {
                Log.i("sendJSCommand", "$command $data")
                webView.evaluateJavascript("$jsCommandListener('$command', $data)") {}
            }
        }
    }

    @JavascriptInterface
    fun setCommandListener(listener: String) {
        jsCommandListener = listener

        // sync current state with web app
        controlServiceBinder.getDevice()?.let {
            sendJSCommand("restoreDevice", it)
        }
        // sync current devices list with app
        controlServiceBinder.getDevicesList()?.let {
            sendJSCommand("devicesList", it)
        }
    }

    @JavascriptInterface
    fun connectToDevice(device: String) {
        controlServiceBinder.getService().connectToDevice(device)
    }

    @JavascriptInterface
    fun sendDeviceAction(payload: String) {
        controlServiceBinder.getService().sendDeviceAction(payload)
    }

    @JavascriptInterface
    fun disconnectDevice(){
        controlServiceBinder.getService().disconnectDevice()
    }

    @JavascriptInterface
    fun downloadFile(url: String, name: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        } else {
            val request = DownloadManager.Request(Uri.parse(url))

            request.allowScanningByMediaScanner()
            //Notify client once download is completed!
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setDestinationInExternalPublicDir(DIRECTORY_DOWNLOADS, name)

            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)

            //To notify the Client that the file is being downloaded
            Toast.makeText(
                applicationContext, "Downloading $name",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
