/*
 * DailyFlow companion device - QOO state machine.
 *
 * The device shows the QOO mascot in whichever state it is actually in, so the mascot is
 * the interface rather than decoration. There are no on-screen controls: the whole input
 * vocabulary is a long press and a short tap, anywhere on the glass. That is deliberate -
 * on stage the device should look like it is simply running, and a long press needs no
 * aim, which a hidden hotspot would.
 *
 * The encoder button is a separate control from the screen. Holding the screen starts
 * a recording that becomes a memory; holding the encoder asks a question and expects an
 * answer played back. Two different intents should not share one gesture.
 *
 * STATE      MASCOT     SHOWS             LONG PRESS       SHORT TAP   LEAVES WHEN
 * Pairing    Waving     6-digit code      -                -           phone sends the code
 * Ready      Resting    READY             start recording  mute        -
 * Recording  Running    mm:ss elapsed     stop             -           cap, or buffer full
 * Thinking   Thinking   SENDING/THINKING  -                -           upload finishes
 * Muted      Sleeping   MIC MUTED         unmute           unmute      -
 *
 * Nothing dismisses the pairing code except a phone actually pairing. It is the one
 * thing the user is trying to read, and a gesture that cleared it would be a bug.
 */
#include <stdio.h>
#include <string.h>
#include "display.h"
#include "esp_log.h"
#include "esp_random.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "button.h"
#include "mic.h"
#include "net.h"
#include "qoo_frames.h"
#include "qoo_ui.h"
#include "speaker.h"
#include "touch.h"

/** Tag used by ESP-IDF logs from the state machine. */
static const char *TAG = "qoo";
/** Hold this long for a long press, in milliseconds. */
#define LONG_PRESS_MS 500
/** Touch polling interval, in milliseconds. */
#define POLL_MS 20
/** Hard cap on one recording, in seconds.
 *
 * The product story is continuous capture; the demo is a bounded take. The cap lives
 * here rather than on the phone because the device owns the microphone - if the link
 * drops mid-recording it still has to stop on its own. */
#define RECORD_CAP_S 180
/** How long to wait for a spoken answer before giving up, in seconds.
 *
 * Generous on purpose: answering runs two inferences on the phone - transcribe, then
 * search and answer - and then synthesises speech. Tens of seconds is normal. */
#define ANSWER_TIMEOUT_S 120

/** The states the device can be in. One mascot pose each. */
typedef enum {
    STATE_PAIRING,                                    /* Showing a code, waiting for the phone. */
    STATE_READY,                                      /* Paired and idle. */
    STATE_RECORDING,                                  /* Capturing audio. */
    STATE_THINKING,                                   /* Uploading, then the phone runs Gemma. */
    STATE_MUTED,                                      /* Microphone off, deliberately and visibly. */
    STATE_ASKING,                                     /* Encoder held: recording a question. */
    STATE_ANSWERING,                                  /* Question sent; waiting to play the answer. */
} device_state_t;

/** The current state. */
static device_state_t state = STATE_PAIRING;
/** Pairing code shown on screen, as six digits plus a terminator. */
static char pairing_code[7] = "000000";
/** When the current recording started, in microseconds. */
static int64_t record_started_us = 0;
/** Seconds of recording already rendered, so the timer only redraws when it changes. */
static int rendered_elapsed = -1;
/** Bytes of PCM waiting to be uploaded to the phone. */
static size_t pending_bytes = 0;
/** Bytes of a recorded question waiting to be sent. */
static size_t pending_question = 0;
/** When the device started waiting for an answer, in microseconds. Zero when not waiting. */
static int64_t answer_wait_started_us = 0;
/** The most recent answer in words, shown on screen. Empty when there is none. */
static char answer_text[512] = "";
/** Whether WiFi was up at the last render, so the Pairing label can follow it. */
static bool rendered_online = false;

/**
 * Function: generate_pairing_code
 * Description: Draws a fresh six-digit code from the hardware RNG.
 * Parameters: None.
 * Return Value: None.
 *
 * Regenerated on every boot so a code cannot be reused after a power cycle, and so two
 * devices on the same table are unlikely to collide.
 */
static void generate_pairing_code(void)
{
    for (int i = 0; i < 6; i++) {                     /* Fill each digit position. */
        pairing_code[i] = (char)('0' + (esp_random() % 10)); /* One uniform digit. */
    }
    pairing_code[6] = '\0';                           /* Terminate the string. */
}

/**
 * Function: render
 * Description: Composes and presents the screen for the current state.
 * Parameters: animate - true to slide the new screen in, false to cut to it.
 * Return Value: None.
 */
