<!-- doc/reaticle_docs/debug/2026-09-11-onvif-batch-export-overwrite-and-id-reuse-plan.md -->

# ONVIF 设备批量导出、同IP覆盖更新与ID紧凑复用系统分析与更新方案

本文档基于 `doc/reaticle_docs/onvif-implementation/` 系列规范及 `doc/reaticle_docs/debug/2026-09-09-onvif-debug-optimization-plan.md` 现有成果，针对现场批量运维管理中的三大痛点——**缺少选中设备批量导出能力**、**导入已有IP设备直接报错无法批量改名与改国标号**、以及 **设备删除后自增ID不释放导致ID持续膨胀**，进行系统级架构解构与更新方案制定。

---

## 1 Objectives (核心目标)

1. **支持选中的 ONVIF 设备全链路批量导出**：
   - **已纳管设备列表 (`web/src/views/onvif/index.vue`)**：主表补全复选框勾选（`type="selection"`），支持用户选中部分或全部设备，一键导出为符合标准导入模板规范的 `.xlsx` 表格；
   - **局域网搜寻向导 (`web/src/views/onvif/deviceDiscovery.vue`)**：在发现列表提供“批量导出选中 (N)”能力，探测到的设备可直接导出为 Excel 预填单，便于工程人员线下补全密码与国标号后再行导入；
   - **数据闭环规范**：导出字段严格对齐 `OnvifDeviceImportDto`（设备名称、IP地址、服务端口、用户名、密码、流媒体节点ID、主通道国标编号、行政区划编码），实现“**导出 -> 批量修改 -> 重新导入覆盖**”的无缝闭环。

2. **支持同 IP 设备覆盖更新，实现批量重命名与国标编码变更**：
   - 消除当前批量导入时遇到重复 IP 直接抛出 `第 X 行 [IP:Port]: 设备已存在` 并计入失败的硬性阻断机制；
   - 当检测到待导入设备在系统中已存在（按 `IP` + `Port` 命中）时，平滑切换为**覆盖更新（Upsert / Overwrite）**模式：
     - **设备基础信息**：更新设备名称、流媒体服务节点；
     - **凭据与网络校验**：若用户名或密码发生变化，执行探针重新校验；若凭据未变，直接保留原探测元数据，**即使现场设备临时离线亦可完成属性覆盖**；
     - **通道国标属性穿透联动**：修复旧版 `syncChannels` 忽略外部 `customGbDeviceId` 的缺陷，将 Excel 中填写的新设备名称（`name`）、新国标编号（`gbDeviceId`）和行政区划（`civilCode`）实时穿透同步至 `wvp_onvif_channel` 与核心通道表 `wvp_device_channel`（`CommonGBChannel`），实现批量更名与国标号批量重映射。

3. **删除设备即时释放 ID，实行紧凑型 ID 复用机制**：
   - 摒弃 MySQL 底层 `AUTO_INCREMENT` 仅递增不回收的单向膨胀机制，彻底解决设备频繁增删后 ID 飙升至数十上百，导致主表 ID 显示凌乱、以及国标虚拟编码生成算法 `String.format("3402000000132%03d%04d", deviceId % 1000, index)` 在 `deviceId >= 1000` 时发生编码碰撞的隐患；
   - 引入**首个缺失正整数算法（First Missing Positive / 空洞扫描与优先复用机制）**：查询当前已占用的所有设备 ID，动态选取未被使用的最小正整数（从 1 开始）；
   - 改造 `OnvifDeviceMapper.insert` 支持显式指定主键插入，确保被删除设备释放的物理 ID 能够被后续新增/导入的设备优先填补，系统内设备 ID 始终保持紧凑连续。

---

## 2 Constraints & Boundary Contract Matrix (约束条件与边界契约矩阵)

### 2.1 事实、判断与推测 (Fact Separation)

