/* I2S speaker playback. See speaker.h. */
#include "speaker.h"

#include "driver/gpio.h"
#include "driver/i2s_std.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

/** Tag used by ESP-IDF logs from the speaker layer. */
static const char *TAG = "speaker";
/** Amplifier enable GPIO; high enables. */
#define AUDIO_ENABLE_GPIO GPIO_NUM_48
/** I2S data output GPIO. */
#define I2S_DATA_GPIO GPIO_NUM_41
/** I2S bit-clock GPIO. */
#define I2S_BCLK_GPIO GPIO_NUM_42
/** I2S word-select GPIO. */
#define I2S_LRCLK_GPIO GPIO_NUM_46

/** I2S transmit channel handle. */
static i2s_chan_handle_t tx_channel = NULL;

bool speaker_init(void)
{
    gpio_config_t out = {0};                          /* Amplifier enable pin configuration. */
    /* I2S_NUM_1 because the PDM microphone holds I2S_NUM_0. Asking a question records
     * and then plays, so both have to exist at once. */
    i2s_chan_config_t channel_config = I2S_CHANNEL_DEFAULT_CONFIG(I2S_NUM_1, I2S_ROLE_MASTER);
    i2s_std_config_t std_config = {
        .clk_cfg = I2S_STD_CLK_DEFAULT_CONFIG(SPEAKER_SAMPLE_RATE),
        .slot_cfg = I2S_STD_PHILIPS_SLOT_DEFAULT_CONFIG(I2S_DATA_BIT_WIDTH_16BIT,
                                                        I2S_SLOT_MODE_MONO),
        .gpio_cfg = {
            .mclk = I2S_GPIO_UNUSED,
            .bclk = I2S_BCLK_GPIO,
            .ws = I2S_LRCLK_GPIO,
            .dout = I2S_DATA_GPIO,
            .din = I2S_GPIO_UNUSED,
            .invert_flags = {
                .mclk_inv = false,
                .bclk_inv = false,
                .ws_inv = false
            }
        }
    };

    out.pin_bit_mask = (1ULL << AUDIO_ENABLE_GPIO);   /* Select the amplifier enable pin. */
    out.mode = GPIO_MODE_OUTPUT;                      /* Drive it. */
    out.pull_up_en = GPIO_PULLUP_DISABLE;
    out.pull_down_en = GPIO_PULLDOWN_DISABLE;
    out.intr_type = GPIO_INTR_DISABLE;
    ESP_ERROR_CHECK(gpio_config(&out));
    /* Left off until there is something to play. A powered amplifier with an idle I2S
     * line hisses, and this sits next to a microphone that would hear it. */
    ESP_ERROR_CHECK(gpio_set_level(AUDIO_ENABLE_GPIO, 0));

    if (i2s_new_channel(&channel_config, &tx_channel, NULL) != ESP_OK) {
        ESP_LOGE(TAG, "could not create the I2S transmit channel");
        return false;
    }
    if (i2s_channel_init_std_mode(tx_channel, &std_config) != ESP_OK) {
        ESP_LOGE(TAG, "could not initialise I2S standard mode");
        return false;
    }
    ESP_LOGI(TAG, "speaker ready, %d Hz mono", SPEAKER_SAMPLE_RATE);
    return true;
}

bool speaker_play(const uint8_t *pcm, size_t length)
{
    size_t written_total = 0;                         /* Bytes handed to the driver so far. */
    bool ok = true;                                   /* Whether every write succeeded. */
    static int16_t staged[2048];                      /* Gain is applied into this, not in place:
                                                       * the source is the network buffer. */
    int peak = 0;                                     /* Loudest sample in the whole answer. */

    if (tx_channel == NULL || pcm == NULL || length == 0) {
        ESP_LOGW(TAG, "nothing to play (channel=%p length=%u)",
                 (void *)tx_channel, (unsigned)length);
        return false;
    }

    /* Measured before anything is played, and logged, because "no sound" has two very
     * different causes that are otherwise indistinguishable from the outside: audio
     * that never arrived or arrived silent, versus audio that is fine and is not
     * reaching the ear. A peak near zero means the phone sent silence. */
    for (size_t i = 0; i + 1 < length; i += 2) {
        int v = (int16_t)((uint16_t)pcm[i] | ((uint16_t)pcm[i + 1] << 8));

        if (v < 0) {
            v = -v;
        }
        if (v > peak) {
            peak = v;
        }
    }
    ESP_LOGI(TAG, "answer peak %d of 32767 (%d%%), %u bytes, gain x%d",
             peak, (peak * 100) / 32767, (unsigned)length, SPEAKER_GAIN);

    ESP_ERROR_CHECK(gpio_set_level(AUDIO_ENABLE_GPIO, 1)); /* Power the amplifier. */
    vTaskDelay(pdMS_TO_TICKS(20));                    /* Let the amplifier settle before the first
                                                       * sample, otherwise it clicks. */
    if (i2s_channel_enable(tx_channel) != ESP_OK) {
        gpio_set_level(AUDIO_ENABLE_GPIO, 0);
        return false;
    }

    while (written_total < length) {
        size_t chunk = length - written_total;        /* Bytes remaining. */
        size_t written = 0;                           /* Bytes the driver accepted. */

        if (chunk > sizeof(staged)) {
            chunk = sizeof(staged);
        }
        chunk &= ~(size_t)1;                          /* Whole samples only. */
        if (chunk == 0) {
            break;
        }

        /* Synthesised speech comes back quieter than this small speaker needs, so it is
         * amplified here rather than on the phone: the phone would have to guess at
         * hardware it cannot hear, and clipping is better judged next to the driver. */
        for (size_t i = 0; i < chunk; i += 2) {
            int v = (int16_t)((uint16_t)pcm[written_total + i] |
                              ((uint16_t)pcm[written_total + i + 1] << 8));

            v *= SPEAKER_GAIN;
            if (v > 32767) {
                v = 32767;                            /* Clip rather than wrap: wrapping turns
                                                       * a loud passage into noise. */
            } else if (v < -32768) {
                v = -32768;
            }
            staged[i / 2] = (int16_t)v;
        }

        /* Blocks until the DMA ring has room, which is what paces playback: this
         * returns roughly when the audio has actually been heard. */
        if (i2s_channel_write(tx_channel, staged, chunk, &written,
                              pdMS_TO_TICKS(2000)) != ESP_OK) {
            ESP_LOGE(TAG, "write failed after %u of %u bytes",
                     (unsigned)written_total, (unsigned)length);
            ok = false;
            break;
        }
        written_total += written;
    }

    /* Let the tail of the DMA buffer drain before cutting the amplifier, or the last
     * fraction of a second is clipped off the answer. */
    vTaskDelay(pdMS_TO_TICKS(60));
    i2s_channel_disable(tx_channel);
    ESP_ERROR_CHECK(gpio_set_level(AUDIO_ENABLE_GPIO, 0));
    ESP_LOGI(TAG, "played %u bytes (%.1f s)",
             (unsigned)written_total, (double)written_total / (SPEAKER_SAMPLE_RATE * 2));
    return ok;
}