static void render(bool animate)
{
    char timer[12];                                   /* "mm:ss"; oversized so the compiler can
                                                       * see snprintf cannot truncate. */
    int elapsed;                                      /* Seconds since recording started. */

    rendered_online = net_is_online();                /* Remember what the label was drawn against. */

    switch (state) {
    case STATE_PAIRING:
        display_begin(qoo_waving);                    /* Greeting pose while waiting to be paired. */
        display_scrim(210, 86, 22);                   /* Darken the lower band so the code reads. */
        /* The code is shown even before WiFi is up: it never changes, and seeing it
         * immediately beats a blank wait. The label says which step is outstanding. */
        display_blit(rendered_online ? qoo_label_pairing : qoo_label_connecting,
                     LABEL_W, LABEL_H, 0, 216);
        display_text_centered(pairing_code, 242);     /* The code itself, in large digits. */
        break;

    case STATE_READY:
        display_begin(qoo_resting);                   /* Everything read, nothing outstanding. */
        display_scrim(256, 40, 25);
        display_blit(qoo_label_ready, LABEL_W, LABEL_H, 0, 262);
        break;

    case STATE_RECORDING:
        elapsed = (int)((esp_timer_get_time() - record_started_us) / 1000000);
        if (elapsed < 0) {                            /* Clamped at both ends, which also lets the */
            elapsed = 0;                              /* compiler prove the mm:ss format cannot */
        }                                             /* truncate. */
        if (elapsed > RECORD_CAP_S) {
            elapsed = RECORD_CAP_S;
        }
        snprintf(timer, sizeof(timer), "%02d:%02d", elapsed / 60, elapsed % 60);
        display_begin(qoo_running);                   /* Capture in flight. */
        display_scrim(210, 86, 22);
        display_blit(qoo_label_recording, LABEL_W, LABEL_H, 0, 216);
        display_text_centered(timer, 242);            /* Elapsed time, so the take is visibly bounded. */
        rendered_elapsed = elapsed;                   /* Lets the next tick skip a redraw. */
        break;

    case STATE_THINKING:
        display_begin(qoo_thinking);                  /* Uploading, then the phone works. */
        display_scrim(256, 40, 25);
        display_blit(pending_bytes > 0 ? qoo_label_sending : qoo_label_thinking,
                     LABEL_W, LABEL_H, 0, 262);
        break;

    case STATE_MUTED:
        display_begin(qoo_sleeping);                  /* Microphone genuinely off. */
        display_scrim(256, 40, 25);
        display_blit(qoo_label_muted, LABEL_W, LABEL_H, 0, 262);
        break;

    case STATE_ASKING:
        display_begin(qoo_waving);                    /* Attentive: QOO is being spoken to. */
        display_scrim(256, 40, 25);
        display_blit(qoo_label_listening, LABEL_W, LABEL_H, 0, 262);
        break;

    case STATE_ANSWERING:
        if (answer_text[0] != ' ') {
            /* Once the words are here they are the answer, so they get the whole
             * screen rather than a caption under the mascot. Reading it must not
             * depend on the speaker working. */
            display_fill(10, 7, 3);                   /* Warm near-black, easier on amber. */
            display_paragraph(answer_text, 12, 16, DISPLAY_W - 24, 12);
        } else {
            display_begin(qoo_thinking);              /* Still waiting on the phone. */
            display_scrim(256, 40, 25);
            display_blit(qoo_label_answering, LABEL_W, LABEL_H, 0, 262);
        }
        break;
    }
    display_commit(animate);                          /* Present the composed frame. */
}

/**
 * Function: enter
 * Description: Moves to a new state, logs it, and redraws with a transition.
 * Parameters: next - State to enter.
 * Return Value: None.
 */
static void enter(device_state_t next)
{
    static const char *const names[] = {"Pairing", "Ready", "Recording", "Thinking", "Muted",
                                        "Asking", "Answering"};

    state = next;                                     /* Adopt the new state. */
    rendered_elapsed = -1;                            /* Force the timer to redraw on first tick. */
    if (next == STATE_RECORDING) {                    /* Start the recording clock. */
        record_started_us = esp_timer_get_time();
    }
    ESP_LOGI(TAG, "state -> %s", names[next]);        /* Make the flow followable over serial. */
    render(true);
}

/**
 * Function: on_long_press
 * Description: Applies the primary action for the current state.
 * Parameters: None.
 * Return Value: None.
 */
