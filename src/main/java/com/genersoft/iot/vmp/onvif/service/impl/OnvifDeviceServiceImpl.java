// src/main/java/com/genersoft/iot/vmp/onvif/service/impl/OnvifDeviceServiceImpl.java
package com.genersoft.iot.vmp.onvif.service.impl;

import com.genersoft.iot.vmp.conf.exception.ControllerException;
import com.genersoft.iot.vmp.gb28181.bean.CommonGBChannel;
import com.genersoft.iot.vmp.gb28181.service.IGbChannelService;
import com.genersoft.iot.vmp.onvif.bean.OnvifChannel;
import com.genersoft.iot.vmp.onvif.bean.OnvifDevice;
import com.genersoft.iot.vmp.onvif.client.OnvifSecurityHeader;
import com.genersoft.iot.vmp.onvif.client.OnvifSoapClient;
import com.genersoft.iot.vmp.onvif.client.OnvifXmlBuilder;
import com.genersoft.iot.vmp.onvif.client.OnvifXmlParser;
import com.genersoft.iot.vmp.onvif.dao.OnvifChannelMapper;
import com.genersoft.iot.vmp.onvif.dao.OnvifDeviceMapper;
import com.genersoft.iot.vmp.onvif.service.IOnvifDeviceService;
import com.genersoft.iot.vmp.utils.DateUtil;
import com.genersoft.iot.vmp.vmanager.bean.ErrorCode;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dom4j.Document;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class OnvifDeviceServiceImpl implements IOnvifDeviceService {

    private final OnvifDeviceMapper deviceMapper;
    private final OnvifChannelMapper channelMapper;
    private final OnvifSoapClient soapClient;
    private final IGbChannelService gbChannelService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OnvifDevice addDevice(OnvifDevice device) {
        if (device.getPort() == null || device.getPort() == 0) {
            device.setPort(80);
        }

        OnvifDevice exist = deviceMapper.selectByIpAndPort(device.getIp(), device.getPort());
        if (exist != null) {
            throw new ControllerException(ErrorCode.ERROR100.getCode(), "该 IP 和端口的 ONVIF 设备已存在");
        }

        // 1. 自动拼接并校准 device_service_url
        if (device.getDeviceServiceUrl() == null || device.getDeviceServiceUrl().trim().isEmpty()) {
            device.setDeviceServiceUrl("http://" + device.getIp() + ":" + device.getPort() + "/onvif/device_service");
        }

        // 2. 握手探测与时钟偏差测算
        probeAndSyncMetadata(device);

        device.setCreateTime(DateUtil.getNow());
        device.setUpdateTime(DateUtil.getNow());
        device.setStatus(1);
        deviceMapper.insert(device);

        // 3. 自动同步通道 Profile 并生成国标挂接
        syncChannels(device.getId());

        return device;
    }

    @Override
    public void probeAndSyncMetadata(OnvifDevice device) {
        try {
            if (device.getDeviceServiceUrl() == null || device.getDeviceServiceUrl().trim().isEmpty()) {
                int port = (device.getPort() != null && device.getPort() > 0) ? device.getPort() : 80;
                device.setDeviceServiceUrl("http://" + device.getIp() + ":" + port + "/onvif/device_service");
            }

            // A. 免鉴权获取摄像机 UTC 时间
            String timeReq = OnvifXmlBuilder.buildGetSystemDateAndTime();
            String timeResp = soapClient.sendSoap(device.getDeviceServiceUrl(), null, timeReq);
            Instant cameraUtc = OnvifXmlParser.parseSystemDateTime(timeResp);
            long clockOffset = Duration.between(Instant.now(), cameraUtc).toMillis();
            device.setClockOffset(clockOffset);
            log.info("[ONVIF-Device] 设备 {}:{} 时钟偏差为 {}ms", device.getIp(), device.getPort(), clockOffset);

            // B. 获取 Capabilities 能力集 (Media & PTZ XAddr)
            String header = OnvifSecurityHeader.buildHeader(device.getUsername(), device.getPassword(), clockOffset);
            String capReq = OnvifXmlBuilder.buildGetCapabilities(header);
            String capResp = soapClient.sendSoap(device.getDeviceServiceUrl(), null, capReq);
            Map<String, String> services = OnvifXmlParser.parseCapabilities(capResp);
            if (services.containsKey("mediaUrl")) device.setMediaServiceUrl(services.get("mediaUrl"));
            if (services.containsKey("ptzUrl")) device.setPtzServiceUrl(services.get("ptzUrl"));
            if (services.containsKey("imagingUrl")) device.setImagingServiceUrl(services.get("imagingUrl"));

            // C. 获取硬件信息
            String devInfoReq = OnvifXmlBuilder.buildGetDeviceInformation(header);
            String devInfoResp = soapClient.sendSoap(device.getDeviceServiceUrl(), null, devInfoReq);
            Map<String, String> devInfo = OnvifXmlParser.parseDeviceInformation(devInfoResp);
            device.setManufacturer(devInfo.getOrDefault("manufacturer", "Generic"));
            device.setModel(devInfo.getOrDefault("model", "IPC"));
            device.setFirmwareVersion(devInfo.getOrDefault("firmwareVersion", ""));
            device.setSerialNumber(devInfo.getOrDefault("serialNumber", ""));
        } catch (Exception e) {
            log.error("[ONVIF-Device] 设备通信或认证失败: {}:{}", device.getIp(), device.getPort(), e);
            throw new ControllerException(ErrorCode.ERROR100.getCode(), "设备通信或鉴权失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void syncChannels(Integer deviceId) {
        OnvifDevice device = deviceMapper.selectById(deviceId);
        if (device == null) {
            throw new ControllerException(ErrorCode.ERROR404.getCode(), "设备不存在");
        }

        try {
            long clockOffset = device.getClockOffset() != null ? device.getClockOffset() : 0L;
            String header = OnvifSecurityHeader.buildHeader(device.getUsername(), device.getPassword(), clockOffset);
            String profilesReq = OnvifXmlBuilder.buildGetProfiles(header);
            String mediaUrl = device.getMediaServiceUrl() != null ? device.getMediaServiceUrl() : device.getDeviceServiceUrl();
            String profilesResp = soapClient.sendSoap(mediaUrl, null, profilesReq);

            Document doc = DocumentHelper.parseText(profilesResp);
            List<Element> profileElements = OnvifXmlParser.findElementsIgnoreCase(doc.getRootElement(), "Profiles");

            int index = 1;
            for (Element profileElem : profileElements) {
                String token = profileElem.attributeValue("token");
                if (token == null || token.trim().isEmpty()) continue;

                Element nameElem = OnvifXmlParser.findElementIgnoreCase(profileElem, "Name");
                String name = (nameElem != null && !nameElem.getTextTrim().isEmpty()) ? nameElem.getTextTrim() : token;

                // 提取视频编码与分辨率
                String encoding = "H264";
                String resolution = "1920x1080";
                Integer frameRate = null;
                Integer bitrate = null;

                Element videoEncoder = OnvifXmlParser.findElementIgnoreCase(profileElem, "VideoEncoderConfiguration");
                if (videoEncoder != null) {
                    Element encElem = OnvifXmlParser.findElementIgnoreCase(videoEncoder, "Encoding");
                    if (encElem != null) encoding = encElem.getTextTrim();
                    Element resElem = OnvifXmlParser.findElementIgnoreCase(videoEncoder, "Resolution");
                    if (resElem != null) {
                        Element w = OnvifXmlParser.findElementIgnoreCase(resElem, "Width");
                        Element h = OnvifXmlParser.findElementIgnoreCase(resElem, "Height");
                        if (w != null && h != null) resolution = w.getTextTrim() + "x" + h.getTextTrim();
                    }
                    Element rateElem = OnvifXmlParser.findElementIgnoreCase(videoEncoder, "FrameRateLimit");
                    if (rateElem != null) {
                        try {
                            frameRate = (int) Double.parseDouble(rateElem.getTextTrim());
                        } catch (Exception ignored) {}
                    }
                    Element bitrateElem = OnvifXmlParser.findElementIgnoreCase(videoEncoder, "BitrateLimit");
                    if (bitrateElem != null) {
                        try {
                            bitrate = Integer.parseInt(bitrateElem.getTextTrim());
                        } catch (Exception ignored) {}
                    }
                }

                // 判断是否具备 PTZ 能力
                int hasPtz = (device.getPtzServiceUrl() != null &&
                        OnvifXmlParser.findElementIgnoreCase(profileElem, "PTZConfiguration") != null) ? 1 : 0;

                // 查询 RTSP URL
                String streamUriReq = OnvifXmlBuilder.buildGetStreamUri(header, token);
                String streamUriResp = soapClient.sendSoap(mediaUrl, null, streamUriReq);
                String rtspUrl = OnvifXmlParser.parseStreamUri(streamUriResp);

                // 查询快照 Snapshot URL
                String snapUrl = null;
                try {
                    String snapReq = OnvifXmlBuilder.buildGetSnapshotUri(header, token);
                    String snapResp = soapClient.sendSoap(mediaUrl, null, snapReq);
                    snapUrl = OnvifXmlParser.parseStreamUri(snapResp);
                } catch (Exception ignored) {}

                OnvifChannel channel = channelMapper.selectByDeviceIdAndProfileToken(deviceId, token);
                boolean isNew = (channel == null);
                if (isNew) {
                    channel = new OnvifChannel();
                    channel.setDeviceId(deviceId);
                    channel.setProfileToken(token);
                    channel.setCreateTime(DateUtil.getNow());
                    // 自动生成符合 GB/T 28181 标准的 20 位虚拟编码: 3402000000132 + 设备ID(3位) + 通道号(4位)
                    channel.setGbDeviceId(String.format("3402000000132%03d%04d", deviceId % 1000, index));
                }

                channel.setName(name);
                channel.setChannelIndex(index++);
                channel.setVideoEncoding(encoding);
                channel.setResolution(resolution);
                channel.setFrameRate(frameRate);
                channel.setBitrate(bitrate);
                channel.setRtspUrl(rtspUrl);
                channel.setSnapshotUrl(snapUrl);
                channel.setHasPtz(hasPtz);
                channel.setUpdateTime(DateUtil.getNow());

                if (isNew) {
                    channelMapper.insert(channel);
                    // 挂接注册至平台统一核心通道表
                    CommonGBChannel gbChannel = channel.toCommonGBChannel(device);
                    gbChannelService.add(gbChannel);
                } else {
                    channelMapper.update(channel);
                    CommonGBChannel gbChannel = channel.toCommonGBChannel(device);
                    CommonGBChannel existingGb = gbChannelService.queryByDeviceId(channel.getGbDeviceId());
                    if (existingGb != null) {
                        gbChannel.setGbId(existingGb.getGbId());
                        gbChannelService.update(gbChannel);
                    } else {
                        gbChannelService.add(gbChannel);
                    }
                }
            }
        } catch (Exception e) {
            log.error("[ONVIF-Device] 同步设备 {} 通道 Profile 失败: {}", deviceId, e.getMessage(), e);
            throw new ControllerException(ErrorCode.ERROR100.getCode(), "同步通道失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDevice(Integer deviceId) {
        List<OnvifChannel> channels = channelMapper.selectByDeviceId(deviceId);
        for (OnvifChannel ch : channels) {
            // 从核心国标通道表中注销
            if (ch.getGbDeviceId() != null) {
                CommonGBChannel gbChannel = gbChannelService.queryByDeviceId(ch.getGbDeviceId());
                if (gbChannel != null) {
                    gbChannelService.delete(gbChannel.getGbId());
                }
            }
        }
        channelMapper.deleteByDeviceId(deviceId);
        deviceMapper.deleteById(deviceId);
    }

    @Override
    public PageInfo<OnvifDevice> getDeviceList(int page, int count, String query, Integer status) {
        PageHelper.startPage(page, count);
        List<OnvifDevice> list = deviceMapper.selectList(query, status);
        return new PageInfo<>(list);
    }

    @Override
    public OnvifDevice getDevice(Integer deviceId) {
        return deviceMapper.selectById(deviceId);
    }

    @Override
    public List<OnvifChannel> getChannelsByDeviceId(Integer deviceId) {
        return channelMapper.selectByDeviceId(deviceId);
    }
}
