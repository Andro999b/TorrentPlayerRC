package com.example.torrentplayerrc

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.github.nkzawa.socketio.client.IO
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.json.JSONObject


class ControlActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var connectingText: TextView
    private lateinit var connectingContainer: View
    private lateinit var jsCommandListener: String
    private lateinit var controlServiceBinder: ControlService.ControlServiceBinder
    private lateinit var serviceConnection: ServiceConnection

    var serverAddress: String? = null;

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

        bindService(Intent(this, ControlService::class.java), serviceConnection, BIND_AUTO_CREATE)

        connectingText = findViewById(R.id.connecting_text)
        connectingContainer = findViewById<View>(R.id.connecting)

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

        GlobalScope.launch(Dispatchers.IO) {
            try {
                HttpClient().use {client ->
                    val page = client.get<String>(serverAddress!!)
                    runOnUiThread {
                        connectingContainer.visibility = View.INVISIBLE

                        webView.loadDataWithBaseURL(serverAddress, page, "text/html", "utf-8", null)
                        webView.visibility = View.VISIBLE

                        controlServiceBinder.getService().startWebSocket(serverAddress)
                    }
                }
            } catch (ex: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this@ControlActivity,
                        R.string.fail_to_load_page,
                        Toast.LENGTH_LONG
                    ).show()
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

    fun cancelConnection() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
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
        val lastDevice = controlServiceBinder.getDevice()
        if(lastDevice != null) {
            sendJSCommand("restoreDevice", lastDevice)
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
}