static void on_long_press(void)
{
    switch (state) {
    case STATE_PAIRING:
        break;                                        /* Only the phone leaves this state. */
    case STATE_READY:
        if (!mic_start()) {                           /* Never pretend to be recording. */
            ESP_LOGE(TAG, "microphone would not start");
            break;
        }
        enter(STATE_RECORDING);                       /* Begin a take. */
        break;
    case STATE_RECORDING:
        pending_bytes = mic_stop();                   /* Freeze the take and queue the upload. */
        ESP_LOGI(TAG, "recording stopped, %u bytes", (unsigned)pending_bytes);
        enter(STATE_THINKING);                        /* Hand off to the phone. */
        break;
    case STATE_THINKING:
        break;                                        /* Inert while an upload is in flight. */
    case STATE_MUTED:
        enter(STATE_READY);                           /* Unmute. */
        break;
    case STATE_ASKING:
        break;                                        /* The encoder drives this, not the screen. */
    case STATE_ANSWERING:
        answer_text[0] = ' ';                        /* Dismiss the answer. */
        enter(STATE_READY);
        break;
    }
}

/**
 * Function: on_short_tap
 * Description: Applies the secondary action for the current state.
 * Parameters: None.
 * Return Value: None.
 */
static void on_short_tap(void)
{
    switch (state) {
    case STATE_READY:
        enter(STATE_MUTED);                           /* Hard mute, visible to everyone in the room. */
        break;
    case STATE_MUTED:
        enter(STATE_READY);                           /* Unmute. */
        break;
    case STATE_ANSWERING:
        answer_text[0] = ' ';                        /* A tap dismisses a shown answer. */
        enter(STATE_READY);
        break;
    default:
        break;                                        /* No secondary action elsewhere. */
    }
}

/**
 * Function: app_main
 * Description: Brings up panel, touch, microphone and link, then runs the state loop.
 * Parameters: None.
 * Return Value: None.
 */
