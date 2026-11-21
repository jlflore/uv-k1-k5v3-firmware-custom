/*
 * K1 Relais — passerelle USB (Quansheng UV-K1 / F4HWN) <-> Bluetooth LE
 * pour l'application K1 Remote.
 *
 * Carte : ESP32-S3 DevKitC-1 N16R8 (clone YD, deux USB-C).
 *   - port « COM » (CH343) : alimentation par batterie externe + programmation
 *   - port « USB » (USB natif de l'ESP32-S3) : la radio K1
 *
 * Bluetooth : service type « Nordic UART » (NUS)
 *   6E400001-B5A3-F393-E0A9-E50E24DCCA9E  service
 *   6E400002-...  RX  (téléphone -> radio) : write / write without response
 *   6E400003-...  TX  (radio -> téléphone) : notify
 *   6E400004-...  état (read / notify) : 1 octet, bit0 = radio branchée
 *
 *   6E400005-...  commande (write, liaison chiffrée) : [0x01, n] intensité LED
 *                 0 = éteinte, 1 = faible, 2 = normale (mémorisée)
 *                 [0x02, m] veille après m minutes sans radio (0 = jamais, mémorisé)
 *
 * Veille : sans radio pendant le délai choisi (10 min par défaut), le boîtier
 * coupe le Bluetooth et s'endort profondément. Il se réveille tout seul quand on
 * allume ou branche la radio (sa ligne USB D+ remonte), ou par un appui sur BOOT.
 *
 * LED RGB (GPIO48) : éclat rouge = en attente, bleu = téléphone sans radio,
 *                    orange = radio sans téléphone, vert = tout est prêt,
 *                    violet clignotant = appairage ouvert.
 *
 * Sécurité : liaison chiffrée (LE Secure Connections) et appairée.
 * Un nouveau téléphone ne peut s'appairer que si aucun n'est encore
 * enregistré, ou pendant 2 minutes après un appui sur BOOT.
 * Aucune donnée ne circule tant que la liaison n'est pas chiffrée.
 * Écran OLED SSD1306 I2C facultatif : SDA = GPIO8, SCL = GPIO9.
 */

#include <stdio.h>
#include <string.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/semphr.h"
#include "freertos/stream_buffer.h"
#include "esp_log.h"
#include "nvs_flash.h"
#include "usb/usb_host.h"
#include "usb/cdc_acm_host.h"
#include "led_strip.h"
#include "driver/i2c_master.h"
#include "driver/gpio.h"
#include "nvs.h"
#include "esp_timer.h"
#include "esp_sleep.h"
#include "driver/rtc_io.h"

#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "host/ble_hs.h"
#include "host/util/util.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"
#include "host/ble_store.h"

void ble_store_config_init(void);

#define FW_VERSION      "1.2"
#define DEVICE_NAME     "K1 Relais"
#define LED_GPIO        48
#define OLED_SDA        8
#define OLED_SCL        9
#define OLED_ADDR       0x3C
#define BOOT_GPIO       0
#define PAIR_WINDOW_US  (120LL * 1000 * 1000)

static const char *TAG = "k1relais";

/* ---------- état partagé ---------- */
static volatile bool s_radio = false;          // radio K1 ouverte en USB
static volatile bool s_phone = false;          // téléphone connecté en BLE
static volatile bool s_notify = false;         // notifications TX activées
static volatile bool s_secure = false;         // liaison chiffrée et autorisée
static volatile uint8_t s_led_level = 1;       // 0 éteinte, 1 faible, 2 normale
static volatile uint8_t s_sleep_min = 10;      // veille après N minutes sans radio (0 = jamais)
static volatile int64_t s_radio_gone_at = 0;   // depuis quand la radio est absente (µs)
static volatile int64_t s_pair_until = 0;      // fin de la fenêtre d'appairage (µs)
static int s_bonds_at_connect = 0;
static volatile uint32_t s_rx_bytes = 0;       // radio -> téléphone
static volatile uint32_t s_tx_bytes = 0;       // téléphone -> radio
static uint16_t s_conn = BLE_HS_CONN_HANDLE_NONE;
static uint16_t s_mtu = 23;
static uint16_t s_tx_handle, s_state_handle;

static cdc_acm_dev_hdl_t s_cdc = NULL;
static SemaphoreHandle_t s_cdc_gone;
static StreamBufferHandle_t s_to_phone;        // radio -> BLE
static StreamBufferHandle_t s_to_radio;        // BLE -> radio

