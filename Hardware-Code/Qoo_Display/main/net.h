/* WiFi link to the phone: discovery, pairing, and audio upload.
 *
 * Protocol, deliberately line-based and readable so it can be driven by hand with nc
 * while debugging:
 *
 *   discovery   device --UDP broadcast--> 255.255.255.255:5151  "QOO <code> <ip>"
 *               sent once a second, only while unpaired
 *
 *   pairing     phone  --TCP 5152-->  "PAIR <code>\n"
 *               device --> "OK\n"  and stays connected
 *                      or  "NO\n"  and closes
 *
 *   audio       device --> "AUDIO <bytes>\n" followed by exactly that many bytes of
 *               raw 16 kHz mono 16-bit PCM. Becomes a memory.
 *
 *   question    device --> "ASK <bytes>\n" then the same kind of PCM
 *               phone  --> "REPLY <bytes>\n" then 16 kHz mono PCM to play back
 *
 * AUDIO and ASK are separate verbs carrying identical payloads on purpose: the bytes
 * are the same, the intent is not. One is something to remember, the other is a
 * question expecting an answer, and the phone cannot tell them apart from the audio.
 *
 * The phone types the code, so the code is what selects one device out of however many
 * are on the network - which is the real reason it exists, not decoration. Raw PCM goes
 * over the wire rather than WAV because the header needs a length that is only known
 * once recording stops, and the phone already has a WAV writer.
 */
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

/**
 * Function: net_start
 * Description: Brings up WiFi in station mode and starts the discovery and pairing
 *              server. Returns immediately; connection happens in the background.
 * Parameters: code - The six-digit pairing code to advertise and require.
 * Return Value: None.
 */
void net_start(const char *code);

/**
 * Function: net_is_online
 * Description: Reports whether WiFi is associated and has an IP.
 * Parameters: None.
 * Return Value: true when the device has an address.
 */
bool net_is_online(void);

/**
 * Function: net_is_paired
 * Description: Reports whether a phone has connected and sent the right code.
 * Parameters: None.
 * Return Value: true while a paired phone is connected.
 */
bool net_is_paired(void);

/**
 * Function: net_ip
 * Description: The device's current IPv4 address as text, or "..." before one is
 *              assigned. Shown on screen under the pairing code so a connection can be
 *              debugged without a serial cable.
 * Parameters: None.
 * Return Value: Pointer to a static string.
 */
const char *net_ip(void);

/**
 * Function: net_send_audio
 * Description: Sends captured PCM to the paired phone. Blocks until the whole buffer
 *              has been written.
 * Parameters: pcm - PCM bytes; length - How many.
 * Return Value: true if everything was written.
 */
bool net_send_audio(const uint8_t *pcm, size_t length);

/**
 * Function: net_send_ask
 * Description: Sends a spoken question to the paired phone. Same payload as
 *              net_send_audio, different verb, so the phone answers instead of
 *              remembering.
 * Parameters: pcm - PCM bytes; length - How many.
 * Return Value: true if everything was written.
 */
bool net_send_ask(const uint8_t *pcm, size_t length);

/**
 * Function: net_take_reply
 * Description: Collects a spoken answer, if one has arrived since the last call.
 * Parameters: data - Receives a pointer to the PCM; length - Receives its size.
 * Return Value: true when an answer was waiting. Consumes it, so a second call
 *               returns false until the next answer arrives.
 */
bool net_take_reply(const uint8_t **data, size_t *length);

/**
 * Function: net_take_text
 * Description: Collects the answer in words, if one has arrived since the last call.
 * Parameters: out - Destination buffer; max - Its size.
 * Return Value: true when text was waiting. Consumes it.
 */
bool net_take_text(char *out, size_t max);
