# 第二阶段实施细节：数据持久化与设备/通道模型同步

## 1 阶段概述与架构定位

本阶段承接第一阶段的底层通信底座，负责完成 ONVIF 设备与通道的数据持久化建模、业务服务封装以及与 WVP-PRO 核心国标通道体系（`wvp_device_channel`）的深度无缝挂接。

核心任务包括：
1. **多数据库兼容的增量 DDL**：编写 `增量-onvif.sql`，原生适配 MySQL 8.0+、PostgreSQL / Kingbase 以及本地调试环境 H2；
2. **领域实体与传输对象**：构建 `OnvifDevice`、`OnvifChannel`、`OnvifProfile` 实体，规范字段映射与约束；
3. **MyBatis 数据访问层**：编写 `OnvifDeviceMapper`、`OnvifChannelMapper` 及其动态 SQL Provider，支持条件分页查询与批量操作；
4. **设备握手与 Profile 自动解析服务**：
   - 连通性测试并获取 `GetSystemDateAndTime` 计算 `clock_offset`；
   - 调用 `GetDeviceInformation` 提取厂商、型号、固件版本与序列号；
   - 调用 `GetCapabilities` 提取 Media 与 PTZ 服务的标准 XAddr；
   - 调用 `GetProfiles` 提取主码流（MainStream）与子码流（SubStream）配置；
   - 调用 `GetStreamUri` 与 `GetSnapshotUri` 提取标准 RTSP 流与快照地址；
5. **国标通道表级联挂接**：将 ONVIF 通道自动封装为 `CommonGBChannel`，注册 `data_type = 4` (`ChannelDataType.ONVIF`)，纳入平台统一目录与鉴权体系。

---

## 2 数据库 DDL 增量脚本设计

脚本路径：`数据库/2.7.4/增量-onvif.sql`

```sql
-- 数据库/2.7.4/增量-onvif.sql
-- =============================================================
-- WVP-PRO ONVIF 协议支持增量表结构 (MySQL 8.0+ / 5.7)
-- =============================================================

-- 1. ONVIF 物理根设备表
CREATE TABLE IF NOT EXISTS `wvp_onvif_device` (
    `id`                     INT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    `name`                   VARCHAR(255) NOT NULL COMMENT '设备自定义名称',
    `ip`                     VARCHAR(50) NOT NULL COMMENT '设备 IPv4 地址',
    `port`                   INT NOT NULL DEFAULT 80 COMMENT 'ONVIF 服务端口 (通常为 80/8080/8899)',
    `username`               VARCHAR(100) NOT NULL COMMENT 'ONVIF 认证用户名',
    `password`               VARCHAR(100) NOT NULL COMMENT 'ONVIF 认证密码',
    `device_service_url`     VARCHAR(255) NOT NULL COMMENT '设备服务完整 XAddr',
    `media_service_url`      VARCHAR(255) DEFAULT NULL COMMENT 'Media 服务完整 XAddr',
    `ptz_service_url`        VARCHAR(255) DEFAULT NULL COMMENT 'PTZ 服务完整 XAddr',
    `imaging_service_url`    VARCHAR(255) DEFAULT NULL COMMENT 'Imaging 服务完整 XAddr',
    `manufacturer`           VARCHAR(100) DEFAULT NULL COMMENT '厂商信息 (Hikvision, Dahua 等)',
    `model`                  VARCHAR(100) DEFAULT NULL COMMENT '设备型号',
    `firmware_version`       VARCHAR(100) DEFAULT NULL COMMENT '固件版本',
    `serial_number`          VARCHAR(100) DEFAULT NULL COMMENT '硬件序列号',
    `mac`                    VARCHAR(50) DEFAULT NULL COMMENT 'MAC 地址',
    `clock_offset`           BIGINT DEFAULT 0 COMMENT '服务器与摄像头的时钟差 (毫秒，用于 WS-Security)',
    `status`                 TINYINT(1) DEFAULT 1 COMMENT '在线状态 (1:在线, 0:离线)',
    `media_server_id`        VARCHAR(50) DEFAULT 'auto' COMMENT '绑定的流媒体服务ID',
    `create_time`            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY `uk_onvif_ip_port` (`ip`, `port`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ONVIF 物理设备表';

-- 2. ONVIF 逻辑码流通道表 (与 Profile 一对一映射)
CREATE TABLE IF NOT EXISTS `wvp_onvif_channel` (
    `id`                     INT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    `device_id`              INT NOT NULL COMMENT '关联的 wvp_onvif_device 主键ID',
    `channel_index`          INT NOT NULL DEFAULT 1 COMMENT '物理镜头序号 (多目相机使用)',
    `profile_token`          VARCHAR(100) NOT NULL COMMENT 'ONVIF Profile Token (如 Profile_1)',
    `name`                   VARCHAR(255) DEFAULT NULL COMMENT 'Profile 名称 (如 MainStream / SubStream)',
    `video_encoding`         VARCHAR(50) DEFAULT NULL COMMENT '视频编码 (H264 / H265)',
    `resolution`             VARCHAR(50) DEFAULT NULL COMMENT '分辨率 (如 1920x1080)',
    `frame_rate`             INT DEFAULT NULL COMMENT '帧率',
    `bitrate`                INT DEFAULT NULL COMMENT '码率 (kbps)',
    `rtsp_url`               VARCHAR(512) DEFAULT NULL COMMENT '解析出的标准 RTSP 流拉取地址',
    `snapshot_url`           VARCHAR(512) DEFAULT NULL COMMENT '抓拍快照 URL',
    `has_ptz`                TINYINT(1) DEFAULT 0 COMMENT '该 Profile 是否具备 PTZ 控制能力 (1:是, 0:否)',
    `gb_device_id`           VARCHAR(50) DEFAULT NULL COMMENT '挂接的国标通道编码 (20位国标编码)',
    `create_time`            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    CONSTRAINT `fk_onvif_channel_dev` FOREIGN KEY (`device_id`) REFERENCES `wvp_onvif_device` (`id`) ON DELETE CASCADE,
    UNIQUE KEY `uk_onvif_dev_profile` (`device_id`, `profile_token`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ONVIF 码流通道表';
```

