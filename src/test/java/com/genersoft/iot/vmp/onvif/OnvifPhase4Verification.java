// src/test/java/com/genersoft/iot/vmp/onvif/OnvifPhase4Verification.java
package com.genersoft.iot.vmp.onvif;

import com.genersoft.iot.vmp.conf.exception.ControllerException;
import com.genersoft.iot.vmp.gb28181.bean.CommonGBChannel;
import com.genersoft.iot.vmp.gb28181.bean.FrontEndControlCodeForPTZ;
import com.genersoft.iot.vmp.gb28181.service.IGbChannelControlService;
import com.genersoft.iot.vmp.gb28181.service.IGbChannelService;
import com.genersoft.iot.vmp.onvif.bean.OnvifChannel;
import com.genersoft.iot.vmp.onvif.bean.OnvifDevice;
import com.genersoft.iot.vmp.onvif.bean.OnvifProbeResult;
import com.genersoft.iot.vmp.onvif.client.OnvifDiscoveryClient;
import com.genersoft.iot.vmp.onvif.controller.OnvifDeviceController;
import com.genersoft.iot.vmp.onvif.controller.OnvifDiscoveryController;
import com.genersoft.iot.vmp.onvif.controller.OnvifPtzController;
import com.genersoft.iot.vmp.onvif.dao.OnvifChannelMapper;
import com.genersoft.iot.vmp.onvif.service.IOnvifDeviceService;
import com.genersoft.iot.vmp.service.bean.ErrorCallback;
import com.genersoft.iot.vmp.vmanager.bean.WVPResult;
import com.github.pagehelper.PageInfo;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.web.context.request.async.DeferredResult;

import java.io.File;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 第四阶段验收标准自动化自检启动器
 */
public class OnvifPhase4Verification {

