/* AXS5106L capacitive touch panel.
 *
 * The device deliberately has no visible on-screen controls - during the demo it should
 * look like it is simply running. So the whole input vocabulary is two gestures anywhere
 * on the glass: a short tap and a long press. This header exposes the raw state; the
 * gesture timing lives in main.c, where the state machine can decide what each means.
 */
#pragma once

#include <stdbool.h>

/**
 * Function: touch_init
 * Description: Opens the I2C bus and verifies the AXS5106L answers. The controller must
 *              already have been reset, which display_init() does as part of bringing up
 *              the shared peripheral rail.
 * Parameters: None.
 * Return Value: None.
 */
void touch_init(void);

/**
 * Function: touch_is_pressed
 * Description: Reports whether a finger is currently on the panel.
 * Parameters: None.
 * Return Value: true when the controller reports one plausible touch point.
 */
bool touch_is_pressed(void);
