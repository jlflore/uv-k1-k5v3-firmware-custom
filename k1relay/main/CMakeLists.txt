idf_component_register(SRCS "main.c" "font6x8.c"
                       INCLUDE_DIRS "."
                       REQUIRES nvs_flash bt usb usb_host_cdc_acm led_strip esp_driver_i2c esp_driver_gpio esp_timer)
