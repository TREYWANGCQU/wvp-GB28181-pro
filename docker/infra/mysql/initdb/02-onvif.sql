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
