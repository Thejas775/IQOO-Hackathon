/* GC9309 panel driver and compositor. See display.h for the model.
 *
 * Pin assignments and the init command table come from Lesson03 of the CrowPanel course.
 * Pixels are big-endian RGB565 throughout, which is what this panel wants on the wire -
 * confirmed from the factory Arduino source, where LV_COLOR_16_SWAP is 1 and the sketch
 * calls draw16bitBeRGBBitmap(). Getting that backwards turns amber into blue.
 */
#include "display.h"

#include <string.h>
#include "driver/gpio.h"
#include "driver/spi_master.h"
#include "esp_heap_caps.h"
#include "esp_lcd_panel_io.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
#include "qoo_ui.h"

/** Tag used by ESP-IDF logs from the display layer. */
static const char *TAG = "display";
/** GC9309 LCD reset GPIO. */
#define LCD_RESET_GPIO GPIO_NUM_6
/** GC9309 LCD SPI clock GPIO. */
#define LCD_SCLK_GPIO GPIO_NUM_7
/** GC9309 LCD SPI MOSI GPIO. */
#define LCD_MOSI_GPIO GPIO_NUM_8
/** GC9309 LCD chip-select GPIO. */
#define LCD_CS_GPIO GPIO_NUM_9
/** GC9309 LCD data-command GPIO. */
#define LCD_DC_GPIO GPIO_NUM_10
/** LCD backlight GPIO. */
#define LCD_BACKLIGHT_GPIO GPIO_NUM_13
/** LCD tearing-effect input GPIO. */
#define LCD_TE_GPIO GPIO_NUM_21
/** LCD power-enable GPIO; low enables the rail. */
#define LCD_POWER_GPIO GPIO_NUM_40
/** Shared peripheral power GPIO; high enables the rail. */
#define PERIPHERAL_POWER_GPIO GPIO_NUM_47
/** AXS5106L touch reset GPIO, released here because it shares the peripheral rail. */
#define TOUCH_RESET_GPIO GPIO_NUM_5
/** Rows of pixels moved per SPI transfer; 296 divides evenly by 37. */
#define CHUNK_LINES 37
/** Bytes in one chunk transfer. */
#define CHUNK_BYTES (DISPLAY_W * CHUNK_LINES * 2)
/** Number of chunks needed to cover the panel. */
#define CHUNK_COUNT (DISPLAY_H / CHUNK_LINES)
/** Bytes in one full frame. */
#define FRAME_BYTES_LOCAL (DISPLAY_W * DISPLAY_H * 2)
/** Animation steps used to slide one frame off-screen. */
#define SLIDE_STEPS 16

/** LCD panel I/O handle used to send commands and pixels. */
static esp_lcd_panel_io_handle_t lcd_io = NULL;
/** Signals that an asynchronous SPI colour transfer has finished. */
static SemaphoreHandle_t transfer_done = NULL;
/** Two DMA-capable chunk buffers so the CPU can fill one while the other is in flight. */
static uint8_t *chunk_buffer[2] = {NULL, NULL};
/** Frame being composed. PSRAM: too big for internal RAM, and only the CPU touches it. */
static uint8_t *frame = NULL;
/** Last frame presented, kept so a transition has something to slide out. */
static uint8_t *presented = NULL;

/** One GC9309 initialization command entry. */
typedef struct {
    uint8_t command;                                  /* LCD command byte. */
    uint8_t data[16];                                 /* Command payload bytes. */
    uint8_t data_length;                              /* Number of payload bytes. */
    uint16_t delay_ms;                                /* Delay after the command, in milliseconds. */
} lcd_init_cmd_t;

