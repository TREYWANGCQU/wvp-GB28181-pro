<!-- web/src/views/platform/dialog/importCustomChannel.vue -->
<template>
  <el-dialog
    v-el-drag-dialog
    title="批量导入级联通道国标编码"
    :visible.sync="dialogVisible"
    width="580px"
    :close-on-click-modal="false"
    append-to-body
    @close="handleClose"
  >
    <div v-loading="loading" element-loading-text="正在校验并批量更新国标编码中...">
      <div style="margin-bottom: 16px; font-size: 13px; color: #606266; line-height: 1.6;">
        请先导出当前平台的通道编码映射表，在 Excel 中填写 20 位自定义国标编号（支持留空恢复原编码）及选填的自定义名称，然后拖拽上传批量覆盖。
        <div style="margin-top: 8px; display: flex; align-items: center;">
          <el-button type="primary" size="mini" icon="el-icon-download" :loading="downloading" @click="handleDownloadTemplate">
            导出当前映射表 (.xlsx)
          </el-button>
          <span style="color: #909399; font-size: 12px; margin-left: 12px;">
            提示：请勿修改表格第一列的映射ID
          </span>
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
        <div slot="tip" class="el-upload__tip">只能上传 xlsx/xls 文件，自定义国标编号必须为 20 位纯数字</div>
      </el-upload>

      <!-- 结果展示区 -->
      <div v-if="result" style="margin-top: 20px;">
        <el-alert
          :title="`导入完成：共处理 ${result.total} 条，成功更新 ${result.success} 条，失败 ${result.failure} 条`"
          :type="result.failure > 0 ? 'warning' : 'success'"
          show-icon
          :closable="false"
        />

        <div v-if="result.errorMessages && result.errorMessages.length > 0" style="margin-top: 12px;">
          <div style="font-size: 13px; font-weight: bold; color: #F56C6C; margin-bottom: 6px;">异常行明细：</div>
          <el-scrollbar style="max-height: 150px; border: 1px solid #ebeef5; border-radius: 4px; padding: 6px 10px;">
            <div
              v-for="(msg, index) in result.errorMessages"
              :key="index"
              style="font-size: 12px; color: #f56c6c; line-height: 1.6; padding: 2px 0;"
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
import { exportCustomChannel, importCustomChannel } from '@/api/platform'

export default {
  name: 'ImportCustomChannel',
  directives: { elDragDialog },
  props: {
    visible: {
      type: Boolean,
      default: false
    },
    platformId: {
      type: [Number, String],
      required: true
    }
  },
  data() {
    return {
      loading: false,
      downloading: false,
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
      if (!this.platformId) {
        this.$message.warning('缺少平台ID')
        return
      }
      this.downloading = true
      exportCustomChannel(this.platformId)
        .then(response => {
          const blob = new Blob([response.data || response], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' })
          const link = document.createElement('a')
          link.href = window.URL.createObjectURL(blob)
          link.download = `级联平台_${this.platformId}_通道编码映射表.xlsx`
          link.click()
          window.URL.revokeObjectURL(link.href)
          this.$message.success('导出映射表成功')
        })
        .catch(err => {
          this.$message.error('导出映射表失败: ' + (err.message || err))
        })
        .finally(() => {
          this.downloading = false
        })
    },

    handleUpload(param) {
      const file = param.file
      const isExcel = file.name.endsWith('.xlsx') || file.name.endsWith('.xls')
      if (!isExcel) {
        this.$message.error('只允许上传 Excel (.xlsx / .xls) 文件')
        return
      }

      const formData = new FormData()
      formData.append('file', file)
      formData.append('platformId', this.platformId)

      this.loading = true
      this.result = null

      importCustomChannel(formData)
        .then(res => {
          const data = res.data || res
          this.result = data
          if (data.failure === 0) {
            this.$message.success(`批量更新成功，共更新 ${data.success} 条通道`)
          } else {
            this.$message.warning(`导入更新完成，部分存在异常 (${data.success} 成功, ${data.failure} 失败)`)
          }
        })
        .catch(err => {
          this.$message.error('批量导入更新失败: ' + (err.message || err))
        })
        .finally(() => {
          this.loading = false
        })
    },

    handleConfirm() {
      this.$emit('refresh')
      this.handleClose()
    },

    handleClose() {
      this.result = null
      this.dialogVisible = false
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
  height: 160px;
}
</style>
