// src/main/java/com/genersoft/iot/vmp/onvif/client/OnvifSoapClient.java
package com.genersoft.iot.vmp.onvif.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 基于 JDK 21 原生 HttpClient 的 SOAP 传输核心客户端
 */
@Slf4j
@Component
public class OnvifSoapClient {

    private final HttpClient httpClient;

    public OnvifSoapClient() {
        // 创建原生 HttpClient，设置 5 秒连接超时
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 发送无认证免签 SOAP 请求 (用于 GetSystemDateAndTime)
     */
    public String sendSoap(String serviceUrl, String soapAction, String xmlBody) throws Exception {
        return executePost(serviceUrl, soapAction, xmlBody);
    }

    /**
     * 发送带 WS-Security 签名的 SOAP 请求 (自动注入时钟补偿)
     */
    public String sendAuthenticatedSoap(String serviceUrl, String soapAction, String innerBodyXml,
                                        String username, String password, long clockOffsetMillis) throws Exception {
        String headerXml = OnvifSecurityHeader.buildHeader(username, password, clockOffsetMillis);
        String fullEnvelope = OnvifXmlBuilder.wrapEnvelope(headerXml, innerBodyXml);
        return executePost(serviceUrl, soapAction, fullEnvelope);
    }

    private String executePost(String serviceUrl, String soapAction, String xml) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(serviceUrl))
                .timeout(Duration.ofSeconds(6))
                .header("Content-Type", "application/soap+xml; charset=utf-8")
                .header("Accept", "application/soap+xml, multipart/related, text/html, image/jpeg, *; q=.2")
                .header("User-Agent", "WVP-PRO/2.7.4 (ONVIF Client)")
                .POST(HttpRequest.BodyPublishers.ofString(xml));

        if (soapAction != null && !soapAction.trim().isEmpty()) {
            builder.header("SOAPAction", soapAction);
        }

        HttpRequest request = builder.build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        int statusCode = response.statusCode();
        if (statusCode == 200) {
            return response.body();
        } else if (statusCode == 401) {
            log.warn("[ONVIF-SOAP] 请求未授权 401 Unauthorized: url={}", serviceUrl);
            throw new RuntimeException("401 Unauthorized: 密码错误或时钟偏斜超限");
        } else {
            log.warn("[ONVIF-SOAP] 请求异常 HTTP {}: url={}, body={}", statusCode, serviceUrl, response.body());
            throw new RuntimeException("HTTP " + statusCode + ": " + response.body());
        }
    }
}