/** GC9309 command sequence, matching the validated Arduino_GFX driver behaviour. */
static const lcd_init_cmd_t gc9309_init_commands[] = {
    {0xFE, {0}, 0, 0}, {0xEF, {0}, 0, 0}, {0x80, {0xC0}, 1, 0}, {0x81, {0x01}, 1, 0},
    {0x82, {0x07}, 1, 0}, {0x83, {0x38}, 1, 0}, {0x88, {0x64}, 1, 0}, {0x89, {0x86}, 1, 0},
    {0x8B, {0x3C}, 1, 0}, {0x8D, {0x51}, 1, 0}, {0x8E, {0x70}, 1, 0}, {0x35, {0x00}, 1, 0},
    {0x36, {0x48}, 1, 0}, {0x3A, {0x05}, 1, 0}, {0xBF, {0x1F}, 1, 0}, {0x7D, {0x45, 0x06}, 2, 0},
    {0xEE, {0x00, 0x06}, 2, 0}, {0xF4, {0x53}, 1, 0}, {0xF6, {0x17, 0x08}, 2, 0}, {0x70, {0x4F, 0x4F}, 2, 0},
    {0x71, {0x12, 0x20}, 2, 0}, {0x72, {0x12, 0x20}, 2, 0}, {0xB5, {0x50}, 1, 0}, {0xBA, {0x00}, 1, 0},
    {0xEC, {0x71}, 1, 0}, {0x7B, {0x00, 0x0D}, 2, 0}, {0x7C, {0x0D, 0x03}, 2, 0}, {0xF5, {0x02, 0x10, 0x12}, 3, 0},
    {0xF0, {0x0C, 0x11, 0x0B, 0x0A, 0x05, 0x32, 0x44, 0x8E, 0x9A, 0x29, 0x2E, 0x5F}, 12, 0},
    {0xF1, {0x0B, 0x11, 0x0B, 0x07, 0x07, 0x32, 0x45, 0xBD, 0x8D, 0x21, 0x28, 0xAF}, 12, 0},
    {0x2A, {0x00, 0x00, 0x00, 0xEF}, 4, 0}, {0x2B, {0x00, 0x00, 0x01, 0x27}, 4, 0},
    {0x66, {0x2C}, 1, 0}, {0x67, {0x18}, 1, 0}, {0x68, {0x3E}, 1, 0}, {0xCA, {0x0E}, 1, 0},
    {0xE8, {0xF0}, 1, 0}, {0xCB, {0x06}, 1, 0}, {0xB6, {0x5C, 0x40, 0x40}, 3, 0}, {0xCC, {0x33}, 1, 0},
    {0xCD, {0x33}, 1, 0}, {0x11, {0}, 0, 80}, {0xE8, {0xA0}, 1, 0}, {0xE8, {0xF0}, 1, 0},
    {0xFE, {0}, 0, 0}, {0xEE, {0}, 0, 0}, {0x29, {0}, 0, 0}, {0x2C, {0}, 0, 10},
};

/**
 * Function: color_done
 * Description: Releases the pacing semaphore once an SPI colour transfer completes.
 * Parameters: io - LCD I/O handle; edata - Event data; ctx - Unused.
 * Return Value: true when a higher-priority task was woken.
 */
static bool IRAM_ATTR color_done(esp_lcd_panel_io_handle_t io, esp_lcd_panel_io_event_data_t *edata, void *ctx)
{
    BaseType_t woken = pdFALSE;                       /* Track whether the give unblocks a waiting task. */

    (void)io;                                         /* Unused. */
    (void)edata;                                      /* Unused. */
    (void)ctx;                                        /* Unused. */
    xSemaphoreGiveFromISR(transfer_done, &woken);     /* Report that a chunk buffer is free again. */
    return woken == pdTRUE;                           /* Request a context switch only when needed. */
}

/**
 * Function: set_window
 * Description: Selects the rectangle of display memory that the next pixel write targets.
 * Parameters: x1, y1 - Top-left pixel; x2, y2 - Bottom-right pixel, inclusive.
 * Return Value: None.
 */
