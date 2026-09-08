// src/main/java/com/genersoft/iot/vmp/onvif/service/impl/SourcePTZServiceForOnvifImpl.java
package com.genersoft.iot.vmp.onvif.service.impl;

import com.genersoft.iot.vmp.common.enums.ChannelDataType;
import com.genersoft.iot.vmp.gb28181.bean.*;
import com.genersoft.iot.vmp.gb28181.service.ISourcePTZService;
import com.genersoft.iot.vmp.onvif.bean.OnvifChannel;
import com.genersoft.iot.vmp.onvif.bean.OnvifDevice;
import com.genersoft.iot.vmp.onvif.client.OnvifSoapClient;
import com.genersoft.iot.vmp.onvif.client.OnvifXmlBuilder;
import com.genersoft.iot.vmp.onvif.client.OnvifXmlParser;
import com.genersoft.iot.vmp.onvif.dao.OnvifChannelMapper;
import com.genersoft.iot.vmp.onvif.dao.OnvifDeviceMapper;
import com.genersoft.iot.vmp.service.bean.ErrorCallback;
import com.genersoft.iot.vmp.vmanager.bean.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dom4j.Document;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * ONVIF 云台控制与预置位实现 (实现 ISourcePTZService)
 */
@Slf4j
@Service(ChannelDataType.PTZ_SERVICE + ChannelDataType.ONVIF)
@RequiredArgsConstructor
public class SourcePTZServiceForOnvifImpl implements ISourcePTZService {

    private final OnvifChannelMapper channelMapper;
    private final OnvifDeviceMapper deviceMapper;
    private final OnvifSoapClient soapClient;

    @Override
    public void ptz(CommonGBChannel channel, FrontEndControlCodeForPTZ code, ErrorCallback<String> callback) {
        OnvifChannel onvifChannel = channelMapper.selectById(channel.getDataDeviceId());
        if (onvifChannel == null) {
            callback.run(ErrorCode.ERROR404.getCode(), "ONVIF 通道不存在", null);
            return;
        }

        OnvifDevice device = deviceMapper.selectById(onvifChannel.getDeviceId());
        if (device == null || !StringUtils.hasText(device.getPtzServiceUrl())) {
            callback.run(ErrorCode.ERROR100.getCode(), "该设备不支持 PTZ 云台服务", null);
            return;
        }

        try {
            String ptzUrl = device.getPtzServiceUrl();
            String profileToken = onvifChannel.getProfileToken();
            long clockOffset = device.getClockOffset() != null ? device.getClockOffset() : 0L;

            // 1. 停止判断 (松开按键时下发全空指令)
            if (code.getPan() == null && code.getTilt() == null && code.getZoom() == null) {
                log.info("[ONVIF-PTZ] 下发刹车即时停止: device={}, profile={}", device.getId(), profileToken);
                String stopReq = OnvifXmlBuilder.buildStop(null, profileToken, true, true);
                soapClient.sendAuthenticatedSoap(ptzUrl, null, stopReq, device.getUsername(), device.getPassword(), clockOffset);
                callback.run(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMsg(), null);
                return;
            }

            // 2. 坐标与速度归一化计算
            double vx = 0.0;
            double vy = 0.0;
            double vz = 0.0;

            if (code.getPan() != null) {
                int panSpeed = (code.getPanSpeed() != null && code.getPanSpeed() > 0) ? code.getPanSpeed() : 128;
                double speedNorm = Math.min(1.0, panSpeed / 255.0);
                vx = (code.getPan() == 0) ? -speedNorm : speedNorm; // 0 左, 1 右
            }

            if (code.getTilt() != null) {
                int tiltSpeed = (code.getTiltSpeed() != null && code.getTiltSpeed() > 0) ? code.getTiltSpeed() : 128;
                double speedNorm = Math.min(1.0, tiltSpeed / 255.0);
                vy = (code.getTilt() == 0) ? speedNorm : -speedNorm; // 0 上, 1 下
            }

            if (code.getZoom() != null) {
                int zoomSpeed = (code.getZoomSpeed() != null && code.getZoomSpeed() > 0) ? code.getZoomSpeed() : 128;
                double speedNorm = Math.min(1.0, zoomSpeed / 255.0);
                vz = (code.getZoom() == 0) ? -speedNorm : speedNorm; // 0 缩小, 1 放大
            }

            log.info("[ONVIF-PTZ] 连续转动: vx={}, vy={}, vz={}, device={}, profile={}", vx, vy, vz, device.getId(), profileToken);
            String moveReq = OnvifXmlBuilder.buildContinuousMove(null, profileToken, vx, vy, vz);
            soapClient.sendAuthenticatedSoap(ptzUrl, null, moveReq, device.getUsername(), device.getPassword(), clockOffset);

            callback.run(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMsg(), null);
        } catch (Exception e) {
            log.error("[ONVIF-PTZ] 云台控制指令发送失败: {}", e.getMessage(), e);
            callback.run(ErrorCode.ERROR100.getCode(), "PTZ 控制失败: " + e.getMessage(), null);
        }
    }

