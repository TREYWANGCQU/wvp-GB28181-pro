// src/main/java/com/genersoft/iot/vmp/onvif/service/impl/SourceOtherServiceForOnvifImpl.java
package com.genersoft.iot.vmp.onvif.service.impl;

import com.genersoft.iot.vmp.common.enums.ChannelDataType;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.gb28181.bean.MobilePosition;
import com.genersoft.iot.vmp.gb28181.service.ISourceOtherService;
import com.genersoft.iot.vmp.media.bean.MediaInfo;
import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.media.service.IMediaServerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * ONVIF 辅助服务 (无人观看自动停流)
 */
@Slf4j
@Service(ChannelDataType.OTHER_SERVICE + ChannelDataType.ONVIF)
@RequiredArgsConstructor
public class SourceOtherServiceForOnvifImpl implements ISourceOtherService {

    private final UserSetting userSetting;
    private final IMediaServerService mediaServerService;
    private final SourcePlayServiceForOnvifImpl sourcePlayService;

    @Override
    public Boolean closeStreamOnNoneReader(String mediaServerId, String app, String stream, String schema) {
        // 仅拦截 onvif 应用的流
        if (!"onvif".equals(app)) {
            return null;
        }

        // 检查全局设置是否开启了无人观看按需拉流停流
        if (userSetting.getStreamOnDemand()) {
            MediaServer mediaServer = mediaServerService.getOne(mediaServerId);
            if (mediaServer == null) {
                return false;
            }
            // 查询 ZLM 中该流的全局状态与总活跃读者数
            MediaInfo mediaInfo = mediaServerService.getMediaInfo(mediaServer, app, stream);
            if (mediaInfo != null && mediaInfo.getReaderCount() != null && mediaInfo.getReaderCount() > 0) {
                // 仍有其它协议（如 ws-flv / rtc）观众正在观看，单协议切片（如 hls）无人观看时严禁关闭主代理
                return false;
            }
            log.info("[ONVIF-Stream] 检测到所有协议均无活跃读者 (totalReaderCount=0)，释放拉流代理: app={}, stream={}", app, stream);
            sourcePlayService.stopStream(mediaServer, app, stream);
            return true;
        }
        return false;
    }

    @Override
    public Boolean addChannelIdForMobilePosition(List<? extends MobilePosition> mobilePositionList) {
        // 固定安装摄像机不支持移动轨迹
        return null;
    }
}