static void set_window(uint16_t x1, uint16_t y1, uint16_t x2, uint16_t y2)
{
    uint8_t col[4] = {(uint8_t)(x1 >> 8), (uint8_t)x1, (uint8_t)(x2 >> 8), (uint8_t)x2}; /* Column-address payload. */
    uint8_t row[4] = {(uint8_t)(y1 >> 8), (uint8_t)y1, (uint8_t)(y2 >> 8), (uint8_t)y2}; /* Row-address payload. */

    ESP_ERROR_CHECK(esp_lcd_panel_io_tx_param(lcd_io, 0x2A, col, sizeof(col))); /* Select the column range. */
    ESP_ERROR_CHECK(esp_lcd_panel_io_tx_param(lcd_io, 0x2B, row, sizeof(row))); /* Select the row range. */
}

/**
 * Function: push_slide
 * Description: Writes one composite of two frames, the first shifted left by offset pixels.
 * Parameters: outgoing - Frame sliding away; incoming - Frame sliding in; offset - Shift in pixels.
 * Return Value: None.
 *
 * Both sources are in PSRAM, which SPI DMA should not stream from directly, so each band
 * is copied into one of two internal DMA buffers before being queued.
 */
static void push_slide(const uint8_t *outgoing, const uint8_t *incoming, int offset)
{
    int left = DISPLAY_W - offset;                    /* Pixels still taken from the outgoing frame. */

    for (int chunk = 0; chunk < CHUNK_COUNT; chunk++) { /* Walk the panel in horizontal bands. */
        uint8_t *dst = chunk_buffer[chunk & 1];       /* Alternate buffers so one fills while the other transfers. */

        xSemaphoreTake(transfer_done, portMAX_DELAY); /* Wait until this buffer is free before overwriting it. */
        for (int line = 0; line < CHUNK_LINES; line++) { /* Build every row of the band. */
            size_t src_row = (size_t)(chunk * CHUNK_LINES + line) * DISPLAY_W; /* First pixel index of this row. */
            uint8_t *row = dst + (size_t)line * DISPLAY_W * 2; /* This row inside the chunk buffer. */

            if (left > 0) {                           /* Copy the part of the outgoing frame still on screen. */
                memcpy(row, outgoing + (src_row + offset) * 2, (size_t)left * 2);
            }
            if (offset > 0) {                         /* Copy the leading edge of the incoming frame. */
                memcpy(row + (size_t)left * 2, incoming + src_row * 2, (size_t)offset * 2);
            }
        }
        set_window(0, (uint16_t)(chunk * CHUNK_LINES), DISPLAY_W - 1,
                   (uint16_t)(chunk * CHUNK_LINES + CHUNK_LINES - 1)); /* Target just this band. */
        ESP_ERROR_CHECK(esp_lcd_panel_io_tx_color(lcd_io, 0x2C, dst, CHUNK_BYTES)); /* Write the band. */
    }
}

