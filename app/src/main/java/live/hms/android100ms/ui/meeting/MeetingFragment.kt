package live.hms.android100ms.ui.meeting

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.brytecam.lib.*
import com.brytecam.lib.payload.HMSPayloadData
import com.brytecam.lib.payload.HMSPublishStream
import com.brytecam.lib.payload.HMSStreamInfo
import com.brytecam.lib.webrtc.HMSRTCMediaStream
import com.brytecam.lib.webrtc.HMSRTCMediaStreamConstraints
import com.brytecam.lib.webrtc.HMSStream
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayoutMediator
import live.hms.android100ms.R
import live.hms.android100ms.databinding.FragmentMeetingBinding
import live.hms.android100ms.model.RoomDetails
import live.hms.android100ms.ui.home.HomeActivity
import live.hms.android100ms.ui.home.settings.SettingsStore
import live.hms.android100ms.ui.meeting.chat.ChatMessage
import live.hms.android100ms.ui.meeting.chat.ChatViewModel
import live.hms.android100ms.ui.meeting.videogrid.VideoGridAdapter
import live.hms.android100ms.util.*
import org.appspot.apprtc.AppRTCAudioManager
import org.webrtc.AudioTrack
import org.webrtc.MediaStream
import org.webrtc.VideoTrack
import java.util.*
import kotlin.collections.ArrayList


class MeetingFragment : Fragment(), HMSEventListener {

  companion object {
    private const val TAG = "MeetingFragment"
  }

  private var binding by viewLifecycle<FragmentMeetingBinding>()

  private lateinit var settings: SettingsStore
  private lateinit var roomDetails: RoomDetails

  // TODO: Disable video/audio buttons during audio/video only publish modes
  private var isAudioEnabled = true
  private var isVideoEnabled = true

  private var currentDeviceTrack: MeetingTrack? = null
  private val videoGridItems = ArrayList<MeetingTrack>()

  private var hmsClient: HMSClient? = null
  private var hmsRoom: HMSRoom? = null
  private var hmsPeer: HMSPeer? = null

  private val chatViewModel: ChatViewModel by activityViewModels()

  private lateinit var clipboard: ClipboardManager

  private lateinit var audioManager: AppRTCAudioManager

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    roomDetails = requireActivity().intent!!.extras!![ROOM_DETAILS] as RoomDetails

    roomDetails.apply {
      crashlytics.setCustomKey(ROOM_ID, roomId)
      crashlytics.setCustomKey(USERNAME, username)
    }

