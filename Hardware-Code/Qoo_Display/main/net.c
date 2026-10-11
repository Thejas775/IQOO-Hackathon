/* WiFi link to the phone. See net.h for the protocol. */
#include "net.h"

#include <stdlib.h>
#include <string.h>
#include "esp_event.h"
#include "esp_heap_caps.h"
#include "esp_log.h"
#include "esp_netif.h"
#include "esp_wifi.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "lwip/sockets.h"
#include "nvs_flash.h"
#include "wifi_credentials.h"

/** Tag used by ESP-IDF logs from the network layer. */
static const char *TAG = "net";
/** Port the device broadcasts discovery announcements to. */
#define ANNOUNCE_PORT 5151
/** Port the device listens on for the phone. */
#define PAIR_PORT 5152
/** Seconds between discovery broadcasts. */
#define ANNOUNCE_INTERVAL_MS 1000

/** Whether WiFi has an IP. */
static volatile bool online = false;
/** Whether a phone has sent the right code and is still connected. */
static volatile bool paired = false;
/** The connected phone's socket, or -1. */
static volatile int client_socket = -1;
/** The code the phone must present. */
static char expected_code[8] = "";
/** Current IPv4 address as text. */
static char ip_text[16] = "...";
/** Longest spoken answer the device will accept: 30 s of 16 kHz mono PCM. */
#define REPLY_CAPACITY (30 * 16000 * 2)
/** Buffer holding the most recent answer from the phone. PSRAM: ~1 MB. */
static uint8_t *reply_buffer = NULL;
/** Size of the answer in reply_buffer. */
static volatile size_t reply_length = 0;
/** Whether an unplayed answer is waiting. */
static volatile bool reply_ready = false;
/** Longest answer text the device will keep. */
#define TEXT_CAPACITY 512
/** The answer in words, so it can be read when the speaker cannot be heard. */
static char reply_text[TEXT_CAPACITY] = "";
/** Whether unread answer text is waiting. */
static volatile bool text_ready = false;

/**
 * Function: on_wifi_event
 * Description: Drives reconnection and tracks whether an address has been assigned.
 * Parameters: arg, base, id, data - Standard ESP-IDF event parameters.
 * Return Value: None.
 */
static void on_wifi_event(void *arg, esp_event_base_t base, int32_t id, void *data)
{
    (void)arg;
    if (base == WIFI_EVENT && id == WIFI_EVENT_STA_START) {
        esp_wifi_connect();
    } else if (base == WIFI_EVENT && id == WIFI_EVENT_STA_DISCONNECTED) {
        wifi_event_sta_disconnected_t *event = (wifi_event_sta_disconnected_t *)data;

        online = false;
        strcpy(ip_text, "...");
        // Logged every time, with the reason code. Retrying quietly is what made this
        // look like the radio had simply stopped: reason 201 (no AP found) and reason
        // 15 (handshake timeout, i.e. wrong password) are completely different problems
        // and are indistinguishable from the outside without this line.
        ESP_LOGW(TAG, "disconnected from %s, reason %d%s", WIFI_SSID, event->reason,
                 event->reason == 201 ? " (no AP found - is the network up?)" :
                 event->reason == 15 ? " (handshake failed - wrong password?)" : "");
        // Retry forever rather than giving up after N attempts: this is an appliance
        // with no way to re-enter credentials, and the network may simply be slow to
        // appear - a phone hotspot often is not up yet when the device boots.
        vTaskDelay(pdMS_TO_TICKS(2000));
        esp_wifi_connect();
    } else if (base == IP_EVENT && id == IP_EVENT_STA_GOT_IP) {
        ip_event_got_ip_t *event = (ip_event_got_ip_t *)data;

        snprintf(ip_text, sizeof(ip_text), IPSTR, IP2STR(&event->ip_info.ip));
        online = true;
        ESP_LOGI(TAG, "online at %s", ip_text);
    }
}

/**
 * Function: announce_loop
 * Description: Broadcasts "QOO <code> <ip>" once a second while unpaired, so the phone
 *              can find the device by the code shown on its screen.
 * Parameters: arg - Unused.
 * Return Value: None.
 */
static void announce_loop(void *arg)
{
    int sock = -1;                                    /* UDP broadcast socket. */
    int broadcast = 1;                                /* Option value for SO_BROADCAST. */
    struct sockaddr_in to = {0};                      /* Broadcast destination. */
    char message[64];                                 /* Announcement text. */

    (void)arg;
    sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (sock < 0) {
        ESP_LOGE(TAG, "could not open the announce socket");
        vTaskDelete(NULL);
        return;
    }
    setsockopt(sock, SOL_SOCKET, SO_BROADCAST, &broadcast, sizeof(broadcast));
    to.sin_family = AF_INET;
    to.sin_port = htons(ANNOUNCE_PORT);
    to.sin_addr.s_addr = htonl(INADDR_BROADCAST);

    while (true) {
        if (online && !paired) {                      /* Silent once paired; nothing is listening. */
            int n = snprintf(message, sizeof(message), "QOO %s %s", expected_code, ip_text);

            sendto(sock, message, n, 0, (struct sockaddr *)&to, sizeof(to));
        }
        vTaskDelay(pdMS_TO_TICKS(ANNOUNCE_INTERVAL_MS));
    }
}