void display_init(void)
{
    gpio_config_t out = {0};                          /* Output GPIO configuration for power and reset pins. */
    gpio_config_t in = {0};                           /* Input GPIO configuration for the TE pin. */
    spi_bus_config_t bus = {0};                       /* SPI bus configuration. */
    esp_lcd_panel_io_spi_config_t io = {0};           /* LCD SPI I/O configuration. */
    esp_lcd_panel_io_callbacks_t cbs = {0};           /* LCD transfer callbacks. */

    out.pin_bit_mask = (1ULL << PERIPHERAL_POWER_GPIO) | (1ULL << LCD_POWER_GPIO) |
                       (1ULL << LCD_BACKLIGHT_GPIO) | (1ULL << LCD_RESET_GPIO) |
                       (1ULL << TOUCH_RESET_GPIO);    /* Select all output pins. */
    out.mode = GPIO_MODE_OUTPUT;                      /* Configure selected pins as outputs. */
    out.pull_up_en = GPIO_PULLUP_DISABLE;             /* Disable pull-up on driven outputs. */
    out.pull_down_en = GPIO_PULLDOWN_DISABLE;         /* Disable pull-down on driven outputs. */
    out.intr_type = GPIO_INTR_DISABLE;                /* Disable interrupts on output pins. */
    ESP_ERROR_CHECK(gpio_config(&out));               /* Apply output GPIO configuration. */

    in.pin_bit_mask = (1ULL << LCD_TE_GPIO);          /* Select the tearing-effect input. */
    in.mode = GPIO_MODE_INPUT;                        /* Configure it as an input. */
    in.pull_up_en = GPIO_PULLUP_ENABLE;               /* Enable its pull-up. */
    in.pull_down_en = GPIO_PULLDOWN_DISABLE;          /* Disable pull-down. */
    in.intr_type = GPIO_INTR_DISABLE;                 /* No interrupt; the TE pin is unused for now. */
    ESP_ERROR_CHECK(gpio_config(&in));                /* Apply input GPIO configuration. */

    ESP_ERROR_CHECK(gpio_set_level(PERIPHERAL_POWER_GPIO, 1)); /* Enable the shared peripheral rail first. */
    vTaskDelay(pdMS_TO_TICKS(30));                    /* Wait for the shared rail to stabilize. */
    ESP_ERROR_CHECK(gpio_set_level(LCD_POWER_GPIO, 0)); /* Enable LCD power; GPIO40 is active low. */
    ESP_ERROR_CHECK(gpio_set_level(LCD_BACKLIGHT_GPIO, 0)); /* Keep the backlight off during init. */
    vTaskDelay(pdMS_TO_TICKS(30));                    /* Wait for the LCD power rail to settle. */
    ESP_ERROR_CHECK(gpio_set_level(TOUCH_RESET_GPIO, 0)); /* Reset the touch controller. */
    vTaskDelay(pdMS_TO_TICKS(200));                   /* Hold touch reset low for a clean reset. */
    ESP_ERROR_CHECK(gpio_set_level(TOUCH_RESET_GPIO, 1)); /* Release touch reset. */
    vTaskDelay(pdMS_TO_TICKS(300));                   /* Wait for touch firmware to boot. */

    transfer_done = xSemaphoreCreateCounting(2, 2);   /* Both chunk buffers start out free. */
    ESP_ERROR_CHECK(transfer_done == NULL ? ESP_ERR_NO_MEM : ESP_OK);
    for (int i = 0; i < 2; i++) {                     /* Allocate both DMA-capable chunk buffers. */
        chunk_buffer[i] = heap_caps_malloc(CHUNK_BYTES, MALLOC_CAP_DMA | MALLOC_CAP_INTERNAL);
        ESP_ERROR_CHECK(chunk_buffer[i] == NULL ? ESP_ERR_NO_MEM : ESP_OK);
    }
    frame = heap_caps_malloc(FRAME_BYTES_LOCAL, MALLOC_CAP_SPIRAM); /* Compose target. */
    presented = heap_caps_malloc(FRAME_BYTES_LOCAL, MALLOC_CAP_SPIRAM); /* Last presented frame. */
    ESP_ERROR_CHECK(frame == NULL || presented == NULL ? ESP_ERR_NO_MEM : ESP_OK);
    memset(presented, 0, FRAME_BYTES_LOCAL);          /* Start from black so the first slide has a source. */

    bus.sclk_io_num = LCD_SCLK_GPIO;                  /* Assign LCD SPI clock pin. */
    bus.mosi_io_num = LCD_MOSI_GPIO;                  /* Assign LCD SPI MOSI pin. */
    bus.miso_io_num = GPIO_NUM_NC;                    /* MISO unused; the LCD write path is one-way. */
    bus.quadwp_io_num = GPIO_NUM_NC;                  /* Quad WP unused. */
    bus.quadhd_io_num = GPIO_NUM_NC;                  /* Quad HD unused. */
    bus.max_transfer_sz = CHUNK_BYTES;                /* One full band per transfer. */
    ESP_ERROR_CHECK(spi_bus_initialize(SPI2_HOST, &bus, SPI_DMA_CH_AUTO)); /* Initialize the SPI bus. */

    io.dc_gpio_num = LCD_DC_GPIO;                     /* Assign the LCD D/C pin. */
    io.cs_gpio_num = LCD_CS_GPIO;                     /* Assign the LCD chip-select pin. */
    io.pclk_hz = 80000000;                            /* 80 MHz, as in the verified Arduino project. */
    io.lcd_cmd_bits = 8;                              /* Eight-bit commands. */
    io.lcd_param_bits = 8;                            /* Eight-bit parameters. */
    io.spi_mode = 0;                                  /* SPI mode 0, as required by GC9309. */
    io.trans_queue_depth = 4;                         /* Allow several queued colour transactions. */
    ESP_ERROR_CHECK(esp_lcd_new_panel_io_spi((esp_lcd_spi_bus_handle_t)SPI2_HOST, &io, &lcd_io));

    cbs.on_color_trans_done = color_done;             /* Register the transfer-complete callback. */
    ESP_ERROR_CHECK(esp_lcd_panel_io_register_event_callbacks(lcd_io, &cbs, NULL));

    ESP_ERROR_CHECK(gpio_set_level(LCD_RESET_GPIO, 1)); /* Drive LCD reset high before the pulse. */
    vTaskDelay(pdMS_TO_TICKS(10));
    ESP_ERROR_CHECK(gpio_set_level(LCD_RESET_GPIO, 0)); /* Pull LCD reset low. */
    vTaskDelay(pdMS_TO_TICKS(10));
    ESP_ERROR_CHECK(gpio_set_level(LCD_RESET_GPIO, 1)); /* Release LCD reset. */
    vTaskDelay(pdMS_TO_TICKS(120));                   /* Wait for the controller to boot. */

    for (size_t i = 0; i < sizeof(gc9309_init_commands) / sizeof(gc9309_init_commands[0]); i++) {
        const lcd_init_cmd_t *cmd = &gc9309_init_commands[i]; /* Current init command. */

        ESP_ERROR_CHECK(esp_lcd_panel_io_tx_param(lcd_io, cmd->command,
                                                  cmd->data_length ? cmd->data : NULL,
                                                  cmd->data_length)); /* Send command and payload. */
        if (cmd->delay_ms > 0) {                      /* Honour the command's settling delay. */
            vTaskDelay(pdMS_TO_TICKS(cmd->delay_ms));
        }
    }
    ESP_LOGI(TAG, "GC9309 ready, %dx%d", DISPLAY_W, DISPLAY_H); /* Report that the panel is up. */
}

