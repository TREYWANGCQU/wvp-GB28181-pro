// src/main/java/com/genersoft/iot/vmp/onvif/dto/OnvifDeviceImportDto.java
package com.genersoft.iot.vmp.onvif.dto;

import com.alibaba.excel.annotation.ExcelProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "ONVIF 设备批量导入 DTO")
public class OnvifDeviceImportDto {

    @ExcelProperty(value = "设备名称", index = 0)
    @Schema(description = "设备名称 (必填)")
    private String name;

    @ExcelProperty(value = "IP地址", index = 1)
    @Schema(description = "IP 地址 (必填)")
    private String ip;

    @ExcelProperty(value = "服务端口", index = 2)
    @Schema(description = "服务端口 (非必填，默认 80)")
    private Integer port;

    @ExcelProperty(value = "用户名", index = 3)
    @Schema(description = "用户名 (必填)")
    private String username;

    @ExcelProperty(value = "密码", index = 4)
    @Schema(description = "密码 (必填)")
    private String password;

    @ExcelProperty(value = "流媒体节点ID", index = 5)
    @Schema(description = "流媒体节点ID (选填)")
    private String mediaServerId;

    @ExcelProperty(value = "主通道国标编号", index = 6)
    @Schema(description = "主通道国标编号 (选填，20位)")
    private String gbDeviceId;

    @ExcelProperty(value = "行政区划编码", index = 7)
    @Schema(description = "行政区划编码 (选填)")
    private String civilCode;
}
