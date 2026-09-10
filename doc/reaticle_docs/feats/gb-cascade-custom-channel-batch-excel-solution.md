<!-- doc/reaticle_docs/feats/gb-cascade-custom-channel-batch-excel-solution.md -->

# 国标级联通道国标编码批量修改（Excel 方案）系统分析与技术方案

## 1 Objectives

### 1.1 需求定位与背景
在 WVP-PRO 的 GB28181 国标级联业务中，下级平台需要向上级管理平台（如公安视频专网、交警管控中心、城市运行中枢）共享并推送通道目录（Catalog）。在实际工程交付中，上级平台往往要求按照统一的安防规划规范（如《GB/T 28181-2016/2022 附录 D》）重新编制下发点位编码（20 位国标编码），下级平台原生通道编码无法直接使用。

当前系统已支持在 `wvp_platform_channel` 表中存储 `custom_device_id`（自定义国标编码）和 `custom_name`（自定义名称），并在信令查询/推送时通过 `COALESCE(wpgc.custom_device_id, wdc.gb_device_id, wdc.device_id)` 动态覆盖。但系统仅提供了单行网页修改与保存接口，面对百路至千路通道的大规模交接入库，逐行点选操作成本极高且极易出错。

### 1.2 方案核心目标
1. **支持全量/离线批量映射**：实现按上级平台（`platformId`）导出已共享通道映射表、离线编辑核对、上传一键导入更新的能力。
2. **轻量高性能流式处理**：深度复用工程内已集成的 `com.alibaba.excel (EasyExcel)` 框架，采用基于事件驱动的流式读写，内存消耗恒定在极低水平（<15MB），支持 2000+ 通道秒级导入导出。
3. **信令风暴拦截解耦**：切断单条持久化即时发送 SIP Catalog Notify 的高频循环，杜绝 SIP 协议栈阻塞与上级平台丢包。
4. **强健的边界防御与异常回溯**：前置拦截科学计数法变形、非 20 位数字非法编码、批内重复、跨库冲突，提供精准到 Excel 行号的错误清单。

---

## 2 Constraints & Boundary Contract Matrix

### 2.1 协议、系统与运行时边界契约矩阵

| 交互层级 | 约束对象 | 硬性约束规范与边界条件 | 容错与防卫策略 |
| :--- | :--- | :--- | :--- |
| **L0 编码格式** | 国标 20 位编码规范 | 符合 GB/T 28181 附录 D 规范：必须严格由 20 位纯数字组成（前 6 位行政区划码，7-8 位网络标识，9-10 位系统类型，11-13 位设备/通道类型，14 位网络标识，15-20 位序号）。 | 导入解析器使用严格正则 `^[0-9]{20}$` 校验，非 20 位或含非数字字符即刻拦截，记录行号错误。 |
| **L0 数据存储** | Excel 单元格精度截断 | 超过 15 位纯数字在 Excel/CSV 中默认自动转换为 IEEE-754 双精度浮点数（科学计数法，如 `3.10115E+19`），导致低 5 位精度归零；且首位为 `0` 的编码会被默认丢弃。 | 导出时强制单元格数据格式为 `@`（纯文本 Text 格式）；导入解析时强制使用 String 类型承接，严禁读取为 Double 或 Long。 |
| **L1 数据库契约** | 唯一键与映射约束 | 1. 对应表 `wvp_platform_channel` 存在主键 `id` 与复合唯一索引 `(platform_id, device_channel_id)`；<br>2. `custom_device_id` 存在唯一约束 `uk_platform_gb_channel_device_id`，同库不可重复。 | 1. 导出模版隐藏/只读输出 `id` 作为唯一主键更新标记；<br>2. 导入时在内存中维护 `Set<String>` 进行批内唯一性防重；<br>3. 数据库执行阶段若命中全局重复，由异常捕获器捕获并标记回滚或精准上报冲突。 |
| **L2 协议信令** | SIP Catalog 信令风暴 (RFC 3261) | 现有实现每更新单条通道即触发 `eventPublisher.catalogEventPublish` 发送 SIP NOTIFY。若批量导入 1000 条，将瞬间产生 1000 条异步报文，导致 UDP 丢包、TCP 粘包或上级平台超时失联。 | 批量更新接口内**显式切断逐条事件发布**，更新成功后不自动发送逐条 NOTIFY，提示用户或由用户在前端点击已有的【推送通道】（`/api/platform/channel/push`）进行合并推送。 |
| **L3 人机交互** | 权限与跨平台误操作 | 只能修改当前指定 `platformId` 下已共享（`hasShare=true`）的通道；严禁通过伪造主键 ID 跨平台串改。 | 批量更新 SQL 强制附加 `WHERE id = #{item.id} AND platform_id = #{platformId}`，杜绝越权串改。 |

---

## 3 Architecture

