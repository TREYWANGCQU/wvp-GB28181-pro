<!-- web/src/views/onvif/index.vue -->
<template>
  <div class="app-container">
    <!-- 顶部操作栏 -->
    <div class="filter-container" style="display: flex; gap: 10px; margin-bottom: 20px;">
      <el-input
        v-model="listQuery.query"
        placeholder="设备名称 / IP / 厂商"
        style="width: 260px;"
        clearable
        @keyup.enter.native="fetchData"
      />
      <el-select v-model="listQuery.status" placeholder="在线状态" clearable style="width: 130px;">
        <el-option label="在线" :value="1" />
        <el-option label="离线" :value="0" />
      </el-select>
      <el-button type="primary" icon="el-icon-search" @click="fetchData">查询</el-button>
      <el-button type="success" icon="el-icon-plus" @click="handleAddManual">手动添加</el-button>
      <el-button type="warning" icon="el-icon-radar" @click="handleOpenDiscovery">局域网搜寻</el-button>
    </div>

    <!-- 设备主表格 -->
    <el-table
      v-loading="listLoading"
      :data="deviceList"
      border
      fit
      highlight-current-row
      style="width: 100%"
      @expand-change="handleExpandChange"
    >
      <!-- 通道展开行 -->
      <el-table-column type="expand">
        <template slot-scope="props">
          <div style="padding: 10px 20px; background: #fafafa;">
            <div style="font-weight: bold; margin-bottom: 10px;">码流 Profile 通道列表：</div>
            <el-table :data="props.row.channels || []" size="mini" border>
              <el-table-column prop="channelIndex" label="镜头" width="60" align="center" />
              <el-table-column prop="name" label="Profile 名称" width="180" />
              <el-table-column prop="videoEncoding" label="编码" width="90" align="center" />
              <el-table-column prop="resolution" label="分辨率" width="120" align="center" />
              <el-table-column prop="gbDeviceId" label="挂接国标编码" width="220" />
              <el-table-column prop="hasPtz" label="支持PTZ" width="90" align="center">
                <template slot-scope="scope">
                  <el-tag size="mini" :type="scope.row.hasPtz === 1 ? 'success' : 'info'">
                    {{ scope.row.hasPtz === 1 ? '是' : '否' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="rtspUrl" label="RTSP 地址" show-overflow-tooltip />
              <el-table-column label="操作" width="160" align="center">
                <template slot-scope="scope">
                  <el-button size="mini" type="primary" icon="el-icon-video-play" @click="handlePlay(scope.row)">点播</el-button>
                  <el-button v-if="scope.row.hasPtz === 1" size="mini" type="warning" icon="el-icon-coordinate" @click="handlePtz(props.row, scope.row)">云台</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </template>
      </el-table-column>

      <el-table-column prop="id" label="ID" width="70" align="center" />
      <el-table-column prop="name" label="设备名称" min-width="150" />
      <el-table-column prop="ip" label="IPv4 地址" width="140" align="center" />
      <el-table-column prop="port" label="端口" width="90" align="center" />
      <el-table-column prop="manufacturer" label="厂商" width="120" align="center" />
      <el-table-column prop="model" label="型号" width="120" align="center" />
      <el-table-column prop="status" label="状态" width="90" align="center">
        <template slot-scope="scope">
          <el-tag size="small" :type="scope.row.status === 1 ? 'success' : 'danger'">
            {{ scope.row.status === 1 ? '在线' : '离线' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="220" align="center">
        <template slot-scope="scope">
          <el-button size="mini" type="text" icon="el-icon-refresh" @click="handleSync(scope.row)">同步Profile</el-button>
          <el-button size="mini" type="text" style="color: #F56C6C;" icon="el-icon-delete" @click="handleDelete(scope.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 分页器 -->
    <el-pagination
      style="margin-top: 20px;"
      :current-page="listQuery.page"
      :page-sizes="[10, 20, 50]"
      :page-size="listQuery.count"
      :total="total"
      layout="total, sizes, prev, pager, next, jumper"
      @size-change="handleSizeChange"
      @current-change="handleCurrentChange"
    />

    <!-- 弹窗：局域网一键搜寻 -->
    <device-discovery v-if="discoveryVisible" :visible.sync="discoveryVisible" @success="fetchData" />

    <!-- 弹窗：手动添加设备 -->
    <el-dialog title="接入 ONVIF 设备" :visible.sync="dialogAddVisible" width="500px">
      <el-form ref="dataForm" :model="tempDevice" :rules="rules" label-width="110px">
        <el-form-item label="设备名称" prop="name">
          <el-input v-model="tempDevice.name" placeholder="例: 办公室枪机" />
        </el-form-item>
        <el-form-item label="IP 地址" prop="ip">
          <el-input v-model="tempDevice.ip" placeholder="192.168.1.108" />
        </el-form-item>
        <el-form-item label="服务端口" prop="port">
          <el-input-number v-model="tempDevice.port" :min="1" :max="65535" />
        </el-form-item>
        <el-form-item label="用户名" prop="username">
          <el-input v-model="tempDevice.username" placeholder="admin" />
        </el-form-item>
        <el-form-item label="认证密码" prop="password">
          <el-input v-model="tempDevice.password" type="password" show-password />
        </el-form-item>
      </el-form>
      <div slot="footer">
        <el-button @click="dialogAddVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitLoading" @click="submitAdd">确定</el-button>
      </div>
    </el-dialog>

    <!-- 抽屉：云台控制盘 -->
    <el-drawer title="云台控制" :visible.sync="ptzDrawerVisible" size="360px">
      <ptz-controller v-if="ptzDrawerVisible" :channel="currentChannel" />
    </el-drawer>
  </div>
</template>

<script>
import { getOnvifDeviceList, addOnvifDevice, deleteOnvifDevice, syncOnvifChannels, getOnvifChannels } from '@/api/onvif'
import DeviceDiscovery from './deviceDiscovery.vue'
import PtzController from './ptzController.vue'

export default {
  name: 'OnvifDeviceIndex',
  components: { DeviceDiscovery, PtzController },
  data() {
    return {
      listLoading: false,
      submitLoading: false,
      deviceList: [],
      total: 0,
      listQuery: { page: 1, count: 10, query: '', status: null },
      dialogAddVisible: false,
      discoveryVisible: false,
      ptzDrawerVisible: false,
      currentChannel: null,
      tempDevice: { name: '', ip: '', port: 80, username: 'admin', password: '' },
      rules: {
        name: [{ required: true, message: '请输入设备名称', trigger: 'blur' }],
        ip: [{ required: true, message: '请输入 IP 地址', trigger: 'blur' }],
        port: [{ required: true, message: '请输入服务端口', trigger: 'blur' }],
        username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
        password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
      }
    }
  },
  created() {
    this.fetchData()
  },
  methods: {
    fetchData() {
      this.listLoading = true
      getOnvifDeviceList(this.listQuery).then(res => {
        this.deviceList = res.data.list.map(d => ({ ...d, channels: [] }))
        this.total = res.data.total
        this.listLoading = false
      }).catch(() => { this.listLoading = false })
    },
    handleExpandChange(row, expanded) {
      if (expanded && (!row.channels || row.channels.length === 0)) {
        getOnvifChannels(row.id).then(res => {
          row.channels = res.data
        })
      }
    },
    handleAddManual() {
      this.tempDevice = { name: '', ip: '', port: 80, username: 'admin', password: '' }
      this.dialogAddVisible = true
    },
    submitAdd() {
      this.$refs.dataForm.validate(valid => {
        if (valid) {
          this.submitLoading = true
          addOnvifDevice(this.tempDevice).then(() => {
            this.$message.success('接入成功并已完成 Profile 解析')
            this.dialogAddVisible = false
            this.submitLoading = false
            this.fetchData()
          }).catch(() => { this.submitLoading = false })
        }
      })
    },
    handleOpenDiscovery() {
      this.discoveryVisible = true
    },
    handleSync(row) {
      syncOnvifChannels(row.id).then(() => {
        this.$message.success('重新同步 Profile 成功')
        getOnvifChannels(row.id).then(res => { row.channels = res.data })
      })
    },
    handleDelete(row) {
      this.$confirm(`确认删除设备 "${row.name}" 及其所有通道吗？`, '警告', { type: 'warning' }).then(() => {
        deleteOnvifDevice(row.id).then(() => {
          this.$message.success('删除成功')
          this.fetchData()
        })
      })
    },
    handlePlay(channel) {
      // 路由跳转至分屏监控或弹窗播放器
      this.$router.push({ path: '/live', query: { channelId: channel.gbDeviceId }})
    },
    handlePtz(device, channel) {
      this.currentChannel = channel
      this.ptzDrawerVisible = true
    },
    handleSizeChange(val) { this.listQuery.count = val; this.fetchData() },
    handleCurrentChange(val) { this.listQuery.page = val; this.fetchData() }
  }
}
</script>
