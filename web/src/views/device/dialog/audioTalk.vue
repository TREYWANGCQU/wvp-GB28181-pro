<template>
  <div>
    <el-dialog
      title="语音对讲"
      top="10vh"
      width="61.5vw"
      :close-on-click-modal="false"
      :visible.sync="showDialog"
      @close="close()"
    >
      <el-alert
        v-if="!isSecureContext"
        title="当前处于不安全 HTTP 协议，浏览器限制麦克风采集，请配置 HTTPS 证书后重试。"
        type="error"
        show-icon
        :closable="false"
        style="margin-bottom: 12px;"
      />
      <div style="display: flex; gap: 16px;">
        <div style="flex: 1; min-width: 0;">
          <div v-if="!showPlayer" class="player-placeholder">
            <el-button
              type="primary"
              icon="el-icon-video-play"
              :loading="previewLoading"
              @click="startPreview"
            >开启预览</el-button>
          </div>
          <playerTabs
            v-if="showPlayer"
            ref="playerTabs"
            style="min-height: 60vh;"
            :has-audio="hasAudio"
            :show-button="true"
          />
        </div>

        <div class="broadcast-panel">
          <div style="text-align: center; width: 100%;">
            <video id="audioTalkVideo" controls autoplay style="width: 0; height: 0">
              Your browser is too old which doesn't support HTML5 video.
            </video>
            <el-radio-group v-model="talkMode" size="big" @change="onModeChange">
              <el-radio-button :label="false">对讲 (推荐)</el-radio-button>
              <el-radio-button :label="true">喊话</el-radio-button>
            </el-radio-group>
            <p style="color: #909399; font-size: 13px; margin-top: 6px; line-height: 1.4;">
              {{ talkMode ? '【喊话】向现场广播语音，需设备支持反向呼叫' : '【对讲·推荐】主动呼叫设备，双向语音通话，兼容性高' }}
            </p>
            <div v-if="!isAudioChannel" style="margin-top: 8px; padding: 6px 10px; background: #fdf6ec; border-radius: 4px; font-size: 12px; color: #e6a23c; text-align: left; line-height: 1.4;">
              <i class="el-icon-warning-outline"></i> 当前通道为视频通道（非137音频输出通道）。若现场扬声器无声音，请确认设备是否外接有源功放/喇叭，或检查是否有专用的137通道。
            </div>
          </div>
          <div style="text-align: center;">
            <el-button
              :type="getTalkButtonType()"
              :disabled="talkStatus === -2 || !isSecureContext"
              circle
              icon="el-icon-microphone"
              style="font-size: 32px; padding: 24px;"
              @click="talkButtonClick()"
            />
            <p style="margin-top: 16px; color: #606266; font-size: 14px;">
              <span v-if="talkStatus === -2"><i class="el-icon-loading"></i> 正在释放资源...</span>
              <span v-if="talkStatus === -1">点击开始{{ talkMode ? '喊话' : '对讲' }}</span>
              <span v-if="talkStatus === 0"><i class="el-icon-loading"></i> 正在协商媒体格式与建立信令...</span>
              <span v-if="talkStatus === 1 && talkMode" style="color: #67c23a;"><i class="el-icon-microphone"></i> 广播喊话中（音频传输正常）</span>
              <span v-if="talkStatus === 1 && !talkMode && !playConnected"><i class="el-icon-loading"></i> 等待设备音频链路接通...</span>
              <span v-if="talkStatus === 1 && !talkMode && playConnected" style="color: #67c23a;"><i class="el-icon-phone-outline"></i> 双向对讲中（音频传输正常）</span>
            </p>
            <p v-if="talkStatus === 1 && !talkMode && talkAudioFailed" style="margin-top: 8px;">
              <el-button
                type="warning"
                size="mini"
                icon="el-icon-refresh"
                @click="retryTalkAudio"
              >重试音频</el-button>
            </p>
          </div>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import playerTabs from '../../common/playerTabs.vue'