/* =====================================================================
 *  USB hôte : radio K1 (CDC-ACM)
 * ===================================================================== */

static bool usb_rx_cb(const uint8_t *data, size_t len, void *arg)
{
    if (s_phone && s_notify && s_secure) {
        xStreamBufferSend(s_to_phone, data, len, 0);   // jamais bloquant
    }
    s_rx_bytes += len;
    return true;
}

static void usb_event_cb(const cdc_acm_host_dev_event_data_t *ev, void *arg)
{
    switch (ev->type) {
    case CDC_ACM_HOST_DEVICE_DISCONNECTED:
        ESP_LOGI(TAG, "radio débranchée");
        s_radio = false;
        xSemaphoreGive(s_cdc_gone);
        break;
    case CDC_ACM_HOST_ERROR:
        ESP_LOGW(TAG, "erreur USB %d", ev->data.error);
        break;
    default:
        break;
    }
}

static void usb_lib_task(void *arg)
{
    while (1) {
        uint32_t flags;
        usb_host_lib_handle_events(portMAX_DELAY, &flags);
        if (flags & USB_HOST_LIB_EVENT_FLAGS_NO_CLIENTS) {
            usb_host_device_free_all();
        }
    }
}

static void notify_state(void);

static void radio_task(void *arg)
{
    const usb_host_config_t host_cfg = {
        .skip_phy_setup = false,
        .intr_flags = ESP_INTR_FLAG_LEVEL1,
    };
    ESP_ERROR_CHECK(usb_host_install(&host_cfg));
    xTaskCreate(usb_lib_task, "usb_lib", 4096, NULL, 10, NULL);
    ESP_ERROR_CHECK(cdc_acm_host_install(NULL));

    const cdc_acm_host_device_config_t dev_cfg = {
        .connection_timeout_ms = 1000,
        .out_buffer_size = 512,
        .in_buffer_size = 512,
        .event_cb = usb_event_cb,
        .data_cb = usb_rx_cb,
        .user_arg = NULL,
    };

    while (1) {
        cdc_acm_dev_hdl_t h = NULL;
        if (cdc_acm_host_open(CDC_HOST_ANY_VID, CDC_HOST_ANY_PID, 0, &dev_cfg, &h) != ESP_OK) {
            continue;   // pas de radio : on réessaie (le délai d'attente fait la pause)
        }
        ESP_LOGI(TAG, "radio branchée");
        cdc_acm_line_coding_t lc = {
            .dwDTERate = 38400, .bCharFormat = 0, .bParityType = 0, .bDataBits = 8,
        };
        cdc_acm_host_line_coding_set(h, &lc);           // sans effet sur un port virtuel, mais poli
        cdc_acm_host_set_control_line_state(h, true, true);
        xSemaphoreTake(s_cdc_gone, 0);
        s_cdc = h;
        s_radio = true;
        s_radio_gone_at = 0;
        notify_state();

        xSemaphoreTake(s_cdc_gone, portMAX_DELAY);      // attend le débranchement
        s_radio_gone_at = esp_timer_get_time();
        s_cdc = NULL;
        cdc_acm_host_close(h);
        xStreamBufferReset(s_to_radio);
        notify_state();
    }
}

/* Téléphone -> radio */
static void to_radio_task(void *arg)
{
    uint8_t buf[256];
    while (1) {
        size_t n = xStreamBufferReceive(s_to_radio, buf, sizeof buf, portMAX_DELAY);
        cdc_acm_dev_hdl_t h = s_cdc;
        if (n && h && s_radio) {
            if (cdc_acm_host_data_tx_blocking(h, buf, n, 500) == ESP_OK) {
                s_tx_bytes += n;
            }
        }
    }
}

/* =====================================================================
 *  Bluetooth LE (NimBLE)
 * ===================================================================== */

static const ble_uuid128_t UUID_SVC = BLE_UUID128_INIT(
    0x9e, 0xca, 0xdc, 0x24, 0x0e, 0xe5, 0xa9, 0xe0, 0x93, 0xf3, 0xa3, 0xb5, 0x01, 0x00, 0x40, 0x6e);
static const ble_uuid128_t UUID_RX = BLE_UUID128_INIT(
    0x9e, 0xca, 0xdc, 0x24, 0x0e, 0xe5, 0xa9, 0xe0, 0x93, 0xf3, 0xa3, 0xb5, 0x02, 0x00, 0x40, 0x6e);
