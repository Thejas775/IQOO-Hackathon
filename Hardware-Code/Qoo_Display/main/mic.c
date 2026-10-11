/* PDM microphone capture. See mic.h. */
#include "mic.h"

#include <string.h>
#include "driver/i2s_pdm.h"
#include "esp_heap_caps.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

/** Tag used by ESP-IDF logs from the microphone layer. */
static const char *TAG = "mic";
/** PDM clock GPIO. */
#define PDM_CLOCK_GPIO GPIO_NUM_12
/** PDM data GPIO. */
#define PDM_DATA_GPIO GPIO_NUM_11
/** Samples read per I2S call. */
#define BLOCK_SAMPLES 1024
/** Total capture buffer size. */
#define BUFFER_BYTES ((size_t)MIC_CAP_SECONDS * MIC_BYTES_PER_SECOND)

/** I2S receive channel handle. */
static i2s_chan_handle_t rx_channel = NULL;
/** Capture buffer, in PSRAM: 5.76 MB will not fit in internal RAM. */
static uint8_t *buffer = NULL;
/** Bytes captured in the current recording. */
static volatile size_t captured = 0;
/** Whether the capture task should keep reading. */
static volatile bool running = false;
/** Most recent block's peak, as a percentage. */
static volatile int level = 0;
/** Handle of the capture task, so stop() can wait for it to finish. */
static TaskHandle_t capture_task = NULL;

/**
 * Function: capture_loop
 * Description: Reads PCM blocks into the capture buffer until stopped or full.
 * Parameters: arg - Unused.
 * Return Value: None.
 *
 * Runs on its own task rather than being polled from the UI loop: a missed read means
 * the I2S DMA ring overruns and the recording gains a gap, and the UI loop also drives
 * full-screen redraws that take tens of milliseconds.
 */
static void capture_loop(void *arg)
{
    static int16_t block[BLOCK_SAMPLES];              /* One block of PCM. Static: too big for the task stack. */

    (void)arg;
    while (running) {
        size_t read_bytes = 0;                        /* Bytes the driver actually returned. */

        if (i2s_channel_read(rx_channel, block, sizeof(block), &read_bytes, pdMS_TO_TICKS(200)) != ESP_OK) {
            continue;                                 /* A timeout is not fatal; try again. */
        }
        if (read_bytes == 0) {
            continue;
        }

        if (captured + read_bytes > BUFFER_BYTES) {   /* Stop cleanly at the cap rather than wrapping. */
            read_bytes = BUFFER_BYTES - captured;
            running = false;
        }
        if (read_bytes > 0) {
            memcpy(buffer + captured, block, read_bytes);
            captured += read_bytes;
        }

        int peak = 0;                                 /* Track the loudest sample in this block. */
        for (size_t i = 0; i < read_bytes / 2; i++) {
            int v = block[i] < 0 ? -block[i] : block[i];
            if (v > peak) peak = v;
        }
        level = (peak * 100) / 32767;                 /* Scale to a percentage for the meter. */
    }

    capture_task = NULL;                              /* Published before exit so stop() stops waiting. */
    vTaskDelete(NULL);
}

bool mic_init(void)
{
    i2s_chan_config_t channel_config = I2S_CHANNEL_DEFAULT_CONFIG(I2S_NUM_0, I2S_ROLE_MASTER);
    i2s_pdm_rx_config_t pdm_config = {
        .clk_cfg = I2S_PDM_RX_CLK_DEFAULT_CONFIG(MIC_SAMPLE_RATE),
        .slot_cfg = I2S_PDM_RX_SLOT_DEFAULT_CONFIG(I2S_DATA_BIT_WIDTH_16BIT, I2S_SLOT_MODE_MONO),
        .gpio_cfg = {
            .clk = PDM_CLOCK_GPIO,
            .din = PDM_DATA_GPIO,
            .invert_flags = { .clk_inv = false }
        }
    };

    buffer = heap_caps_malloc(BUFFER_BYTES, MALLOC_CAP_SPIRAM);
    if (buffer == NULL) {
        ESP_LOGE(TAG, "No PSRAM for a %u byte capture buffer", (unsigned)BUFFER_BYTES);
        return false;
    }
    if (i2s_new_channel(&channel_config, NULL, &rx_channel) != ESP_OK) {
        ESP_LOGE(TAG, "Could not create the I2S channel");
        return false;
    }
    if (i2s_channel_init_pdm_rx_mode(rx_channel, &pdm_config) != ESP_OK) {
        ESP_LOGE(TAG, "Could not initialise PDM receive mode");
        return false;
    }
    ESP_LOGI(TAG, "PDM mic ready, %d Hz mono, room for %d s", MIC_SAMPLE_RATE, MIC_CAP_SECONDS);
    return true;
}

bool mic_start(void)
{
    if (running || buffer == NULL) {
        return running;
    }
    captured = 0;
    level = 0;
    if (i2s_channel_enable(rx_channel) != ESP_OK) {
        ESP_LOGE(TAG, "Could not enable the microphone channel");
        return false;
    }
    running = true;
    if (xTaskCreate(capture_loop, "mic", 4096, NULL, 5, &capture_task) != pdPASS) {
        running = false;
        i2s_channel_disable(rx_channel);
        ESP_LOGE(TAG, "Could not start the capture task");
        return false;
    }
    return true;
}

size_t mic_stop(void)
{
    if (!running && capture_task == NULL) {
        return captured;
    }
    running = false;
    while (capture_task != NULL) {                    /* Let the task finish its current block. */
        vTaskDelay(pdMS_TO_TICKS(10));
    }
    i2s_channel_disable(rx_channel);
    level = 0;
    ESP_LOGI(TAG, "captured %u bytes (%.1f s)",
             (unsigned)captured, (double)captured / MIC_BYTES_PER_SECOND);
    return captured;
}

bool mic_is_recording(void) { return running; }

size_t mic_captured_bytes(void) { return captured; }

const uint8_t *mic_data(void) { return buffer; }

int mic_level(void) { return level; }
