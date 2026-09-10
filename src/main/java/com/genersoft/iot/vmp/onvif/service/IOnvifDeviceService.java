// src/main/java/com/genersoft/iot/vmp/onvif/service/IOnvifDeviceService.java
package com.genersoft.iot.vmp.onvif.service;

import com.genersoft.iot.vmp.onvif.bean.OnvifChannel;
import com.genersoft.iot.vmp.onvif.bean.OnvifDevice;
import com.genersoft.iot.vmp.onvif.dto.OnvifDeviceExportRequest;
import com.genersoft.iot.vmp.onvif.dto.OnvifDeviceImportDto;
import com.genersoft.iot.vmp.onvif.dto.OnvifImportResult;
import com.github.pagehelper.PageInfo;

import java.util.List;

public interface IOnvifDeviceService {

    OnvifDevice addDevice(OnvifDevice device);

    OnvifDevice updateDevice(OnvifDevice device);

    void probeAndSyncMetadata(OnvifDevice device);

    void syncChannels(Integer deviceId);

    void syncChannels(Integer deviceId, String customGbDeviceId, String civilCode);

    void deleteDevice(Integer deviceId);

    OnvifImportResult importDevices(List<OnvifDeviceImportDto> importList);

    List<OnvifDeviceImportDto> getExportDeviceList(OnvifDeviceExportRequest request);

    PageInfo<OnvifDevice> getDeviceList(int page, int count, String query, Integer status);

    OnvifDevice getDevice(Integer deviceId);

    List<OnvifChannel> getChannelsByDeviceId(Integer deviceId);
}
