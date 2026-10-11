/* PDM microphone capture.
 *
 * Records 16 kHz mono 16-bit PCM into PSRAM. That is deliberately the exact format the
 * phone's VoiceRecorder produces, so the phone can wrap the bytes in a 44-byte WAV header
 * and hand the file to Gemma's audio encoder without resampling or converting anything -
 * a device recording and a phone recording become literally the same kind of file.
 *
 * Raw PCM goes over the wire, not WAV: the header needs the final length, which is not
 * known until recording stops, and the phone already has a WAV writer.
 */
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

/** Sample rate, matching the phone recorder and the encoder's native rate. */
#define MIC_SAMPLE_RATE 16000
/** Bytes of PCM per second: mono, two bytes per sample. */
#define MIC_BYTES_PER_SECOND (MIC_SAMPLE_RATE * 2)
/** Longest recording the buffer can hold, in seconds. */
#define MIC_CAP_SECONDS 180

/**
 * Function: mic_init
 * Description: Allocates the capture buffer and opens the PDM receive channel. The
 *              shared peripheral rail must already be powered, which display_init() does.
 * Parameters: None.
 * Return Value: true if the microphone is ready to record.
 */
bool mic_init(void);

/**
 * Function: mic_start
 * Description: Begins recording from zero. Any previous capture is discarded.
 * Parameters: None.
 * Return Value: true if recording started.
 */
bool mic_start(void);

/**
 * Function: mic_stop
 * Description: Stops recording.
 * Parameters: None.
 * Return Value: Number of PCM bytes captured.
 */
size_t mic_stop(void);

/**
 * Function: mic_is_recording
 * Description: Reports whether capture is currently running.
 * Parameters: None.
 * Return Value: true while recording.
 */
bool mic_is_recording(void);

/**
 * Function: mic_captured_bytes
 * Description: Bytes captured so far, which is live while recording.
 * Parameters: None.
 * Return Value: Byte count.
 */
size_t mic_captured_bytes(void);

/**
 * Function: mic_data
 * Description: Pointer to the captured PCM. Valid until the next mic_start().
 * Parameters: None.
 * Return Value: Buffer pointer, or NULL if the microphone never initialized.
 */
const uint8_t *mic_data(void);

/**
 * Function: mic_level
 * Description: Most recent block's peak level, for a live meter on screen.
 * Parameters: None.
 * Return Value: 0 to 100.
 */
int mic_level(void);
