// src/main/java/com/genersoft/iot/vmp/onvif/dao/provider/OnvifDeviceProvider.java
package com.genersoft.iot.vmp.onvif.dao.provider;

import org.apache.ibatis.annotations.Param;
import org.springframework.util.StringUtils;

public class OnvifDeviceProvider {

    public String queryListSql(@Param("query") String query, @Param("status") Integer status) {
        StringBuilder sql = new StringBuilder("SELECT * FROM wvp_onvif_device WHERE 1=1 ");
        if (StringUtils.hasText(query)) {
            sql.append("AND (name LIKE CONCAT('%', #{query}, '%') OR ip LIKE CONCAT('%', #{query}, '%') ")
               .append("OR manufacturer LIKE CONCAT('%', #{query}, '%') OR model LIKE CONCAT('%', #{query}, '%')) ");
        }
        if (status != null) {
            sql.append("AND status = #{status} ");
        }
        sql.append("ORDER BY id DESC");
        return sql.toString();
    }
}
