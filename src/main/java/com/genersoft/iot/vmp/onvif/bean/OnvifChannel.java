// src/main/java/com/genersoft/iot/vmp/onvif/bean/OnvifChannel.java
package com.genersoft.iot.vmp.onvif.bean;

import com.genersoft.iot.vmp.common.enums.ChannelDataType;
import com.genersoft.iot.vmp.gb28181.bean.CommonGBChannel;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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

    @Schema(description = "WVP 核心通道主键 ID (wvp_device_channel.id)")
    private Integer gbId;

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
        channel.setGbName(this.name != null ? this.name : (parentDevice != null ? parentDevice.getName() + "-" + this.profileToken : this.profileToken));
        if (parentDevice != null) {
            channel.setGbManufacturer(parentDevice.getManufacturer());
            channel.setGbModel(parentDevice.getModel());
            channel.setGbStatus(parentDevice.getStatus() != null && parentDevice.getStatus() == 1 ? "ON" : "OFF");
        } else {
            channel.setGbStatus("ON");
        }
        channel.setDataType(ChannelDataType.ONVIF);
        channel.setDataDeviceId(this.id);
        channel.setCreateTime(this.createTime);
        channel.setUpdateTime(this.updateTime);
        channel.setGbPtzType(this.hasPtz != null && this.hasPtz == 1 ? 1 : 0);
        return channel;
    }
}
