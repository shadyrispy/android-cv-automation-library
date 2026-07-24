# Keep permission guide classes to prevent reflection failures after consumer minification.
-keep class com.steve1316.automation_library.utils.PermissionGuide { *; }
-keep class com.steve1316.automation_library.utils.PermissionGuideActivity { *; }
-keep class com.steve1316.automation_library.utils.PermissionChecker { *; }
-keep class com.steve1316.automation_library.utils.PermissionChecker$* { *; }
-keep class com.steve1316.automation_library.utils.PermissionStep { *; }
-keep class com.steve1316.automation_library.utils.PermissionState { *; }
-keep class com.steve1316.automation_library.utils.PermissionStatus { *; }

# Keep AccessibilityService subclass (system instantiates via reflection).
-keep class com.steve1316.automation_library.utils.MyAccessibilityService { *; }

# EventBus uses reflection for @Subscribe methods.
-keepattributes *Annotation*
-keepclassmembers class * {
    @org.greenrobot.eventbus.Subscribe <methods>;
}

# ONNX Runtime (PP-OCRv6 inference) uses JNI + reflection.
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** { *; }
-keepclassmembers class * {
    native <methods>;
}
