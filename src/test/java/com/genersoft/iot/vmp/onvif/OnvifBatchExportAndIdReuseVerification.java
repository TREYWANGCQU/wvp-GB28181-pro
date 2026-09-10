// src/test/java/com/genersoft/iot/vmp/onvif/OnvifBatchExportAndIdReuseVerification.java
package com.genersoft.iot.vmp.onvif;

import com.genersoft.iot.vmp.common.enums.ChannelDataType;
import com.genersoft.iot.vmp.gb28181.bean.CommonGBChannel;
import com.genersoft.iot.vmp.gb28181.dao.CommonGBChannelMapper;
import com.genersoft.iot.vmp.gb28181.service.IGbChannelService;
import com.genersoft.iot.vmp.onvif.bean.OnvifChannel;
import com.genersoft.iot.vmp.onvif.bean.OnvifDevice;
import com.genersoft.iot.vmp.onvif.client.OnvifSoapClient;
import com.genersoft.iot.vmp.onvif.dao.OnvifChannelMapper;
import com.genersoft.iot.vmp.onvif.dao.OnvifDeviceMapper;
import com.genersoft.iot.vmp.onvif.dto.OnvifDeviceExportRequest;
import com.genersoft.iot.vmp.onvif.dto.OnvifDeviceImportDto;
import com.genersoft.iot.vmp.onvif.dto.OnvifImportResult;
import com.genersoft.iot.vmp.onvif.service.impl.OnvifDeviceServiceImpl;

import java.util.*;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ONVIF 批量导出、同IP覆盖更新与紧凑ID复用验证类
 */
public class OnvifBatchExportAndIdReuseVerification {

