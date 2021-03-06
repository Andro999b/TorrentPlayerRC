package com.example.torrentplayerrc

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import io.ktor.client.HttpClient
import io.ktor.client.call.call
import io.ktor.client.engine.android.Android
import io.ktor.client.response.readBytes
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.lang.Exception


const val NOTIFICATION_CHANNEL_ID = "TorrentPlayerRC"
const val NOTIFICATION_ID = 1

class ControlService: Service() {
    private var rootSocket: Socket? = null
    private var serverAddress: String? = null

    private lateinit var mediaSession: MediaSession
    private val playbackStateBuilder = PlaybackState.Builder().setActions(
         PlaybackState.ACTION_PLAY
         or PlaybackState.ACTION_PAUSE
         or PlaybackState.ACTION_SKIP_TO_NEXT
         or PlaybackState.ACTION_SKIP_TO_PREVIOUS
         or PlaybackState.ACTION_FAST_FORWARD
         or PlaybackState.ACTION_REWIND
         or PlaybackState.ACTION_STOP
    )

    private var deviceSocket: Socket? = null
    private var lastDevice: JSONObject? = null

    @Volatile
    private var lastDeviceId: String? = null
    private var lastDeviceState: JSONObject? = null
    private var lastDevicesList: JSONArray? = null
    private var lastImageUrl: String? = null
    private var lastImage: Bitmap? = null
    private var loadImageJob: Job? = null

    override fun onCreate() {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            notificationManager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {

            val notificationChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Torrent Player RC",
                NotificationManager.IMPORTANCE_LOW
            )

            notificationManager.createNotificationChannel(notificationChannel)
        }

        mediaSession = MediaSession(applicationContext, "TorrentPlayerRC")
        // setup media controls callback
        mediaSession.setCallback(object: MediaSession.Callback() {
            override fun onPause() {
                createAndSendDeviceAction("pause")
            }

            override fun onPlay() {
                createAndSendDeviceAction("resume")
            }

            override fun onSkipToPrevious() {
                offsetPlaylist(-1)
            }

            override fun onSkipToNext() {
                offsetPlaylist(1)
            }

            override fun onFastForward() {
                skip(30.0)
            }

            override fun onRewind() {
                skip(-30.0)
            }

            override fun onStop() { // close device on stop
                disconnectDevice()
                sendJSCommand("deviceClosed", null)
            }
        })

