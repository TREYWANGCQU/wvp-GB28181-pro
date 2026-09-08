// src/main/java/com/genersoft/iot/vmp/onvif/controller/OnvifDiscoveryController.java
package com.genersoft.iot.vmp.onvif.controller;

import com.genersoft.iot.vmp.onvif.bean.OnvifProbeResult;
import com.genersoft.iot.vmp.onvif.client.OnvifDiscoveryClient;
import com.genersoft.iot.vmp.vmanager.bean.ErrorCode;
import com.genersoft.iot.vmp.vmanager.bean.WVPResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/onvif/discovery")
@Tag(name = "ONVIF 局域网探测接口")
@RequiredArgsConstructor
public class OnvifDiscoveryController {

    private final OnvifDiscoveryClient discoveryClient;

    @PostMapping("/probe")
    @Operation(summary = "发起局域网 WS-Discovery 多播探测")
    public WVPResult<List<OnvifProbeResult>> probe(
            @RequestParam(value = "timeout", defaultValue = "3") int timeoutSeconds) {
        int safeTimeout = Math.min(Math.max(timeoutSeconds, 1), 6);
        log.info("[ONVIF-API] 收到局域网搜寻指令，超时设定: {}s", safeTimeout);
        List<OnvifProbeResult> results = discoveryClient.probe(safeTimeout);
        return WVPResult.success(results);
    }

    @PostMapping("/probe-single")
    @Operation(summary = "单播探测指定 IP 与端口")
    public WVPResult<OnvifProbeResult> probeSingle(
            @RequestParam("ip") String ip,
            @RequestParam(value = "port", defaultValue = "80") int port) {
        OnvifProbeResult result = discoveryClient.probeSingle(ip, port);
        if (result == null) {
            return WVPResult.fail(ErrorCode.ERROR404.getCode(), "未探测到响应或设备非 ONVIF 兼容");
        }
        return WVPResult.success(result);
    }
}
