/*
 * recly_tracker.h — API C do rastreador e das máscaras do Recly (biblioteca Rust).
 *
 * Compilação para Android (exemplo):
 *   cargo ndk -t arm64-v8a -t armeabi-v7a -o ../app/src/main/jniLibs build --release
 * Gera librecly_tracker.so, que pode ser chamada por um shim JNI fino.
 */
#ifndef RECLY_TRACKER_H
#define RECLY_TRACKER_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define RECLY_OK 0
#define RECLY_SKIPPED 1
#define RECLY_ERR_ARG (-1)
#define RECLY_ERR_JSON (-2)

/* Formatos de pixel aceitos. */
#define RECLY_FORMAT_RGB24 0u
#define RECLY_FORMAT_RGBA8888 1u /* layout de um Bitmap ARGB_8888 em memória */

/* Coordenadas normalizadas (0..1) no quadro de origem, y para baixo. */
typedef struct ReclyPoint {
    int64_t time_us;
    float center_x;
    float center_y;
    float width;
    float height;
    float confidence;
} ReclyPoint;

typedef struct ReclyTracker ReclyTracker;

/* Inicia no primeiro quadro com a seleção normalizada. Retorna NULL se algo for inválido. */
ReclyTracker *recly_tracker_new(const uint8_t *pixels, uint32_t width, uint32_t height, uint32_t stride,
                                uint32_t format, int64_t time_us, float center_x, float center_y,
                                float region_width, float region_height);

/* Entrega o próximo quadro (pode ter outro tamanho). `out` pode ser NULL.
 * Retorna RECLY_OK, RECLY_SKIPPED (tempo não avançou) ou RECLY_ERR_ARG. */
int32_t recly_tracker_push(ReclyTracker *tracker, const uint8_t *pixels, uint32_t width, uint32_t height,
                           uint32_t stride, uint32_t format, int64_t time_us, ReclyPoint *out);

/* Número de pontos online até agora. */
uint32_t recly_tracker_len(const ReclyTracker *tracker);

/* Finaliza (suavização RTS se smooth != 0), copia até `capacity` pontos e LIBERA o rastreador.
 * O resultado tem no máximo 2000 pontos (limite do projeto). Retorna quantos foram escritos. */
uint32_t recly_tracker_finish(ReclyTracker *tracker, int32_t smooth, ReclyPoint *out, uint32_t capacity);

/* Libera sem finalizar. */
void recly_tracker_free(ReclyTracker *tracker);

/* Rasteriza o alpha da camada (0..255, width*height bytes) de uma máscara em JSON (mesmos nomes
 * de campos do MaskState em Kotlin), avaliada em time_us. track_json (TrackingTrack) é opcional. */
int32_t recly_mask_rasterize(const char *mask_json, const char *track_json, int64_t time_us,
                             uint32_t width, uint32_t height, uint8_t *out);

#ifdef __cplusplus
}
#endif

#endif /* RECLY_TRACKER_H */
