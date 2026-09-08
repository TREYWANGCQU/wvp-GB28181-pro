// src/test/java/com/genersoft/iot/vmp/onvif/OnvifPhase2Verification.java
package com.genersoft.iot.vmp.onvif;

import com.genersoft.iot.vmp.common.enums.ChannelDataType;
import com.genersoft.iot.vmp.gb28181.bean.CommonGBChannel;
import com.genersoft.iot.vmp.gb28181.service.IGbChannelService;
import com.genersoft.iot.vmp.onvif.bean.OnvifChannel;
import com.genersoft.iot.vmp.onvif.bean.OnvifDevice;
import com.genersoft.iot.vmp.onvif.bean.OnvifProfile;
import com.genersoft.iot.vmp.onvif.client.OnvifSoapClient;
import com.genersoft.iot.vmp.onvif.dao.OnvifChannelMapper;
import com.genersoft.iot.vmp.onvif.dao.OnvifDeviceMapper;
import com.genersoft.iot.vmp.onvif.dao.provider.OnvifDeviceProvider;
import com.genersoft.iot.vmp.onvif.service.impl.OnvifDeviceServiceImpl;
import org.mockito.Mockito;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 阶段二验收标准自动化自检启动器
 */
public class OnvifPhase2Verification {

    public static void main(String[] args) {
        System.out.println("========== 开始执行 ONVIF 第二阶段自动化验收自检 ==========");
        int total = 0;
        int passed = 0;

        // 1. 测试 ChannelDataType.ONVIF 常量与描述
        total++;
        try {
            if (ChannelDataType.ONVIF != 4) {
                throw new RuntimeException("ChannelDataType.ONVIF 期望值为 4，实际为 " + ChannelDataType.ONVIF);
            }
            String desc = ChannelDataType.getDateTypeDesc(ChannelDataType.ONVIF);
            if (!"ONVIF".equals(desc)) {
                throw new RuntimeException("ChannelDataType.getDateTypeDesc 期望为 ONVIF，实际为 " + desc);
            }
            System.out.println("  [PASS] 1. ChannelDataType.ONVIF 常量与描述验证通过");
            passed++;
        } catch (Exception e) {
            System.err.println("  [FAIL] 1. ChannelDataType 验证失败: " + e.getMessage());
        }

        // 2. 测试 增量-onvif.sql 脚本存在与核心 DDL 约束
        total++;
        try {
            File ddlFile = new File("数据库/2.7.4/增量-onvif.sql");
            if (!ddlFile.exists()) {
                throw new RuntimeException("数据库/2.7.4/增量-onvif.sql 文件不存在");
            }
            String sqlContent = Files.readString(ddlFile.toPath());
            if (!sqlContent.contains("wvp_onvif_device") || !sqlContent.contains("wvp_onvif_channel")) {
                throw new RuntimeException("DDL 缺少 wvp_onvif_device 或 wvp_onvif_channel 建表语句");
            }
            if (!sqlContent.contains("uk_onvif_ip_port") || !sqlContent.contains("uk_onvif_dev_profile")) {
                throw new RuntimeException("DDL 缺少唯一键约束");
            }
            System.out.println("  [PASS] 2. 数据库增量 DDL 脚本存在性与约束验证通过");
            passed++;
        } catch (Exception e) {
            System.err.println("  [FAIL] 2. 数据库增量 DDL 脚本验证失败: " + e.getMessage());
        }

        // 3. 测试 OnvifDevice 与 OnvifChannel 实体及转国标 CommonGBChannel 映射
        total++;
        try {
            OnvifDevice dev = OnvifDevice.builder()
                    .id(101)
                    .name("大门主摄")
                    .ip("192.168.1.64")
                    .port(80)
                    .manufacturer("Hikvision")
                    .model("DS-2CD2047G2-L")
                    .status(1)
                    .build();

            OnvifChannel ch = OnvifChannel.builder()
                    .id(201)
                    .deviceId(101)
                    .channelIndex(1)
                    .profileToken("Profile_1")
                    .name("MainStream")
                    .videoEncoding("H264")
                    .resolution("2560x1440")
                    .rtspUrl("rtsp://192.168.1.64:554/Streaming/Channels/101")
                    .hasPtz(1)
                    .gbDeviceId("34020000001321010001")
                    .createTime("2026-09-08 20:00:00")
                    .updateTime("2026-09-08 20:00:00")
                    .build();

            CommonGBChannel gb = ch.toCommonGBChannel(dev);
            if (gb.getDataType() != ChannelDataType.ONVIF) {
                throw new RuntimeException("CommonGBChannel.dataType 期望为 ChannelDataType.ONVIF (4)，实际为 " + gb.getDataType());
            }
            if (!Integer.valueOf(201).equals(gb.getDataDeviceId())) {
                throw new RuntimeException("CommonGBChannel.dataDeviceId 期望为 201，实际为 " + gb.getDataDeviceId());
            }
            if (!"34020000001321010001".equals(gb.getGbDeviceId())) {
                throw new RuntimeException("CommonGBChannel.gbDeviceId 映射错误: " + gb.getGbDeviceId());
            }
            if (!"MainStream".equals(gb.getGbName())) {
                throw new RuntimeException("CommonGBChannel.gbName 映射错误: " + gb.getGbName());
            }
            if (!"Hikvision".equals(gb.getGbManufacturer()) || !"DS-2CD2047G2-L".equals(gb.getGbModel())) {
                throw new RuntimeException("CommonGBChannel 厂商/型号映射错误");
            }
            if (!"ON".equals(gb.getGbStatus())) {
                throw new RuntimeException("CommonGBChannel 在线状态映射错误: " + gb.getGbStatus());
            }
            if (gb.getGbPtzType() == null || gb.getGbPtzType() != 1) {
                throw new RuntimeException("CommonGBChannel PTZ 映射错误: " + gb.getGbPtzType());
            }
            System.out.println("  [PASS] 3. OnvifDevice/OnvifChannel 实体与 toCommonGBChannel 级联映射验证通过");
            passed++;
        } catch (Exception e) {
            System.err.println("  [FAIL] 3. 实体与映射验证失败: " + e.getMessage());
        }

        // 4. 测试 OnvifDeviceProvider 动态 SQL 生成
        total++;
        try {
            OnvifDeviceProvider provider = new OnvifDeviceProvider();
            String sql1 = provider.queryListSql(null, null);
            if (!sql1.contains("SELECT * FROM wvp_onvif_device WHERE 1=1 ORDER BY id DESC")) {
                throw new RuntimeException("空查询 SQL 结构不符: " + sql1);
            }

            String sql2 = provider.queryListSql("海康", 1);
            if (!sql2.contains("LIKE CONCAT('%', #{query}, '%')") || !sql2.contains("status = #{status}")) {
                throw new RuntimeException("条件查询 SQL 结构不符: " + sql2);
            }
            System.out.println("  [PASS] 4. OnvifDeviceProvider 动态 SQL 生成验证通过");
            passed++;
        } catch (Exception e) {
            System.err.println("  [FAIL] 4. OnvifDeviceProvider 验证失败: " + e.getMessage());
        }

        // 5. 模拟测试 OnvifDeviceServiceImpl 握手与通道同步流水线
        total++;
        try {
            OnvifDeviceMapper mockDevMapper = Mockito.mock(OnvifDeviceMapper.class);
            OnvifChannelMapper mockChMapper = Mockito.mock(OnvifChannelMapper.class);
            OnvifSoapClient mockSoapClient = Mockito.mock(OnvifSoapClient.class);
            IGbChannelService mockGbChannelService = Mockito.mock(IGbChannelService.class);

            // Mock SOAP responses
            String mockUtcXml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                    "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\">\n" +
                    "  <s:Body>\n" +
                    "    <tds:GetSystemDateAndTimeResponse xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\" xmlns:tt=\"http://www.onvif.org/ver10/schema\">\n" +
                    "      <tds:SystemDateAndTime>\n" +
                    "        <tt:UTCDateTime>\n" +
                    "          <tt:Time><tt:Hour>12</tt:Hour><tt:Minute>0</tt:Minute><tt:Second>0</tt:Second></tt:Time>\n" +
                    "          <tt:Date><tt:Year>2026</tt:Year><tt:Month>9</tt:Month><tt:Day>8</tt:Day></tt:Date>\n" +
                    "        </tt:UTCDateTime>\n" +
                    "      </tds:SystemDateAndTime>\n" +
                    "    </tds:GetSystemDateAndTimeResponse>\n" +
                    "  </s:Body>\n" +
                    "</s:Envelope>";

            String mockCapXml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                    "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\">\n" +
                    "  <s:Body>\n" +
                    "    <tds:GetCapabilitiesResponse xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\" xmlns:tt=\"http://www.onvif.org/ver10/schema\">\n" +
                    "      <tds:Capabilities>\n" +
                    "        <tt:Media><tt:XAddr>http://192.168.1.64:80/onvif/media_service</tt:XAddr></tt:Media>\n" +
                    "        <tt:PTZ><tt:XAddr>http://192.168.1.64:80/onvif/ptz_service</tt:XAddr></tt:PTZ>\n" +
                    "      </tds:Capabilities>\n" +
                    "    </tds:GetCapabilitiesResponse>\n" +
                    "  </s:Body>\n" +
                    "</s:Envelope>";

            String mockDevInfoXml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                    "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\">\n" +
                    "  <s:Body>\n" +
                    "    <tds:GetDeviceInformationResponse xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\">\n" +
                    "      <tds:Manufacturer>Dahua</tds:Manufacturer>\n" +
                    "      <tds:Model>DH-IPC-HFW</tds:Model>\n" +
                    "      <tds:FirmwareVersion>V2.800</tds:FirmwareVersion>\n" +
                    "      <tds:SerialNumber>DH123456789</tds:SerialNumber>\n" +
                    "    </tds:GetDeviceInformationResponse>\n" +
                    "  </s:Body>\n" +
                    "</s:Envelope>";

            String mockProfilesXml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                    "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\">\n" +
                    "  <s:Body>\n" +
                    "    <trt:GetProfilesResponse xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\" xmlns:tt=\"http://www.onvif.org/ver10/schema\">\n" +
                    "      <trt:Profiles token=\"Profile_Main\">\n" +
                    "        <tt:Name>MainStream</tt:Name>\n" +
                    "        <tt:VideoEncoderConfiguration>\n" +
                    "          <tt:Encoding>H264</tt:Encoding>\n" +
                    "          <tt:Resolution><tt:Width>1920</tt:Width><tt:Height>1080</tt:Height></tt:Resolution>\n" +
                    "          <tt:FrameRateLimit>25</tt:FrameRateLimit>\n" +
                    "          <tt:BitrateLimit>4096</tt:BitrateLimit>\n" +
                    "        </tt:VideoEncoderConfiguration>\n" +
                    "        <tt:PTZConfiguration token=\"ptz_cfg_1\"/>\n" +
                    "      </trt:Profiles>\n" +
                    "    </trt:GetProfilesResponse>\n" +
                    "  </s:Body>\n" +
                    "</s:Envelope>";

            String mockRtspXml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                    "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\">\n" +
                    "  <s:Body>\n" +
                    "    <trt:GetStreamUriResponse xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\" xmlns:tt=\"http://www.onvif.org/ver10/schema\">\n" +
                    "      <trt:MediaUri>\n" +
                    "        <tt:Uri>rtsp://192.168.1.64:554/cam/realmonitor?channel=1&amp;subtype=0</tt:Uri>\n" +
                    "      </trt:MediaUri>\n" +
                    "    </trt:GetStreamUriResponse>\n" +
                    "  </s:Body>\n" +
                    "</s:Envelope>";

            when(mockSoapClient.sendSoap(anyString(), any(), anyString())).thenAnswer(inv -> {
                String req = inv.getArgument(2);
                if (req.contains("GetSystemDateAndTime")) return mockUtcXml;
                if (req.contains("GetCapabilities")) return mockCapXml;
                if (req.contains("GetDeviceInformation")) return mockDevInfoXml;
                if (req.contains("GetProfiles")) return mockProfilesXml;
                if (req.contains("GetStreamUri")) return mockRtspXml;
                return "";
            });

            OnvifDeviceServiceImpl service = new OnvifDeviceServiceImpl(mockDevMapper, mockChMapper, mockSoapClient, mockGbChannelService);

            OnvifDevice dev = OnvifDevice.builder()
                    .ip("192.168.1.64")
                    .port(80)
                    .username("admin")
                    .password("admin123")
                    .build();

            // 握手测试
            service.probeAndSyncMetadata(dev);
            if (!"Dahua".equals(dev.getManufacturer()) || !"DH-IPC-HFW".equals(dev.getModel())) {
                throw new RuntimeException("设备元数据解析错误: " + dev.getManufacturer());
            }
            if (!"http://192.168.1.64:80/onvif/media_service".equals(dev.getMediaServiceUrl())) {
                throw new RuntimeException("MediaServiceUrl 提取错误: " + dev.getMediaServiceUrl());
            }
            if (!"http://192.168.1.64:80/onvif/ptz_service".equals(dev.getPtzServiceUrl())) {
                throw new RuntimeException("PtzServiceUrl 提取错误: " + dev.getPtzServiceUrl());
            }

            // 通道同步测试
            dev.setId(1);
            when(mockDevMapper.selectById(1)).thenReturn(dev);
            List<OnvifChannel> insertedChannels = new ArrayList<>();
            when(mockChMapper.insert(any())).thenAnswer(inv -> {
                OnvifChannel ch = inv.getArgument(0);
                ch.setId(99);
                insertedChannels.add(ch);
                return 1;
            });

            service.syncChannels(1);

            if (insertedChannels.isEmpty()) {
                throw new RuntimeException("未成功解析并插入 ONVIF 通道");
            }
            OnvifChannel ch = insertedChannels.get(0);
            if (!"Profile_Main".equals(ch.getProfileToken()) || !"1920x1080".equals(ch.getResolution())) {
                throw new RuntimeException("通道 ProfileToken 或 Resolution 解析错误: " + ch);
            }
            if (ch.getHasPtz() != 1) {
                throw new RuntimeException("PTZ 能力提取失败: hasPtz = " + ch.getHasPtz());
            }
            if (!ch.getRtspUrl().contains("rtsp://192.168.1.64:554")) {
                throw new RuntimeException("RTSP URL 解析错误: " + ch.getRtspUrl());
            }

            System.out.println("  [PASS] 5. OnvifDeviceServiceImpl 握手探测与 Profile 通道同步完整流水线验证通过");
            passed++;
        } catch (Exception e) {
            System.err.println("  [FAIL] 5. 服务流水线验证失败: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.printf("========== 自检完成: %d/%d 通过 ==========%n", passed, total);
        if (passed != total) {
            System.exit(1);
        }
    }
}
