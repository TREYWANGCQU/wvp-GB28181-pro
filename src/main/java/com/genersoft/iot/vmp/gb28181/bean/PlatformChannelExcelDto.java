// src/main/java/com/genersoft/iot/vmp/gb28181/bean/PlatformChannelExcelDto.java
package com.genersoft.iot.vmp.gb28181.bean;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "国标级联通道自定义国标编码批量导入导出 DTO")
public class PlatformChannelExcelDto {

    @ExcelProperty(value = "级联通道映射ID", index = 0)
    @ColumnWidth(16)
    @Schema(description = "级联通道关联主键ID (系统自动生成，用于精准定位，请勿随意修改)")
    private Integer id;

    @ExcelProperty(value = "通道名称", index = 1)
    @ColumnWidth(26)
    @Schema(description = "通道名称 (只读参考)")
    private String name;

    @ExcelProperty(value = "原始国标编号", index = 2)
    @ColumnWidth(26)
    @Schema(description = "原始国标编号 (只读参考)")
    private String gbDeviceId;

    @ExcelProperty(value = "设备厂商", index = 3)
    @ColumnWidth(18)
    @Schema(description = "设备厂商 (只读参考)")
    private String manufacturer;

    @ExcelProperty(value = "自定义国标编号", index = 4)
    @ColumnWidth(26)
    @Schema(description = "自定义国标编号 (必须为20位纯数字，留空则使用原国标编号)")
    private String customDeviceId;

    @ExcelProperty(value = "自定义通道名称", index = 5)
    @ColumnWidth(26)
    @Schema(description = "自定义通道名称 (选填，留空则使用原通道名称)")
    private String customName;
}
