// src/main/java/com/genersoft/iot/vmp/onvif/bean/OnvifProbeResult.java
package com.genersoft.iot.vmp.onvif.bean;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.List;

/**
 * WS-Discovery 搜寻发现结果实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OnvifProbeResult {

    /** 设备 IP 地址 */
    private String ip;

    /** ONVIF 服务端口号 (通常为 80, 8080, 8899) */
    private int port;

    /** 设备服务 XAddr 地址 (如 http://192.168.1.108/onvif/device_service) */
    private String deviceServiceUrl;

    /** 设备全球唯一 Endpoint UUID */
    private String endpointReference;

    /** 类型标识列表 (如 dn:NetworkVideoTransmitter) */
    private List<String> types;

    /** 厂商名称 (从 Scopes 中解析，如 Hikvision, Dahua) */
    private String manufacturer;

    /** 设备型号 (从 Scopes 中解析) */
    private String model;

    /** 硬件名称或位置信息 (从 Scopes 中解析) */
    private String name;

    /** 原始 Scopes 集合 */
    private List<String> scopes;
}