void app_main(void)
{
    bool was_pressed = false;                         /* Touch state on the previous poll. */
    bool long_fired = false;                          /* Whether this press already fired a long press. */
    bool encoder_was_down = false;                    /* Encoder state on the previous poll. */
    int64_t pressed_at_us = 0;                        /* When the current press began. */

    display_init();                                   /* Panel, rails, framebuffers. */
    touch_init();                                     /* Touch controller on the shared I2C bus. */
    button_init();                                    /* Encoder push button, for questions. */
    if (!speaker_init()) {                            /* I2S speaker, for spoken answers. */
        ESP_LOGE(TAG, "speaker unavailable; answers cannot be played");
    }
    if (!mic_init()) {                                /* PDM microphone and its PSRAM buffer. */
        ESP_LOGE(TAG, "microphone unavailable; recording will refuse to start");
    }
    generate_pairing_code();                          /* A fresh code for this power cycle. */
    net_start(pairing_code);                          /* Join WiFi, advertise, listen for the phone. */

    render(false);                                    /* Compose the first screen before it is visible. */
    vTaskDelay(pdMS_TO_TICKS(20));                    /* Let the last band reach the panel. */
    display_backlight(true);                          /* Reveal a finished screen, never a half-drawn one. */
    ESP_LOGI(TAG, "pairing code %s", pairing_code);

    while (true) {                                    /* Gesture and state loop. */
        bool pressed = touch_is_pressed();            /* Sample the panel. */
        int64_t now = esp_timer_get_time();           /* One timestamp per iteration. */

        if (pressed && !was_pressed) {                /* Press began. */
            pressed_at_us = now;
            long_fired = false;
        } else if (pressed && !long_fired && (now - pressed_at_us) >= (int64_t)LONG_PRESS_MS * 1000) {
            long_fired = true;                        /* Fire on the hold, not on release, so the */
            on_long_press();                          /* screen reacts while the finger is still down. */
        } else if (!pressed && was_pressed && !long_fired) {
            on_short_tap();                           /* A press that ended before the threshold. */
        }
        was_pressed = pressed;

        /* The link owns the pairing state in both directions. Losing the phone returns
         * the device to showing its code, which is the only thing that can fix it. */
        if (state == STATE_PAIRING && net_is_paired()) {
            ESP_LOGI(TAG, "phone paired");
            enter(STATE_READY);
        } else if (state != STATE_PAIRING && !net_is_paired()) {
            if (mic_is_recording()) {
                mic_stop();                           /* Never hold a mic open with nowhere to send. */
            }
            pending_bytes = 0;
            ESP_LOGW(TAG, "phone lost, back to pairing");
            enter(STATE_PAIRING);
        } else if (state == STATE_PAIRING && net_is_online() != rendered_online) {
            render(false);                            /* Swap JOINING WIFI for PAIRING CODE. */
        }

        if (state == STATE_RECORDING) {               /* Keep the elapsed timer live. */
            int elapsed = (int)((now - record_started_us) / 1000000);

            if (elapsed >= RECORD_CAP_S || !mic_is_recording()) {
                /* Either the cap was reached or the capture buffer filled. Both end the
                 * take the same way, on the device, without asking anything off-board. */
                pending_bytes = mic_stop();
                ESP_LOGI(TAG, "take ended at %d s, %u bytes", elapsed, (unsigned)pending_bytes);
                enter(STATE_THINKING);
            } else if (elapsed != rendered_elapsed) { /* Redraw only when the second changes. */
                render(false);                        /* Cut, not slide: this is the same screen. */
            }
        }

        if (state == STATE_THINKING && pending_bytes > 0) {
            size_t bytes = pending_bytes;             /* Upload blocks; the UI is parked anyway. */

            if (!net_send_audio(mic_data(), bytes)) {
                ESP_LOGE(TAG, "upload failed");
            }
            pending_bytes = 0;
            render(false);                            /* SENDING becomes THINKING. */
            enter(STATE_READY);
        }

        /* The encoder button asks a question: hold to speak, release to send. Held
         * rather than toggled so the length of the question is bounded by the finger,
         * which is also the clearest possible signal to anyone watching that the
         * device is listening on purpose. */
        bool encoder = button_is_down();
        if (encoder && !encoder_was_down && state == STATE_READY) {
            if (mic_start()) {
                enter(STATE_ASKING);
            } else {
                ESP_LOGE(TAG, "microphone would not start for a question");
            }
        } else if (!encoder && encoder_was_down && state == STATE_ASKING) {
            pending_question = mic_stop();
            ESP_LOGI(TAG, "question recorded, %u bytes", (unsigned)pending_question);
            enter(STATE_ANSWERING);
        }
        encoder_was_down = encoder;

        if (state == STATE_ANSWERING && pending_question > 0) {
            size_t bytes = pending_question;          /* Upload blocks; the UI is parked. */

            pending_question = 0;
            if (net_send_ask(mic_data(), bytes)) {
                answer_wait_started_us = now;         /* Start the patience clock. */
                ESP_LOGI(TAG, "question sent, waiting for an answer");
            } else {
                ESP_LOGE(TAG, "could not send the question");
                enter(STATE_READY);
            }
        }

        /* The words arrive before the audio and are shown as soon as they land, so the
         * answer is readable while the speech is still in flight - and remains readable
         * if the speaker is silent, unplugged or drowned out. */
        if (net_take_text(answer_text, sizeof(answer_text))) {
            ESP_LOGI(TAG, "answer text ready");
            if (state != STATE_ANSWERING) {
                enter(STATE_ANSWERING);
            } else {
                render(false);
            }
        }

        /* An answer is played whenever it arrives, in whatever state the device has
         * reached by then - deliberately not only while ANSWERING.
         *
         * Two inferences plus speech synthesis can outrun any timeout worth setting. The
         * device would give up, return to Ready, and the answer would land seconds later
         * in a buffer nothing was reading, while the phone reported a successful send.
         * Silence on one side and success on the other, with nothing linking them.
         *
         * The timeout below now only governs what the screen shows. The answer itself is
         * never discarded for being late. */
        {
            const uint8_t *answer = NULL;
            size_t answer_bytes = 0;

            if (net_take_reply(&answer, &answer_bytes)) {
                ESP_LOGI(TAG, "playing %u bytes of answer", (unsigned)answer_bytes);
                answer_wait_started_us = 0;
                if (state != STATE_ANSWERING) {
                    enter(STATE_ANSWERING);           /* Show what is happening before it sounds. */
                }
                speaker_play(answer, answer_bytes);
                /* The answer stays on screen after the audio finishes. Tap to dismiss -
                 * a spoken answer is gone the moment it ends, and an answer about your
                 * own commitments is exactly the thing you want to read twice. */
                ESP_LOGI(TAG, "answer shown; tap to dismiss");
            }
        }

        if (state == STATE_ANSWERING && answer_wait_started_us > 0 &&
            (now - answer_wait_started_us) > (int64_t)ANSWER_TIMEOUT_S * 1000000) {
            /* Stop showing ANSWERING, but keep waiting: the reply handler above still
             * plays whatever turns up. */
            ESP_LOGW(TAG, "still no answer after %d s; returning to Ready but still listening",
                     ANSWER_TIMEOUT_S);
            answer_wait_started_us = 0;
            enter(STATE_READY);
        }

        vTaskDelay(pdMS_TO_TICKS(POLL_MS));           /* Poll at roughly 50 Hz. */
    }
}
