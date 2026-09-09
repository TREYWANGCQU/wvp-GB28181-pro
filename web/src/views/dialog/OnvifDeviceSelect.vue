<!-- web/src/views/dialog/OnvifDeviceSelect.vue -->
<template>
  <div id="onvifDeviceSelect" v-loading="loading">
    <el-dialog
      v-el-drag-dialog
      title="添加 ONVIF 设备通道"
      width="60%"
      top="2rem"
      :close-on-click-modal="false"
      :visible.sync="showDialog"
      :destroy-on-close="true"
      append-to-body
      @close="close"
    >
      <el-form :inline="true" size="mini">
        <el-form-item label="搜索">
          <el-input
            v-model="searchStr"
            style="margin-right: 1rem; width: auto;"
            size="mini"
            placeholder="名称 / IP / 厂商"
            prefix-icon="el-icon-search"
            clearable
            @input="getDeviceList"
          />
        </el-form-item>
        <el-form-item label="在线状态">
          <el-select
            v-model="online"
            size="mini"
            style="width: 8rem; margin-right: 1rem;"
            placeholder="请选择"
            clearable
            @change="getDeviceList"
          >
            <el-option label="全部" :value="null" />
            <el-option label="在线" :value="1" />
            <el-option label="离线" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item style="float: right;">
          <el-button icon="el-icon-refresh-right" circle @click="getDeviceList" />
          <el-button type="primary" @click="onSubmit">确 定</el-button>
        </el-form-item>
      </el-form>

      <!-- 设备列表 -->
      <el-table
        size="medium"
        :data="deviceList"
        style="width: 100%; font-size: 12px;"
        :height="winHeight"
        header-row-class-name="table-header"
        @selection-change="handleSelectionChange"
      >
        <el-table-column type="selection" width="55" />
        <el-table-column prop="name" label="设备名称" min-width="160" />
        <el-table-column prop="ip" label="IP 地址" min-width="140" align="center" />
        <el-table-column prop="port" label="端口" width="90" align="center" />
        <el-table-column prop="manufacturer" label="厂商" min-width="120" align="center" />
        <el-table-column prop="model" label="型号" min-width="120" align="center" />
        <el-table-column label="状态" width="100" align="center">
          <template v-slot:default="scope">
            <el-tag size="small" :type="scope.row.status === 1 ? 'success' : 'danger'">
              {{ scope.row.status === 1 ? '在线' : '离线' }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        style="text-align: right; margin-top: 15px;"
        :current-page="currentPage"
        :page-size="count"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        :total="total"
        @size-change="handleSizeChange"
        @current-change="currentChange"
      />
    </el-dialog>
  </div>
</template>

<script>
import elDragDialog from '@/directive/el-drag-dialog'
import { getOnvifDeviceList } from '@/api/onvif'

export default {
  name: 'OnvifDeviceSelect',
  directives: { elDragDialog },
  data() {
    return {
      showDialog: false,
      deviceList: [],
      searchStr: '',
      online: null,
      winHeight: 480,
      currentPage: 1,
      count: 10,
      total: 0,
      loading: false,
      multipleSelection: [],
      listChangeCallback: null
    }
  },
  methods: {
    getDeviceList() {
      this.loading = true
      getOnvifDeviceList({
        page: this.currentPage,
        count: this.count,
        query: this.searchStr,
        status: this.online
      }).then(res => {
        this.total = res.data.total
        this.deviceList = res.data.list
      }).finally(() => {
        this.loading = false
      })
    },
    currentChange(val) {
      this.currentPage = val
      this.getDeviceList()
    },
    handleSizeChange(val) {
      this.count = val
      this.getDeviceList()
    },
    handleSelectionChange(val) {
      this.multipleSelection = val
    },
    openDialog(callback) {
      this.listChangeCallback = callback
      this.showDialog = true
      this.getDeviceList()
    },
    onSubmit() {
      if (this.listChangeCallback) {
        this.listChangeCallback(this.multipleSelection)
      }
      this.showDialog = false
    },
    close() {
      this.showDialog = false
    }
  }
}
</script>

<style scoped>
.el-dialog__body {
  padding: 20px;
}
</style>
