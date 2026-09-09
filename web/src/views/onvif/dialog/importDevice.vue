<!-- web/src/views/onvif/dialog/importDevice.vue -->
<template>
  <el-dialog
    v-el-drag-dialog
    title="批量导入 ONVIF 设备"
    :visible.sync="dialogVisible"
    width="560px"
    :close-on-click-modal="false"
    @close="handleClose"
  >
    <div v-loading="loading" element-loading-text="正在上传并连接设备探测纳管中...">
      <div style="margin-bottom: 16px; font-size: 13px; color: #606266; line-height: 1.6;">
        请先下载标准导入模板，按照格式填写设备 IP、端口、鉴权凭据及可选的国标编码后拖拽上传。
        <div style="margin-top: 8px;">
          <el-button type="primary" size="mini" icon="el-icon-download" @click="handleDownloadTemplate">
            下载导入模板 (.xlsx)
          </el-button>
        </div>
      </div>

      <el-upload
        class="upload-demo"
        drag
        action=""
        :http-request="handleUpload"
        :show-file-list="false"
        accept=".xlsx,.xls"
      >
        <i class="el-icon-upload" />
        <div class="el-upload__text">将 Excel 文件拖到此处，或<em>点击上传</em></div>
        <div slot="tip" class="el-upload__tip">只能上传 xlsx/xls 文件，单次建议不超过 50 台设备</div>
      </el-upload>

      <!-- 结果展示区 -->
      <div v-if="result" style="margin-top: 20px;">
        <el-alert
          :title="`导入完成：共 ${result.total} 台，成功纳管 ${result.success} 台，失败 ${result.failure} 台`"
          :type="result.failure > 0 ? 'warning' : 'success'"
          show-icon
          :closable="false"
        />

        <div v-if="result.errorMessages && result.errorMessages.length > 0" style="margin-top: 12px;">
          <div style="font-size: 13px; font-weight: bold; color: #F56C6C; margin-bottom: 6px;">异常清单：</div>
          <el-scrollbar style="max-height: 140px; border: 1px solid #ebeef5; border-radius: 4px; padding: 6px 10px;">
            <div
              v-for="(msg, index) in result.errorMessages"
              :key="index"
              style="font-size: 12px; color: #f56c6c; line-height: 1.5; padding: 2px 0;"
            >
              • {{ msg }}
            </div>
          </el-scrollbar>
        </div>
      </div>
    </div>

    <div slot="footer" class="dialog-footer">
      <el-button size="small" @click="handleClose">关 闭</el-button>
      <el-button v-if="result && result.success > 0" size="small" type="primary" @click="handleConfirm">完成并刷新</el-button>
    </div>
  </el-dialog>
</template>

<script>
import elDragDialog from '@/directive/el-drag-dialog'
import { importOnvifDevices } from '@/api/onvif'

export default {
  name: 'ImportDevice',
  directives: { elDragDialog },
  props: {
    visible: {
      type: Boolean,
      default: false
    }
  },
  data() {
    return {
      loading: false,
      result: null
    }
  },
  computed: {
    dialogVisible: {
      get() {
        return this.visible
      },
      set(val) {
        this.$emit('update:visible', val)
      }
    }
  },
  methods: {
    handleDownloadTemplate() {
      window.open('/api/onvif/device/import/template', '_blank')
    },
    handleUpload(param) {
      const file = param.file
      if (!file) return

      const formData = new FormData()
      formData.append('file', file)

      this.loading = true
      this.result = null

      importOnvifDevices(formData)
        .then(res => {
          this.result = res.data
          if (this.result.failure === 0) {
            this.$message.success(`成功导入 ${this.result.success} 台设备`)
          } else {
            this.$message.warning(`导入完成，有 ${this.result.failure} 台设备失败`)
          }
        })
        .catch(err => {
          this.$message.error(typeof err === 'string' ? err : (err && err.msg) || '导入失败')
        })
        .finally(() => {
          this.loading = false
        })
    },
    handleClose() {
      this.dialogVisible = false
      this.result = null
    },
    handleConfirm() {
      this.$emit('success')
      this.handleClose()
    }
  }
}
</script>

<style scoped>
.upload-demo {
  text-align: center;
}
.upload-demo >>> .el-upload {
  width: 100%;
}
.upload-demo >>> .el-upload-dragger {
  width: 100%;
}
</style>