    clipboard = requireActivity()
      .getSystemService(Context.CLIPBOARD_SERVICE)
        as ClipboardManager
    audioManager = AppRTCAudioManager.create(requireContext())
  }

  override fun onDestroy() {
    super.onDestroy()
    stopAudioManager()
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    when (item.itemId) {
      R.id.action_share_link -> {
        val meetingUrl = roomDetails.let {
          "https://${it.env}.100ms.live/?room=${it.roomId}&env=${it.env}&role=Guest"
        }
        val sendIntent = Intent().apply {
          action = Intent.ACTION_SEND
          putExtra(Intent.EXTRA_TEXT, meetingUrl)
          type = "text/plain"
        }
        val shareIntent = Intent.createChooser(sendIntent, null)
        startActivity(shareIntent)
      }
      R.id.action_record_meeting -> {
        Toast.makeText(requireContext(), "Recording Not Supported", Toast.LENGTH_SHORT).show()
      }
      R.id.action_share_screen -> {
        Toast.makeText(requireContext(), "Screen Share Not Supported", Toast.LENGTH_SHORT).show()
      }
    }
    return false
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    initViewModel()
    setHasOptionsMenu(true)
  }

  override fun onCreateView(
    inflater: LayoutInflater, container: ViewGroup?,
    savedInstanceState: Bundle?
  ): View {
    binding = FragmentMeetingBinding.inflate(inflater, container, false)
    settings = SettingsStore(requireContext())

    hideErrorView()

    updateProgressBarUI(
      "Connecting...",
      "Please wait while we connect you to ${roomDetails.endpoint}"
    )
    showProgressBar()

    initVideoGrid()
    initButtons()
    initOnBackPress()


    // We need only instance of HMSClient, hence it is not safe to initialize
    // inside onViewCreated as it will trigger redundant connect calls whenever,
    // onViewCreated is called.
    if (hmsClient == null) initHMSClient()

    return binding.root
  }

  private fun initViewModel() {
    chatViewModel.setSendBroadcastCallback { message ->
      Log.v(TAG, "Sending broadcast: $message via $hmsClient")
      hmsClient?.broadcast(message.message, hmsRoom, object : HMSRequestHandler {
        override fun onSuccess(s: String?) {
          Log.v(TAG, "Successfully broadcast message=${message.message} (s=$s)")
        }

        override fun onFailure(errorCode: Long, errorMessage: String) {
          Toast.makeText(
            requireContext(),
            "Cannot send '${message}'. Please try again",
            Toast.LENGTH_SHORT
          ).show()
          crashlyticsLog(
            TAG,
            "Cannot broadcast message=${message} code=${errorCode} errorMessage=${errorMessage}"
          )
        }
      })
    }
  }

  private fun startAudioManager() {
    Log.d(TAG, "Starting Audio manager")
    audioManager.start { selectedAudioDevice, availableAudioDevices ->
      Log.d(
        TAG,
        "onAudioManagerDevicesChanged: $availableAudioDevices, selected: $selectedAudioDevice"
      )
    }
  }

  private fun stopAudioManager() {
    Log.v(TAG, "Stopping Audio Manager")
    audioManager.stop()
  }

  private fun initVideoGrid() {
    binding.viewPagerVideoGrid.apply {
      offscreenPageLimit = 1
      adapter = VideoGridAdapter(this@MeetingFragment) { video ->
        // TODO: Implement Hero/Pin View

        Log.v(TAG, "onVideoItemClick: $video")

        Snackbar.make(
          binding.root,
          "Name: ${video.peer.userName} (${video.peer.role}) \nId: ${video.peer.customerUserId}",
          Snackbar.LENGTH_LONG,
        ).setAction("Copy") {
          val clip = ClipData.newPlainText("Customer Id", video.peer.customerUserId)
          clipboard.setPrimaryClip(clip)
          Toast.makeText(
            requireContext(),
            "Copied customer id of ${video.peer.userName} to clipboard",
            Toast.LENGTH_SHORT
          ).show()
        }.show()
      }

      TabLayoutMediator(binding.tabLayoutDots, this) { _, _ ->
        // No text to be shown
      }.attach()
    }

  }

  private fun updateVideoGridUI() {
    val adapter = binding.viewPagerVideoGrid.adapter as VideoGridAdapter
    adapter.setItems(videoGridItems)
    Log.v(TAG, "updated video Grid UI with ${videoGridItems.size} items")
  }

  private fun updateProgressBarUI(heading: String, description: String) {
    binding.progressBar.heading.text = heading
    binding.progressBar.description.apply {
      visibility = if (description.isEmpty()) View.GONE else View.VISIBLE
      text = description
    }
  }

  private fun hideProgressBar() {
    binding.viewPagerVideoGrid.visibility = View.VISIBLE
    binding.tabLayoutDots.visibility = View.VISIBLE
    binding.bottomControls.visibility = View.VISIBLE

    binding.progressBar.root.visibility = View.GONE
  }

  private fun showProgressBar() {
    binding.viewPagerVideoGrid.visibility = View.GONE
    binding.tabLayoutDots.visibility = View.GONE
    binding.bottomControls.visibility = View.GONE

    binding.progressBar.root.visibility = View.VISIBLE
  }

  private fun hideErrorView() {
    binding.viewPagerVideoGrid.visibility = View.VISIBLE
    binding.tabLayoutDots.visibility = View.VISIBLE
    binding.bottomControls.visibility = View.VISIBLE

    binding.disconnectError.root.visibility = View.GONE
  }

  private fun showErrorView(reason: String) {
    binding.viewPagerVideoGrid.visibility = View.GONE
    binding.tabLayoutDots.visibility = View.GONE
    binding.bottomControls.visibility = View.GONE

    binding.disconnectError.root.visibility = View.VISIBLE
    binding.disconnectError.reason.text = reason
  }

  private fun getUserMedia() {
    // TODO: Listen to changes in settings.publishVideo
    //  To be done only when the user can change the publishVideo
    //  while in a meeting.

    val localMediaConstraints = HMSRTCMediaStreamConstraints(true, settings.publishVideo)
    localMediaConstraints.apply {
      videoCodec = settings.codec
      videoFrameRate = settings.videoFrameRate
      videoResolution = settings.videoResolution
      videoMaxBitRate = settings.videoBitrate
      cameraFacing = settings.camera
    }

    crashlyticsLog(
      TAG, "getUserMedia() with " +
          "videoCodec=${localMediaConstraints.videoCodec}, " +
          "videoFrameRate=${localMediaConstraints.videoFrameRate}, " +
          "videoResolution=${localMediaConstraints.videoResolution}, " +
          "videoMaxBitRate=${localMediaConstraints.videoMaxBitRate}, " +
          "cameraFacing=${localMediaConstraints.cameraFacing}, "
    )

    // onConnect -> Join -> getUserMedia
    hmsClient?.getUserMedia(
      requireActivity().applicationContext,
      localMediaConstraints,
      object : HMSClient.GetUserMediaListener {
        override fun onSuccess(mediaStream: HMSRTCMediaStream?) {
          Log.v(TAG, "GetUserMedia Success")

          var videoTrack: VideoTrack? = null
          var audioTrack: AudioTrack? = null

          mediaStream?.stream?.apply {
            if (videoTracks.isNotEmpty()) {
              videoTrack = videoTracks[0]
              videoTrack?.setEnabled(settings.publishVideo)
            }
            if (audioTracks.isNotEmpty()) {
              audioTrack = audioTracks[0]
              audioTrack?.setEnabled(settings.publishAudio)
            }
          }

          hmsClient?.publish(
            mediaStream,
            hmsRoom,
            localMediaConstraints,
            object : HMSStreamRequestHandler {
              override fun onSuccess(data: HMSPublishStream) {
                crashlyticsLog(TAG, "Publish Success ${data.mid}")

                runOnUiThread {
                  currentDeviceTrack = MeetingTrack(
                    data.mid,
                    hmsPeer!!,
                    videoTrack,
                    audioTrack,
                    true
                  )

                  Log.v(TAG, "Adding $currentDeviceTrack to ViewPagerVideoGrid")
                  videoGridItems.add(0, currentDeviceTrack!!)
                  updateVideoGridUI()
                }
              }

              override fun onFailure(errorCode: Long, errorReason: String) {
                crashlyticsLog(TAG, "Publish Failure $errorCode $errorReason")
              }
            })
        }

        override fun onFailure(errorCode: Long, errorReason: String) {
          crashlyticsLog(TAG, "GetUserMedia failed: $errorCode $errorReason")
        }
      })

  }

  private fun initHMSClient() {
    updateProgressBarUI(
      "Connecting...",
      "Please wait while we connect you to ${roomDetails.endpoint}"
    )
    showProgressBar()

    hmsPeer = HMSPeer(roomDetails.username, roomDetails.authToken).apply {
      crashlytics.setUserId(customerUserId)
    }

    hmsRoom = HMSRoom(roomDetails.roomId)
    val config = HMSClientConfig(roomDetails.endpoint)
    hmsClient = HMSClient(this, requireContext(), hmsPeer, config).apply {
      setLogLevel(HMSLogger.LogLevel.LOG_DEBUG)
      connect()
    }
  }

  /**
   * Changes the icons for buttons as per the current settings
   */
  private fun updateButtonsUI() {
    // TODO: Listen to changes in publishVideo & publishAudio
    //  when it is possible to switch from Audio/Video only to Audio+Video/Audio/Video/etc
    binding.buttonToggleAudio.apply {
      visibility = if (settings.publishAudio) View.VISIBLE else View.GONE
      isEnabled = settings.publishAudio
      setIconResource(
        if (isAudioEnabled)
          R.drawable.ic_baseline_mic_24
        else
          R.drawable.ic_baseline_mic_off_24
      )
    }

    binding.buttonToggleVideo.apply {
      visibility = if (settings.publishVideo) View.VISIBLE else View.GONE
      isEnabled = settings.publishVideo
      setIconResource(
        if (isVideoEnabled)
          R.drawable.ic_baseline_videocam_24
        else
          R.drawable.ic_baseline_videocam_off_24
      )
    }
  }

  private fun initButtons() {
    updateButtonsUI()

    binding.buttonToggleVideo.setOnClickListener {
      Log.v(TAG, "buttonToggleVideo.onClick()")
      currentDeviceTrack?.apply {
        if (videoTrack != null) {
          isVideoEnabled = !videoTrack.enabled()
          videoTrack.setEnabled(isVideoEnabled)
          if (isVideoEnabled) {
            HMSStream.getCameraCapturer().start()
          } else {
            HMSStream.getCameraCapturer().stop()
          }
          updateButtonsUI()
        }
      }
    }

    binding.buttonToggleAudio.setOnClickListener {
      Log.v(TAG, "buttonToggleAudio.onClick()")
      currentDeviceTrack?.apply {
        if (audioTrack != null) {
          isAudioEnabled = !audioTrack.enabled()
          audioTrack.setEnabled(isAudioEnabled)
          updateButtonsUI()
        }
      }
    }

    binding.buttonOpenChat.setOnClickListener {
      findNavController().navigate(
        MeetingFragmentDirections.actionMeetingFragmentToChatBottomSheetFragment(
          roomDetails,
          hmsPeer!!.customerUserId
        )
      )
    }

    binding.buttonEndCall.setOnClickListener { disconnect() }

    binding.buttonFlipCamera.setOnClickListener {
      hmsClient?.apply {
        switchCamera()
      }
    }

    binding.disconnectError.buttonRetry.setOnClickListener {
      Log.v(TAG, "Trying to reconnect")
      hideErrorView()
      initHMSClient()
    }
  }

  private fun disconnect() {
    try {
      hmsClient?.leave(object : HMSRequestHandler {
        override fun onSuccess(s: String?) {
          Log.v(TAG, "On Leave Success")
        }

        override fun onFailure(l: Long, s: String?) {
          Log.v(TAG, "On Leave Failure")
        }
      })
      HMSStream.stopCapturers()
    } catch (e: Exception) {
      e.printStackTrace()
    }

    cleanup()
    hmsClient?.disconnect()

    // Go to home page
    Intent(requireContext(), HomeActivity::class.java).apply {
      startActivity(this)
      requireActivity().finish()
    }
  }

  private fun cleanup() {
    // Because the scope of Chat View Model is the entire activity
    // We need to perform a cleanup
    chatViewModel.clearMessages()

    // Remove all the video stream
    videoGridItems.clear()
    updateVideoGridUI()

    stopAudioManager()

    currentDeviceTrack = null
    hmsClient = null
    hmsRoom = null
    hmsPeer = null
  }

  private fun initOnBackPress() {
    requireActivity().onBackPressedDispatcher.addCallback(
      viewLifecycleOwner,
      object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
          Log.v(TAG, "initOnBackPress -> handleOnBackPressed")
          disconnect()
        }
      })
  }

  // HMS Event Listener events below
  override fun onConnect() {
    Log.v(TAG, "onConnect");

    runOnUiThread {
      updateProgressBarUI(
        "Connected! Joining meeting...",
        ""
      )

      hmsClient?.join(object : HMSRequestHandler {
        override fun onSuccess(p0: String?) {
          Log.v(TAG, "Join onSuccess($p0)")

          // FIXME: Remove this hacky-fix
          Thread.sleep(1000)

          runOnUiThread {
            startAudioManager()
            hideProgressBar()
            getUserMedia()
          }
        }

        override fun onFailure(errorCode: Long, errorMessage: String?) {
          crashlyticsLog(TAG, "Join onFailure($errorCode, $errorMessage)")
        }
      })

    }
  }

  override fun onDisconnect(errorMessage: String) {
    if (activity != null) {
      runOnUiThread {
        Log.v(TAG, "onDisconnect: $errorMessage")
        cleanup()
        hideProgressBar()
        showErrorView(errorMessage)
      }
    } else {
      // The user quit the app due to which the Fragment was detached from the
      // parent MeetingActivity.
      // It is safe to ignore this case assuming the user will not be able to recreate the
      // same instance of destroyed activity.
      crashlyticsLog(
        TAG,
        "onDisconnect(errorMessage=$errorMessage) called after activity was detached"
      )
    }
  }

  override fun onPeerJoin(peer: HMSPeer) {
    Log.v(
      TAG,
      "onPeerJoin: uid=${peer.uid}, " +
          "role=${peer.role}, " +
          "userId=${peer.customerUserId}, " +
          "peerId=${peer.peerId}"
    )
    runOnUiThread {
      Toast.makeText(
        requireContext(),
        "${peer.userName} - ${peer.peerId} joined",
        Toast.LENGTH_SHORT
      ).show()
    }
  }

  override fun onPeerLeave(peer: HMSPeer) {
    Log.v(
      TAG,
      "onPeerLeave: uid=${peer.uid}, " +
          "role=${peer.role}, " +
          "userId=${peer.customerUserId}, " +
          "peerId=${peer.peerId}"
    )
    runOnUiThread {
      Toast.makeText(
        requireContext(),
        "${peer.userName} - ${peer.peerId} left",
        Toast.LENGTH_SHORT
      ).show()
    }
  }


  override fun onStreamAdd(peer: HMSPeer, streamInfo: HMSStreamInfo) {
    crashlyticsLog(
      TAG,
      "onStreamAdd: peer-uid:${peer.uid}, " +
          "role=${peer.role}, " +
          "userId:${peer.customerUserId}, " +
          "streamInfo:${streamInfo}"
    )

    Log.v(TAG, "Subscribing via $hmsClient")
    hmsClient?.subscribe(streamInfo, hmsRoom, object : HMSMediaRequestHandler {
      override fun onSuccess(stream: MediaStream) {
        crashlyticsLog(
          TAG,
          "Subscribe(" +
              "uid=${streamInfo.uid}, " +
              "mid=${streamInfo.mid}, " +
              "userName=${streamInfo.userName}): " +
              "peer-id=${peer.uid} -- onSuccess($stream)"
        )
        runOnUiThread {
          var videoTrack: VideoTrack? = null
          var audioTrack: AudioTrack? = null

          if (stream.videoTracks.size > 0) {
            videoTrack = stream.videoTracks[0]
            videoTrack.setEnabled(true)
          }

          if (stream.audioTracks.size > 0) {
            audioTrack = stream.audioTracks[0]
            audioTrack.setEnabled(true)
          }

          videoGridItems.add(
            MeetingTrack(
              streamInfo.mid,
              peer,
              videoTrack, audioTrack,
              false
            )
          )
          updateVideoGridUI()
        }
      }

      override fun onFailure(errorCode: Long, errorReason: String) {
        crashlyticsLog(
          TAG,
          "Subscribe($streamInfo): peer-id=${peer.uid} -- onFailure($errorCode, $errorReason)"
        )
      }
    })
  }

  override fun onStreamRemove(streamInfo: HMSStreamInfo) {
    crashlyticsLog(TAG, "onStreamRemove: uid=${streamInfo.uid} mid=${streamInfo.mid}")

    runOnUiThread {
      var found = false
      val toRemove = arrayListOf<MeetingTrack>()

      // Get the index of the meeting track having uid
      videoGridItems.forEach { meetingTrack ->
        if (
          meetingTrack.peer.uid == streamInfo.uid
          && meetingTrack.mediaId == streamInfo.mid
          && meetingTrack.peer.customerUserId == streamInfo.peer.customerUserId
        ) {
          toRemove.add(meetingTrack)
          found = true
        }
      }

      videoGridItems.removeAll(toRemove)

      if (!found) {
        crashlyticsLog(TAG, "onStreamRemove: ${streamInfo.uid} not found in meeting tracks")
      } else {
        // Update the grid layout as we have removed some views
        updateVideoGridUI()
      }
    }
  }

  override fun onBroadcast(data: HMSPayloadData) {
    Log.v(
      TAG,
      "onBroadcast: customerId=${data.peer.customerUserId}, " +
          "userName=${data.peer.userName}, " +
          "msg=${data.msg}"
    )

    runOnUiThread {
      // FIXME: Get the user name anyhow!
      var username = data.peer.userName
      if (username.isEmpty()) username = "error<senderName=null>"

      chatViewModel.receivedMessage(
        ChatMessage(
          // TODO: Change uid -> Customer ID
          data.peer.uid,
          username,
          Date(),
          data.msg,
          false
        )
      )
    }
  }

  // Helper function
  private fun runOnUiThread(action: Runnable) {
    requireActivity().runOnUiThread(action)
  }
}