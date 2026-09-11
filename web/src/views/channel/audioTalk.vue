<template>
  <div>
    <el-dialog
      title="语音对讲"
      top="10vh"
      width="65vw"
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
              <el-radio-button :label="true">对讲 (推荐)</el-radio-button>
              <el-radio-button :label="false">喊话</el-radio-button>
            </el-radio-group>
            <p style="color: #909399; font-size: 13px; margin-top: 6px; line-height: 1.4;">
              {{ talkMode ? '【对讲·推荐】主动呼叫设备，双向语音通话，兼容性高' : '【喊话】向现场广播语音，需设备支持反向呼叫' }}
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
              <span v-if="talkStatus === -1">点击开始{{ talkMode ? '对讲' : '喊话' }}</span>
              <span v-if="talkStatus === 0"><i class="el-icon-loading"></i> 正在协商媒体格式与建立信令...</span>
              <span v-if="talkStatus === 1 && !talkMode" style="color: #67c23a;"><i class="el-icon-microphone"></i> 广播喊话中（音频传输正常）</span>
              <span v-if="talkStatus === 1 && talkMode && !playConnected"><i class="el-icon-loading"></i> 等待设备音频链路接通...</span>
              <span v-if="talkStatus === 1 && talkMode && playConnected" style="color: #67c23a;"><i class="el-icon-phone-outline"></i> 双向对讲中（音频传输正常）</span>
            </p>
            <p v-if="talkStatus === 1 && talkMode && talkAudioFailed" style="margin-top: 8px;">
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
import playerTabs from '../common/playerTabs.vue'

