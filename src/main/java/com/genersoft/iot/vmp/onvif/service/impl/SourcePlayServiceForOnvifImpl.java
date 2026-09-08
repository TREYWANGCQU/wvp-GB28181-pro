// src/main/java/com/genersoft/iot/vmp/onvif/service/impl/SourcePlayServiceForOnvifImpl.java
package com.genersoft.iot.vmp.onvif.service.impl;

import com.genersoft.iot.vmp.common.StreamInfo;
import com.genersoft.iot.vmp.common.enums.ChannelDataType;
import com.genersoft.iot.vmp.conf.DynamicTask;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.gb28181.bean.CommonGBChannel;
import com.genersoft.iot.vmp.gb28181.bean.Platform;
import com.genersoft.iot.vmp.gb28181.service.ISourcePlayService;
import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.media.event.hook.Hook;
import com.genersoft.iot.vmp.media.event.hook.HookSubscribe;
import com.genersoft.iot.vmp.media.event.hook.HookType;
import com.genersoft.iot.vmp.media.service.IMediaServerService;
import com.genersoft.iot.vmp.onvif.bean.OnvifChannel;
import com.genersoft.iot.vmp.onvif.bean.OnvifDevice;
import com.genersoft.iot.vmp.onvif.dao.OnvifChannelMapper;
import com.genersoft.iot.vmp.onvif.dao.OnvifDeviceMapper;
import com.genersoft.iot.vmp.service.bean.ErrorCallback;
import com.genersoft.iot.vmp.service.bean.InviteErrorCode;
import com.genersoft.iot.vmp.vmanager.bean.ErrorCode;
import com.genersoft.iot.vmp.vmanager.bean.WVPResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ONVIF 通道点播拉流调度实现 (实现 ISourcePlayService)
 */
@Slf4j
@Service(ChannelDataType.PLAY_SERVICE + ChannelDataType.ONVIF)
@RequiredArgsConstructor
public class SourcePlayServiceForOnvifImpl implements ISourcePlayService {

    private final OnvifChannelMapper channelMapper;
    private final OnvifDeviceMapper deviceMapper;
    private final IMediaServerService mediaServerService;
    private final HookSubscribe hookSubscribe;
    private final DynamicTask dynamicTask;
    private final UserSetting userSetting;

    private static final Map<String, String> streamKeyMap = new ConcurrentHashMap<>();

    public static void registerStreamKey(String stream, String key) {
        if (stream != null && key != null) {
            streamKeyMap.put(stream, key);
        }
    }

    public static String removeStreamKey(String stream) {
        if (stream == null) {
            return null;
        }
        return streamKeyMap.remove(stream);
    }

    @Override
    public void play(CommonGBChannel channel, Platform platform, Boolean record, ErrorCallback<StreamInfo> callback) {
        log.info("[ONVIF-Play] 开始点播通道, dataDeviceId: {}, gbDeviceId: {}", channel.getDataDeviceId(), channel.getGbDeviceId());

        OnvifChannel onvifChannel = channelMapper.selectById(channel.getDataDeviceId());
        if (onvifChannel == null) {
            callback.run(ErrorCode.ERROR404.getCode(), "ONVIF 通道不存在", null);
            return;
        }

        OnvifDevice device = deviceMapper.selectById(onvifChannel.getDeviceId());
        if (device == null) {
            callback.run(ErrorCode.ERROR404.getCode(), "ONVIF 所属设备不存在", null);
            return;
        }

        String rawRtspUrl = onvifChannel.getRtspUrl();
        if (!StringUtils.hasText(rawRtspUrl)) {
            callback.run(ErrorCode.ERROR100.getCode(), "该通道未解析到有效 RTSP 地址", null);
            return;
        }

        // 1. 构建注入认证信息的 RTSP 地址 (处理特殊字符转义)
        String authRtspUrl = buildAuthenticatedRtspUrl(rawRtspUrl, device.getUsername(), device.getPassword());

        // 2. 选取负载均衡最优的流媒体节点
        MediaServer mediaServer = getMediaServer(device.getMediaServerId());
        if (mediaServer == null) {
            callback.run(ErrorCode.ERROR100.getCode(), "未找到可用的 ZLMediaKit 节点", null);
            return;
        }

        String app = "onvif";
        String stream = String.format("onvif_%d_%d", device.getId(), onvifChannel.getId());

        // 3. 检查流是否已在播放
        StreamInfo existingStream = mediaServerService.getStreamInfoByAppAndStreamWithCheck(app, stream, mediaServer.getId(), null, false);
        if (existingStream != null) {
            log.info("[ONVIF-Play] 流已存在复用播放: app={}, stream={}", app, stream);
            callback.run(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMsg(), existingStream);
            return;
        }

        // 4. 设置收流超时检测任务 (playTimeout 为毫秒)
        String timeoutTaskKey = UUID.randomUUID().toString();
        Hook rtpArrivalHook = Hook.getInstance(HookType.on_media_arrival, app, stream, mediaServer.getId());

        int playTimeoutMs = (userSetting.getPlayTimeout() != null && userSetting.getPlayTimeout() > 0) ? userSetting.getPlayTimeout() : 10000;
        int playTimeoutSec = playTimeoutMs / 1000;

        dynamicTask.startDelay(timeoutTaskKey, () -> {
            log.warn("[ONVIF-Play] 收流超时 ({}ms): app={}, stream={}", playTimeoutMs, app, stream);
            hookSubscribe.removeSubscribe(rtpArrivalHook);
            // 超时清理拉流代理
            stopStream(mediaServer, app, stream);
            callback.run(InviteErrorCode.ERROR_FOR_STREAM_TIMEOUT.getCode(), "ONVIF 摄像头 RTSP 拉流超时", null);
        }, playTimeoutMs);

        // 5. 订阅流上线 Hook 事件
        hookSubscribe.addSubscribe(rtpArrivalHook, (hookData) -> {
            log.info("[ONVIF-Play] ZLM 收流成功: app={}, stream={}", hookData.getApp(), hookData.getStream());
            dynamicTask.stop(timeoutTaskKey);
            hookSubscribe.removeSubscribe(rtpArrivalHook);

            StreamInfo streamInfo = mediaServerService.getStreamInfoByAppAndStream(mediaServer, hookData.getApp(), hookData.getStream(), hookData.getMediaInfo(), null);
            callback.run(InviteErrorCode.SUCCESS.getCode(), InviteErrorCode.SUCCESS.getMsg(), streamInfo);
        });

        // 6. 下发 addStreamProxy 命令至 ZLMediaKit
        // rtpType: 0 (TCP/UDP 自适应，优先 TCP 抵抗丢包花屏)
        boolean enableMp4 = (record != null && record);
        WVPResult<String> result = mediaServerService.addStreamProxy(mediaServer, app, stream, authRtspUrl, true, enableMp4, "0", playTimeoutSec);
        if (result.getCode() != 0) {
            dynamicTask.stop(timeoutTaskKey);
            hookSubscribe.removeSubscribe(rtpArrivalHook);
            log.error("[ONVIF-Play] 调用 ZLM addStreamProxy 失败: {}", result.getMsg());
            callback.run(ErrorCode.ERROR100.getCode(), "调用流媒体节点创建代理失败: " + result.getMsg(), null);
        } else if (result.getData() != null) {
            registerStreamKey(stream, result.getData());
        }
    }