> [!NOTE]
> **PostgreSQL / Kingbase / H2 语法适配注意**：
> 在 PostgreSQL 与国产人大金仓环境中，主键采用 `SERIAL PRIMARY KEY`，时间戳采用 `TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP`；外键与唯一索引命名规则一致。

---

## 3 领域实体模型设计

### 3.1 物理设备实体 `OnvifDevice.java`

```java
// src/main/java/com/genersoft/iot/vmp/onvif/bean/OnvifDevice.java
package com.genersoft.iot.vmp.onvif.bean;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * ONVIF 物理根设备实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "ONVIF 物理设备实体")
public class OnvifDevice {

    @Schema(description = "主键ID")
    private Integer id;

    @Schema(description = "自定义设备名称")
    private String name;

    @Schema(description = "设备 IP 地址")
    private String ip;

    @Schema(description = "ONVIF 服务端口号")
    private Integer port;

    @Schema(description = "ONVIF 用户名")
    private String username;

    @Schema(description = "ONVIF 密码")
    private String password;

    @Schema(description = "Device 服务完整地址")
    private String deviceServiceUrl;

    @Schema(description = "Media 服务完整地址")
    private String mediaServiceUrl;

    @Schema(description = "PTZ 服务完整地址")
    private String ptzServiceUrl;

    @Schema(description = "Imaging 服务完整地址")
    private String imagingServiceUrl;

    @Schema(description = "设备厂商")
    private String manufacturer;

    @Schema(description = "设备型号")
    private String model;

    @Schema(description = "固件版本")
    private String firmwareVersion;

    @Schema(description = "设备序列号")
    private String serialNumber;

    @Schema(description = "MAC 地址")
    private String mac;

    @Schema(description = "时钟偏差 (毫秒)")
    private Long clockOffset;

    @Schema(description = "在线状态 (1:在线, 0:离线)")
    private Integer status;

    @Schema(description = "绑定的媒体节点 ID")
    private String mediaServerId;

    @Schema(description = "创建时间")
    private String createTime;

    @Schema(description = "更新时间")
    private String updateTime;
}
```

