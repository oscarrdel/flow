#include <jni.h>
#include <limits.h>
#include <stdint.h>
#include <stdlib.h>
#include "lame.h"

#define JNI_METHOD(name) Java_com_rainyworks_flow_core_mp3_Mp3Encoder_##name

static void fail(JNIEnv *env, const char *message) {
    jclass type = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (type) (*env)->ThrowNew(env, type, message);
}

static jbyteArray bytes(JNIEnv *env, const unsigned char *data, int count) {
    if (count < 0) {
        fail(env, "LAME no pudo codificar el bloque de audio.");
        return NULL;
    }
    jbyteArray result = (*env)->NewByteArray(env, count);
    if (result && count) {
        (*env)->SetByteArrayRegion(env, result, 0, count, (const jbyte *)data);
    }
    return result;
}

JNIEXPORT jlong JNICALL JNI_METHOD(nativeOpen)(JNIEnv *env, jobject self, jint rate,
        jint channels, jint kbps, jint quality, jboolean abr) {
    lame_t encoder = lame_init();
    if (!encoder) {
        fail(env, "Memoria insuficiente para inicializar LAME.");
        return 0;
    }
    lame_set_write_id3tag_automatic(encoder, 0);
    if (lame_set_in_samplerate(encoder, rate) < 0 || lame_set_out_samplerate(encoder, rate) < 0 ||
        lame_set_num_channels(encoder, channels) < 0 || lame_set_quality(encoder, quality) < 0 ||
        lame_set_bWriteVbrTag(encoder, 1) < 0 || lame_set_VBR(encoder, abr ? vbr_abr : vbr_off) < 0 ||
        (abr ? lame_set_VBR_mean_bitrate_kbps(encoder, kbps) : lame_set_brate(encoder, kbps)) < 0 ||
        lame_init_params(encoder) < 0) {
        lame_close(encoder);
        fail(env, "LAME no pudo iniciar con los ajustes solicitados.");
        return 0;
    }
    return (jlong)(intptr_t)encoder;
}

JNIEXPORT jbyteArray JNICALL JNI_METHOD(nativeEncode)(JNIEnv *env, jobject self, jlong handle,
        jshortArray pcm, jint frames) {
    lame_t encoder = (lame_t)(intptr_t)handle;
    // LAME requiere espacio para hasta 1.25 * frames + 7200 bytes.
    int64_t capacity = ((int64_t)frames * 5 + 3) / 4 + 7200;
    if (capacity > INT_MAX || (int64_t)frames * lame_get_num_channels(encoder) * sizeof(float) > INT_MAX) {
        fail(env, "El bloque PCM es demasiado grande.");
        return NULL;
    }
    unsigned char *output = malloc((size_t)capacity);
    if (!output) {
        fail(env, "Memoria insuficiente para codificar el bloque PCM.");
        return NULL;
    }
    jshort *input = (*env)->GetShortArrayElements(env, pcm, NULL);
    jbyteArray result = NULL;
    if (input) {
        int count = lame_get_num_channels(encoder) == 1
            ? lame_encode_buffer(encoder, input, input, frames, output, (int)capacity)
            : lame_encode_buffer_interleaved(encoder, input, frames, output, (int)capacity);
        (*env)->ReleaseShortArrayElements(env, pcm, input, JNI_ABORT);
        result = bytes(env, output, count);
    }
    free(output);
    return result;
}

JNIEXPORT jbyteArray JNICALL JNI_METHOD(nativeFlush)(JNIEnv *env, jobject self, jlong handle) {
    unsigned char output[7200];
    int count = lame_encode_flush((lame_t)(intptr_t)handle, output, sizeof(output));
    return bytes(env, output, count);
}

JNIEXPORT jbyteArray JNICALL JNI_METHOD(nativeTag)(JNIEnv *env, jobject self, jlong handle) {
    unsigned char tag[2880]; // Tamaño máximo de una trama de etiqueta en LAME 3.100.
    size_t size = lame_get_lametag_frame((lame_t)(intptr_t)handle, tag, sizeof(tag));
    if (!size || size > sizeof(tag)) {
        fail(env, "LAME no pudo obtener la etiqueta final de duracion.");
        return NULL;
    }
    return bytes(env, tag, (int)size);
}

JNIEXPORT void JNICALL JNI_METHOD(nativeClose)(JNIEnv *env, jobject self, jlong handle) {
    lame_close((lame_t)(intptr_t)handle);
}