    @Override
    public void stopPlay(CommonGBChannel channel) {
        OnvifChannel onvifChannel = channelMapper.selectById(channel.getDataDeviceId());
        if (onvifChannel == null) return;

        OnvifDevice device = deviceMapper.selectById(onvifChannel.getDeviceId());
        if (device == null) return;

        String app = "onvif";
        String stream = String.format("onvif_%d_%d", device.getId(), onvifChannel.getId());
        MediaServer mediaServer = getMediaServer(device.getMediaServerId());

        if (mediaServer != null) {
            log.info("[ONVIF-Play] 主动停止点播代理: app={}, stream={}", app, stream);
            stopStream(mediaServer, app, stream);
        }
    }

    public void stopStream(MediaServer mediaServer, String app, String stream) {
        String streamKey = removeStreamKey(stream);
        if (streamKey != null) {
            try {
                mediaServerService.delStreamProxy(mediaServer, streamKey);
            } catch (Exception e) {
                log.warn("[ONVIF-Play] 删除拉流代理异常: {}", e.getMessage());
            }
        }
        try {
            mediaServerService.closeStreams(mediaServer, app, stream);
        } catch (Exception e) {
            log.warn("[ONVIF-Play] 关闭流通道异常: {}", e.getMessage());
        }
    }

    @Override
    public void getSnap(CommonGBChannel channel, ErrorCallback<byte[]> callback) {
        // ONVIF 快照抓取能力
        callback.run(ErrorCode.ERROR486.getCode(), "快照请直接访问 snapshot_url", null);
    }

    private MediaServer getMediaServer(String mediaServerId) {
        if (!StringUtils.hasText(mediaServerId) || "auto".equalsIgnoreCase(mediaServerId)) {
            return mediaServerService.getMediaServerForMinimumLoad(null);
        }
        return mediaServerService.getOne(mediaServerId);
    }

    /**
     * 将认证凭证注入到 RTSP URL 中: rtsp://username:password@ip:port/path
     */
    private String buildAuthenticatedRtspUrl(String rtspUrl, String username, String password) {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            return rtspUrl;
        }
        try {
            if (rtspUrl.startsWith("rtsp://")) {
                String sub = rtspUrl.substring(7);
                // 若 URL 中已自带 @ 符号，则直接返回
                if (sub.contains("@")) {
                    return rtspUrl;
                }
                String safeUser = URLEncoder.encode(username, StandardCharsets.UTF_8);
                String safePwd = URLEncoder.encode(password, StandardCharsets.UTF_8);
                return "rtsp://" + safeUser + ":" + safePwd + "@" + sub;
            }
        } catch (Exception e) {
            log.warn("[ONVIF-Play] 构建带认证 RTSP URL 失败: {}", e.getMessage());
        }
        return rtspUrl;
    }
}
