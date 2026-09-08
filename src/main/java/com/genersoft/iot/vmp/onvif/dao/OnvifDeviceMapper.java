// src/main/java/com/genersoft/iot/vmp/onvif/dao/OnvifDeviceMapper.java
package com.genersoft.iot.vmp.onvif.dao;

import com.genersoft.iot.vmp.onvif.bean.OnvifDevice;
import com.genersoft.iot.vmp.onvif.dao.provider.OnvifDeviceProvider;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Repository;

import java.util.List;

@Mapper
@Repository
public interface OnvifDeviceMapper {

    @Insert("INSERT INTO wvp_onvif_device (name, ip, port, username, password, device_service_url, " +
            "media_service_url, ptz_service_url, imaging_service_url, manufacturer, model, firmware_version, " +
            "serial_number, mac, clock_offset, status, media_server_id, create_time, update_time) " +
            "VALUES (#{name}, #{ip}, #{port}, #{username}, #{password}, #{deviceServiceUrl}, " +
            "#{mediaServiceUrl}, #{ptzServiceUrl}, #{imagingServiceUrl}, #{manufacturer}, #{model}, #{firmwareVersion}, " +
            "#{serialNumber}, #{mac}, #{clockOffset}, #{status}, #{mediaServerId}, #{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(OnvifDevice device);

    @Update("UPDATE wvp_onvif_device SET name=#{name}, ip=#{ip}, port=#{port}, username=#{username}, password=#{password}, " +
            "device_service_url=#{deviceServiceUrl}, media_service_url=#{mediaServiceUrl}, ptz_service_url=#{ptzServiceUrl}, " +
            "imaging_service_url=#{imagingServiceUrl}, manufacturer=#{manufacturer}, model=#{model}, firmware_version=#{firmwareVersion}, " +
            "serial_number=#{serialNumber}, mac=#{mac}, clock_offset=#{clockOffset}, status=#{status}, media_server_id=#{mediaServerId}, " +
            "update_time=#{updateTime} WHERE id=#{id}")
    int update(OnvifDevice device);

    @Delete("DELETE FROM wvp_onvif_device WHERE id=#{id}")
    int deleteById(@Param("id") Integer id);

    @Select("SELECT * FROM wvp_onvif_device WHERE id=#{id}")
    OnvifDevice selectById(@Param("id") Integer id);

    @Select("SELECT * FROM wvp_onvif_device WHERE ip=#{ip} AND port=#{port}")
    OnvifDevice selectByIpAndPort(@Param("ip") String ip, @Param("port") Integer port);

    @SelectProvider(type = OnvifDeviceProvider.class, method = "queryListSql")
    List<OnvifDevice> selectList(@Param("query") String query, @Param("status") Integer status);

    @Update("UPDATE wvp_onvif_device SET status=#{status}, update_time=NOW() WHERE id=#{id}")
    int updateStatus(@Param("id") Integer id, @Param("status") Integer status);
}