        applicationContext.registerReceiver(mediaKeysReceiver, IntentFilter(Intent.ACTION_MEDIA_BUTTON))
    }

    override fun onDestroy() {
        clean()
        mediaSession.release()
        applicationContext.unregisterReceiver(mediaKeysReceiver)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    // go to next or previous file in a playlist
    private fun offsetPlaylist(offset: Int) {
        lastDeviceState?.run {
            if(has("currentFileIndex")) {
                val currentFileIndex = getInt("currentFileIndex")
                createAndSendDeviceAction("selectFile", currentFileIndex + offset)
            }
        }
    }

    private fun skip(offset: Double) {
        lastDeviceState?.run {
            if(has("currentTime") && has("duration")) {
                createAndSendDeviceAction("skip", offset)
            }
        }
    }

    fun startWebSocket(serverAddress: String) {
        if(this.serverAddress == serverAddress) return

        clean()

        this.serverAddress = serverAddress
        rootSocket = IO.socket(serverAddress).apply {
            bindServerEvent(this, "devicesList") { lastDevicesList = it as JSONArray }
            connect()
            on("disconnect") {
                val reason = it[0] as String
                when {
                    reason.contains("server") -> stopActivityAndClean()
                    reason.contains("transport") -> stopActivityAndClean()
                }
                Log.i("socket","disconnect: $reason")
            }
            on("reconnect_failed") {
                stopActivityAndClean()
                Log.i("socket","reconnect_failed")
            }
        }
    }

    fun connectToDevice(deviceStr: String) {
        val device = JSONObject(deviceStr)
        val deviceId = device.getString("id")

        if(lastDeviceId == deviceId) { // if we already connected to this device just pass last state to ui
            sendJSCommand("deviceConnected", lastDeviceState)
            return
        }

        lastDeviceId = deviceId
        lastDevice = device

        deviceSocket?.close()
        deviceSocket = IO.socket("$serverAddress/control").apply {
            connect()
            emit("connectDevice", deviceId)
            bindServerEvent(this, "deviceConnected") { updatePlayback(it as JSONObject) }
            bindServerEvent( this, "sync") { updatePlayback(it as JSONObject) }
            bindServerEvent(this, "deviceDisconnected")
            on("reconnect") { emit("connectDevice", deviceId) }

        }
    }

    fun sendDeviceAction(payload: String) {
        deviceSocket?.run {
            Log.i("sendDeviceAction", payload)
            emit("action", JSONObject(payload))
        }
    }

    private fun createAndSendDeviceAction(action: String, payload: Any? = null) {
        deviceSocket?.run {
            Log.i("sendDeviceAction", "action: $action")
            emit("action", JSONObject(mapOf("action" to action, "payload" to payload)))
        }
    }

    private fun stopActivityAndClean() {
        //stop activity
        val activityIntent = Intent(applicationContext, ControlActivity::class.java)
        val bundle = Bundle()
        bundle.putString("serverAddress", null)
        activityIntent.putExtras(bundle)
        activityIntent.flags = FLAG_ACTIVITY_NEW_TASK
        applicationContext.startActivity(activityIntent)
        //clean state
        clean()
    }

    private fun clean() {
        rootSocket?.close()
        serverAddress = null
        disconnectDevice()
    }

    fun disconnectDevice() {
        Log.i("disconnectDevice", "closing socket")
        deviceSocket?.close()
        lastDevice = null
        lastDeviceId = null
        lastDeviceState = null
        lastDevicesList = null
        mediaSession.isActive = true
        loadImageJob?.cancel()
        loadImageJob = null
        stopForeground(true)
    }

    private fun bindServerEvent(socket: Socket, eventName: String, cb:((payload: Any?) -> Unit)? = null) {
        socket.on(eventName) {
            if(it.isNotEmpty())
                sendJSCommand(eventName, it[0])
            else
                sendJSCommand(eventName, null)

            cb?.invoke(it[0])
        }
    }

    private fun sendJSCommand(command: String, data: Any?) {
        binder.jsCallback?.invoke(command, data)
    }

    private fun updatePlayback(newState: JSONObject) {
        // check if state change enough to update notification
        val shouldCreateNewNotification =
            lastDeviceState?.let {
                isPropChanged("currentFileIndex", it, newState) || isPropChanged("isPlaying", it, newState)
            } ?: true

        // update current state
        if(lastDeviceState == null){
            lastDeviceState = newState
        } else {
            newState.keys().forEach { key: Any? -> // Why type info lost? Shame on you kotlin. Shame!!!
                lastDeviceState!!.put(key as String, newState[key])
            }
        }

        if(shouldCreateNewNotification)
            createPlaybackNotification(lastDeviceState!!)
    }

    private fun isPropChanged(propName: String, oldState: JSONObject, newState: JSONObject): Boolean {
        val newValue = newState.opt(propName) ?: return false
        val oldValue = oldState.opt(propName)
        return newValue != oldValue
    }

    private fun createPlaybackNotification(deviceState: JSONObject) {
        if(!deviceState.has("playlist")) return

        // extract params from state
        val isPlaying = deviceState.getBoolean("isPlaying")
        val currentFileIndex = deviceState.getInt("currentFileIndex")
        val playlist = deviceState.getJSONObject("playlist")
        val files = playlist.getJSONArray("files")
        val file = files.getJSONObject(currentFileIndex)

        val imageUrl: String? = playlist.optString("image")
        val artist = playlist.getString("name")
        val track =
            if(files.length() == 1)
                file.getString("name")
            else
                "${currentFileIndex + 1} / ${files.length()}"

        if(lastImageUrl != imageUrl) {
            lastImageUrl = imageUrl
            lastImage = null
            loadImageJob?.cancel()
            // load image in separated thread
            loadImageJob = GlobalScope.launch {
                // load video poster
               lastImage = imageUrl?.let {
                    try {
                        HttpClient(Android) {
                            engine {
                                connectTimeout = 30_000
                                socketTimeout = 30_000
                            }
                        }.use { client ->
                            val imageBytes = client.call(imageUrl).response.readBytes()
                            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                        }
                    } catch (ex: Exception) {
                        Log.e("Fail to load poster", ex.message)
                        null
                    }
               }

                // check if image still need(during image loading, user may start watch other playlist)
                if (lastDeviceId != null && isActive)
                    showNotification(isPlaying, artist, track, lastImage, currentFileIndex, files)
            }
        }

        showNotification(isPlaying, artist, track, lastImage, currentFileIndex, files)
    }

    private fun showNotification(
        isPlaying: Boolean,
        artist: String?,
        track: String?,
        image: Bitmap?,
        currentFileIndex: Int,
        files: JSONArray
    ) {
        val notification = createNotification(isPlaying, artist, track, image, currentFileIndex, files)

        // setup media session
        mediaSession.isActive = true
        mediaSession.setPlaybackState(
            playbackStateBuilder.setState(
                if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                1f
            )
                .build()
        )

        // add metadata
        val metadata = MediaMetadata.Builder()
            .putBitmap(MediaMetadata.METADATA_KEY_ART, image)
            .putString(MediaMetadata.METADATA_KEY_TITLE, track)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, artist)
            .build()

        mediaSession.setMetadata(metadata)

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotification(
        isPlaying: Boolean,
        artist: String?,
        track: String?,
        image: Bitmap?,
        currentFileIndex: Int,
        files: JSONArray
    ): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
        } else {
            Notification.Builder(applicationContext)
        }

        val playbackIcon = if (isPlaying)
            android.R.drawable.ic_media_play
        else
            android.R.drawable.ic_media_pause

        builder.setContentTitle(artist)
        builder.setContentText(track)
        builder.setOngoing(true)
        builder.setVisibility(Notification.VISIBILITY_PUBLIC)
        builder.setSmallIcon(playbackIcon)
        builder.setLargeIcon(image)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setColorized(true)
        }

        // actions
        val activityIntent = Intent(applicationContext, ControlActivity::class.java)
        activityIntent.putExtra("serverAddress", serverAddress)
        builder.setContentIntent(PendingIntent.getActivity(applicationContext, 0, activityIntent, PendingIntent.FLAG_UPDATE_CURRENT))

        var startIndex = 0
        if (currentFileIndex != 0) {
            startIndex = 1
            builder.addAction(createMediaAction(android.R.drawable.ic_media_previous, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        }

        builder.addAction(createMediaAction(android.R.drawable.ic_media_rew, KeyEvent.KEYCODE_MEDIA_REWIND))

        if (isPlaying) {
            builder.addAction(createMediaAction(android.R.drawable.ic_media_pause, KeyEvent.KEYCODE_MEDIA_PAUSE))
        } else {
            builder.addAction(createMediaAction(android.R.drawable.ic_media_play, KeyEvent.KEYCODE_MEDIA_PLAY))
        }

        builder.addAction(createMediaAction(android.R.drawable.ic_media_ff, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))

        if (currentFileIndex < files.length() - 1) {
            builder.addAction(createMediaAction(android.R.drawable.ic_media_next, KeyEvent.KEYCODE_MEDIA_NEXT))
        }

        builder.style = Notification.MediaStyle()
            .setMediaSession(mediaSession.sessionToken)
            .setShowActionsInCompactView(*(startIndex..startIndex+3).toList().toIntArray()) // kotlin magic

        return builder.build()
    }

    private fun createMediaAction(resId: Int, action: Int): Notification.Action {
        val icon = Icon.createWithResource(applicationContext, resId)

        val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
        intent.putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, action))

        //WTF magic with 'requestCode' params here. Android please stop being so shite
        val pendingIntent = PendingIntent.getBroadcast(applicationContext, action, intent, 0)

        return Notification.Action.Builder(icon, "", pendingIntent).build()
    }

    // media receiver
    private val mediaKeysReceiver = MediaKeysReceiver()
    inner class MediaKeysReceiver: BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if(intent?.action == Intent.ACTION_MEDIA_BUTTON) {
                intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)?.let {
                    mediaSession.controller.dispatchMediaButtonEvent(it)
                }
            }
        }
    }

    // expose binding
    private val binder = ControlServiceBinder()
    inner class ControlServiceBinder: Binder() {
        var jsCallback: ((command: String, data: Any?) -> Unit)? = null
        fun getDevice() = lastDevice
        fun getDevicesList() = lastDevicesList
        fun getService() = this@ControlService
    }

    override fun onBind(intent: Intent?) = binder
}