    public static void main(String[] args) {
        System.out.println("========== 开始执行 ONVIF 第四阶段自动化验收自检 ==========");
        int total = 0;
        int passed = 0;

        // 1. 验证 OnvifDeviceController 列表与通道查询
        total++;
        try {
            IOnvifDeviceService deviceService = mock(IOnvifDeviceService.class);
            OnvifChannelMapper channelMapper = mock(OnvifChannelMapper.class);
            OnvifDeviceController controller = new OnvifDeviceController(deviceService, channelMapper);

            PageInfo<OnvifDevice> page = new PageInfo<>(Collections.singletonList(
                    OnvifDevice.builder().id(1).name("Camera1").ip("192.168.1.100").port(80).status(1).build()
            ));
            when(deviceService.getDeviceList(1, 10, null, null)).thenReturn(page);

            WVPResult<PageInfo<OnvifDevice>> listResult = controller.list(1, 10, null, null);
            if (listResult.getCode() != 0 || listResult.getData().getList().size() != 1) {
                throw new RuntimeException("OnvifDeviceController.list 返回异常");
            }

            OnvifChannel ch = new OnvifChannel();
            ch.setId(10);
            ch.setName("Profile1");
            when(channelMapper.selectByDeviceId(1)).thenReturn(Collections.singletonList(ch));

            WVPResult<List<OnvifChannel>> channelsResult = controller.getChannels(1);
            if (channelsResult.getCode() != 0 || channelsResult.getData().size() != 1) {
                throw new RuntimeException("OnvifDeviceController.getChannels 返回异常");
            }

            System.out.println("√ 测试 1 通过: OnvifDeviceController 列表查询与通道台账接口契约正常");
            passed++;
        } catch (Exception e) {
            System.err.println("× 测试 1 失败: " + e.getMessage());
        }

        // 2. 验证 OnvifDeviceController 参数校验与添加/删除/同步
        total++;
        try {
            IOnvifDeviceService deviceService = mock(IOnvifDeviceService.class);
            OnvifChannelMapper channelMapper = mock(OnvifChannelMapper.class);
            OnvifDeviceController controller = new OnvifDeviceController(deviceService, channelMapper);

            // 缺少必填参数
            boolean caught = false;
            try {
                controller.add(new OnvifDevice());
            } catch (ControllerException ce) {
                caught = true;
            }
            if (!caught) {
                throw new RuntimeException("未拦截缺少 IP/端口/凭据的非法设备接入请求");
            }

            OnvifDevice valid = OnvifDevice.builder().ip("192.168.1.200").port(80).username("admin").password("123456").build();
            when(deviceService.addDevice(any())).thenReturn(valid);
            WVPResult<OnvifDevice> addRes = controller.add(valid);
            if (addRes.getCode() != 0 || addRes.getData() == null) {
                throw new RuntimeException("addDevice 处理正常设备返回失败");
            }

            controller.delete(1);
            verify(deviceService, times(1)).deleteDevice(1);

            controller.sync(1);
            verify(deviceService, times(1)).syncChannels(1);

            System.out.println("√ 测试 2 通过: OnvifDeviceController 接入必填校验与增删同步路由逻辑正常");
            passed++;
        } catch (Exception e) {
            System.err.println("× 测试 2 失败: " + e.getMessage());
        }

        // 3. 验证 OnvifDiscoveryController 探测接口
        total++;
        try {
            OnvifDiscoveryClient client = mock(OnvifDiscoveryClient.class);
            OnvifDiscoveryController controller = new OnvifDiscoveryController(client);

            when(client.probe(3)).thenReturn(Collections.singletonList(
                    OnvifProbeResult.builder().ip("192.168.1.108").port(80).manufacturer("Dahua").build()
            ));

            WVPResult<List<OnvifProbeResult>> probeRes = controller.probe(3);
            if (probeRes.getCode() != 0 || probeRes.getData().isEmpty()) {
                throw new RuntimeException("多播探测接口返回失败");
            }

            // 单播探测存在
            when(client.probeSingle("192.168.1.108", 80)).thenReturn(
                    OnvifProbeResult.builder().ip("192.168.1.108").port(80).build()
            );
            WVPResult<OnvifProbeResult> singleRes = controller.probeSingle("192.168.1.108", 80);
            if (singleRes.getCode() != 0 || singleRes.getData() == null) {
                throw new RuntimeException("单播探测接口返回失败");
            }

            // 单播探测不存在返回 404
            when(client.probeSingle("10.0.0.1", 80)).thenReturn(null);
            WVPResult<OnvifProbeResult> notFoundRes = controller.probeSingle("10.0.0.1", 80);
            if (notFoundRes.getCode() != 404) {
                throw new RuntimeException("设备未响应时未返回 404 错误码");
            }

            System.out.println("√ 测试 3 通过: OnvifDiscoveryController 多播与单播搜寻接口正常");
            passed++;
        } catch (Exception e) {
            System.err.println("× 测试 3 失败: " + e.getMessage());
        }

        // 4. 验证 OnvifPtzController 云台转动、变倍与即时制动
        total++;
        try {
            IGbChannelService channelService = mock(IGbChannelService.class);
            IGbChannelControlService controlService = mock(IGbChannelControlService.class);
            OnvifPtzController controller = new OnvifPtzController(channelService, controlService);

            CommonGBChannel gbChannel = new CommonGBChannel();
            gbChannel.setGbId(101);
            gbChannel.setGbDeviceId("34020000001320010001");
            when(channelService.queryByDeviceId("34020000001320010001")).thenReturn(gbChannel);

            // A. 向左连续转动 (pan=0, tilt=null)
            ArgumentCaptor<FrontEndControlCodeForPTZ> ptzCaptor = ArgumentCaptor.forClass(FrontEndControlCodeForPTZ.class);
            controller.ptz("34020000001320010001", 0, null, null, 128, 128, 128);
            verify(controlService).ptz(eq(gbChannel), ptzCaptor.capture(), any());

            FrontEndControlCodeForPTZ captured = ptzCaptor.getValue();
            if (captured.getPan() == null || captured.getPan() != 0 || captured.getTilt() != null) {
                throw new RuntimeException("向左转动指令码映射异常: pan=" + captured.getPan());
            }

            // B. 刹车即时停止 (pan=null, tilt=null, zoom=null)
            ArgumentCaptor<FrontEndControlCodeForPTZ> stopCaptor = ArgumentCaptor.forClass(FrontEndControlCodeForPTZ.class);
            controller.ptz("34020000001320010001", null, null, null, 128, 128, 128);
            verify(controlService, times(2)).ptz(eq(gbChannel), stopCaptor.capture(), any());

            FrontEndControlCodeForPTZ stopCode = stopCaptor.getValue();
            if (stopCode.getPan() != null || stopCode.getTilt() != null || stopCode.getZoom() != null) {
                throw new RuntimeException("刹车停止指令码未完全置空");
            }

            System.out.println("√ 测试 4 通过: OnvifPtzController 云台平滑转动与刹车停止指令分发正常");
            passed++;
        } catch (Exception e) {
            System.err.println("× 测试 4 失败: " + e.getMessage());
        }

        // 5. 验证前端路由配置、API 请求模块与 Vue 视图文件资产完整性
        total++;
        try {
            File routerFile = new File("web/src/router/index.js");
            if (!routerFile.exists()) throw new RuntimeException("web/src/router/index.js 文件不存在");
            String routerContent = Files.readString(routerFile.toPath());
            if (!routerContent.contains("path: '/onvif'") || !routerContent.contains("@/views/onvif/index")) {
                throw new RuntimeException("路由文件中未注册 /onvif 路径");
            }

            File apiFile = new File("web/src/api/onvif.js");
            if (!apiFile.exists()) throw new RuntimeException("web/src/api/onvif.js 文件不存在");
            String apiContent = Files.readString(apiFile.toPath());
            if (!apiContent.contains("getOnvifDeviceList") || !apiContent.contains("probeOnvifDevices")) {
                throw new RuntimeException("web/src/api/onvif.js 缺少核心 API 函数");
            }

            File indexVue = new File("web/src/views/onvif/index.vue");
            File discoveryVue = new File("web/src/views/onvif/deviceDiscovery.vue");
            File ptzVue = new File("web/src/views/onvif/ptzController.vue");

            if (!indexVue.exists() || Files.readString(indexVue.toPath()).length() < 100) {
                throw new RuntimeException("web/src/views/onvif/index.vue 视图缺失或内容不全");
            }
            if (!discoveryVue.exists() || Files.readString(discoveryVue.toPath()).length() < 100) {
                throw new RuntimeException("web/src/views/onvif/deviceDiscovery.vue 视图缺失或内容不全");
            }
            if (!ptzVue.exists() || Files.readString(ptzVue.toPath()).length() < 100) {
                throw new RuntimeException("web/src/views/onvif/ptzController.vue 视图缺失或内容不全");
            }

            System.out.println("√ 测试 5 通过: 前端路由注册、Axios API 封装与三大 Vue 视图完整合规");
            passed++;
        } catch (Exception e) {
            System.err.println("× 测试 5 失败: " + e.getMessage());
        }

        System.out.println("==================================================");
        System.out.printf("自检统计: 共执行 %d 项测试，通过 %d 项，失败 %d 项\n", total, passed, total - passed);
        if (passed == total) {
            System.out.println("【恭喜】ONVIF 第四阶段（RESTful API 与 Web UI）构建验证全部通过！");
        } else {
            System.err.println("【警告】部分测试未通过，请检查修复后再提交交付！");
            System.exit(1);
        }
    }
}