static const ble_uuid128_t UUID_TX = BLE_UUID128_INIT(
    0x9e, 0xca, 0xdc, 0x24, 0x0e, 0xe5, 0xa9, 0xe0, 0x93, 0xf3, 0xa3, 0xb5, 0x03, 0x00, 0x40, 0x6e);
static const ble_uuid128_t UUID_ST = BLE_UUID128_INIT(
    0x9e, 0xca, 0xdc, 0x24, 0x0e, 0xe5, 0xa9, 0xe0, 0x93, 0xf3, 0xa3, 0xb5, 0x04, 0x00, 0x40, 0x6e);

static const ble_uuid128_t UUID_CTL = BLE_UUID128_INIT(
    0x9e, 0xca, 0xdc, 0x24, 0x0e, 0xe5, 0xa9, 0xe0, 0x93, 0xf3, 0xa3, 0xb5, 0x05, 0x00, 0x40, 0x6e);

static void save_u8(const char *k, uint8_t v)
{
    nvs_handle_t h;
    if (nvs_open("k1relais", NVS_READWRITE, &h) == ESP_OK) {
        nvs_set_u8(h, k, v);
        nvs_commit(h);
        nvs_close(h);
    }
}

static void save_led_level(uint8_t v)
{
    nvs_handle_t h;
    if (nvs_open("k1relais", NVS_READWRITE, &h) == ESP_OK) {
        nvs_set_u8(h, "led", v);
        nvs_commit(h);
        nvs_close(h);
    }
}

static void load_led_level(void)
{
    nvs_handle_t h;
    uint8_t v = 1;
    if (nvs_open("k1relais", NVS_READONLY, &h) == ESP_OK) {
        nvs_get_u8(h, "led", &v);
        uint8_t m = 10;
        if (nvs_get_u8(h, "sleep", &m) == ESP_OK) s_sleep_min = m;
        nvs_close(h);
    }
    s_led_level = v > 2 ? 1 : v;
}

static int bond_count(void)
{
    int n = 0;
    ble_store_util_count(BLE_STORE_OBJ_TYPE_PEER_SEC, &n);
    return n;
}

static bool pairing_open(void)
{
    return s_bonds_at_connect == 0 || esp_timer_get_time() < s_pair_until;
}

static uint8_t state_byte(void) { return s_radio ? 1 : 0; }

static int gatt_access(uint16_t conn, uint16_t attr, struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    const ble_uuid_t *u = ctxt->chr->uuid;
    if (ble_uuid_cmp(u, &UUID_CTL.u) == 0 && ctxt->op == BLE_GATT_ACCESS_OP_WRITE_CHR) {
        uint8_t buf[8];
        uint16_t len = 0;
        if (!s_secure) return BLE_ATT_ERR_INSUFFICIENT_AUTHEN;
        if (ble_hs_mbuf_to_flat(ctxt->om, buf, sizeof buf, &len) == 0 && len >= 2 && buf[0] == 0x01) {
            uint8_t v = buf[1] > 2 ? 2 : buf[1];
            if (v != s_led_level) { s_led_level = v; save_led_level(v); }
        }
        if (len >= 2 && buf[0] == 0x02) {
            uint8_t m = buf[1] > 240 ? 240 : buf[1];
            if (m != s_sleep_min) { s_sleep_min = m; save_u8("sleep", m); }
            s_radio_gone_at = esp_timer_get_time();     // le délai repart de maintenant
        }
        return 0;
    }
    if (ble_uuid_cmp(u, &UUID_RX.u) == 0 && ctxt->op == BLE_GATT_ACCESS_OP_WRITE_CHR) {
        uint8_t buf[256];
        if (!s_secure) return 0;   // liaison pas encore chiffrée : ignoré
        uint16_t len = 0;
        if (ble_hs_mbuf_to_flat(ctxt->om, buf, sizeof buf, &len) == 0 && len) {
            xStreamBufferSend(s_to_radio, buf, len, pdMS_TO_TICKS(50));
        }
        return 0;
    }
    if (ble_uuid_cmp(u, &UUID_ST.u) == 0 && ctxt->op == BLE_GATT_ACCESS_OP_READ_CHR) {
        if (!s_secure) return BLE_ATT_ERR_INSUFFICIENT_AUTHEN;
        char info[24];
        int n = snprintf(info, sizeof info, "%c" FW_VERSION, state_byte());
        return os_mbuf_append(ctxt->om, info, n) == 0 ? 0 : BLE_ATT_ERR_INSUFFICIENT_RES;
    }
    if (ble_uuid_cmp(u, &UUID_TX.u) == 0 && ctxt->op == BLE_GATT_ACCESS_OP_READ_CHR) {
        return 0;
    }
    return BLE_ATT_ERR_UNLIKELY;
}

