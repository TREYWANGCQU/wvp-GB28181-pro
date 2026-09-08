// src/main/java/com/genersoft/iot/vmp/onvif/client/OnvifDiscoveryClient.java
package com.genersoft.iot.vmp.onvif.client;

import com.genersoft.iot.vmp.onvif.bean.OnvifProbeResult;
import lombok.extern.slf4j.Slf4j;
import org.dom4j.Document;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.springframework.stereotype.Component;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * WS-Discovery 局域网 UDP 探测与单播搜寻器
 */
@Slf4j
@Component
public class OnvifDiscoveryClient {

    private static final String MULTICAST_ADDRESS = "239.255.255.250";
    private static final int WS_DISCOVERY_PORT = 3702;

    private static final String PROBE_TEMPLATE =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<Envelope xmlns:dn=\"http://www.onvif.org/ver10/network/wsdl\"\n" +
            "          xmlns=\"http://www.w3.org/2003/05/soap-envelope\">\n" +
            "  <Header>\n" +
            "    <wsa:MessageID xmlns:wsa=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\">uuid:%s</wsa:MessageID>\n" +
            "    <wsa:To xmlns:wsa=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\">urn:schemas-xmlsoap-org:ws:2005:04:discovery</wsa:To>\n" +
            "    <wsa:Action xmlns:wsa=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\">http://schemas.xmlsoap.org/ws/2005:04:discovery/Probe</wsa:Action>\n" +
            "  </Header>\n" +
            "  <Body>\n" +
            "    <Probe xmlns=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\">\n" +
            "      <Types>dn:NetworkVideoTransmitter</Types>\n" +
            "    </Probe>\n" +
            "  </Body>\n" +
            "</Envelope>";

    /**
     * 发起局域网多播探测 (收集全部响应)
     *
     * @param timeoutSeconds 接收回包的窗口秒数 (推荐 2.5 ~ 3.0 秒)
     * @return 探测到的 ONVIF 设备列表
     */
    public List<OnvifProbeResult> probe(int timeoutSeconds) {
        Map<String, OnvifProbeResult> resultMap = new ConcurrentHashMap<>();
        String messageId = UUID.randomUUID().toString();
        String probeMessage = String.format(PROBE_TEMPLATE, messageId);
        byte[] sendData = probeMessage.getBytes(StandardCharsets.UTF_8);

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(1000);

            InetAddress group = InetAddress.getByName(MULTICAST_ADDRESS);
            DatagramPacket packet = new DatagramPacket(sendData, sendData.length, group, WS_DISCOVERY_PORT);
            socket.send(packet);
            log.info("[WS-Discovery] 多播探测已发送至 {}:{}, 监听时长: {}s", MULTICAST_ADDRESS, WS_DISCOVERY_PORT, timeoutSeconds);

            long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);
            byte[] buf = new byte[8192];

            while (System.currentTimeMillis() < deadline) {
                try {
                    DatagramPacket receivePacket = new DatagramPacket(buf, buf.length);
                    socket.receive(receivePacket);
                    String responseXml = new String(receivePacket.getData(), 0, receivePacket.getLength(), StandardCharsets.UTF_8);
                    parseProbeMatch(responseXml, receivePacket.getAddress().getHostAddress(), resultMap);
                } catch (SocketTimeoutException ignored) {
                    // 超时等待下一个数据包
                }
            }
        } catch (Exception e) {
            log.error("[WS-Discovery] 发起多播搜寻异常: {}", e.getMessage(), e);
        }

        return new ArrayList<>(resultMap.values());
    }

    /**
     * 单播探测指定 IP:Port
     */
    public OnvifProbeResult probeSingle(String ip, int port) {
        String messageId = UUID.randomUUID().toString();
        String probeMessage = String.format(PROBE_TEMPLATE, messageId);
        byte[] sendData = probeMessage.getBytes(StandardCharsets.UTF_8);

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(2500);
            InetAddress target = InetAddress.getByName(ip);
            DatagramPacket packet = new DatagramPacket(sendData, sendData.length, target, port);
            socket.send(packet);

            byte[] buf = new byte[8192];
            DatagramPacket receivePacket = new DatagramPacket(buf, buf.length);
            socket.receive(receivePacket);

            String responseXml = new String(receivePacket.getData(), 0, receivePacket.getLength(), StandardCharsets.UTF_8);
            Map<String, OnvifProbeResult> map = new HashMap<>();
            parseProbeMatch(responseXml, ip, map);
            return map.isEmpty() ? null : map.values().iterator().next();
        } catch (Exception e) {
            log.warn("[WS-Discovery] 单播探测失败 {}:{}: {}", ip, port, e.getMessage());
            return null;
        }
    }

    public void parseProbeMatch(String xml, String senderIp, Map<String, OnvifProbeResult> collector) {
        try {
            Document doc = DocumentHelper.parseText(xml);
            List<Element> matches = OnvifXmlParser.findElementsIgnoreCase(doc.getRootElement(), "ProbeMatch");
            for (Element match : matches) {
                Element xAddrsElem = OnvifXmlParser.findElementIgnoreCase(match, "XAddrs");
                if (xAddrsElem == null) continue;

                String xAddrs = xAddrsElem.getTextTrim();
                String[] urlList = xAddrs.split("\\s+");
                for (String url : urlList) {
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        URI uri = URI.create(url);
                        String host = uri.getHost();
                        int port = uri.getPort() == -1 ? 80 : uri.getPort();

                        Element scopesElem = OnvifXmlParser.findElementIgnoreCase(match, "Scopes");
                        List<String> scopeList = new ArrayList<>();
                        String manufacturer = "Generic";
                        String model = "IPC";
                        String name = host;

                        if (scopesElem != null) {
                            String[] scopes = scopesElem.getTextTrim().split("\\s+");
                            scopeList.addAll(Arrays.asList(scopes));
                            for (String s : scopes) {
                                if (s.contains("/hardware/")) {
                                    model = s.substring(s.lastIndexOf('/') + 1);
                                } else if (s.contains("/name/")) {
                                    name = URLDecoder.decode(s.substring(s.lastIndexOf('/') + 1), StandardCharsets.UTF_8);
                                } else if (s.contains("/mfr/") || s.contains("/manufacturer/")) {
                                    manufacturer = s.substring(s.lastIndexOf('/') + 1);
                                }
                            }
                        }

                        Element typesElem = OnvifXmlParser.findElementIgnoreCase(match, "Types");
                        List<String> typeList = new ArrayList<>();
                        if (typesElem != null) {
                            String[] types = typesElem.getTextTrim().split("\\s+");
                            typeList.addAll(Arrays.asList(types));
                        }

                        Element endpointElem = OnvifXmlParser.findElementIgnoreCase(match, "Address");
                        String endpointReference = endpointElem != null ? endpointElem.getTextTrim() : null;

                        String key = host + ":" + port;
                        collector.put(key, OnvifProbeResult.builder()
                                .ip(host)
                                .port(port)
                                .deviceServiceUrl(url)
                                .endpointReference(endpointReference)
                                .types(typeList)
                                .manufacturer(manufacturer)
                                .model(model)
                                .name(name)
                                .scopes(scopeList)
                                .build());
                    }
                }
            }
        } catch (Exception e) {
            log.error("[WS-Discovery] 解析 ProbeMatches 报文异常: {}", e.getMessage());
        }
    }
}
