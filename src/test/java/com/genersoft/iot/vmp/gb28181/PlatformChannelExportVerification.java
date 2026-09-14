// src/test/java/com/genersoft/iot/vmp/gb28181/PlatformChannelExportVerification.java
package com.genersoft.iot.vmp.gb28181;

import com.genersoft.iot.vmp.gb28181.bean.PlatformChannel;
import com.genersoft.iot.vmp.gb28181.bean.PlatformChannelExcelDto;
import com.genersoft.iot.vmp.gb28181.dao.PlatformChannelMapper;
import com.genersoft.iot.vmp.gb28181.service.impl.PlatformChannelServiceImpl;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.mockito.Mockito.*;

/**
 * 级联通道导出与选择过滤验证测试
 */
public class PlatformChannelExportVerification {

    public static void main(String[] args) throws Exception {
        System.out.println("========== 开始执行级联通道导出与选择过滤验证测试 ==========");
        int total = 0;
        int passed = 0;

        PlatformChannelMapper platformChannelMapper = mock(PlatformChannelMapper.class);
        PlatformChannelServiceImpl service = new PlatformChannelServiceImpl(
                platformChannelMapper, null, null, null, null, null, null, null, null, null, null
        );

        int platformId = 100;

        PlatformChannel ch1 = new PlatformChannel();
        ch1.setId(1);
        ch1.setGbDeviceId("34020000001320000001");
        ch1.setGbName("通道1");
        ch1.setGbManufacturer("海康");
        ch1.setCustomDeviceId(null); // 未设置自定义国标编码
        ch1.setCustomName(null);

        PlatformChannel ch2 = new PlatformChannel();
        ch2.setId(2);
        ch2.setGbDeviceId("34020000001320000002");
        ch2.setGbName("通道2");
        ch2.setGbManufacturer("大华");
        ch2.setCustomDeviceId("31011500001320000002"); // 已设置自定义国标编码
        ch2.setCustomName("自定义通道2");

        PlatformChannel ch3 = new PlatformChannel();
        ch3.setId(3);
        ch3.setGbDeviceId("34020000001320000003");
        ch3.setGbName("通道3");
        ch3.setGbManufacturer("宇视");
        ch3.setCustomDeviceId("");
        ch3.setCustomName("");

        List<PlatformChannel> mockDbList = Arrays.asList(ch1, ch2, ch3);
        when(platformChannelMapper.queryForPlatformForWebList(eq(platformId), isNull(), isNull(), isNull(), eq(true)))
                .thenReturn(mockDbList);

        // 测试 1: 全量导出验证与默认回显
        total++;
        try {
            List<PlatformChannelExcelDto> exportAll = service.getExportChannelList(platformId);
            if (exportAll.size() != 3) {
                throw new RuntimeException("全量导出预期 3 条，实际返回 " + exportAll.size());
            }

            // 验证未配置自定义编码时回显原始国标编码
            PlatformChannelExcelDto dto1 = exportAll.get(0);
            if (!"34020000001320000001".equals(dto1.getCustomDeviceId())) {
                throw new RuntimeException("通道1未配置自定义编码，预期回显原始编码 34020000001320000001，实际为 " + dto1.getCustomDeviceId());
            }
            if (!"通道1".equals(dto1.getCustomName())) {
                throw new RuntimeException("通道1未配置自定义名称，预期回显原始名称 通道1，实际为 " + dto1.getCustomName());
            }

            // 验证已配置自定义编码时保留自定义值
            PlatformChannelExcelDto dto2 = exportAll.get(1);
            if (!"31011500001320000002".equals(dto2.getCustomDeviceId())) {
                throw new RuntimeException("通道2已配置自定义编码，预期为 31011500001320000002，实际为 " + dto2.getCustomDeviceId());
            }
            if (!"自定义通道2".equals(dto2.getCustomName())) {
                throw new RuntimeException("通道2已配置自定义名称，预期为 自定义通道2，实际为 " + dto2.getCustomName());
            }

            // 验证空字符串也回显原始值
            PlatformChannelExcelDto dto3 = exportAll.get(2);
            if (!"34020000001320000003".equals(dto3.getCustomDeviceId())) {
                throw new RuntimeException("通道3自定义编码为空字符串，预期回显原始编码，实际为 " + dto3.getCustomDeviceId());
            }

            passed++;
            System.out.println("√ 测试 1 通过: 全量导出条数与自定义编码默认回显逻辑验证成功");
        } catch (Exception e) {
            System.err.println("× 测试 1 失败: " + e.getMessage());
        }

        // 测试 2: 选择性导出过滤验证
        total++;
        try {
            List<Integer> selectiveIds = Arrays.asList(1, 3);
            List<PlatformChannelExcelDto> exportSelective = service.getExportChannelList(platformId, selectiveIds);
            if (exportSelective.size() != 2) {
                throw new RuntimeException("选择性导出 [1, 3] 预期 2 条，实际返回 " + exportSelective.size());
            }
            if (exportSelective.get(0).getId() != 1 || exportSelective.get(1).getId() != 3) {
                throw new RuntimeException("选择性导出匹配结果 ID 不匹配");
            }

            passed++;
            System.out.println("√ 测试 2 通过: 勾选通道选择性导出过滤机制验证成功");
        } catch (Exception e) {
            System.err.println("× 测试 2 失败: " + e.getMessage());
        }

        // 测试 3: 空数据防御验证
        total++;
        try {
            when(platformChannelMapper.queryForPlatformForWebList(eq(999), isNull(), isNull(), isNull(), eq(true)))
                    .thenReturn(Collections.emptyList());
            List<PlatformChannelExcelDto> emptyResult = service.getExportChannelList(999);
            if (!emptyResult.isEmpty()) {
                throw new RuntimeException("空平台预期返回空集合");
            }

            passed++;
            System.out.println("√ 测试 3 通过: 空平台数据防御性验证成功");
        } catch (Exception e) {
            System.err.println("× 测试 3 失败: " + e.getMessage());
        }

        System.out.printf("========== 验证完毕: 全部 %d 项, 通过 %d 项 ==========%n", total, passed);
        if (passed != total) {
            System.exit(1);
        }
    }
}
