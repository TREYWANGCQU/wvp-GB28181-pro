<!-- doc/reaticle_docs/onvif-implementation/phase-4-restful-api-and-web-ui.md -->

# 第四阶段实施细节：RESTful API 表现层与前端 Web UI 集成

## 1 阶段概述与架构定位

本阶段旨在打通前后端交互链路，构建面向管理人员与操作员的完整 Web UI 与 RESTful 接口体系。

核心交付内容：
1. **后端表现层 REST API**：
   - `OnvifDeviceController.java`：负责 ONVIF 设备增删改查、通道台账、手动重新同步；
   - `OnvifDiscoveryController.java`：负责向局域网广播 WS-Discovery 探测、单播探针与搜寻结果汇聚；
2. **前端路由与 API 封装**：
   - 在 `web/src/router/index.js` 的“设备接入”导航树下注册 `/onvif` 路由；
   - 编写 `web/src/api/onvif.js`，基于 Axios 封装完整的异步请求方法；
3. **前端 Element-UI 交互视图**：
   - 设备主台账管理视图 `web/src/views/onvif/index.vue`（支持展开行查看 Profile 通道、在线状态标示、直接调用点播播放器）；
   - 局域网一键探测向导 `web/src/views/onvif/deviceDiscovery.vue`（UDP 多播一键扫描、厂商识别、批量勾选并纳管）；
   - 八向云台操控盘组件 `web/src/views/onvif/ptzController.vue`（鼠标按下即走、松手即停，变倍缩放与预置位快速调用）。

---

## 2 后端 RESTful 控制器实现

### 2.1 设备管理控制器 `OnvifDeviceController.java`

```java
// src/main/java/com/genersoft/iot/vmp/onvif/controller/OnvifDeviceController.java
package com.genersoft.iot.vmp.onvif.controller;

import com.genersoft.iot.vmp.conf.exception.ControllerException;
import com.genersoft.iot.vmp.onvif.bean.OnvifChannel;
import com.genersoft.iot.vmp.onvif.bean.OnvifDevice;
import com.genersoft.iot.vmp.onvif.dao.OnvifChannelMapper;
import com.genersoft.iot.vmp.onvif.service.IOnvifDeviceService;
import com.genersoft.iot.vmp.vmanager.bean.ErrorCode;
import com.genersoft.iot.vmp.vmanager.bean.WVPResult;
import com.github.pagehelper.PageInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/onvif/device")
@Tag(name = "ONVIF 设备管理接口")
@RequiredArgsConstructor
public class OnvifDeviceController {

    private final IOnvifDeviceService deviceService;
    private final OnvifChannelMapper channelMapper;

    @GetMapping("/list")
    @Operation(summary = "分页查询 ONVIF 设备列表")
    public WVPResult<PageInfo<OnvifDevice>> list(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "count", defaultValue = "10") int count,
            @RequestParam(value = "query", required = false) String query,
            @RequestParam(value = "status", required = false) Integer status) {
        PageInfo<OnvifDevice> pageInfo = deviceService.getDeviceList(page, count, query, status);
        return WVPResult.success(pageInfo);
    }

    @PostMapping("/add")
    @Operation(summary = "添加 ONVIF 设备")
    public WVPResult<OnvifDevice> add(@RequestBody OnvifDevice device) {
        if (device.getIp() == null || device.getPort() == null ||
            device.getUsername() == null || device.getPassword() == null) {
            throw new ControllerException(ErrorCode.ERROR400.getCode(), "IP、端口、用户名和密码不能为空");
        }
        OnvifDevice saved = deviceService.addDevice(device);
        return WVPResult.success(saved);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除 ONVIF 设备")
    public WVPResult<Void> delete(@RequestParam("id") Integer id) {
        deviceService.deleteDevice(id);
        return WVPResult.success();
    }

    @PostMapping("/sync/{id}")
    @Operation(summary = "手动重新同步设备通道与能力")
    public WVPResult<Void> sync(@PathVariable("id") Integer id) {
        deviceService.syncChannels(id);
        return WVPResult.success();
    }

    @GetMapping("/channels/{deviceId}")
    @Operation(summary = "查询指定设备的通道/Profile列表")
    public WVPResult<List<OnvifChannel>> getChannels(@PathVariable("deviceId") Integer deviceId) {
        List<OnvifChannel> channels = channelMapper.selectByDeviceId(deviceId);
        return WVPResult.success(channels);
    }
}
```

---

### 2.2 搜寻发现控制器 `OnvifDiscoveryController.java`

