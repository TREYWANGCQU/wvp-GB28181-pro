// web/src/api/onvif.js
import request from '@/utils/request'

// 1. 获取设备分页列表
export function getOnvifDeviceList(params) {
  return request({
    url: '/api/onvif/device/list',
    method: 'get',
    params
  })
}

// 2. 添加 ONVIF 设备
export function addOnvifDevice(data) {
  return request({
    url: '/api/onvif/device/add',
    method: 'post',
    data
  })
}

// 3. 删除 ONVIF 设备
export function deleteOnvifDevice(id) {
  return request({
    url: '/api/onvif/device/delete',
    method: 'delete',
    params: { id }
  })
}

// 4. 手动同步设备通道 Profile
export function syncOnvifChannels(id) {
  return request({
    url: `/api/onvif/device/sync/${id}`,
    method: 'post'
  })
}

// 5. 获取指定设备的通道列表
export function getOnvifChannels(deviceId) {
  return request({
    url: `/api/onvif/device/channels/${deviceId}`,
    method: 'get'
  })
}

// 6. 发起局域网 WS-Discovery 探测
export function probeOnvifDevices(timeout = 3) {
  return request({
    url: '/api/onvif/discovery/probe',
    method: 'post',
    params: { timeout }
  })
}

// 7. 单播测试探测指定 IP 与端口
export function probeSingleDevice(ip, port) {
  return request({
    url: '/api/onvif/discovery/probe-single',
    method: 'post',
    params: { ip, port }
  })
}

// 8. 点播指定通道
export function startOnvifPlay(channelId) {
  return request({
    url: `/api/channel/play/${channelId}`,
    method: 'get'
  })
}

// 9. 更新 ONVIF 设备基础信息
export function updateOnvifDevice(data) {
  return request({
    url: '/api/onvif/device/update',
    method: 'post',
    data
  })
}

// 10. 批量导入 ONVIF 设备
export function importOnvifDevices(formData) {
  return request({
    url: '/api/onvif/device/import',
    method: 'post',
    headers: { 'Content-Type': 'multipart/form-data' },
    data: formData
  })
}
