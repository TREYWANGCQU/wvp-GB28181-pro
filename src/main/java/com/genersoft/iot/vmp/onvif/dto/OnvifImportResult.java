// src/main/java/com/genersoft/iot/vmp/onvif/dto/OnvifImportResult.java
package com.genersoft.iot.vmp.onvif.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "ONVIF 设备批量导入结果")
public class OnvifImportResult {

    @Schema(description = "总计条数")
    private int total;

    @Schema(description = "成功导入条数")
    private int success;

    @Schema(description = "失败条数")
    private int failure;

    @Builder.Default
    @Schema(description = "错误详情清单")
    private List<String> errorMessages = new ArrayList<>();
}
