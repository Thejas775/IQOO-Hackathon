idf_component_register(
    SRCS "main.c" "display.c" "touch.c" "button.c" "mic.c" "speaker.c" "net.c"
         "qoo_frames.c" "qoo_ui.c"
    INCLUDE_DIRS "."
    REQUIRES driver esp_lcd esp_timer esp_hw_support esp_wifi esp_netif esp_event nvs_flash lwip
)
