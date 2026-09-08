// src/test/java/com/genersoft/iot/vmp/onvif/OnvifPhase3Verification.java
package com.genersoft.iot.vmp.onvif;

import com.genersoft.iot.vmp.common.StreamInfo;
import com.genersoft.iot.vmp.common.enums.ChannelDataType;
import com.genersoft.iot.vmp.conf.DynamicTask;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.gb28181.bean.CommonGBChannel;
import com.genersoft.iot.vmp.gb28181.bean.FrontEndControlCodeForPTZ;
import com.genersoft.iot.vmp.gb28181.bean.FrontEndControlCodeForPreset;
import com.genersoft.iot.vmp.gb28181.bean.Preset;
import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.media.event.hook.HookSubscribe;
import com.genersoft.iot.vmp.media.service.IMediaServerService;
import com.genersoft.iot.vmp.onvif.bean.OnvifChannel;
import com.genersoft.iot.vmp.onvif.bean.OnvifDevice;
import com.genersoft.iot.vmp.onvif.client.OnvifSoapClient;
import com.genersoft.iot.vmp.onvif.client.OnvifXmlBuilder;
import com.genersoft.iot.vmp.onvif.client.OnvifXmlParser;
import com.genersoft.iot.vmp.onvif.dao.OnvifChannelMapper;
import com.genersoft.iot.vmp.onvif.dao.OnvifDeviceMapper;
import com.genersoft.iot.vmp.onvif.service.impl.SourceOtherServiceForOnvifImpl;
import com.genersoft.iot.vmp.onvif.service.impl.SourcePTZServiceForOnvifImpl;
import com.genersoft.iot.vmp.onvif.service.impl.SourcePlayServiceForOnvifImpl;
import com.genersoft.iot.vmp.vmanager.bean.WVPResult;
import org.dom4j.Document;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 阶段三验收标准自动化自检启动器
 */
public class OnvifPhase3Verification {

