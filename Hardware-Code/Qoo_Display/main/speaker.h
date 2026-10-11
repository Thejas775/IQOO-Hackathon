/* I2S speaker playback.
 *
 * Plays the answer the phone sends back: 16 kHz mono 16-bit PCM, the same format the
 * microphone records in, so one rate and one width serve the whole device.
 *
 * On its own I2S controller because the PDM microphone already owns I2S_NUM_0 and the
 * two are independent - asking a question records and then plays, and a shared
 * controller would mean tearing one down to use the other.
 */
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

/** Playback rate, matching the microphone and the phone's synthesiser. */
#define SPEAKER_SAMPLE_RATE 16000

/** Software gain applied to every sample before playback.
 *
 * One, i.e. none, and that is a measurement rather than a default. This was briefly 4,
 * on the assumption that synthesised speech arrives quiet. It does not: the first
 * answer actually measured peaked at 23778 of 32767, which is 72% of full scale, so
 * x4 put every loud syllable roughly three times over the ceiling and clipped it.
 *
 * The peak of each answer is logged before gain is applied, so this can be tuned
 * against a number instead of a guess. If answers are genuinely quiet the log will say
 * so - a low peak - and only then is raising this the right move. */
#define SPEAKER_GAIN 1

/**
 * Function: speaker_init
 * Description: Opens the I2S transmit channel and prepares the amplifier enable pin.
 * Parameters: None.
 * Return Value: true if the speaker is ready.
 */
bool speaker_init(void);

/**
 * Function: speaker_play
 * Description: Plays a PCM buffer, blocking until it has all been written. Enables the
 *              amplifier for the duration and switches it off afterwards.
 * Parameters: pcm - 16-bit mono samples; length - Number of bytes.
 * Return Value: true if the whole buffer was played.
 */
bool speaker_play(const uint8_t *pcm, size_t length);
