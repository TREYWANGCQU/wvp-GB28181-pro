<template>
  <div id="shareChannelAdd" style="background-color: #FFFFFF; display: grid; grid-template-columns: 83px minmax(0, 1fr);">
    <el-tabs v-model="hasShare" tab-position="left" style="" @tab-click="search">
      <el-tab-pane label="未共享" name="false" />
      <el-tab-pane label="已共享" name="true" />
    </el-tabs>
    <div style="padding: 0 2rem">
      <el-form :inline="true" size="mini">
        <el-form-item label="搜索">
          <el-input
            v-model="searchStr"
            style="margin-right: 1rem; width: auto;"
            size="mini"
            placeholder="关键字"
            prefix-icon="el-icon-search"
            clearable
            @input="search"
          />
        </el-form-item>
        <el-form-item label="在线状态">
          <el-select
            v-model="online"
            size="mini"
            style="width: 8rem; margin-right: 1rem;"
            placeholder="请选择"
            default-first-option
            @change="search"
          >
            <el-option label="全部" value="" />
            <el-option label="在线" value="true" />
            <el-option label="离线" value="false" />
          </el-select>
        </el-form-item>
        <el-form-item label="类型">
          <el-select
            v-model="channelType"
            size="mini"
            style="width: 8rem; margin-right: 1rem;"
            placeholder="请选择"
            default-first-option
            @change="search"
          >
            <el-option label="全部" value="" />
            <el-option v-for="item in Object.values($channelTypeList)" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button v-if="hasShare !=='true'" size="mini" type="primary" :loading="addLoading" @click="add()">
            添加
          </el-button>
          <el-button v-if="hasShare ==='true'" size="mini" type="danger" :loading="removeLoading" @click="remove()">
            移除
          </el-button>
          <el-button v-if="hasShare !=='true'" size="mini" :loading="addByDeviceLoading" @click="addByDevice()">按国标设备添加</el-button>
          <el-button v-if="hasShare !=='true'" size="mini" :loading="addByOnvifDeviceLoading" @click="addByOnvifDevice()">按ONVIF设备添加</el-button>
          <el-button v-if="hasShare ==='true'" size="mini" :loading="removeByDeviceLoading" @click="removeByDevice()">按国标设备移除</el-button>
          <el-button v-if="hasShare ==='true'" size="mini" :loading="removeByOnvifDeviceLoading" @click="removeByOnvifDevice()">按ONVIF设备移除</el-button>
          <el-button v-if="hasShare !=='true'" size="mini" :loading="addAllLoading" @click="addAll()">全部添加</el-button>
          <el-button v-if="hasShare ==='true'" size="mini" :loading="removeAllLoading" @click="removeAll()">全部移除</el-button>
          <el-button v-if="hasShare ==='true'" size="mini" icon="el-icon-download" :loading="exportLoading" @click="handleExport()">导出编码映射</el-button>
          <el-button v-if="hasShare ==='true'" size="mini" type="primary" icon="el-icon-upload2" @click="handleImport()">批量导入修改</el-button>
        </el-form-item>
        <el-form-item style="float: right;">
          <el-button icon="el-icon-refresh-right" circle @click="getChannelList()" />
        </el-form-item>
      </el-form>
      <el-table
        ref="channelListTable"
        size="small"
        :data="channelList"
        :height="winHeight"
        header-row-class-name="table-header"
        @selection-change="handleSelectionChange"
      >
        <el-table-column type="selection" width="55" :selectable="selectable" />
        <el-table-column prop="gbName" label="名称" min-width="180" />
        <el-table-column prop="gbDeviceId" label="编号" min-width="180" />
        <el-table-column v-if="hasShare ==='true'" label="自定义名称" min-width="180">
          <template v-slot:default="scope">
            <div slot="—" class="name-wrapper">
              <el-input v-model:value="scope.row.customName" size="mini" placeholder="不填按原名称" />
            </div>
          </template>
        </el-table-column>
        <el-table-column v-if="hasShare ==='true'" label="自定义编号" min-width="180">
          <template v-slot:default="scope">
            <div slot="—" class="name-wrapper">
              <el-input v-model:value="scope.row.customDeviceId" size="mini" placeholder="不填按原编号" />
            </div>
          </template>
        </el-table-column>
        <el-table-column v-if="hasShare ==='true'" label="" min-width="80">
          <template v-slot:default="scope">
            <el-button size="mini" type="primary" @click="saveCustom(scope.row)">保存
            </el-button>
          </template>
        </el-table-column>
        <el-table-column prop="gbManufacturer" label="厂家" min-width="100" />
        <el-table-column label="类型" min-width="100">
          <template v-slot:default="scope">
            <div slot="reference" class="name-wrapper">
              <el-tag size="medium" effect="plain" type="success" :style="$channelTypeList[scope.row.dataType].style">{{ $channelTypeList[scope.row.dataType].name }}</el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" min-width="100">
          <template v-slot:default="scope">
            <div slot="reference" class="name-wrapper">
              <el-tag v-if="scope.row.gbStatus === 'ON'" size="medium">在线</el-tag>
              <el-tag v-if="scope.row.gbStatus !== 'ON'" size="medium" type="info">离线</el-tag>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        style="text-align: right"
        :current-page="currentPage"
        :page-size="count"
        :page-sizes="[15, 25, 35, 50]"
        layout="total, sizes, prev, pager, next"
        :total="total"
        @size-change="handleSizeChange"
        @current-change="currentChange"
      />
      <gbDeviceSelect ref="gbDeviceSelect" />
      <onvifDeviceSelect ref="onvifDeviceSelect" />
      <importCustomChannel :visible.sync="importDialogVisible" :platform-id="platformId" @refresh="getChannelList" />
    </div>
  </div>
