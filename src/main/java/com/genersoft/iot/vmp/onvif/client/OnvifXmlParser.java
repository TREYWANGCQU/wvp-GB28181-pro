// src/main/java/com/genersoft/iot/vmp/onvif/client/OnvifXmlParser.java
package com.genersoft.iot.vmp.onvif.client;

import lombok.extern.slf4j.Slf4j;
import org.dom4j.Document;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * 基于 dom4j 的宽容型 XML 响应抽取器 (忽略命名空间前缀与多层包裹)
 */
@Slf4j
public class OnvifXmlParser {

    /** 递归查找指定名称的第一个子节点 (忽略大小写与命名空间前缀) */
    public static Element findElementIgnoreCase(Element root, String targetName) {
        if (root == null) return null;
        if (root.getName().equalsIgnoreCase(targetName)) {
            return root;
        }
        for (Element child : root.elements()) {
            Element found = findElementIgnoreCase(child, targetName);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** 递归查找指定名称的所有子节点 (忽略大小写与命名空间前缀) */
    public static List<Element> findElementsIgnoreCase(Element root, String targetName) {
        List<Element> result = new ArrayList<>();
        if (root == null) return result;
        collectElements(root, targetName, result);
        return result;
    }

    private static void collectElements(Element current, String targetName, List<Element> collector) {
        if (current.getName().equalsIgnoreCase(targetName)) {
            collector.add(current);
        }
        for (Element child : current.elements()) {
            collectElements(child, targetName, collector);
        }
    }

    /** 提取摄像机 UTC 硬件时间戳 */
    public static Instant parseSystemDateTime(String xml) {
        try {
            Document doc = DocumentHelper.parseText(xml);
            Element utcDateTime = findElementIgnoreCase(doc.getRootElement(), "UTCDateTime");
            if (utcDateTime == null) {
                log.warn("[ONVIF-Parser] 未在响应中找到 UTCDateTime 节点");
                return Instant.now();
            }

            Element timeElem = findElementIgnoreCase(utcDateTime, "Time");
            Element dateElem = findElementIgnoreCase(utcDateTime, "Date");

            int hour = Integer.parseInt(Objects.requireNonNull(findElementIgnoreCase(timeElem, "Hour")).getTextTrim());
            int minute = Integer.parseInt(Objects.requireNonNull(findElementIgnoreCase(timeElem, "Minute")).getTextTrim());
            int second = Integer.parseInt(Objects.requireNonNull(findElementIgnoreCase(timeElem, "Second")).getTextTrim());

            int year = Integer.parseInt(Objects.requireNonNull(findElementIgnoreCase(dateElem, "Year")).getTextTrim());
            int month = Integer.parseInt(Objects.requireNonNull(findElementIgnoreCase(dateElem, "Month")).getTextTrim());
            int day = Integer.parseInt(Objects.requireNonNull(findElementIgnoreCase(dateElem, "Day")).getTextTrim());

            return LocalDateTime.of(year, month, day, hour, minute, second).toInstant(ZoneOffset.UTC);
        } catch (Exception e) {
            log.error("[ONVIF-Parser] 解析 SystemDateTime 失败，降级使用本机时钟: {}", e.getMessage());
            return Instant.now();
        }
    }

    /** 别名方法：提取摄像机 UTC 硬件时间戳 */
    public static Instant extractUtcDateTime(String xml) {
        return parseSystemDateTime(xml);
    }

    /** 提取设备硬件信息 (Manufacturer, Model, FirmwareVersion, SerialNumber, HardwareId) */
    public static Map<String, String> parseDeviceInformation(String xml) {
        Map<String, String> info = new HashMap<>();
        try {
            Document doc = DocumentHelper.parseText(xml);
            Element root = doc.getRootElement();
            extractText(root, "Manufacturer", info, "manufacturer");
            extractText(root, "Model", info, "model");
            extractText(root, "FirmwareVersion", info, "firmwareVersion");
            extractText(root, "SerialNumber", info, "serialNumber");
            extractText(root, "HardwareId", info, "hardwareId");
        } catch (Exception e) {
            log.error("[ONVIF-Parser] 解析 DeviceInformation 失败: {}", e.getMessage());
        }
        return info;
    }

    /** 别名方法：提取设备硬件信息 */
    public static Map<String, String> extractDeviceInformation(String xml) {
        return parseDeviceInformation(xml);
    }

    /** 提取服务能力 URL (Media XAddr, PTZ XAddr, Imaging XAddr) */
    public static Map<String, String> parseCapabilities(String xml) {
        Map<String, String> services = new HashMap<>();
        try {
            Document doc = DocumentHelper.parseText(xml);
            Element root = doc.getRootElement();

            Element media = findElementIgnoreCase(root, "Media");
            if (media != null) {
                Element xAddr = findElementIgnoreCase(media, "XAddr");
                if (xAddr != null) services.put("mediaUrl", xAddr.getTextTrim());
            }

            Element ptz = findElementIgnoreCase(root, "PTZ");
            if (ptz != null) {
                Element xAddr = findElementIgnoreCase(ptz, "XAddr");
                if (xAddr != null) services.put("ptzUrl", xAddr.getTextTrim());
            }

            Element imaging = findElementIgnoreCase(root, "Imaging");
            if (imaging != null) {
                Element xAddr = findElementIgnoreCase(imaging, "XAddr");
                if (xAddr != null) services.put("imagingUrl", xAddr.getTextTrim());
            }
        } catch (Exception e) {
            log.error("[ONVIF-Parser] 解析 Capabilities 失败: {}", e.getMessage());
        }
        return services;
    }

    /** 别名方法：提取服务能力 URL */
    public static Map<String, String> extractServices(String xml) {
        return parseCapabilities(xml);
    }

    /** 提取 Profile 列表信息 */
    public static List<Map<String, Object>> extractProfiles(String xml) {
        List<Map<String, Object>> profiles = new ArrayList<>();
        try {
            Document doc = DocumentHelper.parseText(xml);
            List<Element> profileElements = findElementsIgnoreCase(doc.getRootElement(), "Profiles");
            for (Element profileElem : profileElements) {
                Map<String, Object> profile = new HashMap<>();
                String token = profileElem.attributeValue("token");
                profile.put("token", token);
                Element nameElem = findElementIgnoreCase(profileElem, "Name");
                profile.put("name", nameElem != null ? nameElem.getTextTrim() : token);
                profiles.add(profile);
            }
        } catch (Exception e) {
            log.error("[ONVIF-Parser] 解析 Profiles 失败: {}", e.getMessage());
        }
        return profiles;
    }

    /** 提取 RTSP Stream URI */
    public static String parseStreamUri(String xml) {
        try {
            Document doc = DocumentHelper.parseText(xml);
            Element uriElem = findElementIgnoreCase(doc.getRootElement(), "Uri");
            if (uriElem != null) {
                return uriElem.getTextTrim();
            }
        } catch (Exception e) {
            log.error("[ONVIF-Parser] 解析 StreamUri 失败: {}", e.getMessage());
        }
        return null;
    }

    /** 别名方法：提取 RTSP Stream URI */
    public static String extractStreamUri(String xml) {
        return parseStreamUri(xml);
    }

    private static void extractText(Element root, String tag, Map<String, String> map, String key) {
        Element elem = findElementIgnoreCase(root, tag);
        if (elem != null) {
            map.put(key, elem.getTextTrim());
        }
    }
}