    public static void main(String[] args) {
        System.out.println("========== 开始执行 ONVIF 批量导出/同IP覆盖/ID紧凑复用验证 ==========");
        int total = 0;
        int passed = 0;

        OnvifDeviceMapper deviceMapper = mock(OnvifDeviceMapper.class);
        OnvifChannelMapper channelMapper = mock(OnvifChannelMapper.class);
        OnvifSoapClient soapClient = mock(OnvifSoapClient.class);
        IGbChannelService gbChannelService = mock(IGbChannelService.class);
        CommonGBChannelMapper commonGBChannelMapper = mock(CommonGBChannelMapper.class);

        OnvifDeviceServiceImpl service = new OnvifDeviceServiceImpl(
                deviceMapper, channelMapper, soapClient, gbChannelService, commonGBChannelMapper
        );

        // 1. 验证空洞扫描与 ID 紧凑复用算法
        total++;
        try {
            // Case 1: 库表为空 -> 1
            when(deviceMapper.selectAllIds()).thenReturn(Collections.emptyList());
            int idEmpty = service.getNextAvailableDeviceId(null);
            if (idEmpty != 1) throw new RuntimeException("库表为空预期为 1，实际为 " + idEmpty);

            // Case 2: 连续递增 [1, 2, 3] -> 4
            when(deviceMapper.selectAllIds()).thenReturn(Arrays.asList(1, 2, 3));
            int idContinuous = service.getNextAvailableDeviceId(null);
            if (idContinuous != 4) throw new RuntimeException("连续列表预期为 4，实际为 " + idContinuous);

            // Case 3: 释放中间 ID [1, 3] (删除了2) -> 2 (复用空洞)
            when(deviceMapper.selectAllIds()).thenReturn(Arrays.asList(1, 3));
            int idHoleMiddle = service.getNextAvailableDeviceId(null);
            if (idHoleMiddle != 2) throw new RuntimeException("中间空洞预期复用 2，实际为 " + idHoleMiddle);

            // Case 4: 释放首部 ID [2, 3] (删除了1) -> 1 (复用空洞)
            when(deviceMapper.selectAllIds()).thenReturn(Arrays.asList(2, 3));
            int idHoleStart = service.getNextAvailableDeviceId(null);
            if (idHoleStart != 1) throw new RuntimeException("首部空洞预期复用 1，实际为 " + idHoleStart);

            // Case 5: 批量导入预留集合预占
            Set<Integer> reserved = new HashSet<>();
            int idRes1 = service.getNextAvailableDeviceId(reserved);
            int idRes2 = service.getNextAvailableDeviceId(reserved);
            if (idRes1 != 1 || idRes2 != 4) {
                throw new RuntimeException("批量预留分配预期为 1 和 4，实际为 " + idRes1 + ", " + idRes2);
            }

            System.out.println("√ 测试 1 通过: 首个缺失正整数算法与紧凑 ID 释放复用验证成功");
            passed++;
        } catch (Exception e) {
            System.err.println("× 测试 1 失败: " + e.getMessage());
        }

        // 2. 验证批量导出数据组装
        total++;
        try {
            OnvifDevice dev1 = OnvifDevice.builder().id(1).name("西门摄像机").ip("192.168.1.10").port(80).username("admin").password("123456").build();
            when(deviceMapper.selectByIds(Collections.singletonList(1))).thenReturn(Collections.singletonList(dev1));

            OnvifChannel ch1 = new OnvifChannel();
            ch1.setId(101);
            ch1.setDeviceId(1);
            ch1.setGbDeviceId("34020000001320000001");
            when(channelMapper.selectByDeviceId(1)).thenReturn(Collections.singletonList(ch1));

            CommonGBChannel gb = new CommonGBChannel();
            gb.setGbId(201);
            gb.setGbDeviceId("34020000001320000001");
            gb.setGbCivilCode("34020000");
            when(gbChannelService.queryByDataId(ChannelDataType.ONVIF, 101)).thenReturn(gb);

            // 场景 A: 按已纳管 deviceIds 导出
            OnvifDeviceExportRequest reqA = OnvifDeviceExportRequest.builder().deviceIds(Collections.singletonList(1)).build();
            List<OnvifDeviceImportDto> listA = service.getExportDeviceList(reqA);
            if (listA.size() != 1 || !"34020000001320000001".equals(listA.get(0).getGbDeviceId()) || !"34020000".equals(listA.get(0).getCivilCode())) {
                throw new RuntimeException("已纳管设备导出字段不符合预期: " + listA);
            }

            // 场景 B: 按搜寻发现的 customDevices 导出
            OnvifDeviceImportDto custom = OnvifDeviceImportDto.builder().name("探测设备").ip("192.168.1.99").port(80).username("admin").build();
            OnvifDeviceExportRequest reqB = OnvifDeviceExportRequest.builder().customDevices(Collections.singletonList(custom)).build();
            List<OnvifDeviceImportDto> listB = service.getExportDeviceList(reqB);
            if (listB.size() != 1 || !"192.168.1.99".equals(listB.get(0).getIp())) {
                throw new RuntimeException("探测设备导出回传不符合预期: " + listB);
            }

            System.out.println("√ 测试 2 通过: 批量导出数据组装与双场景支持验证成功");
            passed++;
        } catch (Exception e) {
            System.err.println("× 测试 2 失败: " + e.getMessage());
        }

        // 3. 验证同 IP 设备覆盖更新 (批量修改名字与国标编码)
        total++;
        try {
            OnvifDevice existDev = OnvifDevice.builder().id(2).name("旧设备名称").ip("192.168.1.50").port(80).username("admin").password("admin").build();
            when(deviceMapper.selectByIpAndPort("192.168.1.50", 80)).thenReturn(existDev);

            OnvifChannel ch2 = new OnvifChannel();
            ch2.setId(102);
            ch2.setDeviceId(2);
            ch2.setChannelIndex(1);
            ch2.setGbDeviceId("34020000001320020001");
            when(channelMapper.selectByDeviceId(2)).thenReturn(Collections.singletonList(ch2));

            CommonGBChannel existingGb = new CommonGBChannel();
            existingGb.setGbId(202);
            existingGb.setGbDeviceId("34020000001320020001");
            existingGb.setGbName("旧设备名称");
            when(gbChannelService.queryByDataId(ChannelDataType.ONVIF, 102)).thenReturn(existingGb);

            OnvifDeviceImportDto overwriteDto = OnvifDeviceImportDto.builder()
                    .name("新高空球机")
                    .ip("192.168.1.50")
                    .port(80)
                    .username("admin")
                    .password("admin") // 凭据未变，不触发 SOAP 探测
                    .gbDeviceId("34020000001329990001") // 批量修改国标编码
                    .civilCode("34020100")
                    .build();

            OnvifImportResult importResult = service.importDevices(Collections.singletonList(overwriteDto));
            if (importResult.getSuccess() != 1 || importResult.getFailure() != 0) {
                throw new RuntimeException("同IP覆盖导入失败: " + importResult.getErrorMessages());
            }

            // 验证设备名是否更新
            verify(deviceMapper, atLeastOnce()).update(argThat(dev -> "新高空球机".equals(dev.getName())));
            // 验证国标通道是否穿透更新新编码与新名称
            verify(gbChannelService, atLeastOnce()).update(argThat(gb ->
                    "34020000001329990001".equals(gb.getGbDeviceId()) && "新高空球机".equals(gb.getGbName())
            ));

            System.out.println("√ 测试 3 通过: 同 IP 覆盖更新与名称/国标编码穿透同步验证成功");
            passed++;
        } catch (Exception e) {
            System.err.println("× 测试 3 失败: " + e.getMessage());
        }

        System.out.println(String.format("========== 验收自检结束: 共 %d 项，通过 %d 项 ==========", total, passed));
        if (passed != total) {
            System.exit(1);
        }
    }
}
