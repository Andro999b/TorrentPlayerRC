package com.example.torrentplayerrc

import android.Manifest.permission
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView


const val SERVERS_KEY = "servers"
const val REQUEST_QR_CODE = 1

class MainActivity : AppCompatActivity() {
    private lateinit var preferences: SharedPreferences

    private val servers: MutableList<String> = arrayListOf()
    private val serverAddressAdapter =
        ServerAddressAdapter(servers, this::onServerDelete, this::onServerSelect)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preferences = getSharedPreferences("TorrentPlayerRC", Context.MODE_PRIVATE)
        servers.addAll(
            preferences.getString(SERVERS_KEY, "")
                .split(",")
                .filter { !it.isBlank() }
                .toMutableList()
        )

        val linearLayoutManager = LinearLayoutManager(this)

        findViewById<RecyclerView>(R.id.servers).apply {
            adapter = serverAddressAdapter
            layoutManager = linearLayoutManager
        }
    }

    private fun onServerDelete(pos: Int) {
        servers.removeAt(pos)
        serverAddressAdapter.notifyItemRangeRemoved(pos, 1)
    }

    private fun onServerSelect(pos: Int) {
        connectToServer(servers[pos])
    }

    private fun connectToServer(server: String) {
        val intent = Intent(this, ControlActivity::class.java)
        intent.putExtra("serverAddress", server)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
    }

    fun onQRScanner(view: View) {
        if (ContextCompat.checkSelfPermission(this, permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(permission.CAMERA), 1)
        } else {
            val intent = Intent(this, QRScannerActivity::class.java)
            startActivityForResult(intent, REQUEST_QR_CODE)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if(requestCode == REQUEST_QR_CODE && resultCode == Activity.RESULT_OK) {
            addServerAddress(data!!.getStringExtra("serverAddress"))
        }
    }

    fun onEnterServerAddress(view: View) {
        val body = layoutInflater.inflate(R.layout.enter_address_dialog, null)
        val serverAddress = body.findViewById<EditText>(R.id.server_address)

        AlertDialog.Builder(this)
            .setTitle(R.string.enter_address)
            .setView(body)
            .setPositiveButton(R.string.ok) { dialog, _ ->
                dialog.dismiss()
                addServerAddress(serverAddress.text.toString())
            }
            .setNegativeButton(R.string.cancel) { dialog, _ -> dialog.cancel() }
            .show()
    }

    private fun addServerAddress(text: String) {
        val url = if (!text.startsWith("http://") && !text.startsWith("https://")) {
            "http://$text"
        } else {
            text
        }

        if (Patterns.WEB_URL.matcher(url).matches()) {
            if(!servers.contains(url)) {
                servers.add(0, url)
            }
            connectToServer(url)
        } else {
            Toast.makeText(this, "Invalid url", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStop() {
        super.onStop()
        preferences
            .edit()
            .putString(SERVERS_KEY, servers.joinToString(","))
            .apply()
    }
}