export default {
  name: 'AudioTalk',
  components: { playerTabs },
  data() {
    return {
      showDialog: false,
      showPlayer: false,
      previewLoading: false,
      deviceId: null,
      channelId: null,
      hasAudio: false,
      streamInfo: null,
      talkMode: false,
      talkStatus: -1,
      isSecureContext: true,
      broadcastRtc: null,
      talkAudioRtc: null,
      talkAudioRetryTimer: null,
      talkAudioFailed: false,
      talkAudioPlayStream: null,
      playConnected: false
    }
  },
  computed: {
    isAudioChannel() {
      if (!this.channelId) return true
      const str = String(this.channelId)
      if (str.length >= 13) {
        return str.substring(10, 13) === '137'
      }
      return true
    }
  },
  created() {
    this.talkStatus = -1
    this.checkSecureContext()
  },
  methods: {
    checkSecureContext() {
      if (typeof window !== 'undefined') {
        this.isSecureContext = window.isSecureContext || location.hostname === 'localhost' || location.hostname === '127.0.0.1'
      }
    },
    getMicrophoneErrorMessage(error) {
      if (!error) return '麦克风异常'
      if (error.name === 'NotAllowedError' || error.name === 'PermissionDeniedError') {
        return '麦克风权限被拒绝，请在浏览器地址栏允许麦克风访问权限'
      }
      if (error.name === 'NotFoundError' || error.name === 'DevicesNotFoundError') {
        return '未检测到可用麦克风设备，无法发起语音通话'
      }
      if (error.name === 'NotReadableError' || error.name === 'TrackStartError' || error.name === 'AbortError') {
        return '本地麦克风被其他应用占用或暂不可用，请检查后重试'
      }
      return '麦克风检测失败: ' + (error.message || error.name)
    },
    async checkMicrophoneAvailability() {
      this.checkSecureContext()
      if (!this.isSecureContext) {
        throw new Error('当前处于不安全 HTTP 协议，现代浏览器限制麦克风采集，请配置 HTTPS 证书后重试')
      }
      if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
        throw new Error('当前浏览器环境不支持麦克风采集')
      }
      let stream = null
      try {
        stream = await navigator.mediaDevices.getUserMedia({ audio: true, video: false })
        const audioTracks = stream.getAudioTracks()
        if (!audioTracks.length) throw new Error('未检测到有效的麦克风音轨')
        if (audioTracks.every(track => track.readyState === 'ended')) {
          throw new Error('麦克风已断开或不可用')
        }
      } finally {
        if (stream) stream.getTracks().forEach(t => t.stop())
      }
    },
    openDialog(deviceId, channelId) {
      if (this.showDialog) return
      this.checkSecureContext()
      this.deviceId = deviceId
      this.channelId = channelId
      this.talkMode = false
      this.showPlayer = false
      this.streamInfo = null
      this.showDialog = true
    },
    onModeChange() {
      if (this.talkStatus > -1) {
        this.stopTalk()
      }
    },
    startPreview() {
      this.previewLoading = true
      this.$store.dispatch('play/play', [this.deviceId, this.channelId])
        .then(data => {
          this.streamInfo = data
          this.hasAudio = data.hasAudio
          this.showPlayer = true
          this.$nextTick(() => {
            if (this.$refs.playerTabs) {
              this.$refs.playerTabs.setStreamInfo(data.transcodeStream || data)
            }
          })
        })
        .catch(e => {
          this.$message({ showClose: true, message: e, type: 'error' })
        })
        .finally(() => {
          this.previewLoading = false
        })
    },
    getTalkButtonType() {
      if (this.talkStatus === -2) return 'primary'
      if (this.talkStatus === -1) return 'primary'
      if (this.talkStatus === 0) return 'warning'
      if (this.talkStatus === 1) {
        if (!this.talkMode && !this.playConnected) return 'warning'
        return 'danger'
      }
    },
    async talkButtonClick() {
      if (this.talkStatus === -1) {
        await this.startTalk()
      } else if (this.talkStatus === 1) {
        this.stopTalk()
      }
    },
    async startTalk() {
      try {
        await this.checkMicrophoneAvailability()
      } catch (e) {
        this.$message({ showClose: true, message: this.getMicrophoneErrorMessage(e), type: 'error' })
        return
      }
      this.talkStatus = 0
      try {
        const data = await this.$store.dispatch('play/broadcastStart', [this.deviceId, this.channelId, this.talkMode])
        const si = data.streamInfo
        const url = document.location.protocol.includes('https') ? si.rtcs : si.rtc
        this.startWebrtcPush(url)

        const playStreamInfo = data?.playStreamInfo
        if (!this.talkMode && playStreamInfo) {
          this.talkAudioPlayStream = playStreamInfo
          this.startTalkAudioPlay(playStreamInfo)
          this.muteVideoPlayer()
        }
      } catch (e) {
        this.$message({ showClose: true, message: e, type: 'error' })
        this.talkStatus = -1
      }
    },
    startWebrtcPush(url) {
      this.$store.dispatch('user/getUserInfo')
        .then((data) => {
          if (data === null) { this.talkStatus = -1; return }
          const pushKey = data.pushKey
          url += '&sign=' + pushKey

          if (this.broadcastRtc) {
            this.broadcastRtc.close()
          }
          this.broadcastRtc = new ZLMRTCClient.Endpoint({
            debug: true,
            zlmsdpUrl: url,
            simulecast: false,
            useCamera: false,
            audioEnable: true,
            videoEnable: false,
            recvOnly: false
          })
          this.broadcastRtc.on(ZLMRTCClient.Events.WEBRTC_NOT_SUPPORT, () => { this.talkStatus = -1 })
          this.broadcastRtc.on(ZLMRTCClient.Events.WEBRTC_ICE_CANDIDATE_ERROR, () => { this.talkStatus = -1 })
          this.broadcastRtc.on(ZLMRTCClient.Events.WEBRTC_OFFER_ANWSER_EXCHANGE_FAILED, () => { this.talkStatus = -1 })
          this.broadcastRtc.on(ZLMRTCClient.Events.WEBRTC_ON_CONNECTION_STATE_CHANGE, (e) => {
            if (e === 'connecting') this.talkStatus = 0
            else if (e === 'connected') this.talkStatus = 1
            else if (e === 'disconnected') this.talkStatus = -1
          })
          this.broadcastRtc.on(ZLMRTCClient.Events.CAPTURE_STREAM_FAILED, () => { this.talkStatus = -1 })
        })
        .catch(() => { this.talkStatus = -1 })
    },
    muteVideoPlayer() {
      const player = this.$refs.playerTabs
      if (!player) return
      if (player.mute) {
        player.mute()
      }
    },
    unmuteVideoPlayer() {
      const player = this.$refs.playerTabs
      if (!player) return
      if (player.cancelMute) {
        player.cancelMute()
      }
    },
    startTalkAudioPlay(playStreamInfo) {
      if (this.talkAudioRtc) {
        this.talkAudioRtc.close()
      }
      if (this.talkAudioRetryTimer) {
        clearTimeout(this.talkAudioRetryTimer)
      }

      const url = location.protocol === 'https:' ? playStreamInfo.rtcs : playStreamInfo.rtc
      if (!url) {
        console.warn('[AudioTalk] 无可用的设备音频播放地址')
        return
      }
      this.talkAudioRetryTimer = setTimeout(() => {
        this.pollMediaInfoAndPlay(playStreamInfo)
      }, 800)
    },
    async pollMediaInfoAndPlay(playStreamInfo) {
      try {
        const data = await this.$store.dispatch('server/getMediaInfo', {
          app: playStreamInfo.app,
          stream: playStreamInfo.stream,
          mediaServerId: playStreamInfo.mediaServerId
        })
        if (data) {
          const url = location.protocol === 'https:' ? playStreamInfo.rtcs : playStreamInfo.rtc
          this.startTalkAudioByRtc(url)
        } else {
          throw new Error('no data')
        }
      } catch (e) {
        if (this.talkStatus === 1 || this.talkStatus === 0) {
          this.talkAudioRetryTimer = setTimeout(() => {
            this.pollMediaInfoAndPlay(playStreamInfo)
          }, 800)
        }
      }
    },
    startTalkAudioByRtc(url) {
      this.talkAudioFailed = false
      this.talkAudioRtc = new ZLMRTCClient.Endpoint({
        debug: false,
        element: document.getElementById('audioTalkVideo'),
        zlmsdpUrl: url,
        simulecast: false,
        useCamera: false,
        audioEnable: true,
        videoEnable: false,
        recvOnly: true,
        usedatachannel: false
      })

      this.talkAudioRtc.on(ZLMRTCClient.Events.WEBRTC_OFFER_ANWSER_EXCHANGE_FAILED, (e) => {
        console.warn('[AudioTalk] 播放流offer失败:', e?.code, e?.msg)
        if (e && e.code == -400 && e.msg == '流不存在') {
          this.talkAudioRetryTimer = setTimeout(() => {
            this.startTalkAudioByRtc(url)
          }, 1000)
        }
      })

      this.talkAudioRtc.on(ZLMRTCClient.Events.WEBRTC_ON_REMOTE_STREAMS, () => {
        console.warn('[AudioTalk] 设备音频流到达')
        this.playConnected = true
      })

      this.talkAudioRtc.on(ZLMRTCClient.Events.WEBRTC_ICE_CANDIDATE_ERROR, () => {
        console.error('[AudioTalk] 音频播放ICE协商失败')
      })

      this.talkAudioRtc.on(ZLMRTCClient.Events.WEBRTC_ON_CONNECTION_STATE_CHANGE, (s) => {
        console.warn('[AudioTalk] 音频播放连接状态:', s)
        if (s === 'connected') {
          this.playConnected = true
        } else if (s === 'disconnected' || s === 'failed' || s === 'closed') {
          this.playConnected = false
          this.talkAudioFailed = true
          if (this.talkStatus === 1) {
            this.talkAudioRetryTimer = setTimeout(() => {
              this.startTalkAudioByRtc(url)
            }, 2000)
          }
        }
      })
    },
    async stopTalk() {
      this.talkStatus = -2
      if (this.broadcastRtc) {
        this.broadcastRtc.close()
        this.broadcastRtc = null
      }
      if (this.talkAudioRtc) {
        this.talkAudioRtc.close()
        this.talkAudioRtc = null
      }
      if (this.talkAudioRetryTimer) {
        clearTimeout(this.talkAudioRetryTimer)
        this.talkAudioRetryTimer = null
      }
      this.talkAudioFailed = false
      this.talkAudioPlayStream = null
      this.playConnected = false
      this.unmuteVideoPlayer()
      try {
        await this.$store.dispatch('play/broadcastStop', [this.deviceId, this.channelId])
      } catch (e) {
        console.warn('停止对讲失败', e)
      }
      this.talkStatus = -1
    },
    retryTalkAudio() {
      if (this.talkAudioPlayStream) {
        this.startTalkAudioPlay(this.talkAudioPlayStream)
      }
    },
    close() {
      if (this.showPlayer && this.$refs.playerTabs) {
        this.$refs.playerTabs.stop()
      }
      this.stopTalk()
      this.streamInfo = null
      this.showPlayer = false
      this.showDialog = false
    }
  }
}
</script>

<style scoped>
.player-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  aspect-ratio: 16 / 9;
  background: #1a1a1a;
}
.broadcast-panel {
  width: 220px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 16px 10px;
  border-left: 1px solid #ebeef5;
}
.broadcast-panel > div:first-child {
  flex-shrink: 0;
}
.broadcast-panel > div:last-child {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
}
</style>
