package com.example.torrentplayerrc

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.hardware.Camera
import android.hardware.Camera.CameraInfo.CAMERA_FACING_BACK
import android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT
import android.hardware.Camera.getNumberOfCameras
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.zxing.BarcodeFormat
import com.google.zxing.Result
import me.dm7.barcodescanner.zxing.ZXingScannerView


private const val FLASH_STATE = "FLASH_STATE"
private const val AUTO_FOCUS_STATE = "AUTO_FOCUS_STATE"
private const val CAMERA_ID = "CAMERA_ID"

class QRScannerActivity: AppCompatActivity(), ZXingScannerView.ResultHandler {

    private lateinit var scannerView: ZXingScannerView
    private var flash = false
    private var autoFocus = true
    private var cameraId = -1

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if(state != null) {
            flash = state.getBoolean(FLASH_STATE, false)
            autoFocus = state.getBoolean(AUTO_FOCUS_STATE, true)
            cameraId = state.getInt(CAMERA_ID, -1)
        }

        setContentView(R.layout.qr_scanner)
        setupToolbar()

        val contentFrame = findViewById<ViewGroup>(R.id.content_frame)
        scannerView = ZXingScannerView(this)
        scannerView.setFormats(listOf(BarcodeFormat.QR_CODE))

        contentFrame.addView(scannerView)
    }

    private fun setupToolbar() {
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.qr_scanner_menu, menu)

        menu?.getItem(1).let {
            it?.title = if(flash) {
                getString(R.string.flash_on)
            } else {
                getString(R.string.flash_off)
            }
        }

        menu?.getItem(2).let {
            it?.title = if(autoFocus) {
                getString(R.string.auto_focus_on)
            } else {
                getString(R.string.auto_focus_off)
            }
        }

        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.home -> {
                finish()
                return true
            }
            R.id.switch_flash -> {
                flash = !flash
                if(flash) {
                    item.setTitle(R.string.flash_on)
                } else {
                    item.setTitle(R.string.flash_off)
                }
                scannerView.flash = flash
                return true
            }
            R.id.switch_auto_focus -> {
                autoFocus = !autoFocus
                if(flash) {
                    item.setTitle(R.string.auto_focus_on)
                } else {
                    item.setTitle(R.string.auto_focus_off)
                }
                scannerView.setAutoFocus(autoFocus)
                return true
            }
            R.id.select_camera -> {
                showSelectCameraDialog()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showSelectCameraDialog() {
        val numberOfCameras = getNumberOfCameras()
        val cameraNames = arrayOfNulls<String>(numberOfCameras)
        var checkedIndex = 0

        for (i in 0 until numberOfCameras) {
            val info = Camera.CameraInfo()
            Camera.getCameraInfo(i, info)
            if (info.facing == CAMERA_FACING_FRONT) {
                cameraNames[i] = "Front Facing"
            } else if (info.facing == CAMERA_FACING_BACK) {
                cameraNames[i] = "Rear Facing"
            } else {
                cameraNames[i] = "Camera ID: $i"
            }
            if (i == cameraId) {
                checkedIndex = i
            }
        }

        var targetCamera = cameraId

        AlertDialog.Builder(this)
            .setTitle(R.string.select_camera)
            .setSingleChoiceItems(cameraNames, checkedIndex) { _, which ->
                targetCamera = which
            }
            .setPositiveButton(R.string.ok) { dialog, _ ->
                dialog.dismiss()
                selectCamera(targetCamera)
            }
            .setNegativeButton(R.string.cancel) { dialog, _ -> dialog.cancel() }
            .show()

    }

    private fun selectCamera(targetCamera: Int) {
        cameraId = targetCamera
        scannerView.stopCamera()
        scannerView.startCamera(targetCamera)
        scannerView.flash = flash
        scannerView.setAutoFocus(autoFocus)
    }

    public override fun onResume() {
        super.onResume()
        scannerView.setResultHandler(this)
        scannerView.startCamera(cameraId)
        scannerView.flash = flash
        scannerView.setAutoFocus(autoFocus)
    }

    public override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(FLASH_STATE, flash)
        outState.putBoolean(AUTO_FOCUS_STATE, autoFocus)
        outState.putInt(CAMERA_ID, cameraId)
    }

    public override fun onPause() {
        super.onPause()
        scannerView.stopCamera()
    }

    override fun handleResult(rawResult: Result?) {
        val intent = Intent()
        intent.putExtra("serverAddress",  rawResult?.text)
        setResult(Activity.RESULT_OK, intent)
        finish()
    }
}