    public static void main(String[] args) {
        System.out.println("========== 开始执行 ONVIF 第三阶段自动化验收自检 ==========");
        int total = 0;
        int passed = 0;

        // 1. 验证策略服务名称与注解契约
        total++;
        try {
            Service playAnnotation = SourcePlayServiceForOnvifImpl.class.getAnnotation(Service.class);
            Service otherAnnotation = SourceOtherServiceForOnvifImpl.class.getAnnotation(Service.class);
            Service ptzAnnotation = SourcePTZServiceForOnvifImpl.class.getAnnotation(Service.class);

            if (playAnnotation == null || !("sourceChannelPlayService4".equals(playAnnotation.value()))) {
                throw new RuntimeException("SourcePlayServiceForOnvifImpl 注册名不匹配: " + (playAnnotation != null ? playAnnotation.value() : null));
            }
            if (otherAnnotation == null || !("sourceChannelOtherService4".equals(otherAnnotation.value()))) {
                throw new RuntimeException("SourceOtherServiceForOnvifImpl 注册名不匹配: " + (otherAnnotation != null ? otherAnnotation.value() : null));
            }
            if (ptzAnnotation == null || !("sourceChannelPTZService4".equals(ptzAnnotation.value()))) {
                throw new RuntimeException("SourcePTZServiceForOnvifImpl 注册名不匹配: " + (ptzAnnotation != null ? ptzAnnotation.value() : null));
            }
            System.out.println("  [PASS] 1. 核心策略服务名注解契约匹配验证通过");
            passed++;
        } catch (Exception e) {
            System.err.println("  [FAIL] 1. 核心策略服务名注解契约验证失败: " + e.getMessage());
        }

        // 2. 验证 PTZ 速度归一化计算模型与 ContinuousMove / Stop 报文
        total++;
        try {
            OnvifChannelMapper channelMapper = mock(OnvifChannelMapper.class);
            OnvifDeviceMapper deviceMapper = mock(OnvifDeviceMapper.class);
            OnvifSoapClient soapClient = mock(OnvifSoapClient.class);

            OnvifChannel mockChannel = OnvifChannel.builder()
                    .id(10)
                    .deviceId(100)
                    .profileToken("profile_token_main")
                    .hasPtz(1)
                    .build();
            OnvifDevice mockDevice = OnvifDevice.builder()
                    .id(100)
                    .username("admin")
                    .password("123456")
                    .clockOffset(0L)
                    .ptzServiceUrl("http://192.168.1.100:80/onvif/ptz")
                    .build();

            when(channelMapper.selectById(10)).thenReturn(mockChannel);
            when(deviceMapper.selectById(100)).thenReturn(mockDevice);

            SourcePTZServiceForOnvifImpl ptzService = new SourcePTZServiceForOnvifImpl(channelMapper, deviceMapper, soapClient);
            CommonGBChannel gbChannel = new CommonGBChannel();
            gbChannel.setDataDeviceId(10);

            // A. 测试左上转动 (pan=0, tilt=0, speed=255)
            FrontEndControlCodeForPTZ moveCode = new FrontEndControlCodeForPTZ();
            moveCode.setPan(0); // 左
            moveCode.setPanSpeed(255);
            moveCode.setTilt(0); // 上
            moveCode.setTiltSpeed(255);

            AtomicBoolean successFlag = new AtomicBoolean(false);
            ptzService.ptz(gbChannel, moveCode, (code, msg, data) -> {
                if (code == 0) successFlag.set(true);
            });

            if (!successFlag.get()) {
                throw new RuntimeException("PTZ 控制回调未成功返回");
            }

            ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
            verify(soapClient, times(1)).sendAuthenticatedSoap(eq("http://192.168.1.100:80/onvif/ptz"), isNull(), bodyCaptor.capture(), eq("admin"), eq("123456"), eq(0L));
            String moveXml = bodyCaptor.getValue();
            if (!moveXml.contains("ContinuousMove") || !moveXml.contains("x=\"-1.000\"") || !moveXml.contains("y=\"1.000\"")) {
                throw new RuntimeException("速度归一化报文错误: " + moveXml);
            }

            // B. 测试制动即停 (pan=null, tilt=null, zoom=null)
            FrontEndControlCodeForPTZ stopCode = new FrontEndControlCodeForPTZ();
            ptzService.ptz(gbChannel, stopCode, (code, msg, data) -> {});

            verify(soapClient, times(2)).sendAuthenticatedSoap(eq("http://192.168.1.100:80/onvif/ptz"), isNull(), bodyCaptor.capture(), eq("admin"), eq("123456"), eq(0L));
            String stopXml = bodyCaptor.getValue();
            if (!stopXml.contains("Stop") || !stopXml.contains("<tptz:PanTilt>true</tptz:PanTilt>")) {
                throw new RuntimeException("Stop 报文错误: " + stopXml);
            }

            System.out.println("  [PASS] 2. PTZ 速度归一化模型与 ContinuousMove / Stop 下发验证通过");
            passed++;
        } catch (Exception e) {
            System.err.println("  [FAIL] 2. PTZ 控制验证失败: " + e.getMessage());
        }

        // 3. 验证预置位全生命周期 (GotoPreset, SetPreset, RemovePreset, GetPresets)
        total++;
        try {
            OnvifChannelMapper channelMapper = mock(OnvifChannelMapper.class);
            OnvifDeviceMapper deviceMapper = mock(OnvifDeviceMapper.class);
            OnvifSoapClient soapClient = mock(OnvifSoapClient.class);

            OnvifChannel mockChannel = OnvifChannel.builder()
                    .id(10)
                    .deviceId(100)
                    .profileToken("token_1")
                    .hasPtz(1)
                    .build();
            OnvifDevice mockDevice = OnvifDevice.builder()
                    .id(100)
                    .username("admin")
                    .password("123456")
                    .clockOffset(0L)
                    .ptzServiceUrl("http://192.168.1.100/ptz")
                    .build();

            when(channelMapper.selectById(10)).thenReturn(mockChannel);
            when(deviceMapper.selectById(100)).thenReturn(mockDevice);

            String mockGetPresetsResp = """
                    <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl" xmlns:tt="http://www.onvif.org/ver10/schema">
                      <s:Body>
                        <tptz:GetPresetsResponse>
                          <tptz:Preset token="1">
                            <tt:Name>GatePos</tt:Name>
                          </tptz:Preset>
                          <tptz:Preset token="2">
                            <tt:Name>ParkingLot</tt:Name>
                          </tptz:Preset>
                        </tptz:GetPresetsResponse>
                      </s:Body>
                    </s:Envelope>
                    """;
            when(soapClient.sendAuthenticatedSoap(eq("http://192.168.1.100/ptz"), isNull(), anyString(), anyString(), anyString(), anyLong()))
                    .thenReturn(mockGetPresetsResp);

            SourcePTZServiceForOnvifImpl ptzService = new SourcePTZServiceForOnvifImpl(channelMapper, deviceMapper, soapClient);
            CommonGBChannel gbChannel = new CommonGBChannel();
            gbChannel.setDataDeviceId(10);

            AtomicReference<List<Preset>> presetResult = new AtomicReference<>();
            ptzService.queryPreset(gbChannel, (code, msg, data) -> presetResult.set(data));

            if (presetResult.get() == null || presetResult.get().size() != 2) {
                throw new RuntimeException("预置位列表解析结果不符合预期: " + presetResult.get());
            }
            if (!"1".equals(presetResult.get().get(0).getPresetId()) || !"GatePos".equals(presetResult.get().get(0).getPresetName())) {
                throw new RuntimeException("预置位 1 属性不匹配: " + presetResult.get().get(0));
            }

            // 测试调用预置位 (code = 2)
            FrontEndControlCodeForPreset gotoCode = new FrontEndControlCodeForPreset();
            gotoCode.setCode(2);
            gotoCode.setPresetId(1);
            ptzService.preset(gbChannel, gotoCode, (code, msg, data) -> {});

            System.out.println("  [PASS] 3. 预置位全生命周期与解析验证通过");
            passed++;
        } catch (Exception e) {
            System.err.println("  [FAIL] 3. 预置位测试失败: " + e.getMessage());
        }

        // 4. 验证流媒体调度 SourcePlayServiceForOnvifImpl 与认证 RTSP 构建
        total++;
        try {
            OnvifChannelMapper channelMapper = mock(OnvifChannelMapper.class);
            OnvifDeviceMapper deviceMapper = mock(OnvifDeviceMapper.class);
            IMediaServerService mediaServerService = mock(IMediaServerService.class);
            HookSubscribe hookSubscribe = mock(HookSubscribe.class);
            DynamicTask dynamicTask = mock(DynamicTask.class);
            UserSetting userSetting = new UserSetting();
            userSetting.setPlayTimeout(10000);

            OnvifChannel mockChannel = OnvifChannel.builder()
                    .id(2)
                    .deviceId(1)
                    .profileToken("Profile_001")
                    .rtspUrl("rtsp://192.168.1.100:554/live/ch0")
                    .build();
            OnvifDevice mockDevice = OnvifDevice.builder()
                    .id(1)
                    .username("admin")
                    .password("pass@123")
                    .mediaServerId("auto")
                    .build();

            MediaServer mockMediaServer = new MediaServer();
            mockMediaServer.setId("zlm_node_1");

            when(channelMapper.selectById(2)).thenReturn(mockChannel);
            when(deviceMapper.selectById(1)).thenReturn(mockDevice);
            when(mediaServerService.getMediaServerForMinimumLoad(null)).thenReturn(mockMediaServer);
            when(mediaServerService.addStreamProxy(any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any()))
                    .thenReturn(WVPResult.success("stream_proxy_key_test"));

            SourcePlayServiceForOnvifImpl playService = new SourcePlayServiceForOnvifImpl(
                    channelMapper, deviceMapper, mediaServerService, hookSubscribe, dynamicTask, userSetting);

            CommonGBChannel gbChannel = new CommonGBChannel();
            gbChannel.setDataDeviceId(2);
            playService.play(gbChannel, null, false, (code, msg, data) -> {});

            ArgumentCaptor<String> rtspUrlCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> appCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> streamCaptor = ArgumentCaptor.forClass(String.class);

            verify(mediaServerService).addStreamProxy(eq(mockMediaServer), appCaptor.capture(), streamCaptor.capture(),
                    rtspUrlCaptor.capture(), eq(true), eq(false), eq("0"), eq(10));

            if (!"onvif".equals(appCaptor.getValue()) || !"onvif_1_2".equals(streamCaptor.getValue())) {
                throw new RuntimeException("拉流应用或流名不匹配: app=" + appCaptor.getValue() + ", stream=" + streamCaptor.getValue());
            }
            if (!rtspUrlCaptor.getValue().startsWith("rtsp://admin:pass%40123@192.168.1.100:554/live/ch0")) {
                throw new RuntimeException("带认证凭据的 RTSP 地址编码异常: " + rtspUrlCaptor.getValue());
            }

            // 测试主动停止流
            playService.stopPlay(gbChannel);
            verify(mediaServerService).delStreamProxy(eq(mockMediaServer), eq("stream_proxy_key_test"));
            verify(mediaServerService).closeStreams(eq(mockMediaServer), eq("onvif"), eq("onvif_1_2"));

            System.out.println("  [PASS] 4. 流媒体调度代理与凭据转义建联验证通过");
            passed++;
        } catch (Exception e) {
            System.err.println("  [FAIL] 4. 流媒体调度验证失败: " + e.getMessage());
        }

        // 5. 验证无人观看自动停流 SourceOtherServiceForOnvifImpl
        total++;
        try {
            UserSetting userSetting = new UserSetting();
            userSetting.setStreamOnDemand(true);

            IMediaServerService mediaServerService = mock(IMediaServerService.class);
            SourcePlayServiceForOnvifImpl playService = mock(SourcePlayServiceForOnvifImpl.class);
            MediaServer mockMediaServer = new MediaServer();
            mockMediaServer.setId("node_1");
            when(mediaServerService.getOne("node_1")).thenReturn(mockMediaServer);

            SourceOtherServiceForOnvifImpl otherService = new SourceOtherServiceForOnvifImpl(userSetting, mediaServerService, playService);

            // 非 onvif 流不拦截
            Boolean nonOnvif = otherService.closeStreamOnNoneReader("node_1", "live", "ch0", "rtsp");
            if (nonOnvif != null) {
                throw new RuntimeException("非 onvif 应用流期望返回 null，实际返回: " + nonOnvif);
            }

            // onvif 流触发按需释放
            Boolean onvifClose = otherService.closeStreamOnNoneReader("node_1", "onvif", "onvif_1_2", "rtsp");
            if (onvifClose == null || !onvifClose) {
                throw new RuntimeException("onvif 无人观看期望触发释放返回 true，实际返回: " + onvifClose);
            }
            verify(playService, times(1)).stopStream(eq(mockMediaServer), eq("onvif"), eq("onvif_1_2"));

            System.out.println("  [PASS] 5. 无人观看自动停流服务验证通过");
            passed++;
        } catch (Exception e) {
            System.err.println("  [FAIL] 5. 无人观看停流验证失败: " + e.getMessage());
        }

        System.out.println("==========================================================");
        System.out.printf("测试汇总: 共计 %d 项自检, 通过 %d 项, 失败 %d 项\n", total, passed, total - passed);
        System.out.println("==========================================================");

        if (passed != total) {
            throw new RuntimeException("ONVIF 阶段三自动化验收存在失败用例");
        }
    }
}
