// src/main/java/com/genersoft/iot/vmp/onvif/dao/OnvifChannelMapper.java
package com.genersoft.iot.vmp.onvif.dao;

import com.genersoft.iot.vmp.onvif.bean.OnvifChannel;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Repository;

import java.util.List;

@Mapper
@Repository
public interface OnvifChannelMapper {

    @Insert("INSERT INTO wvp_onvif_channel (device_id, channel_index, profile_token, name, video_encoding, " +
            "resolution, frame_rate, bitrate, rtsp_url, snapshot_url, has_ptz, gb_device_id, create_time, update_time) " +
            "VALUES (#{deviceId}, #{channelIndex}, #{profileToken}, #{name}, #{videoEncoding}, #{resolution}, " +
            "#{frameRate}, #{bitrate}, #{rtspUrl}, #{snapshotUrl}, #{hasPtz}, #{gbDeviceId}, #{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(OnvifChannel channel);

    @Update("UPDATE wvp_onvif_channel SET name=#{name}, video_encoding=#{videoEncoding}, resolution=#{resolution}, " +
            "frame_rate=#{frameRate}, bitrate=#{bitrate}, rtsp_url=#{rtspUrl}, snapshot_url=#{snapshotUrl}, " +
            "has_ptz=#{hasPtz}, gb_device_id=#{gbDeviceId}, update_time=#{updateTime} WHERE id=#{id}")
    int update(OnvifChannel channel);

    @Delete("DELETE FROM wvp_onvif_channel WHERE id=#{id}")
    int deleteById(@Param("id") Integer id);

    @Delete("DELETE FROM wvp_onvif_channel WHERE device_id=#{deviceId}")
    int deleteByDeviceId(@Param("deviceId") Integer deviceId);

    @Select("SELECT * FROM wvp_onvif_channel WHERE id=#{id}")
    OnvifChannel selectById(@Param("id") Integer id);

    @Select("SELECT oc.*, dc.id AS gb_id, " +
            "COALESCE(dc.gb_device_id, oc.gb_device_id) AS gb_device_id, " +
            "COALESCE(dc.gb_name, oc.name) AS name " +
            "FROM wvp_onvif_channel oc " +
            "LEFT JOIN wvp_device_channel dc ON dc.data_type = 4 AND dc.data_device_id = oc.id " +
            "WHERE oc.device_id = #{deviceId} ORDER BY oc.channel_index ASC, oc.id ASC")
    List<OnvifChannel> selectByDeviceId(@Param("deviceId") Integer deviceId);

    @Select("SELECT * FROM wvp_onvif_channel WHERE device_id=#{deviceId} AND profile_token=#{profileToken}")
    OnvifChannel selectByDeviceIdAndProfileToken(@Param("deviceId") Integer deviceId, @Param("profileToken") String profileToken);

    @Update("UPDATE wvp_onvif_channel SET gb_device_id=#{gbDeviceId} WHERE id=#{id}")
    int updateGbDeviceId(@Param("id") Integer id, @Param("gbDeviceId") String gbDeviceId);

    @Select("<script>" +
            "SELECT * FROM wvp_onvif_channel WHERE device_id IN " +
            "<foreach collection='deviceIds' item='item' open='(' separator=',' close=')'>#{item}</foreach>" +
            "</script>")
    List<OnvifChannel> selectByDeviceIds(@Param("deviceIds") List<Integer> deviceIds);
}
