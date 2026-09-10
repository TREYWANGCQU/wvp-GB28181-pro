// src/main/java/com/genersoft/iot/vmp/onvif/dto/OnvifDeviceExportRequest.java
package com.genersoft.iot.vmp.onvif.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "ONVIF 设备批量导出请求对象")
public class OnvifDeviceExportRequest {

    @Schema(description = "选中的已纳管设备ID集合 (适用于主表批量导出)")
    private List<Integer> deviceIds;

    @Schema(description = "前台传入的待导出设备列表 (适用于搜寻发现界面批量导出)")
    private List<OnvifDeviceImportDto> customDevices;
}
