// src/test/java/com/genersoft/iot/vmp/onvif/client/OnvifXmlParserTest.java
package com.genersoft.iot.vmp.onvif.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OnvifXmlParserTest {

    @Test
    @DisplayName("验证 SystemDateTime 宽容解析")
    void testParseSystemDateTime() {
        String sampleXml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<SOAP-ENV:Envelope xmlns:SOAP-ENV=\"http://www.w3.org/2003/05/soap-envelope\" " +
                "                   xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\" " +
                "                   xmlns:tt=\"http://www.onvif.org/ver10/schema\">\n" +
                "  <SOAP-ENV:Body>\n" +
                "    <tds:GetSystemDateAndTimeResponse>\n" +
                "      <tds:SystemDateAndTime>\n" +
                "        <tt:DateTimeType>Manual</tt:DateTimeType>\n" +
                "        <tt:DaylightSavings>false</tt:DaylightSavings>\n" +
                "        <tt:UTCDateTime>\n" +
                "          <tt:Time>\n" +
                "            <tt:Hour>10</tt:Hour>\n" +
                "            <tt:Minute>30</tt:Minute>\n" +
                "            <tt:Second>45</tt:Second>\n" +
                "          </tt:Time>\n" +
                "          <tt:Date>\n" +
                "            <tt:Year>2026</tt:Year>\n" +
                "            <tt:Month>9</tt:Month>\n" +
                "            <tt:Day>8</tt:Day>\n" +
                "          </tt:Date>\n" +
                "        </tt:UTCDateTime>\n" +
                "      </tds:SystemDateAndTime>\n" +
                "    </tds:GetSystemDateAndTimeResponse>\n" +
                "  </SOAP-ENV:Body>\n" +
                "</SOAP-ENV:Envelope>";

        Instant instant = OnvifXmlParser.parseSystemDateTime(sampleXml);
        assertNotNull(instant);
        assertEquals("2026-09-08T10:30:45Z", instant.toString());
    }

    @Test
    @DisplayName("验证 DeviceInformation 宽容解析")
    void testParseDeviceInformation() {
        String sampleXml =
                "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\">\n" +
                "  <s:Body>\n" +
                "    <GetDeviceInformationResponse xmlns=\"http://www.onvif.org/ver10/device/wsdl\">\n" +
                "      <Manufacturer>Hikvision</Manufacturer>\n" +
                "      <Model>DS-2CD2047G2-LU</Model>\n" +
                "      <FirmwareVersion>V5.7.1</FirmwareVersion>\n" +
                "      <SerialNumber>DS-2CD2047G2-LU20210816AAWR123456789</SerialNumber>\n" +
                "      <HardwareId>1.0</HardwareId>\n" +
                "    </GetDeviceInformationResponse>\n" +
                "  </s:Body>\n" +
                "</s:Envelope>";

        Map<String, String> info = OnvifXmlParser.parseDeviceInformation(sampleXml);
        assertEquals("Hikvision", info.get("manufacturer"));
        assertEquals("DS-2CD2047G2-LU", info.get("model"));
        assertEquals("V5.7.1", info.get("firmwareVersion"));
        assertEquals("DS-2CD2047G2-LU20210816AAWR123456789", info.get("serialNumber"));
        assertEquals("1.0", info.get("hardwareId"));
    }

    @Test
    @DisplayName("验证 Capabilities 服务地址宽容提取")
    void testParseCapabilities() {
        String sampleXml =
                "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\">\n" +
                "  <s:Body>\n" +
                "    <GetCapabilitiesResponse xmlns=\"http://www.onvif.org/ver10/device/wsdl\">\n" +
                "      <Capabilities>\n" +
                "        <Media>\n" +
                "          <XAddr>http://192.168.1.64/onvif/media_service</XAddr>\n" +
                "        </Media>\n" +
                "        <PTZ>\n" +
                "          <XAddr>http://192.168.1.64/onvif/ptz_service</XAddr>\n" +
                "        </PTZ>\n" +
                "        <Imaging>\n" +
                "          <XAddr>http://192.168.1.64/onvif/imaging_service</XAddr>\n" +
                "        </Imaging>\n" +
                "      </Capabilities>\n" +
                "    </GetCapabilitiesResponse>\n" +
                "  </s:Body>\n" +
                "</s:Envelope>";

        Map<String, String> caps = OnvifXmlParser.parseCapabilities(sampleXml);
        assertEquals("http://192.168.1.64/onvif/media_service", caps.get("mediaUrl"));
        assertEquals("http://192.168.1.64/onvif/ptz_service", caps.get("ptzUrl"));
        assertEquals("http://192.168.1.64/onvif/imaging_service", caps.get("imagingUrl"));
    }

    @Test
    @DisplayName("验证 StreamUri 提取")
    void testParseStreamUri() {
        String sampleXml =
                "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\">\n" +
                "  <s:Body>\n" +
                "    <GetStreamUriResponse xmlns=\"http://www.onvif.org/ver10/media/wsdl\">\n" +
                "      <MediaUri>\n" +
                "        <Uri>rtsp://192.168.1.64:554/Streaming/Channels/101</Uri>\n" +
                "        <InvalidAfterConnect>false</InvalidAfterConnect>\n" +
                "        <InvalidAfterReboot>false</InvalidAfterReboot>\n" +
                "        <Timeout>PT0S</Timeout>\n" +
                "      </MediaUri>\n" +
                "    </GetStreamUriResponse>\n" +
                "  </s:Body>\n" +
                "</s:Envelope>";

        String uri = OnvifXmlParser.parseStreamUri(sampleXml);
        assertEquals("rtsp://192.168.1.64:554/Streaming/Channels/101", uri);
    }
}