| 类型 | 标识 | 详细论述 | 验证出处 |
| :--- | :--- | :--- | :--- |
| **事实** | F-01 | `wvp_onvif_device` 表定义中 `id` 为 `INT AUTO_INCREMENT PRIMARY KEY`，且 `OnvifDeviceMapper.insert` 中未包含 `id` 列，完全依赖自增分配。 | `数据库/2.7.4/增量-onvif.sql:8` |
| **事实** | F-02 | `OnvifDeviceServiceImpl.importDevices` 遍历时判定 `if (exist != null)` 直接添加错误信息并跳过当前行。 | `OnvifDeviceServiceImpl.java:364-368` |
| **事实** | F-03 | `OnvifDeviceServiceImpl.syncChannels` 中，当通道在数据库已存在时，代码逻辑强制执行 `channel.setGbDeviceId(existingGb.getGbDeviceId())`，外部传入的 `customGbDeviceId` 被丢弃。 | `OnvifDeviceServiceImpl.java:251-255` |
| **事实** | F-04 | `web/src/views/onvif/index.vue` 的主表格未设置 `type="selection"` 列，顶部栏未提供导出按钮；`deviceDiscovery.vue` 中虽有多选，但只有批量导入无导出。 | `views/onvif/index.vue:24-84` |
| **判断** | J-01 | 批量导出若直接复用 `OnvifDeviceImportDto`，不仅能保证导入导出的数据对称性，还能直接作为用户修改后的导入源，架构复杂度最低且最符合用户直觉。 | 系统分析判断 |
| **判断** | J-02 | 批量修改设备名称与国标编码时，若现场网络抖动导致摄像头离线，强行发起 SOAP 网络握手会导致整批导入中断。因此，在网络与鉴权凭据未变的前提下，覆盖更新应以本地数据库元数据差分更新为主，解耦硬件在线强依赖。 | 系统分析判断 |
| **推测** | S-01 | 单台 WVP 纳管的 ONVIF 设备数量通常在数百台以内，并发添加设备的请求频率较低，通过内存空洞扫描结合 JVM 级事务同步即可保证 ID 分配的绝对原子性，无需引入分布式号段发生器。 | 架构性能推测 |

### 2.2 边界契约矩阵 (Boundary Contract Matrix)

#### 2.2.1 批量导出接口契约 (`POST /api/onvif/device/export`)

```
+-------------------+-------------------------------------------------------------------------------+
| 协议项            | 说明与约束                                                                     |
+-------------------+-------------------------------------------------------------------------------+
| 请求路径 / 方法   | POST /api/onvif/device/export                                                 |
| Content-Type      | application/json;charset=UTF-8                                                |
| 鉴权要求          | Header 携带 access-token: Bearer <token>                                      |
| 请求体结构        | {                                                                             |
|                   |   "deviceIds": [1, 3, 5],            // 选中的已纳管设备ID列表（可选）         |
|                   |   "customDevices": [ ... ]           // 探测发现界面的临时设备结构列表（可选） |
|                   | }                                                                             |
| 响应格式          | application/vnd.openxmlformats-officedocument.spreadsheetml.sheet (二进制流)  |
| 响应头            | Content-disposition: attachment;filename*=utf-8''onvif_devices_export.xlsx   |
| 异常处理          | 若发生业务异常（如无权或无数据），以 JSON 格式返回 WVPResult 标准错误码       |
+-------------------+-------------------------------------------------------------------------------+
```

#### 2.2.2 覆盖更新策略矩阵 (Overwrite Strategy Matrix)

当导入 Excel 的某一行数据与数据库已存记录匹配（`ip == exist.ip && port == exist.port`）时，各字段的处理策略如下表：

| 字段名 | Excel 传入状态 | 现有数据库值 | 目标执行动作 | 硬件 SOAP 探针触发 |
| :--- | :--- | :--- | :--- | :---: |
| **name** | 非空且变更 | 原设备名称 | 更新 `wvp_onvif_device.name` 并联动更新 `wvp_device_channel.gb_name` | 否 |
| **mediaServerId** | 非空且变更 | 原流媒体ID | 更新 `wvp_onvif_device.media_server_id` | 否 |
| **username / password** | 与现有值不同 | 原认证凭据 | 更新凭据，重新计算 `clock_offset` 并验证 capabilities | **是** |
| **username / password** | 相同或密码留空 | 原认证凭据 | 保留原凭据与原探针数据，不阻断更新流程 | 否 |
| **gbDeviceId** | 提供有效 20 位编码 | 现有国标号 | 更新 `wvp_onvif_channel.gb_device_id` 并联动更新 `wvp_device_channel.gb_device_id` | 否 |
| **civilCode** | 提供行政区划 | 现有区划 | 更新 `wvp_device_channel.gb_civil_code` | 否 |

#### 2.2.3 ID 释放与复用状态转移矩阵