void display_backlight(bool on)
{
    ESP_ERROR_CHECK(gpio_set_level(LCD_BACKLIGHT_GPIO, on ? 1 : 0)); /* Drive the backlight pin. */
}

void display_begin(const uint8_t *background)
{
    memcpy(frame, background, FRAME_BYTES_LOCAL);     /* Start the frame from a full-screen image. */
}

void display_scrim(int y, int height, int percent)
{
    int y1 = y < 0 ? 0 : y;                           /* Clamp the band to the panel. */
    int y2 = y + height > DISPLAY_H ? DISPLAY_H : y + height;

    for (int row = y1; row < y2; row++) {             /* Walk every row of the band. */
        uint8_t *p = frame + (size_t)row * DISPLAY_W * 2; /* First byte of this row. */

        for (int x = 0; x < DISPLAY_W; x++, p += 2) { /* Walk every pixel of the row. */
            uint16_t v = (uint16_t)((p[0] << 8) | p[1]); /* Decode the big-endian pixel. */
            uint16_t r = ((v >> 11) & 0x1F) * percent / 100; /* Scale red. */
            uint16_t g = ((v >> 5) & 0x3F) * percent / 100;  /* Scale green. */
            uint16_t b = (v & 0x1F) * percent / 100;         /* Scale blue. */

            v = (uint16_t)((r << 11) | (g << 5) | b); /* Re-pack. */
            p[0] = (uint8_t)(v >> 8);                 /* Store high byte first. */
            p[1] = (uint8_t)v;
        }
    }
}