/**
 * Function: read_line
 * Description: Reads bytes up to a newline, or until the buffer is full.
 * Parameters: sock - Socket; out - Destination; max - Buffer size.
 * Return Value: Number of bytes read, or -1 on error or disconnect.
 */
static int read_line(int sock, char *out, size_t max)
{
    size_t used = 0;                                  /* Bytes written to out. */

    while (used + 1 < max) {
        char c;                                       /* One byte from the socket. */
        int n = recv(sock, &c, 1, 0);

        if (n <= 0) {
            return -1;
        }
        if (c == '\n') {
            break;
        }
        if (c != '\r') {
            out[used++] = c;
        }
    }
    out[used] = '\0';
    return (int)used;
}

/**
 * Function: pair_server_loop
 * Description: Accepts one phone at a time and checks the code it presents.
 * Parameters: arg - Unused.
 * Return Value: None.
 */
static void pair_server_loop(void *arg)
{
    int listener = -1;                                /* Listening socket. */
    int reuse = 1;                                    /* Option value for SO_REUSEADDR. */
    struct sockaddr_in bind_address = {0};            /* Address to bind to. */

    (void)arg;
    listener = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (listener < 0) {
        ESP_LOGE(TAG, "could not open the listen socket");
        vTaskDelete(NULL);
        return;
    }
    setsockopt(listener, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));
    bind_address.sin_family = AF_INET;
    bind_address.sin_port = htons(PAIR_PORT);
    bind_address.sin_addr.s_addr = htonl(INADDR_ANY);
    if (bind(listener, (struct sockaddr *)&bind_address, sizeof(bind_address)) != 0 ||
        listen(listener, 1) != 0) {
        ESP_LOGE(TAG, "could not listen on %d", PAIR_PORT);
        close(listener);
        vTaskDelete(NULL);
        return;
    }
    ESP_LOGI(TAG, "listening on %d", PAIR_PORT);

    while (true) {
        struct sockaddr_in from = {0};                /* Connecting peer. */
        socklen_t from_length = sizeof(from);
        int sock = accept(listener, (struct sockaddr *)&from, &from_length);
        char line[64];                                /* First line the phone sends. */

        if (sock < 0) {
            vTaskDelay(pdMS_TO_TICKS(100));
            continue;
        }
        if (read_line(sock, line, sizeof(line)) < 0 ||
            strncmp(line, "PAIR ", 5) != 0 ||
            strcmp(line + 5, expected_code) != 0) {
            // Wrong code, or junk. Say so and drop it - this is the whole value of the
            // code, so a mismatch must not be allowed to proceed quietly.
            ESP_LOGW(TAG, "rejected a connection: %s", line);
            send(sock, "NO\n", 3, 0);
            close(sock);
            continue;
        }

        send(sock, "OK\n", 3, 0);
        client_socket = sock;
        paired = true;
        ESP_LOGI(TAG, "paired with a phone");

        // Read the phone's side of the conversation until the link drops. Only one
        // verb comes back today - the spoken answer to a question.
        while (true) {
            char incoming[64];                        /* One line from the phone. */

            if (read_line(sock, incoming, sizeof(incoming)) < 0) {
                break;                                /* Disconnected. */
            }
            if (strncmp(incoming, "TEXT ", 5) == 0) {
                /* The same answer in words. Sent before the audio so the screen can
                 * show it while the speech is still arriving - and so a silent or
                 * disconnected speaker costs the answer nothing. */
                int want = atoi(incoming + 5);

                if (want <= 0 || want >= TEXT_CAPACITY) {
                    ESP_LOGW(TAG, "implausible text length %d", want);
                    break;
                }
                int got = 0;
                while (got < want) {
                    int n = recv(sock, reply_text + got, want - got, 0);

                    if (n <= 0) {
                        break;
                    }
                    got += n;
                }
                if (got != want) {
                    break;
                }
                reply_text[got] = ' ';
                text_ready = true;
                ESP_LOGI(TAG, "answer text: %s", reply_text);
                continue;
            }
            if (strncmp(incoming, "REPLY ", 6) != 0) {
                continue;                             /* Ignore anything unrecognised. */
            }

            int expected = atoi(incoming + 6);        /* Payload length in bytes. */
            if (reply_buffer == NULL) {
                ESP_LOGW(TAG, "answer arrived but there is nowhere to put it");
                break;
            }
            if (expected <= 0 || expected > REPLY_CAPACITY) {
                /* Refuse rather than read: a desynchronised stream would otherwise
                 * have us consume an arbitrary number of bytes as if they were audio. */
                ESP_LOGW(TAG, "implausible reply length %d", expected);
                break;
            }

            int got = 0;                              /* Bytes of the answer received. */
            while (got < expected) {
                int n = recv(sock, reply_buffer + got, expected - got, 0);

                if (n <= 0) {
                    break;                            /* Link died mid-answer. */
                }
                got += n;
            }
            if (got != expected) {
                break;
            }
            reply_length = (size_t)got;
            reply_ready = true;                       /* Published last, so the main loop
                                                       * never sees a half-filled buffer. */
            ESP_LOGI(TAG, "answer received, %d bytes", got);
        }
        ESP_LOGW(TAG, "phone disconnected");
        paired = false;
        client_socket = -1;
        close(sock);
    }
}