static const struct ble_gatt_svc_def gatt_svcs[] = {
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &UUID_SVC.u,
        .characteristics = (struct ble_gatt_chr_def[]) {
            { .uuid = &UUID_RX.u, .access_cb = gatt_access,
              .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_NO_RSP },
            { .uuid = &UUID_TX.u, .access_cb = gatt_access,
              .flags = BLE_GATT_CHR_F_NOTIFY, .val_handle = &s_tx_handle },
            { .uuid = &UUID_ST.u, .access_cb = gatt_access,
              .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_NOTIFY, .val_handle = &s_state_handle },
            { .uuid = &UUID_CTL.u, .access_cb = gatt_access, .flags = BLE_GATT_CHR_F_WRITE },
            { 0 }
        },
    },
    { 0 }
};

static void notify_state(void)
{
    if (s_conn == BLE_HS_CONN_HANDLE_NONE || !s_secure) return;
    uint8_t b = state_byte();
    struct os_mbuf *om = ble_hs_mbuf_from_flat(&b, 1);
    if (om) ble_gatts_notify_custom(s_conn, s_state_handle, om);
}

static void advertise(void);

static int gap_event(struct ble_gap_event *ev, void *arg)
{
    switch (ev->type) {
    case BLE_GAP_EVENT_CONNECT:
        if (ev->connect.status == 0) {
            s_conn = ev->connect.conn_handle;
            s_phone = true;
            s_mtu = 23;
            s_secure = false;
            s_bonds_at_connect = bond_count();
            ESP_LOGI(TAG, "téléphone connecté, demande de chiffrement");
            ble_gap_security_initiate(s_conn);
            /* connexion rapide : 7,5 à 15 ms */
            struct ble_gap_upd_params p = {
                .itvl_min = 6, .itvl_max = 12, .latency = 0, .supervision_timeout = 400,
            };
            ble_gap_update_params(s_conn, &p);
        } else {
            advertise();
        }
        break;
    case BLE_GAP_EVENT_DISCONNECT:
        ESP_LOGI(TAG, "téléphone déconnecté");
        s_conn = BLE_HS_CONN_HANDLE_NONE;
        s_phone = false;
        s_notify = false;
        s_secure = false;
        xStreamBufferReset(s_to_phone);
        advertise();
        break;
    case BLE_GAP_EVENT_SUBSCRIBE:
        if (ev->subscribe.attr_handle == s_tx_handle) {
            s_notify = ev->subscribe.cur_notify;
        }
        break;
    case BLE_GAP_EVENT_ENC_CHANGE: {
        struct ble_gap_conn_desc d;
        if (ev->enc_change.status != 0 || ble_gap_conn_find(ev->enc_change.conn_handle, &d) != 0) {
            ESP_LOGW(TAG, "chiffrement refusé (%d)", ev->enc_change.status);
            ble_gap_terminate(ev->enc_change.conn_handle, BLE_ERR_AUTH_FAIL);
            break;
        }
        if (bond_count() > s_bonds_at_connect && !pairing_open()) {
            /* Nouvel appareil hors fenêtre d'appairage : refusé et oublié */
            ESP_LOGW(TAG, "nouvel appareil refusé (appuyez sur BOOT pour appairer)");
            ble_gap_unpair(&d.peer_id_addr);
            ble_gap_terminate(ev->enc_change.conn_handle, BLE_ERR_AUTH_FAIL);
            break;
        }
        s_secure = true;
        s_pair_until = 0;
        ESP_LOGI(TAG, "liaison chiffrée");
        notify_state();
        break;
    }
    case BLE_GAP_EVENT_REPEAT_PAIRING: {
        /* Le téléphone a perdu l'appairage : on accepte de recommencer seulement si la fenêtre est ouverte */
        struct ble_gap_conn_desc d;
        if (ble_gap_conn_find(ev->repeat_pairing.conn_handle, &d) == 0 && pairing_open()) {
            ble_store_util_delete_peer(&d.peer_id_addr);
            return BLE_GAP_REPEAT_PAIRING_RETRY;
        }
        return BLE_GAP_REPEAT_PAIRING_IGNORE;
    }
    case BLE_GAP_EVENT_MTU:
        s_mtu = ev->mtu.value;
        ESP_LOGI(TAG, "MTU %d", s_mtu);
        break;
    case BLE_GAP_EVENT_ADV_COMPLETE:
        advertise();
        break;
    default:
        break;
    }
    return 0;
}

