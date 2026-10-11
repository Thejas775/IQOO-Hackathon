/* The encoder push button. See button.h. */
#include "button.h"

#include "driver/gpio.h"
#include "esp_log.h"

/** Tag used by ESP-IDF logs from the button layer. */
static const char *TAG = "button";
/** Encoder push button GPIO; active low. */
#define ENCODER_BUTTON_GPIO GPIO_NUM_18
/** Consecutive agreeing samples needed before the debounced state flips. */
#define DEBOUNCE_SAMPLES 2

/** Debounced state reported to callers. */
static bool stable_down = false;
/** How many consecutive raw samples have disagreed with the stable state. */
static int disagreeing = 0;

void button_init(void)
{
    gpio_config_t in = {0};                           /* Input configuration for the button pin. */

    in.pin_bit_mask = (1ULL << ENCODER_BUTTON_GPIO);  /* Select the encoder push button. */
    in.mode = GPIO_MODE_INPUT;                        /* Configure it as an input. */
    in.pull_up_en = GPIO_PULLUP_ENABLE;               /* Pull up, because the button is active low. */
    in.pull_down_en = GPIO_PULLDOWN_DISABLE;          /* No pull-down. */
    in.intr_type = GPIO_INTR_DISABLE;                 /* Polled, like the touch panel. */
    ESP_ERROR_CHECK(gpio_config(&in));                /* Apply the configuration. */
    ESP_LOGI(TAG, "encoder button ready on GPIO%d", ENCODER_BUTTON_GPIO);
}

bool button_is_down(void)
{
    bool raw = gpio_get_level(ENCODER_BUTTON_GPIO) == 0; /* Active low: 0 means pressed. */

    if (raw == stable_down) {                         /* Sample agrees; nothing to do. */
        disagreeing = 0;
        return stable_down;
    }
    if (++disagreeing >= DEBOUNCE_SAMPLES) {          /* Enough consecutive disagreement to flip. */
        stable_down = raw;
        disagreeing = 0;
    }
    return stable_down;                               /* Hold the old state until the flip commits. */
}
