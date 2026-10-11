/* AXS5106L capacitive touch panel. See touch.h. */
#include "touch.h"

#include "display.h"
#include "driver/gpio.h"
#include "driver/i2c_master.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"

/** Tag used by ESP-IDF logs from the touch layer. */
static const char *TAG = "touch";
/** Shared I2C SDA GPIO. */
#define WATCH_I2C_SDA GPIO_NUM_4
/** Shared I2C SCL GPIO. */
#define WATCH_I2C_SCL GPIO_NUM_3
/** AXS5106L seven-bit I2C address. */
#define AXS5106L_ADDRESS 0x63
/** Consecutive agreeing samples needed before the debounced state flips. */
#define DEBOUNCE_SAMPLES 2

/** I2C device handle for the touch controller. */
static i2c_master_dev_handle_t touch_device = NULL;
/** Debounced touch state reported to callers. */
static bool stable_pressed = false;
/** How many consecutive raw samples have disagreed with the stable state. */
static int disagreeing = 0;

/**
 * Function: read_raw
 * Description: Reads the touch-data block and decides whether a finger is present.
 * Parameters: None.
 * Return Value: true when the controller reports one plausible touch point.
 *
 * The interrupt pin is deliberately NOT consulted. On this controller it behaves as a
 * report-ready pulse rather than a level held for the duration of a touch, so gating
 * reads on it makes a sustained press read as a rapid press/release chatter - which the
 * gesture layer above then sees as a stream of completed short taps, and a long press
 * can never accumulate its hold time. Polling the register directly costs one 14-byte
 * I2C read per cycle at 400 kHz, which is nothing.
 */
static bool read_raw(void)
{
    uint8_t reg = 0x01;                               /* Address of the touch-data block. */
    uint8_t packet[14] = {0};                         /* Touch packet returned by the controller. */
    uint16_t x, y;                                    /* Decoded coordinates. */

    if (i2c_master_transmit_receive(touch_device, &reg, 1, packet, sizeof(packet), pdMS_TO_TICKS(100)) != ESP_OK) {
        return false;                                 /* Treat a failed read as no touch. */
    }
    if (packet[1] == 0 || packet[1] > 2) {            /* Byte one is the touch count; >2 is noise. */
        return false;
    }

    /* Coordinates are validated too, not just the count: the controller will occasionally
     * return a non-zero count alongside out-of-range coordinates, and accepting that
     * produces phantom presses with no finger on the glass. */
    x = (uint16_t)(((packet[2] & 0x0FU) << 8) | packet[3]); /* Decode X. */
    y = (uint16_t)(((packet[4] & 0x0FU) << 8) | packet[5]); /* Decode Y. */
    return x < DISPLAY_W && y < DISPLAY_H;            /* Accept only points inside the panel. */
}

void touch_init(void)
{
    i2c_master_bus_config_t bus = {0};                /* I2C bus configuration. */
    i2c_device_config_t dev = {0};                    /* Touch device configuration. */
    i2c_master_bus_handle_t handle = NULL;            /* Created I2C bus handle. */
    uint8_t reg = 0x08;                               /* Address of the ID register. */
    uint8_t id[3] = {0};                              /* ID bytes read back. */

    bus.i2c_port = I2C_NUM_0;                         /* Use the first I2C controller. */
    bus.sda_io_num = WATCH_I2C_SDA;                   /* Assign board SDA pin. */
    bus.scl_io_num = WATCH_I2C_SCL;                   /* Assign board SCL pin. */
    bus.clk_source = I2C_CLK_SRC_DEFAULT;             /* Use the default clock source. */
    bus.glitch_ignore_cnt = 7;                        /* Filter short I2C glitches. */
    bus.flags.enable_internal_pullup = true;          /* Enable internal pull-ups. */
    ESP_ERROR_CHECK(i2c_new_master_bus(&bus, &handle)); /* Create the I2C bus. */

    dev.dev_addr_length = I2C_ADDR_BIT_LEN_7;         /* Seven-bit addressing. */
    dev.device_address = AXS5106L_ADDRESS;            /* Touch-controller address. */
    dev.scl_speed_hz = 400000;                        /* 400 kHz. */
    ESP_ERROR_CHECK(i2c_master_bus_add_device(handle, &dev, &touch_device)); /* Attach the device. */
    ESP_ERROR_CHECK(i2c_master_transmit_receive(touch_device, &reg, 1, id, sizeof(id), pdMS_TO_TICKS(100)));
    ESP_LOGI(TAG, "AXS5106L ID bytes: %02X %02X %02X", id[0], id[1], id[2]); /* Confirm it answers. */
}

bool touch_is_pressed(void)
{
    bool raw = read_raw();                            /* One fresh sample. */

    if (raw == stable_pressed) {                      /* Sample agrees; nothing to do. */
        disagreeing = 0;
        return stable_pressed;
    }
    if (++disagreeing >= DEBOUNCE_SAMPLES) {          /* Enough consecutive disagreement to flip. */
        stable_pressed = raw;
        disagreeing = 0;
        ESP_LOGD(TAG, "%s", stable_pressed ? "down" : "up"); /* Quiet unless debug is enabled. */
    }
    return stable_pressed;                            /* Hold the old state until the flip commits. */
}
