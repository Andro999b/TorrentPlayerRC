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
    lateinit var webView: WebView
    lateinit var jsCommandListener: String
    lateinit var controlServiceBinder: ControlService.ControlServiceBinder
    lateinit var serviceConnection: ServiceConnection

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

        val serverAddress = intent.extras.getString("serverAddress")

        findViewById<TextView>(R.id.connecting_text).text = getString(R.string.connecting, serverAddress)
        webView = findViewById(R.id.web_view)

        GlobalScope.launch(Dispatchers.IO) {
            try {
                HttpClient().use {client ->
                    val page = client.get<String>(serverAddress)
                    runOnUiThread {
                        findViewById<View>(R.id.connecting).visibility = View.GONE
                        initWebView(serverAddress, page)
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

    private fun initWebView(serverAddress: String?, page: String) {
        WebView.setWebContentsDebuggingEnabled(true)

        webView.settings.javaScriptEnabled = true
        webView.addJavascriptInterface(this, "mobileApp")
        webView.loadDataWithBaseURL(serverAddress, page, "text/html", "utf-8", null)
    }

    fun onCancelConnection(view: View) {
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
    }

    @JavascriptInterface
    fun connectToDevice(deviceId: String) {
        controlServiceBinder.getService().connectToDevice(deviceId)
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
