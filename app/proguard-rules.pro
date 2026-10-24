# Peace — keep device-admin and VpnService entry points
-keep class com.scimsoft.peace.LockDeviceAdminReceiver { *; }
-keep class com.scimsoft.peace.LockVpnService { *; }
-keep class com.scimsoft.peace.LockTimerService { *; }
-keep class com.scimsoft.peace.LockAlarmReceiver { *; }