static void advertise(void)
{
    struct ble_hs_adv_fields f = {0};
    f.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
    f.name = (uint8_t *)DEVICE_NAME;
    f.name_len = strlen(DEVICE_NAME);
    f.name_is_complete = 1;
    ble_gap_adv_set_fields(&f);

    struct ble_hs_adv_fields r = {0};
    r.uuids128 = (ble_uuid128_t *)&UUID_SVC;
    r.num_uuids128 = 1;
    r.uuids128_is_complete = 1;
    ble_gap_adv_rsp_set_fields(&r);

    struct ble_gap_adv_params p = {0};
    p.conn_mode = BLE_GAP_CONN_MODE_UND;
    p.disc_mode = BLE_GAP_DISC_MODE_GEN;
    p.itvl_min = 160;   // 100 ms
    p.itvl_max = 320;   // 200 ms
    ble_gap_adv_start(BLE_OWN_ADDR_PUBLIC, NULL, BLE_HS_FOREVER, &p, gap_event, NULL);
}

static void on_sync(void)
{
    ble_hs_util_ensure_addr(0);
    advertise();
}

static void ble_host_task(void *arg)
{
    nimble_port_run();
    nimble_port_freertos_deinit();
}

/* Radio -> téléphone : découpe en notifications de (MTU - 3) octets */
static void to_phone_task(void *arg)
{
    uint8_t buf[244];
    while (1) {
        size_t max = s_mtu > 3 ? s_mtu - 3 : 20;
        if (max > sizeof buf) max = sizeof buf;
        size_t n = xStreamBufferReceive(s_to_phone, buf, max, portMAX_DELAY);
        if (!n || s_conn == BLE_HS_CONN_HANDLE_NONE || !s_notify || !s_secure) continue;
        for (int tries = 0; tries < 50; tries++) {
            struct os_mbuf *om = ble_hs_mbuf_from_flat(buf, n);
            if (!om) { vTaskDelay(pdMS_TO_TICKS(5)); continue; }
            int rc = ble_gatts_notify_custom(s_conn, s_tx_handle, om);
            if (rc == 0) break;
            vTaskDelay(pdMS_TO_TICKS(5));   // file BLE pleine : on patiente
        }
    }
}

/* =====================================================================
 *  LED d'état
 * ===================================================================== */

static void led_task(void *arg)
{
    led_strip_handle_t led = NULL;
    led_strip_config_t sc = { .strip_gpio_num = LED_GPIO, .max_leds = 1 };
    led_strip_rmt_config_t rc = { .resolution_hz = 10 * 1000 * 1000 };
    if (led_strip_new_rmt_device(&sc, &rc, &led) != ESP_OK) vTaskDelete(NULL);

    gpio_config_t io = { .pin_bit_mask = 1ULL << BOOT_GPIO, .mode = GPIO_MODE_INPUT,
                         .pull_up_en = GPIO_PULLUP_ENABLE };
    gpio_config(&io);

    uint32_t t = 0;
    while (1) {
        /* BOOT : ouvre l'appairage d'un nouveau téléphone pendant 2 minutes */
        if (gpio_get_level(BOOT_GPIO) == 0 && esp_timer_get_time() >= s_pair_until) {
            s_pair_until = esp_timer_get_time() + PAIR_WINDOW_US;
            ESP_LOGI(TAG, "appairage ouvert pendant 2 minutes");
        }
        bool pairing = esp_timer_get_time() < s_pair_until;
        bool phone = s_phone && s_secure;
        /* intensité : 0 éteinte, 1 faible (discrète), 2 normale */
        uint8_t k = s_led_level == 2 ? 24 : (s_led_level == 1 ? 3 : 0);
        uint8_t r = 0, g = 0, b = 0;
        if (pairing) {
            if (t % 6 < 3) { r = 20; b = 24; }                 // violet clignotant, toujours visible
        } else if (k) {
            if (phone && s_radio)      { g = k; }               // tout est prêt
            else if (phone)            { b = k; }               // téléphone, radio absente
            else if (s_radio)          { r = k; g = k / 2 ? k / 2 : 1; }   // radio, pas de téléphone
            else if (t % 20 < 2)       { r = k; }               // éclat rouge : en attente
        }
        led_strip_set_pixel(led, 0, r, g, b);
        led_strip_refresh(led);
        t++;
        vTaskDelay(pdMS_TO_TICKS(100));
    }
}

