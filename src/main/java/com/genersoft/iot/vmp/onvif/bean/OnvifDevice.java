// src/main/java/com/genersoft/iot/vmp/onvif/bean/OnvifDevice.java
package com.genersoft.iot.vmp.onvif.bean;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
