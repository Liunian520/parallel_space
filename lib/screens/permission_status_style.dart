import 'package:flutter/material.dart';

/// 能力状态颜色。只有 [granted] 用绿色。
Color permissionStatusColor(String status, ColorScheme scheme) {
  switch (status) {
    case 'granted':
      return const Color(0xFF2E7D32);
    case 'denied':
      return scheme.error;
    case 'manual':
    case 'conflict':
      return const Color(0xFFEF6C00);
    default:
      return scheme.outline;
  }
}

String permissionStatusBadge(String status, String badge) {
  if (badge.isNotEmpty) return badge;
  switch (status) {
    case 'granted':
      return '已允许';
    case 'denied':
      return '未允许';
    case 'manual':
      return '需手动确认';
    case 'na':
      return '不适用';
    case 'conflict':
      return '冲突';
    default:
      return '未知';
  }
}

const permissionStatusGroups = ['detectable', 'manual', 'clone'];

String permissionStatusGroupTitle(String group) {
  switch (group) {
    case 'manual':
      return '需手动确认';
    case 'clone':
      return '分身相机诊断';
    default:
      return '可检测权限';
  }
}