---

### 3.2 码流通道实体 `OnvifChannel.java`

```java
// src/main/java/com/genersoft/iot/vmp/onvif/bean/OnvifChannel.java
package com.genersoft.iot.vmp.onvif.bean;

import com.genersoft.iot.vmp.common.enums.ChannelDataType;
import com.genersoft.iot.vmp.gb28181.bean.CommonGBChannel;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

/**
 * ONVIF 逻辑码流通道实体 (对应 ONVIF Profile)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "ONVIF 逻辑码流通道")
public class OnvifChannel {

    @Schema(description = "主键ID")
    private Integer id;

    @Schema(description = "关联的物理设备ID")
    private Integer deviceId;

    @Schema(description = "镜头通道序号 (多目相机 1, 2, ...)")
    private Integer channelIndex;

    @Schema(description = "ONVIF Profile 唯一 Token")
    private String profileToken;

    @Schema(description = "Profile 名称 (如 MainStream, SubStream)")
    private String name;

    @Schema(description = "视频编码格式 (H264, H265)")
    private String videoEncoding;

    @Schema(description = "视频分辨率 (如 1920x1080)")
    private String resolution;

    @Schema(description = "视频帧率")
    private Integer frameRate;

    @Schema(description = "视频码率 (kbps)")
    private Integer bitrate;

    @Schema(description = "解析出的完整 RTSP 流拉取地址")
    private String rtspUrl;

    @Schema(description = "抓拍快照 URL")
    private String snapshotUrl;

    @Schema(description = "是否支持 PTZ 控制 (1:是, 0:否)")
    private Integer hasPtz;

    @Schema(description = "绑定的国标通道 20 位编码")
    private String gbDeviceId;

    @Schema(description = "创建时间")
    private String createTime;

    @Schema(description = "更新时间")
    private String updateTime;

    /**
     * 将当前 ONVIF 码流通道封装转换为 WVP 核心 CommonGBChannel 对象
     */
    public CommonGBChannel toCommonGBChannel(OnvifDevice parentDevice) {
        CommonGBChannel channel = new CommonGBChannel();
        channel.setGbDeviceId(this.gbDeviceId);
        channel.setGbName(this.name != null ? this.name : parentDevice.getName() + "-" + this.profileToken);
        channel.setGbManufacturer(parentDevice.getManufacturer());
        channel.setGbModel(parentDevice.getModel());
        channel.setGbStatus(parentDevice.getStatus() != null && parentDevice.getStatus() == 1 ? "ON" : "OFF");
        channel.setDataType(ChannelDataType.ONVIF);
        channel.setDataDeviceId(this.id);
        channel.setCreateTime(this.createTime);
        channel.setUpdateTime(this.updateTime);
        channel.setPtzType(this.hasPtz != null && this.hasPtz == 1 ? 1 : 0);
        return channel;
    }
}
```

---

## 4 MyBatis 数据访问层

### 4.1 设备 Mapper `OnvifDeviceMapper.java`

