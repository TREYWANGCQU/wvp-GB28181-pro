// src/main/java/com/genersoft/iot/vmp/onvif/controller/OnvifDeviceController.java
package com.genersoft.iot.vmp.onvif.controller;

import com.genersoft.iot.vmp.conf.exception.ControllerException;
import com.genersoft.iot.vmp.onvif.bean.OnvifChannel;
import com.genersoft.iot.vmp.onvif.bean.OnvifDevice;
import com.genersoft.iot.vmp.onvif.dao.OnvifChannelMapper;
import com.genersoft.iot.vmp.onvif.service.IOnvifDeviceService;
import com.genersoft.iot.vmp.vmanager.bean.ErrorCode;
import com.genersoft.iot.vmp.vmanager.bean.WVPResult;
import com.github.pagehelper.PageInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/onvif/device")
@Tag(name = "ONVIF 设备管理接口")
@RequiredArgsConstructor
public class OnvifDeviceController {

    private final IOnvifDeviceService deviceService;
    private final OnvifChannelMapper channelMapper;

    @GetMapping("/list")
    @Operation(summary = "分页查询 ONVIF 设备列表")
    public WVPResult<PageInfo<OnvifDevice>> list(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "count", defaultValue = "10") int count,
            @RequestParam(value = "query", required = false) String query,
            @RequestParam(value = "status", required = false) Integer status) {
        PageInfo<OnvifDevice> pageInfo = deviceService.getDeviceList(page, count, query, status);
        return WVPResult.success(pageInfo);
    }

    @PostMapping("/add")
    @Operation(summary = "添加 ONVIF 设备")
    public WVPResult<OnvifDevice> add(@RequestBody OnvifDevice device) {
        if (device.getIp() == null || device.getPort() == null ||
            device.getUsername() == null || device.getPassword() == null) {
            throw new ControllerException(ErrorCode.ERROR400.getCode(), "IP、端口、用户名和密码不能为空");
        }
        OnvifDevice saved = deviceService.addDevice(device);
        return WVPResult.success(saved);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除 ONVIF 设备")
    public WVPResult<Void> delete(@RequestParam("id") Integer id) {
        deviceService.deleteDevice(id);
        return WVPResult.success();
    }

    @PostMapping("/sync/{id}")
    @Operation(summary = "手动重新同步设备通道与能力")
    public WVPResult<Void> sync(@PathVariable("id") Integer id) {
        deviceService.syncChannels(id);
        return WVPResult.success();
    }

    @GetMapping("/channels/{deviceId}")
    @Operation(summary = "查询指定设备的通道/Profile列表")
    public WVPResult<List<OnvifChannel>> getChannels(@PathVariable("deviceId") Integer deviceId) {
        List<OnvifChannel> channels = channelMapper.selectByDeviceId(deviceId);
        return WVPResult.success(channels);
    }
}
