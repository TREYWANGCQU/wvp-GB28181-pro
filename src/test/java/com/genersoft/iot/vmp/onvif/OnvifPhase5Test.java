// src/test/java/com/genersoft/iot/vmp/onvif/OnvifPhase5Test.java
package com.genersoft.iot.vmp.onvif;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ONVIF 第五阶段全流程自动化测试与验证套件")
public class OnvifPhase5Test {

    @Test
    @DisplayName("执行第五阶段全量自检与验收基线验证")
    public void testPhase5Verification() {
        OnvifPhase5Verification.main(new String[0]);
    }
}
