package com.parsa.internetchat

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.util.UUID

class MainActivity : AppCompatActivity() {

    companion object {
        private const val SERVER = "wss://internetchat-server.onrender.com"
        private const val DEFAULT_ROOM = "voice-room-1"
        private const val MIC_REQUEST = 100
    }

    private lateinit var status: TextView
    private lateinit var room: EditText
    private lateinit var button: Button

    private val client = OkHttpClient()
    private var socket: WebSocket? = null
    private var userId = "android-" + UUID.randomUUID().toString().take(8)
    private var peerId: String? = null

    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var audioManager: AudioManager? = null

    private val iceServers = listOf(
        PeerConnection.IceServer.builder(
            "stun:stun.l.google.com:19302"
        ).createIceServer()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        makeUi()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                MIC_REQUEST
            )
        }
    }

    private fun makeUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(32, 70, 32, 32)
        }

        val title = TextView(this).apply {
            text = "InternetChat"
            textSize = 30f
            gravity = Gravity.CENTER
        }

        status = TextView(this).apply {
            text = "🔴 آماده"
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 30, 0, 30)
        }

        room = EditText(this).apply {
            hint = "Room ID"
            setText(DEFAULT_ROOM)
        }

        button = Button(this).apply {
            text = "شروع تماس صوتی"
            setOnClickListener {
                if (socket == null) connect() else disconnect()
            }
        }

        root.addView(title)
        root.addView(status)
        root.addView(room)
        root.addView(button)
        setContentView(root)
    }

    private fun connect() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                MIC_REQUEST
            )
            return
        }

        status.text = "🟡 در حال اتصال..."
        initWebRtc()

        val request = Request.Builder().url(SERVER).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(ws: WebSocket, response: Response) {
                runOnUiThread { status.text = "🟢 سرور وصل شد" }
                send(JSONObject().apply {
                    put("type", "identify")
                    put("userId", userId)
                })
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread {
                    status.text = "🔴 خطا: " + (t.message ?: "connection failed")
                    socket = null
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                runOnUiThread {
                    status.text = "🔴 قطع شد"
                    socket = null
                }
            }
        })
    }

    private fun handleMessage(text: String) {
        val message = try {
            JSONObject(text)
        } catch (_: Exception) {
            return
        }

        when (message.optString("type")) {
            "identified" -> {
                send(JSONObject().apply {
                    put("type", "join-room")
                    put("roomId", room.text.toString().trim().ifEmpty { DEFAULT_ROOM })
                })
            }

            "room-joined" -> {
                val peers = message.optJSONArray("peers")
                if (peers != null && peers.length() > 0) {
                    peerId = peers.getString(0)
                    createPeer(true)
                }
                runOnUiThread {
                    status.text = "🟢 داخل اتاق"
                }
            }

            "peer-joined" -> {
                peerId = message.optString("userId")
                runOnUiThread { status.text = "🟡 طرف مقابل پیدا شد..." }
            }

            "offer" -> {
                peerId = message.optString("from")
                createPeer(false)

                val data = message.optJSONObject("data") ?: return
                val sdp = data.optString("sdp")
                if (sdp.isEmpty()) return

                peer?.setRemoteDescription(
                    Observer(),
                    SessionDescription(SessionDescription.Type.OFFER, sdp)
                )

                peer?.createAnswer(object : Observer() {
                    override fun onCreateSuccess(desc: SessionDescription?) {
                        if (desc == null) return
                        peer?.setLocalDescription(Observer(), desc)
                        send(JSONObject().apply {
                            put("type", "answer")
                            put("target", peerId)
                            put("data", JSONObject().put("sdp", desc.description))
                        })
                    }
                }, MediaConstraints())
            }

            "answer" -> {
                val data = message.optJSONObject("data") ?: return
                val sdp = data.optString("sdp")
                if (sdp.isNotEmpty()) {
                    peer?.setRemoteDescription(
                        Observer(),
                        SessionDescription(SessionDescription.Type.ANSWER, sdp)
                    )
                }
            }

            "ice" -> {
                val data = message.optJSONObject("data") ?: return
                val candidate = IceCandidate(
                    data.optString("sdpMid"),
                    data.optInt("sdpMLineIndex"),
                    data.optString("candidate")
                )
                peer?.addIceCandidate(candidate)
            }

            "peer-left" -> {
                peer?.close()
                peer = null
                peerId = null
                runOnUiThread { status.text = "🟡 طرف مقابل قطع شد" }
            }

            "error" -> runOnUiThread {
                status.text = "⚠️ " + message.optString("message")
            }
        }
    }

    private fun initWebRtc() {
        if (factory != null) return

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions
                .builder(this)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )

        factory = PeerConnectionFactory.builder()
            .createPeerConnectionFactory()

        audioSource = factory!!.createAudioSource(MediaConstraints())
        audioTrack = factory!!.createAudioTrack("local-audio", audioSource)

        audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager?.isSpeakerphoneOn = true
    }

    private fun createPeer(offerer: Boolean) {
        if (peer != null) {
            if (offerer) makeOffer()
            return
        }

        peer = factory!!.createPeerConnection(
            iceServers,
            object : PeerConnection.Observer {

                override fun onIceCandidate(candidate: IceCandidate) {
                    send(JSONObject().apply {
                        put("type", "ice")
                        put("target", peerId)
                        put("data", JSONObject().apply {
                            put("candidate", candidate.sdp)
                            put("sdpMid", candidate.sdpMid)
                            put("sdpMLineIndex", candidate.sdpMLineIndex)
                        })
                    })
                }

                override fun onIceConnectionChange(
                    state: PeerConnection.IceConnectionState
                ) {
                    runOnUiThread {
                        when (state) {
                            PeerConnection.IceConnectionState.CONNECTED,
                            PeerConnection.IceConnectionState.COMPLETED ->
                                status.text = "🟢 تماس صوتی برقرار شد"

                            PeerConnection.IceConnectionState.FAILED ->
                                status.text = "🔴 اتصال صوتی ناموفق"

                            else -> {}
                        }
                    }
                }

                override fun onAddTrack(
                    receiver: RtpReceiver,
                    mediaStreams: Array<out MediaStream>
                ) {
                    val track = receiver.track()
                    if (track is AudioTrack) track.setEnabled(true)
                }

                override fun onSignalingChange(
                    state: PeerConnection.SignalingState
                ) {}

                override fun onIceConnectionReceivingChange(receiving: Boolean) {}

                override fun onIceGatheringChange(
                    state: PeerConnection.IceGatheringState
                ) {}

                override fun onIceCandidatesRemoved(
                    candidates: Array<out IceCandidate>
                ) {}

                override fun onAddStream(stream: MediaStream) {}

                override fun onRemoveStream(stream: MediaStream) {}

                override fun onDataChannel(channel: DataChannel) {}

                override fun onRenegotiationNeeded() {}

                override fun onConnectionChange(
                    newState: PeerConnection.PeerConnectionState
                ) {}

                override fun onStandardizedIceConnectionChange(
                    newState: PeerConnection.IceConnectionState
                ) {}

                override fun onSelectedCandidatePairChanged(
                    event: PeerConnection.CandidatePairChangeEvent
                ) {}

                override fun onIceCandidateError(
                    event: PeerConnection.IceCandidateErrorEvent
                ) {}

                override fun onTrack(transceiver: RtpTransceiver) {}
            }
        )

        audioTrack?.let {
            peer?.addTrack(it, listOf("audio-stream"))
        }

        if (offerer) makeOffer()
    }

    private fun makeOffer() {
        peer?.createOffer(object : Observer() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) return
                peer?.setLocalDescription(Observer(), desc)

                send(JSONObject().apply {
                    put("type", "offer")
                    put("target", peerId)
                    put("data", JSONObject().put("sdp", desc.description))
                })
            }
        }, MediaConstraints())
    }

    private fun send(message: JSONObject) {
        socket?.send(message.toString())
    }

    private fun disconnect() {
        try {
            send(JSONObject().put("type", "leave-room"))
        } catch (_: Exception) {}

        peer?.close()
        peer = null
        peerId = null
        socket?.close(1000, "user disconnect")
        socket = null

        runOnUiThread {
            status.text = "🔴 قطع"
            button.text = "شروع تماس صوتی"
        }
    }

    override fun onDestroy() {
        disconnect()
        audioTrack?.dispose()
        audioSource?.dispose()
        factory?.dispose()
        super.onDestroy()
    }

    open class Observer : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }
}