### 3.1 架构分层与模块边界

```mermaid
graph TD
    User([前端管理员/运维工程师]) -->|1. 点击导出映射模版| WebUI[前端 Vue 视图 shareChannelAdd.vue]
    User -->|4. 拖拽上传修改后的 Excel| WebUI
    
    subgraph Frontend Layer [前端展现层]
        WebUI -->|触发下载| APIExport[api/platform.js: exportCustomChannel]
        WebUI -->|显示导入弹窗| ImportDialog[importCustomChannel.vue]
        ImportDialog -->|文件流上传| APIImport[api/platform.js: importCustomChannel]
    end

    subgraph Controller Layer [表现控制层]
        APIExport -->|GET /api/platform/channel/custom/export| CtrlExport[PlatformController.exportCustomChannel]
        APIImport -->|POST /api/platform/channel/custom/import| CtrlImport[PlatformController.importCustomChannel]
    end

    subgraph Service & Stream Engine [服务调度与流式处理层]
        CtrlExport --> Service[PlatformChannelServiceImpl]
        CtrlImport --> Service
        Service -->|EasyExcel 写入流| ExcelWriter[EasyExcel Write Engine]
        Service -->|EasyExcel SAX 监听器| ExcelListener[PlatformChannelExcelListener]
        ExcelListener -->|行级正则 & 批内防重校验| ValidationEngine[规则与边界校验引擎]
        ValidationEngine -->|合规批次 List| Service
    end

    subgraph Persistence Layer [数据持久层]
        Service -->|批量 UPDATE| Mapper[PlatformChannelMapper.batchUpdateCustomChannel]
        Mapper -->|执行 SQL 更新| DB[(wvp_platform_channel 表)]
    end

    subgraph Signaling Decoupling [信令解耦防御]
        Service -.->|阻断逐条 Catalog Notify| SuppressedNotify[信令静默]
        WebUI -->|必要时用户手动点击| ManualPush[/api/platform/channel/push 一次性推送]
    end
```

### 3.2 实体与 DTO 定义模型

#### 3.2.1 Excel 数据传输对象（PlatformChannelExcelDto）
```text
列 0: 关联主键ID (id)            -> 隐藏或只读标注，用于 MyBatis 主键精准更新
列 1: 本地通道ID (channelId)     -> 只读展示，方便排查
列 2: 原始国标编码 (gbDeviceId)  -> 只读展示，对照参考
列 3: 通道名称 (name)            -> 只读展示，点位参考名称
列 4: 设备厂商 (manufacturer)    -> 只读展示
列 5: 自定义国标编码 (customDeviceId) -> 【核心编辑列】，必须 20 位数字，设为文本格式
列 6: 自定义通道名称 (customName)     -> 【选填编辑列】，支持级联通道名称别名覆盖
```

### 3.3 数据流模型（Data Flow Model）

#### 3.3.1 导出数据流
1. 客户端发起 `GET /api/platform/channel/custom/export?platformId={id}`。
2. 控制器验证 `platformId` 有效性，注入 `HttpServletResponse`。
3. 服务层调用 `platformChannelMapper.queryForPlatformForWebList(platformId, null, null, null, true)` 获取所有已共享通道。
4. 将实体列表转换为 `List<PlatformChannelExcelDto>`，设置 HTTP Header：`Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`，编码文件名 `平台_{id}_级联通道编码映射表.xlsx`。
5. EasyExcel 以 `@` 纯文本单元格格式写入 Response OutputStream，完成极速下载。

#### 3.3.2 导入数据流
1. 客户端上传带有 `platformId` 和 `file` 的 MultipartRequest。
2. 控制器将 `file.getInputStream()` 传给 `PlatformChannelExcelListener`。
3. **监听器逐行执行 L0/L1 校验**：
   - 检查 `id` 是否存在且为有效正整数；
   - 检查 `customDeviceId` 若非空，是否满足 `^[0-9]{20}$`；
   - 检查 `customDeviceId` 在当前 Excel 批次内是否重复（内存 `HashSet` 碰撞检测）；
   - 校验通过的数据进入缓冲区 `cachedDataList`（缓冲区大小设为 500）；
   - 校验失败的行记录详细信息：`"第 " + rowIndex + " 行: " + errorMessage` 收集到 `errorMessages`。
4. **分批入库**：当缓冲区达 500 条或到达文件末尾（`doAfterAllAnalysed`），在同一个本地事务中调用 `platformChannelMapper.batchUpdateCustomChannel(cachedDataList, platformId)`。
5. 监听器收集完毕后返回结构体：
   ```json
   {
     "code": 0,
     "data": {
       "total": 1200,
       "success": 1198,
       "failure": 2,
       "errorMessages": [
         "第 15 行: 自定义国标编码 [31011500] 长度非法，必须严格为 20 位数字",
         "第 88 行: 自定义国标编码 [31011500001320000001] 与第 12 行重复"
       ]
     }
   }
   ```
