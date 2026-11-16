/// 分身详情里「登录隔离」的展示状态。标识本体由原生层保存，这里只解析通道结果。
class DeviceIdentity {
  const DeviceIdentity({required this.enabled, this.androidId = ''});

  final bool enabled;
  final String androidId;

  /// 打开、关闭或更换后都要让用户看到这句话，不能写成可以绕过服务器限制。
  static const relaunchNote =
      '打开、关闭或更换标识后，请重新打开该分身。里面的应用可能会要求重新登录。这不能绕过应用服务器自己的账号、设备和风控限制。';

  static final idPattern = RegExp(r'^[0-9a-f]{16}$');

  bool get showId => enabled && idPattern.hasMatch(androidId);

  static DeviceIdentity fromMap(Map? raw) {
    if (raw == null) return const DeviceIdentity(enabled: false);
    final id = raw['androidId'];
    return DeviceIdentity(
      enabled: raw['enabled'] == true,
      androidId: id is String ? id : '',
    );
  }
}