```
+-----------------------------------------------------------------------------------------------+
| 状态场景                      | 当前库内 ID 集合    | 触发动作       | 计算得出 ID   | 说明           |
+-----------------------------------------------------------------------------------------------+
| 场景 1：库表为空              | []                  | 新增设备       | 1             | 初始基线 ID    |
| 场景 2：连续递增无空洞        | [1, 2, 3]           | 新增设备       | 4             | 顺序后延       |
| 场景 3：删除中间节点产生空洞  | [1, 2, 3] -> 删 2   | 新增设备       | 2 (复用释放ID)| 优先填补空洞   |
| 场景 4：删除首节点产生空洞    | [1, 2, 3] -> 删 1   | 新增设备       | 1 (复用释放ID)| 释放ID立刻回填 |
| 场景 5：全量清空重置          | 全部删除完毕为 []   | 批量导入5台    | 1, 2, 3, 4, 5 | 完全回归紧凑态 |
+-----------------------------------------------------------------------------------------------+
```

---

## 3 Architecture (架构与模块分解)

### 3.1 总体架构流转图

```mermaid
graph TD
    subgraph 视图层 (Web UI)
        A[index.vue 已纳管设备列表] -->|勾选多选框 multipleSelection| B[点击 批量导出]
        C[deviceDiscovery.vue 搜寻向导] -->|勾选探测结果| D[点击 导出选中为Excel]
        E[dialog/importDevice.vue 批量导入] -->|上传修改后的 Excel| F[提交 POST /api/onvif/device/import]
    end

    subgraph 控制层 (REST Controller)
        B -->|POST /api/onvif/device/export deviceIds| G[OnvifDeviceController.exportDevices]
        D -->|POST /api/onvif/device/export customDevices| G
        F --> H[OnvifDeviceController.importDevices]
    end

    subgraph 服务层 (Service Layer)
        G -->|查询关联数据并封装 DTO| I[IOnvifDeviceService.exportDevices]
        I -->|EasyExcel 流式写入| J[下载 onvif_devices_export.xlsx]

        H -->|EasyExcel 解析| K[IOnvifDeviceService.importDevices]
        K -->|按 IP:Port 查重| L{是否存在?}

        L -->|否: 全新设备| M[分配最小未占用 ID (First Missing Positive)]
        M -->|SOAP 探针握手| N[OnvifSoapClient probe]
        N -->|插入设备与通道| O[deviceMapper.insert 显式ID]
        O -->|注册国标核心库| P[CommonGBChannelMapper.add]

        L -->|是: 已存在设备| Q[覆盖更新模式 (Upsert)]
        Q -->|检测凭据变动| R{凭据变更?}
        R -->|是| S[重新探针握手校验]
        R -->|否| T[就地复用原通信元数据]
        S --> U[deviceMapper.update 基础属性]
        T --> U
        U -->|穿透更新国标编号与名称| V[联动更新 OnvifChannel 与 CommonGBChannel]
    end

    subgraph 删除回收 (Delete & Recycle)
        W[点击删除设备] -->|DELETE /api/onvif/device/delete| X[deleteDevice]
        X -->|级联物理清理| Y[清除 CommonGBChannel & OnvifChannel & OnvifDevice]
        Y -->|释放 ID| Z[空洞自动形成，下次分配立即优先复用]
    end
```

### 3.2 模块分解设计

#### 3.2.1 模块 A：批量导出引擎 (Export Engine)

1. **导出数据组装与对齐**：
   - 后端根据传入的 `deviceIds` 集合，批量查询 `wvp_onvif_device`；
   - 关联查询每个设备的第一个主通道（`channel_index = 1`）以及对应的 `CommonGBChannel`（`data_type = 4`），抓取其实际生效的 `gb_device_id` 和 `gb_civil_code`；
   - 构建 `OnvifDeviceImportDto` 列表，严格保持列顺序（0:设备名称, 1:IP地址, 2:端口, 3:用户名, 4:密码, 5:流媒体节点ID, 6:主通道国标编号, 7:行政区划编码）。
2. **多场景导出支持**：
   - 若传入 `customDevices`（来自 `deviceDiscovery.vue` 探测列表），则直接将前台搜寻到的 IP、端口、厂商型号等参数映射转换为 DTO 写入表格；
   - 通过 EasyExcel 将流写入 HTTP 响应，前端采用携权 `fetch` + `Blob` 触发浏览器原生保存。

#### 3.2.2 模块 B：幂等覆盖导入与穿透更新引擎 (Overwrite & Penetration Engine)

1. **查重与分支决策（含端口缺省回退匹配）**：
   - 遍历导入行时，按以下优先级执行查重决策：
     - 若 `dto.getPort()` 大于 0，调用 `deviceMapper.selectByIpAndPort(dto.getIp().trim(), dto.getPort())`；
     - 若 `dto.getPort()` 为空或 0，调用 `deviceMapper.selectListByIp(dto.getIp().trim())`：若库内恰好命中一台该 IP 的设备，直接将其作为目标对象执行覆盖更新；若未命中，端口默认赋值为 80；
   - 若未匹配到已存记录，走**新增设备通道分支**；
   - 若命中已存在对象 `exist`，走**覆盖更新分支**，不再抛出“设备已存在”异常。
