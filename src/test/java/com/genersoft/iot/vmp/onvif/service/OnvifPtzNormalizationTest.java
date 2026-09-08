// src/test/java/com/genersoft/iot/vmp/onvif/service/OnvifPtzNormalizationTest.java
package com.genersoft.iot.vmp.onvif.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ONVIF PTZ 国标速度到归一化浮点速度转换测试")
public class OnvifPtzNormalizationTest {

    private double calculatePanSpeed(Integer pan, Integer panSpeed) {
        if (pan == null) return 0.0;
        int speed = (panSpeed != null && panSpeed > 0) ? panSpeed : 128;
        double speedNorm = Math.min(1.0, speed / 255.0);
        return (pan == 0) ? -speedNorm : speedNorm;
    }

    private double calculateTiltSpeed(Integer tilt, Integer tiltSpeed) {
        if (tilt == null) return 0.0;
        int speed = (tiltSpeed != null && tiltSpeed > 0) ? tiltSpeed : 128;
        double speedNorm = Math.min(1.0, speed / 255.0);
        return (tilt == 0) ? speedNorm : -speedNorm;
    }

    private double calculateZoomSpeed(Integer zoom, Integer zoomSpeed) {
        if (zoom == null) return 0.0;
        int speed = (zoomSpeed != null && zoomSpeed > 0) ? zoomSpeed : 128;
        double speedNorm = Math.min(1.0, speed / 255.0);
        return (zoom == 0) ? -speedNorm : speedNorm;
    }

    @Test
    @DisplayName("验证边界值与方向性")
    public void testSpeedBoundaryAndDirection() {
        // 向左最大速度 (pan=0, panSpeed=255) -> -1.0
        assertEquals(-1.0, calculatePanSpeed(0, 255), 0.001);

        // 向右最大速度 (pan=1, panSpeed=255) -> +1.0
        assertEquals(1.0, calculatePanSpeed(1, 255), 0.001);

        // 向上最大速度 (tilt=0, tiltSpeed=255) -> +1.0
        assertEquals(1.0, calculateTiltSpeed(0, 255), 0.001);

        // 向下最大速度 (tilt=1, tiltSpeed=255) -> -1.0
        assertEquals(-1.0, calculateTiltSpeed(1, 255), 0.001);

        // 放大最大速度 (zoom=1, zoomSpeed=255) -> +1.0
        assertEquals(1.0, calculateZoomSpeed(1, 255), 0.001);

        // 缩小最大速度 (zoom=0, zoomSpeed=255) -> -1.0
        assertEquals(-1.0, calculateZoomSpeed(0, 255), 0.001);

        // 默认速度 (pan=0, panSpeed=null -> 128/255 = 0.50196)
        assertEquals(-128.0 / 255.0, calculatePanSpeed(0, null), 0.001);

        // 停止/未下发 -> 0.0
        assertEquals(0.0, calculatePanSpeed(null, 255), 0.001);
        assertEquals(0.0, calculateTiltSpeed(null, 255), 0.001);
        assertEquals(0.0, calculateZoomSpeed(null, 255), 0.001);
    }
}
