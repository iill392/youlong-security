// ============================================================

// ============================================================






//




//

//   Java_com_youlong_hd_NativeCrypto_deriveKey(

//   Java_com_youlong_hd_NativeCrypto_decrypt(




// ============================================================

#include <jni.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>
#include <sys/types.h>
#include <unistd.h>








#include <android/log.h>
#define LOG_TAG "YLN"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)






#include "crypto_core.h"

// ============================================================

//




// ============================================================
static int check_tracer_pid() {
    FILE* f = fopen("/proc/self/status", "r");
    if (f == NULL) return 0;
    char line[256];
    int tracer = 0;
    while (fgets(line, sizeof(line), f)) {
        if (strncmp(line, "TracerPid:", 10) == 0) {
            tracer = atoi(line + 10);
            break;
        }
    }
    fclose(f);
    return (tracer != 0) ? 1 : 0;
}

// ============================================================



// ============================================================
static int detect_injection() {
    FILE* f = fopen("/proc/self/maps", "r");
    if (f == NULL) return 0;
    char line[512];
    int suspicious = 0;
    
    static const uint8_t enc[][10] = {
        {0x19,0x0D,0x16,0x1B,0x1E},                          // frida
        {0x18,0x1E,0x1B,0x18,0x1A,0x0B},                     // gadget
        {0x07,0x0F,0x10,0x0C,0x1A,0x1B},                     // xposed
        {0x0C,0x0A,0x1D,0x0C,0x0B,0x0D,0x1E,0x0B,0x1A},      // substrate
        {0x13,0x16,0x1D,0x14,0x1A,0x0D,0x11,0x1E,0x13},      // libkernal
        {0x13,0x16,0x1D,0x1B,0x09,0x12},                     // libdvm
    };
    static const int enc_len[] = {5, 6, 6, 9, 9, 6};
    char pat[16];
    while (fgets(line, sizeof(line), f)) {
        for (int i = 0; i < 6; i++) {
            for (int j = 0; j < enc_len[i]; j++) pat[j] = (char)(enc[i][j] ^ 0x7F);
            pat[enc_len[i]] = '\0';
            if (strstr(line, pat) != NULL) { suspicious = 1; break; }
        }
        if (suspicious) break;
    }
    fclose(f);
    memset(pat, 0, sizeof(pat));
    return suspicious;
}

// ============================================================



// ============================================================


static jboolean nc_isNativeLoaded(JNIEnv* env, jobject thiz) {
    (void)env; (void)thiz;
    return JNI_TRUE;
}



// deriveKey(byte[] fingerprint) -> byte[32]
static jbyteArray nc_deriveKey(JNIEnv* env, jobject thiz, jbyteArray fingerprint) {
    (void)thiz;
    jsize fpLen = 0;
    jbyte* fp = NULL;
    if (fingerprint != NULL) {
        fpLen = env->GetArrayLength(fingerprint);
        if (fpLen > 0) {
            fp = env->GetByteArrayElements(fingerprint, NULL);
            if (fp == NULL) return NULL;
        }
    }

    uint8_t key[32];
    derive_key((const uint8_t*)fp, (int)fpLen, key);

    if (fp != NULL) env->ReleaseByteArrayElements(fingerprint, fp, JNI_ABORT);

    jbyteArray result = env->NewByteArray(32);
    if (result != NULL) {
        env->SetByteArrayRegion(result, 0, 32, (const jbyte*)key);
    }
    secure_clear(key, sizeof(key));
    return result;
}


static jbyteArray nc_decrypt(
        JNIEnv* env, jobject thiz,
        jbyteArray iv, jbyteArray ciphertext, jbyteArray fingerprint) {
    (void)thiz;
    if (iv == NULL || ciphertext == NULL) return NULL;

    jsize ivLen = env->GetArrayLength(iv);
    jsize ctLen = env->GetArrayLength(ciphertext);
    if (ivLen != 12 || ctLen <= 16) return NULL;

    jbyte* ivBuf = env->GetByteArrayElements(iv, NULL);
    jbyte* ctBuf = env->GetByteArrayElements(ciphertext, NULL);
    if (ivBuf == NULL || ctBuf == NULL) {
        if (ivBuf != NULL) env->ReleaseByteArrayElements(iv, ivBuf, JNI_ABORT);
        if (ctBuf != NULL) env->ReleaseByteArrayElements(ciphertext, ctBuf, JNI_ABORT);
        return NULL;
    }

    jsize fpLen = 0;
    jbyte* fp = NULL;
    if (fingerprint != NULL) {
        fpLen = env->GetArrayLength(fingerprint);
        if (fpLen > 0) {
            fp = env->GetByteArrayElements(fingerprint, NULL);
        }
    }

    uint8_t key[32];
    derive_key((const uint8_t*)fp, (int)fpLen, key);

    int ptLen = ctLen - 16;
    jbyteArray result = NULL;
    uint8_t* pt = (uint8_t*)malloc((size_t)ptLen);
    if (pt != NULL) {
        int decLen = aes_gcm_decrypt(key, (const uint8_t*)ivBuf,
                                     (const uint8_t*)ctBuf, (int)ctLen,
                                     pt, ptLen);
        if (decLen >= 0) {
            result = env->NewByteArray(decLen);
            if (result != NULL) {
                env->SetByteArrayRegion(result, 0, decLen, (const jbyte*)pt);
            }
        }
        secure_clear(pt, (size_t)ptLen);
        free(pt);
    }

    env->ReleaseByteArrayElements(iv, ivBuf, JNI_ABORT);
    env->ReleaseByteArrayElements(ciphertext, ctBuf, JNI_ABORT);
    if (fp != NULL) env->ReleaseByteArrayElements(fingerprint, fp, JNI_ABORT);
    secure_clear(key, sizeof(key));
    return result;
}

// isTracerAttached() -> boolean
static jboolean nc_isTracerAttached(JNIEnv* env, jobject thiz) {
    (void)env; (void)thiz;
    return check_tracer_pid() ? JNI_TRUE : JNI_FALSE;
}

// detectFrida() -> boolean
static jboolean nc_detectFrida(JNIEnv* env, jobject thiz) {
    (void)env; (void)thiz;
    return detect_injection() ? JNI_TRUE : JNI_FALSE;
}

// ============================================================

//


//










//


//




// ============================================================

// ============================================================

// ============================================================
static const JNINativeMethod kNativeMethods[] = {
    { "isNativeLoaded",          "()Z",    (void*)nc_isNativeLoaded },
    { "deriveKey",               "([B)[B", (void*)nc_deriveKey },
    { "decrypt",                 "([B[B[B)[B", (void*)nc_decrypt },
    { "isTracerAttached",        "()Z",    (void*)nc_isTracerAttached },
    { "detectFrida",             "()Z",    (void*)nc_detectFrida },
};


extern "C" int register_guard_sentinel(JNIEnv* env);

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)reserved;
    
    
    

    JNIEnv* env = NULL;
    if (vm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass cls = env->FindClass("com/youlong/hd/NativeCrypto");
    if (cls == NULL) {
        return JNI_ERR;
    }
    if (env->RegisterNatives(cls, kNativeMethods,
                             sizeof(kNativeMethods) / sizeof(kNativeMethods[0])) != JNI_OK) {
        env->DeleteLocalRef(cls);
        return JNI_ERR;
    }
    env->DeleteLocalRef(cls);

    
    register_guard_sentinel(env);
    return JNI_VERSION_1_6;
}

// ============================================================


// ============================================================
