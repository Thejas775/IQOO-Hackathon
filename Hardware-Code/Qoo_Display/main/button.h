/* The encoder push button.
 *
 * Deliberately a different control from the touch screen. The screen hold starts a
 * recording that becomes a memory; this button asks a question and expects an answer
 * back. Two different intents should not share one gesture - on stage especially, where
 * picking the wrong one is not recoverable in front of an audience.
 *
 * Press and hold to speak a question; release to send it.
 */
#pragma once

#include <stdbool.h>

/**
 * Function: button_init
 * Description: Configures the encoder push button as a pulled-up input.
 * Parameters: None.
 * Return Value: None.
 */
void button_init(void);

/**
 * Function: button_is_down
 * Description: Debounced state of the encoder push button.
 * Parameters: None.
 * Return Value: true while the button is held.
 *
 * Must be called at a steady rate; the debounce counts consecutive agreeing samples
 * rather than measuring time, exactly as the touch layer does.
 */
bool button_is_down(void);
