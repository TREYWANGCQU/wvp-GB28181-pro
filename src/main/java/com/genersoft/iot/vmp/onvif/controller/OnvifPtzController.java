// src/main/java/com/genersoft/iot/vmp/onvif/controller/OnvifPtzController.java
package com.genersoft.iot.vmp.onvif.controller;

import com.genersoft.iot.vmp.gb28181.bean.CommonGBChannel;
import com.genersoft.iot.vmp.gb28181.bean.FrontEndControlCodeForPTZ;
import com.genersoft.iot.vmp.gb28181.service.IGbChannelControlService;
import com.genersoft.iot.vmp.gb28181.service.IGbChannelService;
import com.genersoft.iot.vmp.vmanager.bean.ErrorCode;
import com.genersoft.iot.vmp.vmanager.bean.WVPResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;

@Slf4j
@RestController
@RequestMapping("/api/ptz")
@Tag(name = "ONVIF/通用云台控制接口")
@RequiredArgsConstructor
public class OnvifPtzController {

    private final IGbChannelService channelService;
    private final IGbChannelControlService channelControlService;

    @PostMapping("/ptz/{channelId}")
    @Operation(summary = "云台即时转动/变倍/停止控制")
    public DeferredResult<WVPResult<String>> ptz(
            @PathVariable("channelId") String channelId,
            @RequestParam(value = "pan", required = false) Integer pan,
            @RequestParam(value = "tilt", required = false) Integer tilt,
            @RequestParam(value = "zoom", required = false) Integer zoom,
            @RequestParam(value = "panSpeed", required = false, defaultValue = "128") Integer panSpeed,
            @RequestParam(value = "tiltSpeed", required = false, defaultValue = "128") Integer tiltSpeed,
            @RequestParam(value = "zoomSpeed", required = false, defaultValue = "128") Integer zoomSpeed) {

        log.info("[PTZ-API] 收到云台控制指令: channelId={}, pan={}, tilt={}, zoom={}, speed=({},{},{})",
                channelId, pan, tilt, zoom, panSpeed, tiltSpeed, zoomSpeed);

        DeferredResult<WVPResult<String>> result = new DeferredResult<>(5000L);
        result.onTimeout(() -> result.setResult(WVPResult.fail(ErrorCode.ERROR100.getCode(), "云台控制请求超时")));

        CommonGBChannel channel = channelService.queryByDeviceId(channelId);
        if (channel == null) {
            try {
                int id = Integer.parseInt(channelId);
                channel = channelService.getOne(id);
            } catch (NumberFormatException ignored) {}
        }

        if (channel == null) {
            result.setResult(WVPResult.fail(ErrorCode.ERROR404.getCode(), "通道不存在: " + channelId));
            return result;
        }

        FrontEndControlCodeForPTZ controlCode = new FrontEndControlCodeForPTZ();
        controlCode.setPan(pan);
        controlCode.setTilt(tilt);
        controlCode.setZoom(zoom);
        controlCode.setPanSpeed(panSpeed);
        controlCode.setTiltSpeed(tiltSpeed);
        controlCode.setZoomSpeed(zoomSpeed);

        channelControlService.ptz(channel, controlCode, (code, msg, data) -> {
            WVPResult<String> wvpResult = new WVPResult<>();
            wvpResult.setCode(code);
            wvpResult.setMsg(msg);
            wvpResult.setData(data);
            result.setResult(wvpResult);
        });

        return result;
    }
}
