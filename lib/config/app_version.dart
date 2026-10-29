/// 与 pubspec.yaml 的 `version` 保持一致。
/// 1.3.x 的 versionCode = 12300 + 修订号（1.3.25 → 12325），保证高于已装的 12301/12302 与 10303–10306。
/// 以后只把最后一位加 1，并同步 README 徽章与 CHANGELOG。
const String kAppVersionName = '1.3.25';
const int kAppVersionCode = 12325;
