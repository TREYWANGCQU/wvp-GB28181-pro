<!-- web/src/views/onvif/ptzController.vue -->
<template>
  <div style="padding: 20px; text-align: center;">
    <div style="margin-bottom: 15px; font-weight: bold; color: #303133;">
      {{ channel ? channel.name : '云台控制盘' }}
    </div>

    <!-- 八向方向罗盘 -->
    <div class="ptz-compass">
      <div class="ptz-row">
        <el-button icon="el-icon-top-left" @mousedown.native="ptzMove(0, 0)" @mouseup.native="ptzStop" />
        <el-button icon="el-icon-top" @mousedown.native="ptzMove(null, 0)" @mouseup.native="ptzStop" />
        <el-button icon="el-icon-top-right" @mousedown.native="ptzMove(1, 0)" @mouseup.native="ptzStop" />
      </div>
      <div class="ptz-row">
        <el-button icon="el-icon-back" @mousedown.native="ptzMove(0, null)" @mouseup.native="ptzStop" />
        <el-button type="danger" icon="el-icon-video-pause" @click="ptzStop" />
        <el-button icon="el-icon-right" @mousedown.native="ptzMove(1, null)" @mouseup.native="ptzStop" />
      </div>
      <div class="ptz-row">
        <el-button icon="el-icon-bottom-left" @mousedown.native="ptzMove(0, 1)" @mouseup.native="ptzStop" />
        <el-button icon="el-icon-bottom" @mousedown.native="ptzMove(null, 1)" @mouseup.native="ptzStop" />
        <el-button icon="el-icon-bottom-right" @mousedown.native="ptzMove(1, 1)" @mouseup.native="ptzStop" />
      </div>
    </div>

    <!-- 变倍缩放 -->
    <div style="margin: 20px 0; display: flex; justify-content: center; gap: 15px;">
      <el-button type="info" size="small" icon="el-icon-zoom-out" @mousedown.native="ptzZoom(0)" @mouseup.native="ptzStop">缩小</el-button>
      <el-button type="info" size="small" icon="el-icon-zoom-in" @mousedown.native="ptzZoom(1)" @mouseup.native="ptzStop">放大</el-button>
    </div>

    <!-- 转动速度滑块 -->
    <div style="margin-top: 15px; text-align: left;">
      <span style="font-size: 13px; color: #606266;">转动速度：{{ speed }}</span>
      <el-slider v-model="speed" :min="10" :max="255" :step="5" />
    </div>
  </div>
</template>

<script>
import request from '@/utils/request'

export default {
  name: 'PtzController',
  props: { channel: { type: Object, default: () => null }},
  data() {
    return {
      speed: 128
    }
  },
  methods: {
    ptzMove(pan, tilt) {
      if (!this.channel) return
      request({
        url: `/api/ptz/ptz/${this.channel.gbDeviceId}`,
        method: 'post',
        params: {
          pan: pan,
          tilt: tilt,
          panSpeed: this.speed,
          tiltSpeed: this.speed
        }
      })
    },
    ptzZoom(zoom) {
      if (!this.channel) return
      request({
        url: `/api/ptz/ptz/${this.channel.gbDeviceId}`,
        method: 'post',
        params: {
          zoom: zoom,
          zoomSpeed: this.speed
        }
      })
    },
    ptzStop() {
      if (!this.channel) return
      request({
        url: `/api/ptz/ptz/${this.channel.gbDeviceId}`,
        method: 'post',
        params: {} // 全空即 Stop
      })
    }
  }
}
</script>

<style scoped>
.ptz-compass {
  display: inline-block;
}
.ptz-row {
  display: flex;
  justify-content: center;
  margin-bottom: 6px;
  gap: 6px;
}
</style>