/* =====================================================================
 *  Écran OLED SSD1306 128x64 facultatif
 * ===================================================================== */

extern const uint8_t font6x8[94][6];
static uint8_t fb[8][128];

static void fb_text(int page, int x, const char *s)
{
    for (; *s && x <= 122; s++, x += 6) {
        if (*s > ' ' && *s <= '~') memcpy(&fb[page][x], font6x8[*s - '!'], 6);
    }
}

static i2c_master_dev_handle_t oled;

static esp_err_t oled_cmds(const uint8_t *c, size_t n)
{
    uint8_t buf[32];
    buf[0] = 0x00;
    memcpy(buf + 1, c, n);
    return i2c_master_transmit(oled, buf, n + 1, 50);
}

static void oled_task(void *arg)
{
    i2c_master_bus_handle_t bus;
    i2c_master_bus_config_t bc = {
        .i2c_port = 0, .sda_io_num = OLED_SDA, .scl_io_num = OLED_SCL,
        .clk_source = I2C_CLK_SRC_DEFAULT, .glitch_ignore_cnt = 7,
        .flags.enable_internal_pullup = true,
    };
    if (i2c_new_master_bus(&bc, &bus) != ESP_OK || i2c_master_probe(bus, OLED_ADDR, 50) != ESP_OK) {
        ESP_LOGI(TAG, "pas d'écran OLED");
        vTaskDelete(NULL);
    }
    i2c_device_config_t dc = { .device_address = OLED_ADDR, .scl_speed_hz = 400000 };
    i2c_master_bus_add_device(bus, &dc, &oled);
    static const uint8_t init[] = {
        0xAE, 0xD5, 0x80, 0xA8, 0x3F, 0xD3, 0x00, 0x40, 0x8D, 0x14, 0x20, 0x00,
        0xA1, 0xC8, 0xDA, 0x12, 0x81, 0x8F, 0xD9, 0xF1, 0xDB, 0x40, 0xA4, 0xA6, 0xAF,
    };
    oled_cmds(init, sizeof init);
    ESP_LOGI(TAG, "écran OLED trouvé");

    char line[24];
    while (1) {
        memset(fb, 0, sizeof fb);
        fb_text(0, 0, "K1 RELAIS v" FW_VERSION);
        for (int x = 0; x < 128; x++) fb[1][x] = 0x01;
        snprintf(line, sizeof line, "Radio : %s", s_radio ? "OK" : "--");
        fb_text(2, 0, line);
        snprintf(line, sizeof line, "Tel   : %s", s_phone ? (s_secure ? "OK" : "...") : "--");
        fb_text(3, 0, line);
        snprintf(line, sizeof line, "MTU   : %u", s_mtu);
        fb_text(4, 0, line);
        if (esp_timer_get_time() < s_pair_until) fb_text(5, 0, "APPAIRAGE OUVERT");
        snprintf(line, sizeof line, "K1>tel %lu", (unsigned long)s_rx_bytes);
        fb_text(6, 0, line);
        snprintf(line, sizeof line, "tel>K1 %lu", (unsigned long)s_tx_bytes);
        fb_text(7, 0, line);

        static const uint8_t win[] = { 0x21, 0, 127, 0x22, 0, 7 };
        oled_cmds(win, sizeof win);
        for (int p = 0; p < 8; p++) {
            uint8_t buf[129];
            buf[0] = 0x40;
            memcpy(buf + 1, fb[p], 128);
            i2c_master_transmit(oled, buf, sizeof buf, 50);
        }
        vTaskDelay(pdMS_TO_TICKS(500));
    }
}

/* =====================================================================
 *  Veille profonde sans radio
 * ===================================================================== */

#define USB_DP_GPIO 20      // ligne D+ du port USB natif : la radio la tire vers le haut