2. **通道与国标属性穿透联动更新**：
   - 检查 Excel 中是否存在非空的 `dto.getGbDeviceId()` 或更新后的 `dto.getName()`；
   - 查询该设备下所有现有通道 `channelMapper.selectByDeviceId(exist.getId())`；
   - 对主通道（`channelIndex == 1`）：
     - 若 `dto.getGbDeviceId()` 存在且与原编码不同，更新 `wvp_onvif_channel.gb_device_id`；
     - 查询对应的核心国标通道 `existingGb = gbChannelService.queryByDataId(ChannelDataType.ONVIF, channel.getId())`；
     - 若 `existingGb` 存在，更新其 `gbDeviceId` 为新国标号，更新其 `gbName` 为新设备名，更新其 `gbCivilCode` 为新行政区划，调用 `gbChannelService.update(existingGb)` 持久化；
   - 彻底修复 `syncChannels` 中 `existingGb.getGbDeviceId()` 覆盖回写导致外部参数失效的逻辑缺陷。

#### 3.2.3 模块 C：紧凑型 ID 释放与复用控制器 (ID Recycling Controller)

1. **空洞扫描算法实现**：
   - 在 `OnvifDeviceMapper` 增加接口：
     ```java
     // src/main/java/com/genersoft/iot/vmp/onvif/dao/OnvifDeviceMapper.java
     @Select("SELECT id FROM wvp_onvif_device ORDER BY id ASC")
     List<Integer> selectAllIds();

     @Select("SELECT * FROM wvp_onvif_device WHERE ip=#{ip}")
     List<OnvifDevice> selectListByIp(@Param("ip") String ip);
     ```
   - 在服务层实现确定性寻找空洞算法（配合内存已占用集合规避批量导入单批次全量查询）：
     ```java
     // src/main/java/com/genersoft/iot/vmp/onvif/service/impl/OnvifDeviceServiceImpl.java
     public synchronized int getNextAvailableDeviceId(Set<Integer> reservedIds) {
         List<Integer> ids = deviceMapper.selectAllIds();
         Set<Integer> allUsed = new HashSet<>(ids);
         if (reservedIds != null) {
             allUsed.addAll(reservedIds);
         }
         int candidate = 1;
         while (allUsed.contains(candidate)) {
             candidate++;
         }
         if (reservedIds != null) {
             reservedIds.add(candidate);
         }
         return candidate;
     }
     ```
2. **显式主键入库与反写冲刷防护**：
   - 调整 `OnvifDeviceMapper.insert` 的 SQL 定义，显式将 `id` 放入插入列：
     `INSERT INTO wvp_onvif_device (id, name, ip, port, ...) VALUES (#{id}, #{name}, ...)`；
   - **关键防冲刷约束**：在 Mapper 的 `insert` 方法上**必须移除** `@Options(useGeneratedKeys = true, keyProperty = "id")`。因为在 MySQL JDBC 驱动下，显式插入已指定主键时驱动返回的 generatedKey 为 0，若保留该注解会导致 MyBatis 误将 `device.id` 覆写为 `0`；
   - **临界区时序安全**：`getNextAvailableDeviceId()` 的调用时机**严禁**置于网络探针之前；必须在 `probeAndSyncMetadata(device)` 成功完成之后、`deviceMapper.insert(device)` 执行之前的极短内存临界区内完成，防止摄像机网络握手耗时（1~3秒）引发并发申请相同空洞 ID 的主键冲突。

---

## 4 Work Breakdown Structure (工作分解结构 WBS)

