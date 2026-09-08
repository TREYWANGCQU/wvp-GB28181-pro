// src/test/java/com/genersoft/iot/vmp/onvif/client/OnvifDiscoveryClientTest.java
package com.genersoft.iot.vmp.onvif.client;

import com.genersoft.iot.vmp.onvif.bean.OnvifProbeResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OnvifDiscoveryClientTest {

    @Test
    @DisplayName("验证 WS-Discovery ProbeMatches 响应报文解析")
    void testParseProbeMatch() {
        OnvifDiscoveryClient client = new OnvifDiscoveryClient();
        String sampleXml =
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<Envelope xmlns:dn=\"http://www.onvif.org/ver10/network/wsdl\"\n" +
                "          xmlns=\"http://www.w3.org/2003/05/soap-envelope\"\n" +
                "          xmlns:wsa=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\">\n" +
                "  <Header>\n" +
                "    <wsa:RelatesTo>uuid:12345678-1234-1234-1234-123456789abc</wsa:RelatesTo>\n" +
                "  </Header>\n" +
                "  <Body>\n" +
                "    <ProbeMatches xmlns=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\">\n" +
                "      <ProbeMatch>\n" +
                "        <wsa:EndpointReference>\n" +
                "          <wsa:Address>urn:uuid:98765432-4321-4321-4321-cba987654321</wsa:Address>\n" +
                "        </wsa:EndpointReference>\n" +
                "        <Types>dn:NetworkVideoTransmitter</Types>\n" +
                "        <Scopes>onvif://www.onvif.org/type/video_encoder onvif://www.onvif.org/type/audio_encoder " +
                "onvif://www.onvif.org/hardware/DS-2CD2047G2-LU onvif://www.onvif.org/name/FrontDoorCamera onvif://www.onvif.org/manufacturer/Hikvision</Scopes>\n" +
                "        <XAddrs>http://192.168.1.108:80/onvif/device_service</XAddrs>\n" +
                "        <MetadataVersion>1</MetadataVersion>\n" +
                "      </ProbeMatch>\n" +
                "    </ProbeMatches>\n" +
                "  </Body>\n" +
                "</Envelope>";

        Map<String, OnvifProbeResult> collector = new HashMap<>();
        client.parseProbeMatch(sampleXml, "192.168.1.108", collector);

        assertEquals(1, collector.size());
        OnvifProbeResult result = collector.get("192.168.1.108:80");
        assertNotNull(result);
        assertEquals("192.168.1.108", result.getIp());
        assertEquals(80, result.getPort());
        assertEquals("http://192.168.1.108:80/onvif/device_service", result.getDeviceServiceUrl());
        assertEquals("Hikvision", result.getManufacturer());
        assertEquals("DS-2CD2047G2-LU", result.getModel());
        assertEquals("FrontDoorCamera", result.getName());
        assertEquals("urn:uuid:98765432-4321-4321-4321-cba987654321", result.getEndpointReference());
        assertNotNull(result.getScopes());
        assertEquals(5, result.getScopes().size());
    }
}