6. 事务提交，完全不触发单条 `CatalogEvent.UPDATE`。

---

## 4 Work Breakdown Structure (WBS)

```text
WBS: 国标级联通道国标编码批量修改（Excel方案）
├── 1. 后端数据持久层与 DTO 建模
│   ├── 1.1 新增 PlatformChannelExcelDto，定义 EasyExcel 注解与文本格式化器
│   ├── 1.2 在 PlatformChannelMapper.java 中增加批量更新接口 batchUpdateCustomChannel
│   └── 1.3 编写对应高效的 MyBatis 批量更新动态 SQL（针对 id 与 platformId 双条件约束）
├── 2. EasyExcel 流式解析监听器与校验引擎
│   ├── 2.1 实现 PlatformChannelExcelListener 继承 AnalysisEventListener
│   ├── 2.2 编写 20 位国标编码正则与非空校验逻辑
│   ├── 2.3 编写批内重复检测机制与错误行号精准捕获
│   └── 2.4 实现 500 条/批次的分批持久化与事务保护
├── 3. 业务服务层与信令解耦调度
│   ├── 3.1 IPlatformChannelService 增加 exportCustomChannel 与 importCustomChannel 方法契约
│   ├── 3.2 PlatformChannelServiceImpl 实现流式写响应与解析器挂载
│   └── 3.3 显式隔离 SIP 逐条 Notify 事件（信令静默防风暴）
├── 4. RESTful API 控制器适配
│   ├── 4.1 PlatformController 暴露 GET /api/platform/channel/custom/export 接口
│   ├── 4.2 PlatformController 暴露 POST /api/platform/channel/custom/import 接口
│   └── 4.3 补充 Swagger/OpenAPI 描述与权限注解
├── 5. 前端表现层视图与交互
│   ├── 5.1 api/platform.js 封装 exportCustomChannel 与 importCustomChannel 方法
│   ├── 5.2 shareChannelAdd.vue 在“已共享”标签页操作栏补充【导出编码映射】与【批量导入】按钮
│   └── 5.3 新建/复用 importCustomChannel.vue 对话框（包含模板下载、拖拽上传、进度条与错误清单滚动列表）
└── 6. 验证与回归测试
    ├── 6.1 单元测试：20 位边界、纯数字校验、批内重复拦截
    ├── 6.2 性能测试：1000 路已共享通道导出耗时与导入吞吐耗时验证（<3 秒）
    └── 6.3 协议测试：批量导入完成后确认无突发 SIP 信令洪水，手动推送通道正常生效
```

---

## 5 Acceptance Criteria

### 5.1 功能完整性指标
1. **导出合规性**：
   - 在上级平台的“已共享”通道列表中，点击【导出编码映射】能够完整下载当前平台下所有已分配通道；
   - 导出的 Excel 必须包含当前已设置的 `customDeviceId` 和 `customName`（若未设置则对应单元格为空）；
   - 导出的所有编码列格式必须为原生文本（Text/@），在 Excel 软件打开时不被自动转换为科学计数法或丢失前导零。
2. **导入精准性**：
   - 上传合规填写的 Excel 后，数据库 `wvp_platform_channel` 中对应记录的 `custom_device_id` 和 `custom_name` 精准更新；
   - 未在 Excel 中修改的行或空文本字段不得篡改其他原有数据。
3. **错误提示与容错回显**：
   - 若用户输入了 18 位、含英文字符的编码，系统拒绝入库，并清晰返回：“`第 X 行: 自定义国标编码 [xxx] 长度或格式非法，必须严格为 20 位纯数字`”；
   - 若同一文件中存在相同的自定义编码，系统拒绝入库重复项，并清晰返回重复行号；
   - 前端具备滚动列表完整呈现所有异常项，支持用户排查修正后二次上传。

### 5.2 性能与系统开销指标
1. **吞吐性能**：
   - 1000 条通道的 Excel 导出全链路耗时 $\le 1.5$ 秒；
   - 1000 条通道的 Excel 解析、校验、批量入库全流程耗时 $\le 3.0$ 秒。
2. **内存消耗**：
   - 导入解析阶段 JVM 堆内存峰值波动 $\le 30\text{MB}$，无 Full GC 抖动。

### 5.3 协议与信令稳定指标
1. **信令风暴隔离**：
   - 在批量导入执行期间，Wireshark/抓包工具监测不得出现大量连发的 SIP NOTIFY 报文；
   - 级联心跳保持正常，与上级平台的 SIP 会话不发生超时、重传或注册掉线。
2. **级联目录生效**：
   - 批量导入完成后，上级平台发送 Catalog 查询信令时，本平台响应的 DeviceID 严格展现最新导入的 `custom_device_id`。
