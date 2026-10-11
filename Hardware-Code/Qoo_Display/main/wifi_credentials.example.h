/* Template. Copy to wifi_credentials.h and fill in the network the phone is on.
 *
 * The device and the phone must be on the same network, and that network must not have
 * client isolation enabled - many venue networks do, which blocks the device and the
 * phone from seeing each other at all. A phone hotspot never has this problem and is
 * the safer choice on demo day.
 */
#pragma once

#define WIFI_SSID "your-network"
#define WIFI_PASSWORD "your-password"