</template>

<script>

import gbDeviceSelect from '../../dialog/GbDeviceSelect.vue'
import onvifDeviceSelect from '../../dialog/OnvifDeviceSelect.vue'
import importCustomChannel from './importCustomChannel.vue'
import { exportCustomChannel } from '@/api/platform'

export default {
  name: 'ShareChannelAdd',
  components: { gbDeviceSelect, onvifDeviceSelect, importCustomChannel },
  props: ['platformId'],
  data() {
    return {
      channelList: [],
      searchStr: '',
      channelType: '',
      online: '',
      hasShare: 'false',
      winHeight: window.innerHeight - 300,
      currentPage: 1,
      count: 15,
      total: 0,
      loading: false,
      loadSnap: {},
      multipleSelection: [],
      addLoading: false,
      addByDeviceLoading: false,
      addByOnvifDeviceLoading: false,
      addAllLoading: false,
      removeLoading: false,
      removeByDeviceLoading: false,
      removeByOnvifDeviceLoading: false,
      removeAllLoading: false,
      exportLoading: false,
      importDialogVisible: false
    }
  },

  created() {
    this.initData()
  },
  destroyed() {},
  methods: {
    initData: function() {
      this.getChannelList()
    },
    currentChange: function(val) {
      this.currentPage = val
      this.initData()
    },
    handleSizeChange: function(val) {
      this.count = val
      this.getChannelList()
    },
    getChannelList: function() {
      this.$store.dispatch('platform/getChannelList', {
        page: this.currentPage,
        count: this.count,
        query: this.searchStr,
        online: this.online,
        channelType: this.channelType,
        platformId: this.platformId,
        hasShare: this.hasShare
      })
        .then(data => {
          this.total = data.total
          this.channelList = data.list
          // 防止出现表格错位
          this.$nextTick(() => {
            this.$refs.channelListTable.doLayout()
          })
        })
        .catch((error) => {
          console.log(error)
        })
    },
    handleSelectionChange: function(val) {
      this.multipleSelection = val
    },
    selectable: function(row, rowIndex) {
      if (this.hasShare === '') {
        if (row.platformId) {
          return false
        } else {
          return true
        }
      } else {
        return true
      }
    },
    add: function(row) {
      const channels = []
      for (let i = 0; i < this.multipleSelection.length; i++) {
        channels.push(this.multipleSelection[i].gbId)
      }
      if (channels.length === 0) {
        this.$message.info({
          showClose: true,
          message: '请选择通道'
        })
        return
      }
      this.addLoading = true
      this.$store.dispatch('platform/addChannel', {
        platformId: this.platformId,
        channelIds: channels
      })
        .then(() => {
          this.$message.success({
            showClose: true,
            message: '保存成功'
          })
          this.getChannelList()
        })
        .catch((error) => {
          this.$message.error({
            showClose: true,
            message: error
          })
        })
        .finally(() => {
          this.addLoading = false
        })
    },
    addAll: function(row) {
      this.$confirm('确定全部添加？', '提示', {
        dangerouslyUseHTMLString: true,
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
      }).then(() => {
        this.addAllLoading = true
        this.$store.dispatch('platform/addChannel', {
          platformId: this.platformId,
          all: true
        })
          .then(() => {
            this.$message.success({
              showClose: true,
              message: '保存成功'
            })
            this.getChannelList()
          })
          .catch((error) => {
            this.$message.error({
              showClose: true,
              message: error
            })
          })
          .finally(() => {
            this.addAllLoading = false
          })
      }).catch(() => {
      })
    },

    addByDevice: function(row) {
      this.$refs.gbDeviceSelect.openDialog((rows) => {
        const deviceIds = []
        for (let i = 0; i < rows.length; i++) {
          deviceIds.push(rows[i].id)
        }
        this.addByDeviceLoading = true
        this.$store.dispatch('platform/addChannelByDevice', {
          platformId: this.platformId,
          deviceIds: deviceIds
        })
          .then(() => {
            this.$message.success({
              showClose: true,
              message: '保存成功'
            })
            this.initData()
          })
          .catch((error) => {
            this.$message.error({
              showClose: true,
              message: error
            })
          })
          .finally(() => {
            this.addByDeviceLoading = false
          })
      })
    },

    removeByDevice: function(row) {
      this.$refs.gbDeviceSelect.openDialog((rows) => {
        const deviceIds = []
        for (let i = 0; i < rows.length; i++) {
          deviceIds.push(rows[i].id)
        }
        this.removeByDeviceLoading = true
        this.$store.dispatch('platform/removeChannelByDevice', {
          platformId: this.platformId,
          deviceIds: deviceIds
        })
          .then(() => {
            this.$message.success({
              showClose: true,
              message: '保存成功'
            })
            this.initData()
          })
          .catch((error) => {
            this.$message.error({
              showClose: true,
              message: error
            })
          })
          .finally(() => {
            this.removeByDeviceLoading = false
          })
      })
    },

    addByOnvifDevice: function(row) {
      this.$refs.onvifDeviceSelect.openDialog((rows) => {
        const deviceIds = []
        for (let i = 0; i < rows.length; i++) {
          deviceIds.push(rows[i].id)
        }
        if (deviceIds.length === 0) return
        this.addByOnvifDeviceLoading = true
        this.$store.dispatch('platform/addChannelByDevice', {
          platformId: this.platformId,
          deviceIds: deviceIds,
          dataType: 4
        })
          .then(() => {
            this.$message.success({
              showClose: true,
              message: '保存成功'
            })
            this.initData()
          })
          .catch((error) => {
            this.$message.error({
              showClose: true,
              message: error
            })
          })
          .finally(() => {
            this.addByOnvifDeviceLoading = false
          })
      })
    },

    removeByOnvifDevice: function(row) {
      this.$refs.onvifDeviceSelect.openDialog((rows) => {
        const deviceIds = []
        for (let i = 0; i < rows.length; i++) {
          deviceIds.push(rows[i].id)
        }
        if (deviceIds.length === 0) return
        this.removeByOnvifDeviceLoading = true
        this.$store.dispatch('platform/removeChannelByDevice', {
          platformId: this.platformId,
          deviceIds: deviceIds,
          dataType: 4
        })
          .then(() => {
            this.$message.success({
              showClose: true,
              message: '保存成功'
            })
            this.initData()
          })
          .catch((error) => {
            this.$message.error({
              showClose: true,
              message: error
            })
          })
          .finally(() => {
            this.removeByOnvifDeviceLoading = false
          })
      })
    },
    remove: function(row) {
      const channels = []
      for (let i = 0; i < this.multipleSelection.length; i++) {
        channels.push(this.multipleSelection[i].gbId)
      }
      if (channels.length === 0) {
        this.$message.info({
          showClose: true,
          message: '请选择通道'
        })
        return
      }
      this.removeLoading = true
      this.$store.dispatch('platform/removeChannel', {
        platformId: this.platformId,
        channelIds: channels
      })
        .then(() => {
          this.$message.success({
            showClose: true,
            message: '保存成功'
          })
          this.getChannelList()
        })
        .catch((error) => {
          this.$message.error({
            showClose: true,
            message: error
          })
        })
        .finally(() => {
          this.removeLoading = false
        })
    },
    removeAll: function(row) {
      this.$confirm('确定全部移除？', '提示', {
        dangerouslyUseHTMLString: true,
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
      }).then(() => {
        this.removeAllLoading = true
        this.$store.dispatch('platform/removeChannel', {
          platformId: this.platformId,
          all: true
        })
          .then(() => {
            this.$message.success({
              showClose: true,
              message: '保存成功'
            })
            this.getChannelList()
          })
          .catch((error) => {
            this.$message.error({
              showClose: true,
              message: error
            })
          })
          .finally(() => {
            this.removeAllLoading = false
          })
      }).catch(() => {
      })
    },
    saveCustom: function(row) {
      this.$store.dispatch('platform/updateCustomChannel', row)
        .then(() => {
          this.$message.success({
            showClose: true,
            message: '保存成功'
          })
          this.initData()
        })
        .catch((error) => {
          this.$message.error({
            showClose: true,
            message: error
          })
        })

    },
    search: function() {
      this.currentPage = 1
      this.total = 0
      this.initData()
    },
    refresh: function() {
      this.initData()
    },
    handleExport: function() {
      if (!this.platformId) {
        this.$message.warning('缺少平台ID')
        return
      }
      this.exportLoading = true
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
          this.exportLoading = false
        })
    },
    handleImport: function() {
      this.importDialogVisible = true
    }
  }
}
</script>