```java
// src/main/java/com/genersoft/iot/vmp/onvif/dao/OnvifDeviceMapper.java
package com.genersoft.iot.vmp.onvif.dao;

import com.genersoft.iot.vmp.onvif.bean.OnvifDevice;
import com.genersoft.iot.vmp.onvif.dao.provider.OnvifDeviceProvider;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface OnvifDeviceMapper {

    @Insert("INSERT INTO wvp_onvif_device (name, ip, port, username, password, device_service_url, " +
            "media_service_url, ptz_service_url, imaging_service_url, manufacturer, model, firmware_version, " +
            "serial_number, mac, clock_offset, status, media_server_id, create_time, update_time) " +
            "VALUES (#{name}, #{ip}, #{port}, #{username}, #{password}, #{deviceServiceUrl}, " +
            "#{mediaServiceUrl}, #{ptzServiceUrl}, #{imagingServiceUrl}, #{manufacturer}, #{model}, #{firmwareVersion}, " +
            "#{serialNumber}, #{mac}, #{clockOffset}, #{status}, #{mediaServerId}, #{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(OnvifDevice device);

    @Update("UPDATE wvp_onvif_device SET name=#{name}, username=#{username}, password=#{password}, " +
            "device_service_url=#{deviceServiceUrl}, media_service_url=#{mediaServiceUrl}, ptz_service_url=#{ptzServiceUrl}, " +
            "manufacturer=#{manufacturer}, model=#{model}, firmware_version=#{firmwareVersion}, serial_number=#{serialNumber}, " +
            "clock_offset=#{clockOffset}, status=#{status}, media_server_id=#{mediaServerId}, update_time=#{updateTime} " +
            "WHERE id=#{id}")
    int update(OnvifDevice device);

    @Delete("DELETE FROM wvp_onvif_device WHERE id=#{id}")
    int deleteById(@Param("id") Integer id);

    @Select("SELECT * FROM wvp_onvif_device WHERE id=#{id}")
    OnvifDevice selectById(@Param("id") Integer id);

    @Select("SELECT * FROM wvp_onvif_device WHERE ip=#{ip} AND port=#{port}")
    OnvifDevice selectByIpAndPort(@Param("ip") String ip, @Param("port") Integer port);

    @SelectProvider(type = OnvifDeviceProvider.class, method = "queryListSql")
    List<OnvifDevice> selectList(@Param("query") String query, @Param("status") Integer status);

    @Update("UPDATE wvp_onvif_device SET status=#{status}, update_time=NOW() WHERE id=#{id}")
    int updateStatus(@Param("id") Integer id, @Param("status") Integer status);
}
```

```java
// src/main/java/com/genersoft/iot/vmp/onvif/dao/provider/OnvifDeviceProvider.java
package com.genersoft.iot.vmp.onvif.dao.provider;

import org.apache.ibatis.annotations.Param;
import org.springframework.util.StringUtils;

public class OnvifDeviceProvider {

    public String queryListSql(@Param("query") String query, @Param("status") Integer status) {
        StringBuilder sql = new StringBuilder("SELECT * FROM wvp_onvif_device WHERE 1=1 ");
        if (StringUtils.hasText(query)) {
            sql.append("AND (name LIKE CONCAT('%', #{query}, '%') OR ip LIKE CONCAT('%', #{query}, '%') ")
               .append("OR manufacturer LIKE CONCAT('%', #{query}, '%') OR model LIKE CONCAT('%', #{query}, '%')) ");
        }
        if (status != null) {
            sql.append("AND status = #{status} ");
        }
        sql.append("ORDER BY id DESC");
        return sql.toString();
    }
}
```

---

### 4.2 通道 Mapper `OnvifChannelMapper.java`

```java
// src/main/java/com/genersoft/iot/vmp/onvif/dao/OnvifChannelMapper.java
package com.genersoft.iot.vmp.onvif.dao;

import com.genersoft.iot.vmp.onvif.bean.OnvifChannel;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface OnvifChannelMapper {

    @Insert("INSERT INTO wvp_onvif_channel (device_id, channel_index, profile_token, name, video_encoding, " +
            "resolution, frame_rate, bitrate, rtsp_url, snapshot_url, has_ptz, gb_device_id, create_time, update_time) " +
            "VALUES (#{deviceId}, #{channelIndex}, #{profileToken}, #{name}, #{videoEncoding}, #{resolution}, " +
            "#{frameRate}, #{bitrate}, #{rtspUrl}, #{snapshotUrl}, #{hasPtz}, #{gbDeviceId}, #{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(OnvifChannel channel);

    @Update("UPDATE wvp_onvif_channel SET name=#{name}, video_encoding=#{videoEncoding}, resolution=#{resolution}, " +
            "frame_rate=#{frameRate}, bitrate=#{bitrate}, rtsp_url=#{rtspUrl}, snapshot_url=#{snapshotUrl}, " +
            "has_ptz=#{hasPtz}, gb_device_id=#{gbDeviceId}, update_time=#{updateTime} WHERE id=#{id}")
    int update(OnvifChannel channel);

    @Delete("DELETE FROM wvp_onvif_channel WHERE id=#{id}")
    int deleteById(@Param("id") Integer id);

    @Delete("DELETE FROM wvp_onvif_channel WHERE device_id=#{deviceId}")
    int deleteByDeviceId(@Param("deviceId") Integer deviceId);

    @Select("SELECT * FROM wvp_onvif_channel WHERE id=#{id}")
    OnvifChannel selectById(@Param("id") Integer id);

    @Select("SELECT * FROM wvp_onvif_channel WHERE device_id=#{deviceId} ORDER BY channel_index ASC, id ASC")
    List<OnvifChannel> selectByDeviceId(@Param("deviceId") Integer deviceId);

    @Select("SELECT * FROM wvp_onvif_channel WHERE device_id=#{deviceId} AND profile_token=#{profileToken}")
    OnvifChannel selectByDeviceIdAndProfileToken(@Param("deviceId") Integer deviceId, @Param("profileToken") String profileToken);
}
```

