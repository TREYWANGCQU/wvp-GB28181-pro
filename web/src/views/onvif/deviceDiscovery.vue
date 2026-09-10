<!-- web/src/views/onvif/deviceDiscovery.vue -->
<template>
  <el-dialog title="局域网 ONVIF 设备搜寻向导" :visible="visible" width="800px" @close="handleClose">
    <div style="margin-bottom: 15px; display: flex; justify-content: space-between; align-items: center;">
      <div>
        <el-button type="primary" icon="el-icon-video-camera" :loading="scanning" @click="startScan">
          {{ scanning ? '正在搜索局域网摄像机...' : '开始重新搜寻 (WS-Discovery)' }}
        </el-button>
        <span style="margin-left: 10px; color: #909399; font-size: 13px;">通过 UDP 239.255.255.250:3702 自动探测</span>
      </div>
      <div style="display: flex; gap: 8px;">
        <el-button type="primary" icon="el-icon-download" :disabled="multipleSelection.length === 0" :loading="exporting" @click="handleExportSelected">
          批量导出选中 ({{ multipleSelection.length }})
        </el-button>
        <el-button type="success" icon="el-icon-plus" :disabled="multipleSelection.length === 0" @click="batchImport">
          批量接入选中 ({{ multipleSelection.length }})
        </el-button>
      </div>
    </div>

    <!-- 发现结果列表 -->
    <el-table :data="foundDevices" border size="small" @selection-change="handleSelectionChange">
      <el-table-column type="selection" width="50" align="center" />
      <el-table-column prop="ip" label="IP 地址" width="140" align="center" />
      <el-table-column prop="port" label="端口" width="80" align="center" />
      <el-table-column prop="manufacturer" label="厂商" width="120" align="center" />
      <el-table-column prop="model" label="型号" width="120" align="center" />
      <el-table-column prop="deviceServiceUrl" label="服务地址" show-overflow-tooltip />
    </el-table>

    <!-- 批量录入鉴权凭据对话框 -->
    <el-dialog title="批量接入凭据设定" :visible.sync="batchDialogVisible" append-to-body width="420px">
      <el-form label-width="90px">
        <el-form-item label="统一用户名">
          <el-input v-model="batchAuth.username" placeholder="admin" />
        </el-form-item>
        <el-form-item label="统一密码">
          <el-input v-model="batchAuth.password" type="password" show-password />
        </el-form-item>
      </el-form>
      <div slot="footer">
        <el-button @click="batchDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="importing" @click="executeBatchImport">确认接入</el-button>
      </div>
    </el-dialog>
  </el-dialog>
</template>

<script>
import { probeOnvifDevices, addOnvifDevice, exportOnvifDevices } from '@/api/onvif'

export default {
  name: 'DeviceDiscovery',
  props: { visible: { type: Boolean, default: false }},
  data() {
    return {
      scanning: false,
      importing: false,
      exporting: false,
      foundDevices: [],
      multipleSelection: [],
      batchDialogVisible: false,
      batchAuth: { username: 'admin', password: '' }
    }
  },
  mounted() {
    this.startScan()
  },
  methods: {
    startScan() {
      this.scanning = true
      probeOnvifDevices(3).then(res => {
        this.foundDevices = res.data || []
        this.scanning = false
        if (this.foundDevices.length === 0) {
          this.$message.info('未在当前局域网搜寻到 ONVIF 广播，请确认摄像头已通电且位于同网段')
        }
      }).catch(() => { this.scanning = false })
    },
    handleSelectionChange(val) {
      this.multipleSelection = val
    },
    handleExportSelected() {
      if (this.multipleSelection.length === 0) {
        this.$message.warning('请先勾选需要导出的设备')
        return
      }
      this.exporting = true
      const customDevices = this.multipleSelection.map(dev => ({
        name: dev.name || ((dev.manufacturer || 'ONVIF') + '-' + dev.ip),
        ip: dev.ip,
        port: dev.port || 80,
        username: 'admin',
        password: '',
        mediaServerId: '',
        gbDeviceId: '',
        civilCode: ''
      }))

      exportOnvifDevices({ customDevices })
        .then(blob => {
          const blobUrl = window.URL.createObjectURL(new Blob([blob], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }))
          const link = document.createElement('a')
          link.href = blobUrl
          link.download = 'onvif_discovered_devices.xlsx'
          document.body.appendChild(link)
          link.click()
          document.body.removeChild(link)
          window.URL.revokeObjectURL(blobUrl)
          this.$message.success(`成功导出 ${customDevices.length} 台设备至 Excel`)
        })
        .catch(err => {
          console.error(err)
          this.$message.error('导出失败: ' + (err.message || err))
        })
        .finally(() => {
          this.exporting = false
        })
    },
    batchImport() {
      this.batchDialogVisible = true
    },
    async executeBatchImport() {
      this.importing = true
      let count = 0
      for (const dev of this.multipleSelection) {
        try {
          await addOnvifDevice({
            name: dev.name || (dev.manufacturer + '-' + dev.ip),
            ip: dev.ip,
            port: dev.port,
            username: this.batchAuth.username,
            password: this.batchAuth.password,
            deviceServiceUrl: dev.deviceServiceUrl
          })
          count++
        } catch (e) {
          console.error(e)
        }
      }
      this.importing = false
      this.batchDialogVisible = false
      this.$message.success(`成功导入 ${count} 台设备`)
      this.$emit('success')
      this.handleClose()
    },
    handleClose() {
      this.$emit('update:visible', false)
    }
  }
}
</script>
