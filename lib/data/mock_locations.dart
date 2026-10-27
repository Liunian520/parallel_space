import '../models/location_models.dart';

class PresetLocation {
  const PresetLocation(this.name, this.lat, this.lng);
  final String name;
  final double lat;
  final double lng;
}

const presetLocations = <PresetLocation>[
  PresetLocation('北京天安门', 39.908722, 116.397499),
  PresetLocation('上海外滩', 31.240018, 121.490317),
  PresetLocation('深圳', 22.543096, 114.057865),
  PresetLocation('杭州西湖', 30.259244, 120.130791),
  PresetLocation('成都', 30.572815, 104.066801),
  PresetLocation('广州塔', 23.106414, 113.324553),
  PresetLocation('香港', 22.279693, 114.162281),
  PresetLocation('东京', 35.681236, 139.767125),
  PresetLocation('首尔', 37.566535, 126.977969),
  PresetLocation('新加坡', 1.283404, 103.860727),
  PresetLocation('伦敦', 51.507351, -0.127758),
  PresetLocation('巴黎', 48.858370, 2.294481),
  PresetLocation('纽约', 40.758896, -73.985130),
  PresetLocation('旧金山', 37.774929, -122.419416),
];

/// 内置预设路线（只读，不进入备份）。
List<MockRoute> presetRoutes() => [
      MockRoute(id: 'preset:bund', name: '外滩漫步', points: const [
        GeoPoint(31.2450, 121.4905),
        GeoPoint(31.2405, 121.4903),
        GeoPoint(31.2365, 121.4875),
        GeoPoint(31.2325, 121.4855),
      ]),
      MockRoute(id: 'preset:westlake', name: '西湖环湖骑行', loop: true, speed: SpeedMode.bike, points: const [
        GeoPoint(30.2593, 120.1308),
        GeoPoint(30.2470, 120.1450),
        GeoPoint(30.2400, 120.1500),
        GeoPoint(30.2290, 120.1440),
        GeoPoint(30.2405, 120.1265),
      ]),
      MockRoute(id: 'preset:changan', name: '长安街驾车', speed: SpeedMode.drive, points: const [
        GeoPoint(39.9087, 116.3500),
        GeoPoint(39.9087, 116.3975),
        GeoPoint(39.9087, 116.4500),
      ]),
    ];