---

## 5 设备服务层实现 `OnvifDeviceServiceImpl.java`

服务实现集成了时钟自校准、能力提取、Profile 遍历与国标通道的自动生成与级联纳管。

```java
// src/main/java/com/genersoft/iot/vmp/onvif/service/impl/OnvifDeviceServiceImpl.java
package com.genersoft.iot.vmp.onvif.service.impl;

import com.genersoft.iot.vmp.common.enums.ChannelDataType;
import com.genersoft.iot.vmp.conf.exception.ControllerException;
import com.genersoft.iot.vmp.gb28181.bean.CommonGBChannel;
import com.genersoft.iot.vmp.gb28181.service.IGbChannelService;
import com.genersoft.iot.vmp.onvif.bean.OnvifChannel;
import com.genersoft.iot.vmp.onvif.bean.OnvifDevice;
import com.genersoft.iot.vmp.onvif.client.OnvifSecurityHeader;
import com.genersoft.iot.vmp.onvif.client.OnvifSoapClient;
import com.genersoft.iot.vmp.onvif.client.OnvifXmlBuilder;
import com.genersoft.iot.vmp.onvif.client.OnvifXmlParser;
import com.genersoft.iot.vmp.onvif.dao.OnvifChannelMapper;
import com.genersoft.iot.vmp.onvif.dao.OnvifDeviceMapper;
import com.genersoft.iot.vmp.onvif.service.IOnvifDeviceService;
import com.genersoft.iot.vmp.utils.DateUtil;
import com.genersoft.iot.vmp.vmanager.bean.ErrorCode;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dom4j.Document;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class OnvifDeviceServiceImpl implements IOnvifDeviceService {

    private final OnvifDeviceMapper deviceMapper;
    private final OnvifChannelMapper channelMapper;
    private final OnvifSoapClient soapClient;
    private final IGbChannelService gbChannelService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OnvifDevice addDevice(OnvifDevice device) {
        OnvifDevice exist = deviceMapper.selectByIpAndPort(device.getIp(), device.getPort());
        if (exist != null) {
            throw new ControllerException(ErrorCode.ERROR100.getCode(), "该 IP 和端口的 ONVIF 设备已存在");
        }

        // 1. 自动拼接并校准 device_service_url
        if (device.getDeviceServiceUrl() == null || device.getDeviceServiceUrl().isEmpty()) {
            device.setDeviceServiceUrl("http://" + device.getIp() + ":" + device.getPort() + "/onvif/device_service");
        }

        // 2. 握手探测与时钟偏差测算
        probeAndSyncMetadata(device);

        device.setCreateTime(DateUtil.getNow());
        device.setUpdateTime(DateUtil.getNow());
        device.setStatus(1);
        deviceMapper.insert(device);

        // 3. 自动同步通道 Profile 并生成国标挂接
        syncChannels(device.getId());

        return device;
    }

    @Override
    public void probeAndSyncMetadata(OnvifDevice device) {
        try {
            // A. 免鉴权获取摄像机 UTC 时间
            String timeReq = OnvifXmlBuilder.buildGetSystemDateAndTime();
            String timeResp = soapClient.sendSoap(device.getDeviceServiceUrl(), null, timeReq);
            Instant cameraUtc = OnvifXmlParser.parseSystemDateTime(timeResp);
            long clockOffset = Duration.between(Instant.now(), cameraUtc).toMillis();
            device.setClockOffset(clockOffset);
            log.info("[ONVIF-Device] 设备 {}:{} 时钟偏差为 {}ms", device.getIp(), device.getPort(), clockOffset);

            // B. 获取 Capabilities 能力集 (Media & PTZ XAddr)
            String header = OnvifSecurityHeader.buildHeader(device.getUsername(), device.getPassword(), clockOffset);
            String capReq = OnvifXmlBuilder.buildGetCapabilities(header);
            String capResp = soapClient.sendSoap(device.getDeviceServiceUrl(), null, capReq);
            Map<String, String> services = OnvifXmlParser.parseCapabilities(capResp);
            if (services.containsKey("mediaUrl")) device.setMediaServiceUrl(services.get("mediaUrl"));
            if (services.containsKey("ptzUrl")) device.setPtzServiceUrl(services.get("ptzUrl"));
            if (services.containsKey("imagingUrl")) device.setImagingServiceUrl(services.get("imagingUrl"));

            // C. 获取硬件信息
            String devInfoReq = OnvifXmlBuilder.buildGetDeviceInformation(header);
            String devInfoResp = soapClient.sendSoap(device.getDeviceServiceUrl(), null, devInfoReq);
            Map<String, String> devInfo = OnvifXmlParser.parseDeviceInformation(devInfoResp);
            device.setManufacturer(devInfo.getOrDefault("manufacturer", "Generic"));
            device.setModel(devInfo.getOrDefault("model", "IPC"));
            device.setFirmwareVersion(devInfo.getOrDefault("firmwareVersion", ""));
            device.setSerialNumber(devInfo.getOrDefault("serialNumber", ""));
        } catch (Exception e) {
            log.error("[ONVIF-Device] 设备通信或认证失败: {}:{}", device.getIp(), device.getPort(), e);
            throw new ControllerException(ErrorCode.ERROR100.getCode(), "设备通信或鉴权失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void syncChannels(Integer deviceId) {
        OnvifDevice device = deviceMapper.selectById(deviceId);
        if (device == null) {
            throw new ControllerException(ErrorCode.ERROR404.getCode(), "设备不存在");
        }

        try {
            String header = OnvifSecurityHeader.buildHeader(device.getUsername(), device.getPassword(), device.getClockOffset());
            String profilesReq = OnvifXmlBuilder.buildGetProfiles(header);
            String mediaUrl = device.getMediaServiceUrl() != null ? device.getMediaServiceUrl() : device.getDeviceServiceUrl();
            String profilesResp = soapClient.sendSoap(mediaUrl, null, profilesReq);

            Document doc = DocumentHelper.parseText(profilesResp);
            List<Element> profileElements = OnvifXmlParser.findElementsIgnoreCase(doc.getRootElement(), "Profiles");

            int index = 1;
            for (Element profileElem : profileElements) {
                String token = profileElem.attributeValue("token");
                if (token == null) continue;

                Element nameElem = OnvifXmlParser.findElementIgnoreCase(profileElem, "Name");
                String name = (nameElem != null) ? nameElem.getTextTrim() : token;

                // 提取视频编码与分辨率
                String encoding = "H264";
                String resolution = "1920x1080";
                Element videoEncoder = OnvifXmlParser.findElementIgnoreCase(profileElem, "VideoEncoderConfiguration");
                if (videoEncoder != null) {
                    Element encElem = OnvifXmlParser.findElementIgnoreCase(videoEncoder, "Encoding");
                    if (encElem != null) encoding = encElem.getTextTrim();
                    Element resElem = OnvifXmlParser.findElementIgnoreCase(videoEncoder, "Resolution");
                    if (resElem != null) {
                        Element w = OnvifXmlParser.findElementIgnoreCase(resElem, "Width");
                        Element h = OnvifXmlParser.findElementIgnoreCase(resElem, "Height");
                        if (w != null && h != null) resolution = w.getTextTrim() + "x" + h.getTextTrim();
                    }
                }

                // 判断是否具备 PTZ 能力
                int hasPtz = (device.getPtzServiceUrl() != null &&
                        OnvifXmlParser.findElementIgnoreCase(profileElem, "PTZConfiguration") != null) ? 1 : 0;

                // 查询 RTSP URL
                String streamUriReq = OnvifXmlBuilder.buildGetStreamUri(header, token);
                String streamUriResp = soapClient.sendSoap(mediaUrl, null, streamUriReq);
                String rtspUrl = OnvifXmlParser.parseStreamUri(streamUriResp);

                // 查询快照 Snapshot URL
                String snapUrl = null;
                try {
                    String snapReq = OnvifXmlBuilder.buildGetSnapshotUri(header, token);
                    String snapResp = soapClient.sendSoap(mediaUrl, null, snapReq);
                    snapUrl = OnvifXmlParser.parseStreamUri(snapResp);
                } catch (Exception ignored) {}

                OnvifChannel channel = channelMapper.selectByDeviceIdAndProfileToken(deviceId, token);
                boolean isNew = (channel == null);
                if (isNew) {
                    channel = new OnvifChannel();
                    channel.setDeviceId(deviceId);
                    channel.setProfileToken(token);
                    channel.setCreateTime(DateUtil.getNow());
                    // 自动生成符合 GB/T 28181 标准的 20 位虚拟编码: 3402000000132 + 设备ID(3位) + 通道号(4位)
                    channel.setGbDeviceId(String.format("3402000000132%03d%04d", deviceId % 1000, index));
                }

                channel.setName(name);
                channel.setChannelIndex(index++);
                channel.setVideoEncoding(encoding);
                channel.setResolution(resolution);
                channel.setRtspUrl(rtspUrl);
                channel.setSnapshotUrl(snapUrl);
                channel.setHasPtz(hasPtz);
                channel.setUpdateTime(DateUtil.getNow());

                if (isNew) {
                    channelMapper.insert(channel);
                    // 挂接注册至平台统一核心通道表
                    CommonGBChannel gbChannel = channel.toCommonGBChannel(device);
                    gbChannelService.add(gbChannel);
                } else {
                    channelMapper.update(channel);
                    CommonGBChannel gbChannel = channel.toCommonGBChannel(device);
                    gbChannelService.update(gbChannel);
                }
            }
        } catch (Exception e) {
            log.error("[ONVIF-Device] 同步设备 {} 通道 Profile 失败: {}", deviceId, e.getMessage(), e);
            throw new ControllerException(ErrorCode.ERROR100.getCode(), "同步通道失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDevice(Integer deviceId) {
        List<OnvifChannel> channels = channelMapper.selectByDeviceId(deviceId);
        for (OnvifChannel ch : channels) {
            // 从核心国标通道表中注销
            CommonGBChannel gbChannel = gbChannelService.queryByDeviceId(ch.getGbDeviceId());
            if (gbChannel != null) {
                gbChannelService.delete(gbChannel.getGbId());
            }
        }
        channelMapper.deleteByDeviceId(deviceId);
        deviceMapper.deleteById(deviceId);
    }

    @Override
    public PageInfo<OnvifDevice> getDeviceList(int page, int count, String query, Integer status) {
        PageHelper.startPage(page, count);
        List<OnvifDevice> list = deviceMapper.selectList(query, status);
        return new PageInfo<>(list);
    }
}
```

---

## 6 阶段验收门禁标准

1. **数据库迁移测试**：在全新数据库实例上执行 `增量-onvif.sql`，无任何 SQL 语法错误，外键约束与唯一键索引创建完好；
2. **完整握手与解析测试**：接入一台真实或虚拟 ONVIF 摄像头（输入 IP、Port、用户名、密码），执行 `addDevice`：
   - 自动获取正确的 UTC 时间并计算 `clockOffset`；
   - 自动抓取 `Manufacturer`、`Model`、`MediaUrl`、`PtzUrl`；
   - 自动拉取 Profile 列表，解析出 H.264/H.265 及对应 RTSP URL；
3. **平台国标通道映射一致性**：查询 `wvp_device_channel` 表，确保新增了一条 `data_type = 4` 的记录，其 `data_device_id` 与 `wvp_onvif_channel.id` 完全吻合，可在国标通道列表中直接被检索与级联推送。