static void sleep_task(void *arg)
{
    while (1) {
        vTaskDelay(pdMS_TO_TICKS(5000));
        if (s_radio || s_sleep_min == 0 || s_radio_gone_at == 0) continue;
        if (esp_timer_get_time() < s_pair_until) continue;           // appairage en cours
        if (esp_timer_get_time() - s_radio_gone_at < (int64_t)s_sleep_min * 60 * 1000000) continue;

        ESP_LOGW(TAG, "pas de radio depuis %u min : veille", s_sleep_min);
        if (s_conn != BLE_HS_CONN_HANDLE_NONE) {
            ble_gap_terminate(s_conn, BLE_ERR_REM_USER_CONN_TERM);
            vTaskDelay(pdMS_TO_TICKS(300));
        }
        /* Réveil : D+ à l'état haut (radio allumée/branchée) ou BOOT appuyé */
        rtc_gpio_init(USB_DP_GPIO);
        rtc_gpio_set_direction(USB_DP_GPIO, RTC_GPIO_MODE_INPUT_ONLY);
        rtc_gpio_pullup_dis(USB_DP_GPIO);
        rtc_gpio_pulldown_en(USB_DP_GPIO);
        esp_sleep_enable_ext0_wakeup(USB_DP_GPIO, 1);
        rtc_gpio_pullup_en(BOOT_GPIO);
        rtc_gpio_pulldown_dis(BOOT_GPIO);
        esp_sleep_enable_ext1_wakeup(1ULL << BOOT_GPIO, ESP_EXT1_WAKEUP_ANY_LOW);
        esp_deep_sleep_start();
    }
}

/* ===================================================================== */

void app_main(void)
{
    ESP_LOGW(TAG, "K1 Relais " FW_VERSION " (réveil : %d)", (int)esp_sleep_get_wakeup_cause());
    /* Journal discret : la LED bleue du port COM clignote à chaque message */
    esp_log_level_set("*", ESP_LOG_ERROR);
    esp_log_level_set(TAG, ESP_LOG_WARN);
    esp_log_level_set("HUB", ESP_LOG_NONE);
    /* Au réveil, D+ et BOOT redeviennent des broches normales */
    rtc_gpio_deinit(USB_DP_GPIO);
    rtc_gpio_deinit(BOOT_GPIO);
    esp_err_t e = nvs_flash_init();
    if (e == ESP_ERR_NVS_NO_FREE_PAGES || e == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        nvs_flash_erase();
        nvs_flash_init();
    }

    s_cdc_gone = xSemaphoreCreateBinary();
    s_to_phone = xStreamBufferCreate(8192, 1);
    s_to_radio = xStreamBufferCreate(1024, 1);

    xTaskCreate(led_task, "led", 3072, NULL, 2, NULL);
    xTaskCreate(oled_task, "oled", 4096, NULL, 2, NULL);

    ESP_ERROR_CHECK(nimble_port_init());
    ble_hs_cfg.sync_cb = on_sync;
    ble_hs_cfg.store_status_cb = ble_store_util_status_rr;
    ble_hs_cfg.sm_io_cap = BLE_HS_IO_NO_INPUT_OUTPUT;   // confirmation sur le téléphone
    ble_hs_cfg.sm_bonding = 1;
    ble_hs_cfg.sm_mitm = 0;
    ble_hs_cfg.sm_sc = 1;                               // LE Secure Connections (ECDH)
    ble_hs_cfg.sm_our_key_dist = BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;
    ble_hs_cfg.sm_their_key_dist = BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;
    load_led_level();
    ble_svc_gap_init();
    ble_svc_gatt_init();
    ble_gatts_count_cfg(gatt_svcs);
    ble_gatts_add_svcs(gatt_svcs);
    ble_svc_gap_device_name_set(DEVICE_NAME);
    ble_store_config_init();
    nimble_port_freertos_init(ble_host_task);

    xTaskCreate(to_phone_task, "to_phone", 4096, NULL, 6, NULL);
    xTaskCreate(to_radio_task, "to_radio", 4096, NULL, 6, NULL);
    xTaskCreate(radio_task, "radio", 4096, NULL, 5, NULL);
    s_radio_gone_at = esp_timer_get_time();     // au démarrage : pas encore de radio
    xTaskCreate(sleep_task, "veille", 3072, NULL, 1, NULL);
}
