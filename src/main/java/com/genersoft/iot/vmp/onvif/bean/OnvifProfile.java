// src/main/java/com/genersoft/iot/vmp/onvif/bean/OnvifProfile.java
package com.genersoft.iot.vmp.onvif.bean;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 码流 Profile 描述实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "ONVIF 码流 Profile 描述实体")
public class OnvifProfile {

    @Schema(description = "ONVIF Profile 唯一 Token")
    private String token;

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
}
