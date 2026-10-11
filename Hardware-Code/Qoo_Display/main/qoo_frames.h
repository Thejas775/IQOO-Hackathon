/* Big-endian RGB565 frames of the QOO mascot, sized for the 240x296 GC9309 panel.
 *
 * One frame per state in the app's Qoo enum (ui/QooMascot.kt), so the device shows the
 * same character in the same state the phone is in.
 */
#pragma once

#include <stdint.h>

/** Width in pixels of every generated frame. */
#define FRAME_WIDTH 240
/** Height in pixels of every generated frame. */
#define FRAME_HEIGHT 296
/** Size in bytes of one full-screen RGB565 frame. */
#define FRAME_BYTES (FRAME_WIDTH * FRAME_HEIGHT * 2)

extern const uint8_t qoo_sleeping[FRAME_BYTES];   /* Not listening. */
extern const uint8_t qoo_waving[FRAME_BYTES];     /* Greeting, wake. */
extern const uint8_t qoo_running[FRAME_BYTES];    /* Capture in flight. */
extern const uint8_t qoo_thinking[FRAME_BYTES];   /* Extraction running. */
extern const uint8_t qoo_resting[FRAME_BYTES];    /* All read, nothing open. */
