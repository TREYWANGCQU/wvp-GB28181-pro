// src/main/java/com/genersoft/iot/vmp/onvif/client/OnvifXmlBuilder.java
package com.genersoft.iot.vmp.onvif.client;

import java.util.Locale;

/**
 * 预编译 SOAP XML 报文组装工厂
 */
public class OnvifXmlBuilder {

    private static final String ENVELOPE_TEMPLATE =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" " +
            "xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\" " +
            "xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\" " +
            "xmlns:tptz=\"http://www.onvif.org/ver20/ptz/wsdl\" " +
            "xmlns:tt=\"http://www.onvif.org/ver10/schema\">\n" +
            "  %s\n" +
            "  <s:Body>\n" +
            "    %s\n" +
            "  </s:Body>\n" +
            "</s:Envelope>";

    public static String wrapEnvelope(String headerXml, String bodyXml) {
        String safeHeader = (headerXml != null && !headerXml.trim().isEmpty()) ? headerXml : "<s:Header/>";
        return String.format(ENVELOPE_TEMPLATE, safeHeader, bodyXml);
    }

    /** 1. 获取设备系统时间 (无鉴权接口) */
    public static String buildGetSystemDateAndTime() {
        return wrapEnvelope("<s:Header/>", "<tds:GetSystemDateAndTime/>");
    }

    /** 2. 获取设备硬件信息 (需要鉴权) */
    public static String buildGetDeviceInformation(String headerXml) {
        return wrapEnvelope(headerXml, "<tds:GetDeviceInformation/>");
    }

    public static String buildGetDeviceInformation() {
        return buildGetDeviceInformation(null);
    }

    /** 3. 获取设备服务能力集 (Capabilities) */
    public static String buildGetCapabilities(String headerXml) {
        return wrapEnvelope(headerXml,
                "<tds:GetCapabilities>\n" +
                "  <tds:Category>All</tds:Category>\n" +
                "</tds:GetCapabilities>");
    }

    public static String buildGetCapabilities() {
        return buildGetCapabilities(null);
    }

    /** 4. 获取所有媒体 Profile 列表 */
    public static String buildGetProfiles(String headerXml) {
        return wrapEnvelope(headerXml, "<trt:GetProfiles/>");
    }

    public static String buildGetProfiles() {
        return buildGetProfiles(null);
    }

    /** 5. 获取指定 Profile 的 RTSP 流地址 */
    public static String buildGetStreamUri(String headerXml, String profileToken) {
        return wrapEnvelope(headerXml,
                String.format(
                    "<trt:GetStreamUri>\n" +
                    "  <trt:StreamSetup>\n" +
                    "    <tt:Stream>RTP-Unicast</tt:Stream>\n" +
                    "    <tt:Transport>\n" +
                    "      <tt:Protocol>RTSP</tt:Protocol>\n" +
                    "    </tt:Transport>\n" +
                    "  </trt:StreamSetup>\n" +
                    "  <trt:ProfileToken>%s</trt:ProfileToken>\n" +
                    "</trt:GetStreamUri>",
                    profileToken
                ));
    }

    public static String buildGetStreamUri(String profileToken) {
        return buildGetStreamUri(null, profileToken);
    }

    /** 6. 获取抓拍快照 Snapshot 地址 */
    public static String buildGetSnapshotUri(String headerXml, String profileToken) {
        return wrapEnvelope(headerXml,
                String.format(
                    "<trt:GetSnapshotUri>\n" +
                    "  <trt:ProfileToken>%s</trt:ProfileToken>\n" +
                    "</trt:GetSnapshotUri>",
                    profileToken
                ));
    }

    public static String buildGetSnapshotUri(String profileToken) {
        return buildGetSnapshotUri(null, profileToken);
    }

    /** 7. 云台平滑转动与变倍控制 (ContinuousMove) */
    public static String buildContinuousMove(String headerXml, String profileToken, double pan, double tilt, double zoom) {
        return wrapEnvelope(headerXml,
                String.format(Locale.US,
                    "<tptz:ContinuousMove>\n" +
                    "  <tptz:ProfileToken>%s</tptz:ProfileToken>\n" +
                    "  <tptz:Velocity>\n" +
                    "    <tt:PanTilt x=\"%.3f\" y=\"%.3f\" space=\"http://www.onvif.org/ver10/tptz/PanTiltSpaces/VelocityGenericSpace\"/>\n" +
                    "    <tt:Zoom x=\"%.3f\" space=\"http://www.onvif.org/ver10/tptz/ZoomSpaces/VelocityGenericSpace\"/>\n" +
                    "  </tptz:Velocity>\n" +
                    "</tptz:ContinuousMove>",
                    profileToken, pan, tilt, zoom
                ));
    }

    public static String buildContinuousMove(String profileToken, double pan, double tilt, double zoom) {
        return buildContinuousMove(null, profileToken, pan, tilt, zoom);
    }

    /** 8. 云台立即停止 (Stop) */
    public static String buildStop(String headerXml, String profileToken, boolean panTilt, boolean zoom) {
        return wrapEnvelope(headerXml,
                String.format(
                    "<tptz:Stop>\n" +
                    "  <tptz:ProfileToken>%s</tptz:ProfileToken>\n" +
                    "  <tptz:PanTilt>%s</tptz:PanTilt>\n" +
                    "  <tptz:Zoom>%s</tptz:Zoom>\n" +
                    "</tptz:Stop>",
                    profileToken, panTilt, zoom
                ));
    }

    public static String buildStop(String profileToken, boolean panTilt, boolean zoom) {
        return buildStop(null, profileToken, panTilt, zoom);
    }

    /** 9. 查询预置位列表 */
    public static String buildGetPresets(String headerXml, String profileToken) {
        return wrapEnvelope(headerXml,
                String.format("<tptz:GetPresets><tptz:ProfileToken>%s</tptz:ProfileToken></tptz:GetPresets>", profileToken));
    }

    public static String buildGetPresets(String profileToken) {
        return buildGetPresets(null, profileToken);
    }

    /** 10. 调用指定预置位 */
    public static String buildGotoPreset(String headerXml, String profileToken, String presetToken) {
        return wrapEnvelope(headerXml,
                String.format(
                    "<tptz:GotoPreset>\n" +
                    "  <tptz:ProfileToken>%s</tptz:ProfileToken>\n" +
                    "  <tptz:PresetToken>%s</tptz:PresetToken>\n" +
                    "</tptz:GotoPreset>",
                    profileToken, presetToken
                ));
    }

    public static String buildGotoPreset(String profileToken, String presetToken) {
        return buildGotoPreset(null, profileToken, presetToken);
    }
}