void display_blit(const uint8_t *sprite, int sw, int sh, int dx, int dy)
{
    for (int row = 0; row < sh; row++) {              /* Walk every row of the sprite. */
        int y = dy + row;                             /* Destination row. */

        if (y < 0 || y >= DISPLAY_H) {                /* Skip rows outside the panel. */
            continue;
        }
        for (int col = 0; col < sw; col++) {          /* Walk every column of the sprite. */
            int x = dx + col;                         /* Destination column. */
            const uint8_t *s;                         /* Source pixel. */
            uint8_t *d;                               /* Destination pixel. */
            uint16_t sv, dv, r, g, b;                 /* Decoded channels. */

            if (x < 0 || x >= DISPLAY_W) {            /* Skip columns outside the panel. */
                continue;
            }
            s = sprite + ((size_t)row * sw + col) * 2;
            d = frame + ((size_t)y * DISPLAY_W + x) * 2;
            sv = (uint16_t)((s[0] << 8) | s[1]);      /* Decode the source pixel. */
            dv = (uint16_t)((d[0] << 8) | d[1]);      /* Decode the destination pixel. */

            r = ((sv >> 11) & 0x1F) > ((dv >> 11) & 0x1F) ? ((sv >> 11) & 0x1F) : ((dv >> 11) & 0x1F);
            g = ((sv >> 5) & 0x3F) > ((dv >> 5) & 0x3F) ? ((sv >> 5) & 0x3F) : ((dv >> 5) & 0x3F);
            b = (sv & 0x1F) > (dv & 0x1F) ? (sv & 0x1F) : (dv & 0x1F);

            dv = (uint16_t)((r << 11) | (g << 5) | b); /* Re-pack the brighter of the two. */
            d[0] = (uint8_t)(dv >> 8);                /* Store high byte first. */
            d[1] = (uint8_t)dv;
        }
    }
}

int display_text(const char *text, int dx, int dy)
{
    int x = dx;                                       /* Running cursor position. */

    for (const char *c = text; *c != '\0'; c++) {     /* Walk the string. */
        int cell = -1;                                /* Glyph cell index for this character. */

        if (*c >= '0' && *c <= '9') {                 /* Digits occupy cells 0-9. */
            cell = *c - '0';
        } else if (*c == ':') {                       /* The colon is the last cell. */
            cell = GLYPH_COLON;
        }
        if (cell >= 0) {                              /* Draw only characters the sheet carries. */
            /* The sheet is one wide strip, so a cell is a column slice: step a full sheet
             * row per source row and offset by the cell's x origin. */
            for (int row = 0; row < GLYPH_H; row++) {
                const uint8_t *src = qoo_glyphs +
                    ((size_t)row * GLYPH_COUNT * GLYPH_W + (size_t)cell * GLYPH_W) * 2;

                display_blit(src, GLYPH_W, 1, x, dy + row); /* Blit one row of the cell. */
            }
        }
        x += GLYPH_W;                                 /* Advance by a full cell, drawn or not. */
    }
    return x - dx;                                    /* Report the width consumed. */
}

void display_text_centered(const char *text, int dy)
{
    int width = (int)strlen(text) * GLYPH_W;          /* Fixed-cell text has a predictable width. */

    display_text(text, (DISPLAY_W - width) / 2, dy);  /* Centre it on the panel. */
}

