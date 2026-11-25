LOCAL_PATH := $(call my-dir)

# Prebuilt OpenJDK HotSpot VM (built by platforms/android/scripts/build.sh)
include $(CLEAR_VARS)
LOCAL_MODULE := jvm
LOCAL_SRC_FILES := ../jniLibs/$(TARGET_ARCH_ABI)/libjvm.so
include $(PREBUILT_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := umc_jvm
LOCAL_SRC_FILES := umc_jvm.c
LOCAL_SHARED_LIBRARIES := jvm
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)