void net_start(const char *code)
{
    wifi_init_config_t init_config = WIFI_INIT_CONFIG_DEFAULT();
    wifi_config_t sta_config = {0};
    esp_err_t nvs = nvs_flash_init();

    strncpy(expected_code, code, sizeof(expected_code) - 1);

    reply_buffer = heap_caps_malloc(REPLY_CAPACITY, MALLOC_CAP_SPIRAM);
    if (reply_buffer == NULL) {
        ESP_LOGE(TAG, "no PSRAM for the reply buffer; answers cannot be played");
    }

    if (nvs == ESP_ERR_NVS_NO_FREE_PAGES || nvs == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());           /* WiFi stores calibration in NVS. */
        nvs = nvs_flash_init();
    }
    ESP_ERROR_CHECK(nvs);

    ESP_ERROR_CHECK(esp_netif_init());
    ESP_ERROR_CHECK(esp_event_loop_create_default());
    esp_netif_create_default_wifi_sta();
    ESP_ERROR_CHECK(esp_wifi_init(&init_config));
    ESP_ERROR_CHECK(esp_event_handler_instance_register(WIFI_EVENT, ESP_EVENT_ANY_ID,
                                                        on_wifi_event, NULL, NULL));
    ESP_ERROR_CHECK(esp_event_handler_instance_register(IP_EVENT, IP_EVENT_STA_GOT_IP,
                                                        on_wifi_event, NULL, NULL));

    strncpy((char *)sta_config.sta.ssid, WIFI_SSID, sizeof(sta_config.sta.ssid) - 1);
    strncpy((char *)sta_config.sta.password, WIFI_PASSWORD, sizeof(sta_config.sta.password) - 1);
    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_STA));
    ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_STA, &sta_config));
    ESP_ERROR_CHECK(esp_wifi_start());
    ESP_LOGI(TAG, "joining %s", WIFI_SSID);

    xTaskCreate(announce_loop, "announce", 3072, NULL, 4, NULL);
    xTaskCreate(pair_server_loop, "pair", 4096, NULL, 4, NULL);
}

bool net_is_online(void) { return online; }

bool net_is_paired(void) { return paired; }

const char *net_ip(void) { return ip_text; }

/**
 * Function: send_payload
 * Description: Sends one verb and its PCM payload to the paired phone.
 * Parameters: verb - "AUDIO" or "ASK"; pcm - PCM bytes; length - How many.
 * Return Value: true if everything was written.
 */
static bool send_payload(const char *verb, const uint8_t *pcm, size_t length)
{
    int sock = client_socket;                         /* Snapshot: the server task may clear it. */
    char header[48];                                  /* "<VERB> <bytes>\n". */
    size_t sent = 0;                                  /* Bytes of PCM written so far. */
    int header_length;                                /* Length of the header line. */

    if (sock < 0 || !paired || pcm == NULL || length == 0) {
        return false;
    }
    header_length = snprintf(header, sizeof(header), "%s %u\n", verb, (unsigned)length);
    if (send(sock, header, header_length, 0) != header_length) {
        return false;
    }

    // Written in chunks rather than one call: lwip's send will happily return a short
    // count on a multi-megabyte buffer, and treating that as failure would abandon a
    // recording that was mostly delivered.
    while (sent < length) {
        size_t chunk = length - sent;

        if (chunk > 4096) {
            chunk = 4096;
        }
        int n = send(sock, pcm + sent, chunk, 0);
        if (n <= 0) {
            ESP_LOGE(TAG, "send failed after %u of %u bytes", (unsigned)sent, (unsigned)length);
            return false;
        }
        sent += (size_t)n;
    }
    ESP_LOGI(TAG, "sent %s, %u bytes", verb, (unsigned)sent);
    return true;
}

bool net_send_audio(const uint8_t *pcm, size_t length)
{
    return send_payload("AUDIO", pcm, length);        /* Something to remember. */
}

bool net_send_ask(const uint8_t *pcm, size_t length)
{
    reply_ready = false;                              /* Drop any stale answer before asking
                                                       * again, so a late arrival cannot be
                                                       * mistaken for the reply to this one. */
    text_ready = false;
    return send_payload("ASK", pcm, length);          /* A question expecting an answer. */
}

bool net_take_text(char *out, size_t max)
{
    if (!text_ready || out == NULL || max == 0) {
        return false;
    }
    text_ready = false;                               /* Consumed. */
    strncpy(out, reply_text, max - 1);
    out[max - 1] = ' ';
    return true;
}

bool net_take_reply(const uint8_t **data, size_t *length)
{
    if (!reply_ready) {
        return false;
    }
    reply_ready = false;                              /* Consumed. */
    *data = reply_buffer;
    *length = reply_length;
    return true;
}