void display_fill(uint8_t r, uint8_t g, uint8_t b)
{
    uint16_t v = (uint16_t)(((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3)); /* Pack to RGB565. */
    uint8_t hi = (uint8_t)(v >> 8);                   /* High byte first, as the panel expects. */
    uint8_t lo = (uint8_t)v;

    for (size_t i = 0; i < (size_t)DISPLAY_W * DISPLAY_H; i++) {
        frame[i * 2] = hi;
        frame[i * 2 + 1] = lo;
    }
}

/**
 * Function: glyph_width
 * Description: Advance width of one character, or a space for anything unprintable.
 * Parameters: c - Character.
 * Return Value: Width in pixels.
 */
static int glyph_width(char c)
{
    if ((unsigned char)c < FONT_FIRST || (unsigned char)c > FONT_LAST) {
        return qoo_font_w[' ' - FONT_FIRST];          /* Unknown characters take a space. */
    }
    return qoo_font_w[(unsigned char)c - FONT_FIRST];
}

/**
 * Function: draw_glyph
 * Description: Blits one character from the packed font strip.
 * Parameters: c - Character; x, y - Top-left position.
 * Return Value: Width consumed.
 */
static int draw_glyph(char c, int x, int y)
{
    int index;                                        /* Glyph index in the strip. */
    int width;                                        /* Advance width. */

    if ((unsigned char)c < FONT_FIRST || (unsigned char)c > FONT_LAST) {
        return glyph_width(c);                        /* Leave a gap, draw nothing. */
    }
    index = (unsigned char)c - FONT_FIRST;
    width = qoo_font_w[index];

    /* The strip is one long image, so a glyph is a column slice: step a full strip row
     * per source row and offset by this glyph's x origin. */
    for (int row = 0; row < FONT_H; row++) {
        const uint8_t *src = qoo_font + ((size_t)row * FONT_STRIP_W + qoo_font_x[index]) * 2;

        display_blit(src, width, 1, x, y + row);
    }
    return width;
}

int display_paragraph(const char *text, int x, int y, int width, int max_rows)
{
    int rows = 0;                                     /* Lines drawn so far. */
    const char *at = text;                            /* Reading position. */

    while (*at != '\0' && rows < max_rows) {
        const char *line_start = at;                  /* First character of this line. */
        const char *last_space = NULL;                /* Last space that still fitted. */
        int used = 0;                                 /* Pixels consumed by this line. */

        /* Walk forward until the next character would overflow, remembering the last
         * space so the line can be broken there rather than mid-word. */
        while (*at != '\0' && *at != '\n') {
            int w = glyph_width(*at);

            if (used + w > width) {
                break;
            }
            if (*at == ' ') {
                last_space = at;
            }
            used += w;
            at++;
        }

        const char *line_end = at;                    /* One past the last character drawn. */
        if (*at != '\0' && *at != '\n' && last_space != NULL) {
            line_end = last_space;                    /* Break at the space... */
            at = last_space + 1;                      /* ...and resume after it. */
        } else if (*at == '\n') {
            at++;                                     /* Explicit break: skip the newline. */
        }
        /* No space on the line means a single word wider than the box. line_end stays
         * where the overflow was, so it is split rather than running off the panel. */

        int cursor = x;                               /* Pen position for this line. */
        for (const char *c = line_start; c < line_end; c++) {
            cursor += draw_glyph(*c, cursor, y + rows * FONT_H);
        }
        rows++;
    }
    return rows;
}

void display_commit(bool animate)
{
    if (animate) {                                    /* Slide the previous frame out to the left. */
        for (int step = 1; step <= SLIDE_STEPS; step++) {
            int progress = (step * 100) / SLIDE_STEPS; /* Percentage of the travel. */
            int eased = 100 - ((100 - progress) * (100 - progress)) / 100; /* Quadratic ease-out. */

            push_slide(presented, frame, (eased * DISPLAY_W) / 100);
        }
    }
    push_slide(frame, frame, 0);                      /* Land exactly on the composed frame. */
    memcpy(presented, frame, FRAME_BYTES_LOCAL);      /* Remember it for the next transition. */
}