export default {
  name: 'ChAudioTalk',
  components: { playerTabs },
  data() {
    return {
      showDialog: false,
      showPlayer: false,
      previewLoading: false,
      channelId: null,
      hasAudio: false,
      streamInfo: null,
      talkMode: true,
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
    openDialog(channelId) {
      if (this.showDialog) return
      this.checkSecureContext()
      this.channelId = channelId
      this.talkMode = true
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
      this.$store.dispatch('commonChanel/playChannel', this.channelId)
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
        if (this.talkMode && !this.playConnected) return 'warning'
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
        const storeName = 'commonChanel'
        const actionName = this.talkMode ? 'talkStart' : 'broadcastStart'
        const data = await this.$store.dispatch(storeName + '/' + actionName, this.channelId)

        const pushStream = data?.pushStream
        const playStream = data?.playStream

        if (this.talkMode && playStream) {
          this.talkAudioPlayStream = playStream
          this.startTalkAudioPlay(playStream)
          this.muteVideoPlayer()
        }

        this.startWebrtcPush(pushStream)
      } catch (e) {
        this.$message({ showClose: true, message: e, type: 'error' })
        this.talkStatus = -1
      }
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
    getMicrophoneErrorMessage(error) {
      if (!error || !error.name) return '本地麦克风检测失败，请检查浏览器音频采集权限'
      if (error.name === 'NotAllowedError' || error.name === 'PermissionDeniedError' || error.name === 'SecurityError') {
        return '未授予浏览器麦克风权限，无法发起语音对讲'
      }
      if (error.name === 'NotFoundError' || error.name === 'DevicesNotFoundError') {
        return '未检测到可用麦克风，无法发起语音对讲'
      }
      if (error.name === 'NotReadableError' || error.name === 'TrackStartError' || error.name === 'AbortError') {
        return '本地麦克风被占用或暂不可用，请检查后重试'
      }
      if (error.name === 'OverconstrainedError' || error.name === 'ConstraintNotSatisfiedError') {
        return '当前麦克风不满足采集条件，无法发起语音对讲'
      }
      return '本地麦克风检测失败: ' + (error.message || error.name)
    },
    async checkMicrophoneAvailability() {
      this.checkSecureContext()
      if (!this.isSecureContext) {
        throw new Error('当前页面不是安全上下文（HTTPS/localhost），浏览器无法采集麦克风音频')
      }
      if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
        throw new Error('当前浏览器不支持麦克风采集')
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
    startWebrtcPush(pushStream) {
      if (!pushStream) return
      let url = location.protocol === 'https:' ? pushStream.rtcs : (pushStream.rtc || pushStream.rtcs)
      if (!url) {
        console.warn('[ChAudioTalk] 未找到RTC推流地址')
        return
      }

      this.$store.dispatch('user/getUserInfo').then(user => {
        if (user && user.pushKey) {
          url += '&sign=' + user.pushKey
        } else {
          console.warn('[ChAudioTalk] 未获取到pushKey，推流鉴权可能失败')
        }

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

        this.broadcastRtc.on(ZLMRTCClient.Events.WEBRTC_NOT_SUPPORT, () => {
          this.$message({ showClose: true, message: '不支持WebRTC, 无法进行语音对讲', type: 'error' })
          this.talkStatus = -1
        })
        this.broadcastRtc.on(ZLMRTCClient.Events.WEBRTC_ICE_CANDIDATE_ERROR, () => {
          this.$message({ showClose: true, message: 'ICE协商出错', type: 'error' })
          this.talkStatus = -1
        })
        this.broadcastRtc.on(ZLMRTCClient.Events.WEBRTC_OFFER_ANWSER_EXCHANGE_FAILED, () => {
          this.$message({ showClose: true, message: 'offer/answer交换失败', type: 'error' })
          this.talkStatus = -1
        })
        this.broadcastRtc.on(ZLMRTCClient.Events.WEBRTC_ON_CONNECTION_STATE_CHANGE, (e) => {
          if (e === 'connecting') {
            this.talkStatus = 0
          } else if (e === 'connected') {
            this.talkStatus = 1
          } else if (e === 'disconnected') {
            this.talkStatus = -1
          }
        })
      }).catch(e => {
        console.warn('[ChAudioTalk] 获取用户pushKey失败', e)
        this.talkStatus = -1
      })
    },
    startTalkAudioPlay(playStream) {
      if (this.talkAudioRtc) {
        this.talkAudioRtc.close()
      }
      if (this.talkAudioRetryTimer) {
        clearTimeout(this.talkAudioRetryTimer)
      }

      const url = location.protocol === 'https:' ? playStream.rtcs : playStream.rtc
      if (!url) {
        console.warn('[ChAudioTalk] 无可用的设备音频播放地址')
        return
      }
      this.talkAudioRetryTimer = setTimeout(() => {
        this.pollMediaInfoAndPlay(playStream)
      }, 800)
    },
    async pollMediaInfoAndPlay(playStream) {
      try {
        const data = await this.$store.dispatch('server/getMediaInfo', {
          app: playStream.app,
          stream: playStream.stream,
          mediaServerId: playStream.mediaServerId
        })
        if (data) {
          const url = location.protocol === 'https:' ? playStream.rtcs : playStream.rtc
          this.startTalkAudioByRtc(url)
        } else {
          throw new Error('no data')
        }
      } catch (e) {
        if (this.talkStatus === 1 || this.talkStatus === 0) {
          this.talkAudioRetryTimer = setTimeout(() => {
            this.pollMediaInfoAndPlay(playStream)
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
        console.warn('[ChAudioTalk] 播放流offer失败:', e?.code, e?.msg)
        if (e && e.code == -400 && e.msg == '流不存在') {
          this.talkAudioRetryTimer = setTimeout(() => {
            this.startTalkAudioByRtc(url)
          }, 1000)
        }
      })

      this.talkAudioRtc.on(ZLMRTCClient.Events.WEBRTC_ON_REMOTE_STREAMS, () => {
        console.warn('[ChAudioTalk] 设备音频流到达')
        this.playConnected = true
      })

      this.talkAudioRtc.on(ZLMRTCClient.Events.WEBRTC_ICE_CANDIDATE_ERROR, () => {
        console.error('[ChAudioTalk] 音频播放ICE协商失败')
      })

      this.talkAudioRtc.on(ZLMRTCClient.Events.WEBRTC_ON_CONNECTION_STATE_CHANGE, (s) => {
        console.warn('[ChAudioTalk] 音频播放连接状态:', s)
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

      const storeName = 'commonChanel'
      const actionName = this.talkMode ? 'talkStop' : 'broadcastStop'
      try {
        await this.$store.dispatch(storeName + '/' + actionName, this.channelId)
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