| 阶段 | 编号 | 任务分类 | 涉及文件/路径 | 核心改造项 |
| :--- | :--- | :--- | :--- | :--- |
| **P1** | 1.1 | 导出接口与DTO | `src/main/java/.../onvif/dto/OnvifDeviceExportRequest.java` | **[NEW]** 定义批量导出入参实体（`deviceIds`, `customDevices`） |
| | 1.2 | 导出控制器 | `src/main/java/.../onvif/controller/OnvifDeviceController.java` | 增加 `POST /api/onvif/device/export` 导出流式处理接口 |
| | 1.3 | 导出服务逻辑 | `src/main/java/.../onvif/service/impl/OnvifDeviceServiceImpl.java` | 实现 `exportDevices`，组装包含已纳管或探测设备属性的完整 DTO 列表 |
| | 1.4 | 导出前端API | `web/src/api/onvif.js` | 增加 `exportOnvifDevices(data)` 携权 Blob 下载工具函数 |
| | 1.5 | 设备主表导出UI | `web/src/views/onvif/index.vue` | 增加表格多选列 `selection` 与操作栏“批量导出选中 (N)”按钮 |
| | 1.6 | 搜寻向导导出UI | `web/src/views/onvif/deviceDiscovery.vue` | 在局域网探测列表增加“批量导出选中 (N)”按钮及导出逻辑 |
| **P2** | 2.1 | 覆盖导入逻辑 | `src/main/java/.../onvif/service/impl/OnvifDeviceServiceImpl.java` | 重构 `importDevices`：支持 IP+Port 查重与单 IP 回退匹配，命中时转为覆盖更新 |
| | 2.2 | 穿透更新国标号 | `src/main/java/.../onvif/service/impl/OnvifDeviceServiceImpl.java` | 修复 `syncChannels`，确保新指定的 `customGbDeviceId` 及名称穿透更新到 `CommonGBChannel` |
| | 2.3 | 导入反馈提示 | `web/src/views/onvif/dialog/importDevice.vue` | 完善统计提示信息，标明新增台数与覆盖更新台数 |
| **P3** | 3.1 | ID 查询与入库SQL | `src/main/java/.../onvif/dao/OnvifDeviceMapper.java` | 增加 `selectAllIds` 与 `selectListByIp`；改造 `insert` 显式写入 `id` 并移除 `@Options` |
| | 3.2 | 空洞分配算法 | `src/main/java/.../onvif/service/impl/OnvifDeviceServiceImpl.java` | 实现带预留集合的 `getNextAvailableDeviceId()`，确保在网络探针成功后入库前原子分配 |
| | 3.3 | 级联删除验证 | `src/main/java/.../onvif/service/impl/OnvifDeviceServiceImpl.java` | 验证 `deleteDevice` 后关联通道与国标主键全部注销，无孤儿数据残留阻碍新 ID 复用 |

---

## 5 Acceptance Criteria (验收门禁标准)

### 5.1 批量导出验收标准
1. **主表多选导出**：
   - 在 `index.vue` 中勾选 2 台已纳管设备，点击“批量导出选中 (2)”，浏览器成功弹出文件下载对话框，下载文件名为 `onvif_devices_export.xlsx`；
   - 打开导出的 Excel，行数与勾选设备完全一致，包含正确的 IP、端口、用户名、密码、主通道国标编号（如 `3402000000132...`）及行政区划；
   - 当未勾选任何设备时，“批量导出”按钮呈现置灰禁用状态。
2. **搜寻向导导出**：
   - 打开 `deviceDiscovery.vue` 执行 WS-Discovery 探测，在搜索出的列表中勾选 3 台设备；
   - 点击“批量导出选中 (3)”，能够导出包含这 3 台设备 IP、端口及推荐名称的 Excel 表格。

### 5.2 同 IP 覆盖更新与批量重命名/改国标号验收标准
1. **批量改名与改国标号成功**：
   - 将导出的包含某设备 `192.168.1.108` 的 Excel 打开，将设备名称由“旧名称”改为“东门高空枪机”，将主通道国标编号由原值改为 `34020000001329990001`；
   - 在“批量导入”对话框中上传该 Excel，系统提示“成功导入/更新 1 台设备”，无“设备已存在”报错；
   - 刷新主页面，设备列表中该设备名称已变更为“东门高空枪机”，展开 Profile 列表，其“挂接国标编码”精准变更为 `34020000001329990001`，且可在国标级联库中以新编码正常检索识别。
2. **离线设备容错更新**：
   - 当网络中该摄像头处于关机/断网状态时，仅修改 Excel 中的名称或国标号并重新导入，系统仍可成功完成数据库属性覆盖，不会因 SOAP 连接超时而报错中断。

### 5.3 设备删除释放 ID 与紧凑复用验收标准
1. **空洞填补与紧凑分配**：
   - 当前库内有 ID 为 `1`、`2`、`3` 的三台设备；
   - 在主界面点击删除 ID 为 `2` 的设备；
   - 点击“手动添加”或通过 Excel 导入一台新设备，新录入的设备分配到的 ID 必须精准为 `2`（优先复用释放空洞），而不是 `4`；
   - 再次录入一台新设备，分配到的 ID 顺延为 `4`；
2. **全量删除重置归一**：
   - 将所有设备逐一删除至列表为空；
   - 重新录入第一台设备，其分配到的 ID 必须回归为 `1`。