```java
// src/main/java/com/genersoft/iot/vmp/onvif/controller/OnvifDiscoveryController.java
package com.genersoft.iot.vmp.onvif.controller;

import com.genersoft.iot.vmp.onvif.bean.OnvifProbeResult;
import com.genersoft.iot.vmp.onvif.client.OnvifDiscoveryClient;
import com.genersoft.iot.vmp.vmanager.bean.ErrorCode;
import com.genersoft.iot.vmp.vmanager.bean.WVPResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/onvif/discovery")
@Tag(name = "ONVIF 局域网探测接口")
@RequiredArgsConstructor
public class OnvifDiscoveryController {

    private final OnvifDiscoveryClient discoveryClient;

    @PostMapping("/probe")
    @Operation(summary = "发起局域网 WS-Discovery 多播探测")
    public WVPResult<List<OnvifProbeResult>> probe(
            @RequestParam(value = "timeout", defaultValue = "3") int timeoutSeconds) {
        int safeTimeout = Math.min(Math.max(timeoutSeconds, 1), 6);
        log.info("[ONVIF-API] 收到局域网搜寻指令，超时设定: {}s", safeTimeout);
        List<OnvifProbeResult> results = discoveryClient.probe(safeTimeout);
        return WVPResult.success(results);
    }

    @PostMapping("/probe-single")
    @Operation(summary = "单播探测指定 IP 与端口")
    public WVPResult<OnvifProbeResult> probeSingle(
            @RequestParam("ip") String ip,
            @RequestParam(value = "port", defaultValue = "80") int port) {
        OnvifProbeResult result = discoveryClient.probeSingle(ip, port);
        if (result == null) {
            return WVPResult.fail(ErrorCode.ERROR404.getCode(), "未探测到响应或设备非 ONVIF 兼容");
        }
        return WVPResult.success(result);
    }
}
```

---

## 3 前端路由与 API 模块设计

### 3.1 路由注册 `web/src/router/index.js`

在 `path: '/device'`（“设备接入”）的 `children` 列表中增加 `/onvif` 路由：

```javascript
// web/src/router/index.js
// 在 children 数组内追加:
      {
        path: '/onvif',
        name: 'OnvifDevice',
        component: () => import('@/views/onvif/index'),
        meta: { title: 'ONVIF设备', icon: 'nested' }
      }
```

---

### 3.2 前端 API 封装 `web/src/api/onvif.js`

```javascript
// web/src/api/onvif.js
import request from '@/utils/request'

// 1. 获取设备分页列表
export function getOnvifDeviceList(params) {
  return request({
    url: '/api/onvif/device/list',
    method: 'get',
    params
  })
}

// 2. 添加 ONVIF 设备
export function addOnvifDevice(data) {
  return request({
    url: '/api/onvif/device/add',
    method: 'post',
    data
  })
}

// 3. 删除 ONVIF 设备
export function deleteOnvifDevice(id) {
  return request({
    url: '/api/onvif/device/delete',
    method: 'delete',
    params: { id }
  })
}

// 4. 手动同步设备通道 Profile
export function syncOnvifChannels(id) {
  return request({
    url: `/api/onvif/device/sync/${id}`,
    method: 'post'
  })
}

// 5. 获取指定设备的通道列表
export function getOnvifChannels(deviceId) {
  return request({
    url: `/api/onvif/device/channels/${deviceId}`,
    method: 'get'
  })
}

// 6. 发起局域网 WS-Discovery 探测
export function probeOnvifDevices(timeout = 3) {
  return request({
    url: '/api/onvif/discovery/probe',
    method: 'post',
    params: { timeout }
  })
}

// 7. 单播测试探测指定 IP 与端口
export function probeSingleDevice(ip, port) {
  return request({
    url: '/api/onvif/discovery/probe-single',
    method: 'post',
    params: { ip, port }
  })
}

// 8. 点播指定通道
export function startOnvifPlay(channelId) {
  return request({
    url: `/api/channel/play/${channelId}`,
    method: 'get'
  })
}
```

---

## 4 前端 Vue 交互视图实现

### 4.1 主台账管理页面 `web/src/views/onvif/index.vue`

```vue
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
```

---

### 4.2 局域网一键搜寻抽屉向导 `web/src/views/onvif/deviceDiscovery.vue`

```vue
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
      <el-button type="success" :disabled="multipleSelection.length === 0" @click="batchImport">
        批量导入选中 ({{ multipleSelection.length }})
      </el-button>
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
import { probeOnvifDevices, addOnvifDevice } from '@/api/onvif'

export default {
  name: 'DeviceDiscovery',
  props: { visible: { type: Boolean, default: false }},
  data() {
    return {
      scanning: false,
      importing: false,
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
```

---

### 4.3 八向云台操控盘组件 `web/src/views/onvif/ptzController.vue`

支持鼠标按下（`mousedown`）下发转动、鼠标抬起（`mouseup` / `mouseleave`）即时下发刹车停止指令。

```vue
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
```

---

## 5 阶段验收门禁标准

1. **界面交互顺畅**：
   - 访问 `/device/onvif`，正常呈现设备主台账，无控制台 JS 语法与资源加载错误；
   - 点击“展开行”能够无延迟展示该摄像机对应的各码流 Profile、分辨率与编码格式；
2. **局域网搜寻弹窗联动**：
   - 点击“局域网搜寻”，触发 WS-Discovery 探测并在 3 秒内填充表格；支持勾选设备一键导入；
3. **云台手感测试**：
   - 打开云台抽屉，按住“向左”按键，后端控制台打印正确的 `ContinuousMove` 报文与负向水平速度；
   - 松开鼠标，控制台即刻打印带有 `PanTilt=true` 的 `ptz:Stop` 报文，无粘键超调。