    @Override
    public void preset(CommonGBChannel channel, FrontEndControlCodeForPreset code, ErrorCallback<String> callback) {
        OnvifChannel onvifChannel = channelMapper.selectById(channel.getDataDeviceId());
        if (onvifChannel == null) {
            callback.run(ErrorCode.ERROR404.getCode(), "通道不存在", null);
            return;
        }

        OnvifDevice device = deviceMapper.selectById(onvifChannel.getDeviceId());
        if (device == null || !StringUtils.hasText(device.getPtzServiceUrl())) {
            callback.run(ErrorCode.ERROR100.getCode(), "不支持 PTZ", null);
            return;
        }

        try {
            String ptzUrl = device.getPtzServiceUrl();
            String profileToken = onvifChannel.getProfileToken();
            String presetToken = String.valueOf(code.getPresetId());
            long clockOffset = device.getClockOffset() != null ? device.getClockOffset() : 0L;

            if (code.getCode() == 2) {
                // 调用预置位 (GotoPreset)
                log.info("[ONVIF-Preset] 调用预置位: profile={}, presetToken={}", profileToken, presetToken);
                String gotoReq = OnvifXmlBuilder.buildGotoPreset(null, profileToken, presetToken);
                soapClient.sendAuthenticatedSoap(ptzUrl, null, gotoReq, device.getUsername(), device.getPassword(), clockOffset);
                callback.run(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMsg(), null);
            } else if (code.getCode() == 1) {
                // 设置预置位 (SetPreset)
                String setReq = String.format("<tptz:SetPreset><tptz:ProfileToken>%s</tptz:ProfileToken><tptz:PresetName>%s</tptz:PresetName><tptz:PresetToken>%s</tptz:PresetToken></tptz:SetPreset>",
                        profileToken, code.getPresetName() != null ? code.getPresetName() : presetToken, presetToken);
                soapClient.sendAuthenticatedSoap(ptzUrl, null, setReq, device.getUsername(), device.getPassword(), clockOffset);
                callback.run(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMsg(), null);
            } else if (code.getCode() == 3) {
                // 删除预置位 (RemovePreset)
                String delReq = String.format("<tptz:RemovePreset><tptz:ProfileToken>%s</tptz:ProfileToken><tptz:PresetToken>%s</tptz:PresetToken></tptz:RemovePreset>",
                        profileToken, presetToken);
                soapClient.sendAuthenticatedSoap(ptzUrl, null, delReq, device.getUsername(), device.getPassword(), clockOffset);
                callback.run(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMsg(), null);
            } else {
                callback.run(ErrorCode.ERROR100.getCode(), "不支持的预置位指令: " + code.getCode(), null);
            }
        } catch (Exception e) {
            log.error("[ONVIF-Preset] 预置位操作失败: {}", e.getMessage(), e);
            callback.run(ErrorCode.ERROR100.getCode(), "预置位操作失败: " + e.getMessage(), null);
        }
    }

