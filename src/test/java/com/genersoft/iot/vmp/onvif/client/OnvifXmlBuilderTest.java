// src/test/java/com/genersoft/iot/vmp/onvif/client/OnvifXmlBuilderTest.java
package com.genersoft.iot.vmp.onvif.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OnvifXmlBuilderTest {

    @Test
    @DisplayName("验证 GetSystemDateAndTime 报文封装")
    void testBuildGetSystemDateAndTime() {
        String xml = OnvifXmlBuilder.buildGetSystemDateAndTime();
        assertNotNull(xml);
        assertTrue(xml.contains("<s:Envelope"));
        assertTrue(xml.contains("<tds:GetSystemDateAndTime/>"));
    }

    @Test
    @DisplayName("验证 GetDeviceInformation 报文封装")
    void testBuildGetDeviceInformation() {
        String header = "<s:Header><dummy/></s:Header>";
        String xml = OnvifXmlBuilder.buildGetDeviceInformation(header);
        assertNotNull(xml);
        assertTrue(xml.contains("<dummy/>"));
        assertTrue(xml.contains("<tds:GetDeviceInformation/>"));
    }

    @Test
    @DisplayName("验证 ContinuousMove 报文封装与浮点数格式")
    void testBuildContinuousMove() {
        String xml = OnvifXmlBuilder.buildContinuousMove(null, "Profile_1", 0.5, -0.5, 1.0);
        assertNotNull(xml);
        assertTrue(xml.contains("<tptz:ProfileToken>Profile_1</tptz:ProfileToken>"));
        assertTrue(xml.contains("x=\"0.500\" y=\"-0.500\""));
        assertTrue(xml.contains("x=\"1.000\""));
    }

    @Test
    @DisplayName("验证 GetStreamUri 报文封装")
    void testBuildGetStreamUri() {
        String xml = OnvifXmlBuilder.buildGetStreamUri(null, "Profile_Token_Main");
        assertNotNull(xml);
        assertTrue(xml.contains("<trt:ProfileToken>Profile_Token_Main</trt:ProfileToken>"));
        assertTrue(xml.contains("<tt:Protocol>RTSP</tt:Protocol>"));
    }
}
