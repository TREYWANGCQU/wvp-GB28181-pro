// src/main/java/com/genersoft/iot/vmp/onvif/controller/OnvifDeviceController.java
package com.genersoft.iot.vmp.onvif.controller;

import com.alibaba.excel.EasyExcel;
import com.genersoft.iot.vmp.conf.exception.ControllerException;
import com.genersoft.iot.vmp.onvif.bean.OnvifChannel;
import com.genersoft.iot.vmp.onvif.bean.OnvifDevice;
import com.genersoft.iot.vmp.onvif.dao.OnvifChannelMapper;
import com.genersoft.iot.vmp.onvif.dto.OnvifDeviceExportRequest;
import com.genersoft.iot.vmp.onvif.dto.OnvifDeviceImportDto;
import com.genersoft.iot.vmp.onvif.dto.OnvifImportResult;
import com.genersoft.iot.vmp.onvif.service.IOnvifDeviceService;
import com.genersoft.iot.vmp.vmanager.bean.ErrorCode;
import com.genersoft.iot.vmp.vmanager.bean.WVPResult;
import com.github.pagehelper.PageInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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

    @PostMapping("/update")
    @Operation(summary = "更新 ONVIF 设备基础信息")
    public WVPResult<OnvifDevice> update(@RequestBody OnvifDevice device) {
        OnvifDevice updated = deviceService.updateDevice(device);
        return WVPResult.success(updated);
    }

    @GetMapping("/import/template")
    @Operation(summary = "下载 ONVIF 批量导入模板")
    public void downloadTemplate(HttpServletResponse response) {
        try {
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setCharacterEncoding("utf-8");
            String fileName = URLEncoder.encode("onvif_device_template", StandardCharsets.UTF_8).replaceAll("\\+", "%20");
            response.setHeader("Content-disposition", "attachment;filename*=utf-8''" + fileName + ".xlsx");

            List<OnvifDeviceImportDto> sampleList = new ArrayList<>();
            OnvifDeviceImportDto sample = OnvifDeviceImportDto.builder()
                    .name("示例-西门枪机01")
                    .ip("192.168.1.108")
                    .port(80)
                    .username("admin")
                    .password("admin123")
                    .mediaServerId("")
                    .gbDeviceId("34020000001320000001")
                    .civilCode("34020000")
                    .build();
            sampleList.add(sample);

            EasyExcel.write(response.getOutputStream(), OnvifDeviceImportDto.class)
                    .sheet("ONVIF设备导入")
                    .doWrite(sampleList);
        } catch (Exception e) {
            log.error("[ONVIF] 导出模板失败: {}", e.getMessage(), e);
            throw new ControllerException(ErrorCode.ERROR100.getCode(), "下载模板失败: " + e.getMessage());
        }
    }

    @PostMapping("/import")
    @Operation(summary = "批量导入 ONVIF 设备")
    public WVPResult<OnvifImportResult> importDevices(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ControllerException(ErrorCode.ERROR400.getCode(), "上传文件不能为空");
        }
        try {
            List<OnvifDeviceImportDto> importList = EasyExcel.read(file.getInputStream())
                    .head(OnvifDeviceImportDto.class)
                    .sheet()
                    .doReadSync();
            OnvifImportResult result = deviceService.importDevices(importList);
            return WVPResult.success(result);
        } catch (Exception e) {
            log.error("[ONVIF] 解析导入文件失败: {}", e.getMessage(), e);
            throw new ControllerException(ErrorCode.ERROR100.getCode(), "解析导入文件失败: " + e.getMessage());
        }
    }

    @PostMapping("/export")
    @Operation(summary = "批量导出选中的 ONVIF 设备为 Excel")
    public void exportDevices(@RequestBody(required = false) OnvifDeviceExportRequest request, HttpServletResponse response) {
        try {
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setCharacterEncoding("utf-8");
            String fileName = URLEncoder.encode("onvif_devices_export", StandardCharsets.UTF_8).replaceAll("\\+", "%20");
            response.setHeader("Content-disposition", "attachment;filename*=utf-8''" + fileName + ".xlsx");

            List<OnvifDeviceImportDto> exportList = deviceService.getExportDeviceList(request);
            EasyExcel.write(response.getOutputStream(), OnvifDeviceImportDto.class)
                    .sheet("ONVIF设备清单")
                    .doWrite(exportList);
        } catch (Exception e) {
            log.error("[ONVIF] 批量导出设备失败: {}", e.getMessage(), e);
            throw new ControllerException(ErrorCode.ERROR100.getCode(), "批量导出设备失败: " + e.getMessage());
        }
    }
}