    @Override
    public void queryPreset(CommonGBChannel channel, ErrorCallback<List<Preset>> callback) {
        OnvifChannel onvifChannel = channelMapper.selectById(channel.getDataDeviceId());
        if (onvifChannel == null) {
            callback.run(ErrorCode.ERROR404.getCode(), "通道不存在", null);
            return;
        }

        OnvifDevice device = deviceMapper.selectById(onvifChannel.getDeviceId());
        if (device == null || !StringUtils.hasText(device.getPtzServiceUrl())) {
            callback.run(ErrorCode.SUCCESS.getCode(), "不支持 PTZ", new ArrayList<>());
            return;
        }

        try {
            String ptzUrl = device.getPtzServiceUrl();
            String profileToken = onvifChannel.getProfileToken();
            long clockOffset = device.getClockOffset() != null ? device.getClockOffset() : 0L;
            String getPresetsReq = OnvifXmlBuilder.buildGetPresets(null, profileToken);
            String respXml = soapClient.sendAuthenticatedSoap(ptzUrl, null, getPresetsReq, device.getUsername(), device.getPassword(), clockOffset);

            Document doc = DocumentHelper.parseText(respXml);
            List<Element> presetElements = OnvifXmlParser.findElementsIgnoreCase(doc.getRootElement(), "Preset");

            List<Preset> list = new ArrayList<>();
            for (Element elem : presetElements) {
                String token = elem.attributeValue("token");
                Element nameElem = OnvifXmlParser.findElementIgnoreCase(elem, "Name");
                String name = (nameElem != null) ? nameElem.getTextTrim() : token;

                Preset preset = new Preset();
                preset.setPresetId(token);
                preset.setPresetName(name);
                list.add(preset);
            }
            callback.run(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMsg(), list);
        } catch (Exception e) {
            log.error("[ONVIF-Preset] 查询预置位失败: {}", e.getMessage(), e);
            callback.run(ErrorCode.ERROR100.getCode(), "查询预置位列表失败: " + e.getMessage(), null);
        }
    }

    // 默认空实现或返回不支持的特定硬件接口
    @Override public void fi(CommonGBChannel channel, FrontEndControlCodeForFI code, ErrorCallback<String> cb) { cb.run(ErrorCode.ERROR486.getCode(), "不支持光圈/聚焦控制", null); }
    @Override public void tour(CommonGBChannel channel, FrontEndControlCodeForTour code, ErrorCallback<String> cb) { cb.run(ErrorCode.ERROR486.getCode(), "不支持巡航", null); }
    @Override public void scan(CommonGBChannel channel, FrontEndControlCodeForScan code, ErrorCallback<String> cb) { cb.run(ErrorCode.ERROR486.getCode(), "不支持自动扫描", null); }
    @Override public void auxiliary(CommonGBChannel channel, FrontEndControlCodeForAuxiliary code, ErrorCallback<String> cb) { cb.run(ErrorCode.ERROR486.getCode(), "不支持辅助开关", null); }
    @Override public void wiper(CommonGBChannel channel, FrontEndControlCodeForWiper code, ErrorCallback<String> cb) { cb.run(ErrorCode.ERROR486.getCode(), "不支持雨刷控制", null); }
    @Override public void homePosition(CommonGBChannel channel, Boolean enabled, Integer resetTime, Integer presetIndex, ErrorCallback<String> cb) { cb.run(ErrorCode.ERROR486.getCode(), "不支持看守位", null); }
    @Override public void dragZoom(CommonGBChannel channel, FrontEndControlCodeForDragZoom code, ErrorCallback<String> cb) { cb.run(ErrorCode.ERROR486.getCode(), "不支持拉框放大", null); }
}
