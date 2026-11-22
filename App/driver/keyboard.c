#include <string.h>
/* Copyright 2025 muzkr https://github.com/muzkr
 * Copyright 2023 Manuel Jinger
 * Copyright 2023 Dual Tachyon
 * https://github.com/DualTachyon
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 */

#include "driver/gpio.h"
#include "driver/keyboard.h"
#include "driver/systick.h"
#include "driver/i2c.h"
#include "misc.h"

KEY_Code_t gKeyReading0     = KEY_INVALID;
KEY_Code_t gKeyReading1     = KEY_INVALID;
uint16_t   gDebounceCounter = 0;
bool       gWasFKeyPressed  = false;

#ifdef ENABLE_FEAT_F4HWN_K5VIEWER
#include "k5viewer.h"

// Short press: hold key for SERIAL_KEY_SHORT_POLLS calls.
// Must exceed key_debounce_10ms (2) to trigger ProcessKey(key, true, false).
#define SERIAL_KEY_SHORT_POLLS  5

// Long press: hold key for SERIAL_KEY_LONG_POLLS calls.
// Must exceed key_repeat_delay_10ms (40) to trigger ProcessKey(key, true, true).
#define SERIAL_KEY_LONG_POLLS   45

// Packet types for serial key injection (K5Viewer → radio)
#define SERIAL_KEY_TYPE         0x03
#define SERIAL_KEY_TYPE_LONG    0x04
#define SERIAL_GPS_TYPE         0x07
// K1 Remote — PTT à distance : AA 55 06 v (v ^ 0xA5), v = 1 maintenu, 0 relâché.
// Le téléphone répète « maintenu » toutes les 200 ms ; sans nouvelle pendant
// REMOTE_PTT_IDLE_10MS, ou au bout de REMOTE_PTT_MAX_10MS, l'émission s'arrête.
#define SERIAL_PTT_TYPE         0x06
// K1 Remote — commande directe : AA 55 08 len data[len] chk, chk = 08 ^ len ^ data…
#define SERIAL_CMD_TYPE         0x08
#define REMOTE_PTT_IDLE_10MS    90      // 0,9 s
#define REMOTE_PTT_MAX_10MS     3000    // 30 s
#ifdef ENABLE_FEAT_F4HWN_RXTX_LOG_K5VIEWER
#define SERIAL_FEATURE_TYPE     0x05
#endif

volatile KEY_Code_t gKeyFromSerial      = KEY_INVALID;
#ifdef ENABLE_FEAT_F4HWN_RXTX_LOG_K5VIEWER
volatile uint8_t    gSerialViewerFeatures = 0;
#endif
static   uint8_t    gSerialKeyHoldCount = 0;
volatile bool       gRemotePttActive    = false;
static volatile uint16_t gRemotePttIdle = 0;
static volatile uint8_t  gSerialQuiet10ms = 0;
static uint8_t           gCmdBuf[9];
static volatile uint8_t  gCmdLen = 0;        // > 0 : commande prête

bool KEYBOARD_SerialQuiet(void) { return gSerialQuiet10ms > 0 || gKeyFromSerial != KEY_INVALID; }

bool KEYBOARD_TakeCommand(uint8_t *buf, uint8_t *len)
{
    if (!gCmdLen) return false;
    *len = gCmdLen;
    memcpy(buf, gCmdBuf, gCmdLen);
    gCmdLen = 0;
    return true;
}
static volatile uint16_t gRemotePttHeld = 0;
// Après les 30 s maximum, les « maintenu » suivants sont ignorés jusqu'à un relâchement
// explicite : un téléphone qui continue d'envoyer « maintenu » ne relance pas l'émission.
static volatile bool gRemotePttExpired = false;

void KEYBOARD_RemotePttTick10ms(void)
{
    if (gSerialQuiet10ms) gSerialQuiet10ms--;
    if (!gRemotePttActive)
        return;
    // En mode « une pression » (SetPTT), app.c traite quand même le PTT à distance
    // en « maintenir pour émettre ».
    if (++gRemotePttHeld > REMOTE_PTT_MAX_10MS)
    {
        gRemotePttActive  = false;
        gRemotePttExpired = true;      // durée maximale : il faudra relâcher avant de réémettre
    }
    else if (++gRemotePttIdle > REMOTE_PTT_IDLE_10MS)
    {
        gRemotePttActive = false;      // plus de nouvelles du téléphone
    }
}

static void KEYBOARD_RemotePtt(uint8_t v)
{
    if (v == 1) {
        if (gRemotePttExpired)
            return;
        if (!gRemotePttActive)
            gRemotePttHeld = 0;
        gRemotePttIdle = 0;
        gRemotePttActive = true;
    } else {
        gRemotePttActive  = false;
        gRemotePttExpired = false;
    }
}
static   uint8_t    gSerialKeyLong      = 0;  // 0 = short press, 1 = long press

// Inject a short or long press from serial (UART or VCP).
// KEY_PTT is explicitly blocked — PTT release cannot be guaranteed over serial.
static inline bool KEYBOARD_InjectKey(uint8_t keyCode, bool keyLong)
{
    if (keyCode < KEY_INVALID && keyCode != KEY_PTT) {
        gKeyFromSerial      = (KEY_Code_t)keyCode;
        gSerialQuiet10ms    = 120;     // touche du téléphone : pas de bip (appui et relâchement)
        gSerialKeyHoldCount = 0;
        gSerialKeyLong      = keyLong;
        return true;
    }
    return false;
}

static int32_t KEYBOARD_ReadI32Le(const uint8_t *bytes)
{
    const uint32_t value = (uint32_t)bytes[0] |
                           ((uint32_t)bytes[1] << 8) |
                           ((uint32_t)bytes[2] << 16) |
                           ((uint32_t)bytes[3] << 24);
    return (int32_t)value;
}

void KEYBOARD_ResetProtocolParser(ParseState_t *state)
{
    state->state = STATE_IDLE;
    state->payload_pos = 0;
}

bool KEYBOARD_ProcessProtocolByte(ParseState_t *state, uint8_t b)
{
    bool connected = false;

    switch (state->state)
    {
        case STATE_IDLE:
            if      (b == 0x55) state->state = STATE_KA_1;
            else if (b == 0xAA) state->state = STATE_KEY_1;
            break;
            
        case STATE_KA_1:
            state->state = (b == 0xAA) ? STATE_KA_2 : STATE_IDLE;
            break;
            
        case STATE_KA_2:
#ifdef ENABLE_FEAT_F4HWN_RXTX_LOG_K5VIEWER
            if (b == 0x00)
                state->state = STATE_KA_3;
            else if (b == SERIAL_FEATURE_TYPE)
                state->state = STATE_KA_FEATURE;
            else
                state->state = STATE_IDLE;
#else
            state->state = (b == 0x00) ? STATE_KA_3 : STATE_IDLE;
#endif
            break;

        case STATE_KA_3:
            if (b == 0x00) {
#ifdef ENABLE_FEAT_F4HWN_RXTX_LOG_K5VIEWER
                gSerialViewerFeatures = 0;
#endif
                connected = true;
            }
            state->state = STATE_IDLE;
            break;

#ifdef ENABLE_FEAT_F4HWN_RXTX_LOG_K5VIEWER
        case STATE_KA_FEATURE:
            gSerialViewerFeatures = b;
            connected = true;
            state->state = STATE_IDLE;
            break;
#endif
            
        case STATE_KEY_1:
            state->state = (b == 0x55) ? STATE_KEY_2 : STATE_IDLE;
            break;
            
        case STATE_KEY_2:
            if (b == SERIAL_KEY_TYPE) {
                state->state = STATE_KEY_3;
            } else if (b == SERIAL_KEY_TYPE_LONG) {
                state->state = STATE_KEY_3L;
            } else if (b == SERIAL_CMD_TYPE) {
                state->state = STATE_CMD_LEN;
            } else if (b == SERIAL_PTT_TYPE) {
                state->state = STATE_PTT_1;
            } else if (b == SERIAL_GPS_TYPE) {
                state->state = STATE_GPS;
                state->payload_pos = 0;
            } else {
                state->state = STATE_IDLE;
            }
            break;
            
        case STATE_KEY_3:
        case STATE_KEY_3L:
            // Seule une touche acceptée compte comme activité de l'appli
            connected = KEYBOARD_InjectKey(b, state->state == STATE_KEY_3L);
            state->state = STATE_IDLE;
            break;

        case STATE_PTT_1:
            state->payload[0] = b;
            state->state = STATE_PTT_2;
            break;

        case STATE_PTT_2:
            if (b == (uint8_t)(state->payload[0] ^ 0xA5u) && state->payload[0] <= 1u) {
                KEYBOARD_RemotePtt(state->payload[0]);
                connected = true;
            } else {
                gRemotePttActive = false;       // trame douteuse : sécurité d'abord
            }
            KEYBOARD_ResetProtocolParser(state);
            break;

        case STATE_CMD_LEN:
            if (b == 0 || b > sizeof(state->payload)) { KEYBOARD_ResetProtocolParser(state); break; }
            state->payload_pos = 0;
            state->payload[sizeof(state->payload) - 1u] = b;   // longueur gardée à la fin
            state->len = b;
            state->state = STATE_CMD_DATA;
            break;

        case STATE_CMD_DATA:
            state->payload[state->payload_pos++] = b;
            if (state->payload_pos >= state->len) state->state = STATE_CMD_CHK;
            break;

        case STATE_CMD_CHK: {
            uint8_t chk = SERIAL_CMD_TYPE ^ state->len;
            for (uint8_t i = 0; i < state->len; i++) chk ^= state->payload[i];
            if (chk == b && gCmdLen == 0) {
                memcpy(gCmdBuf, state->payload, state->len);
                gCmdLen = state->len;
                connected = true;
            }
            KEYBOARD_ResetProtocolParser(state);
            break;
        }

        case STATE_GPS:
            state->payload[state->payload_pos++] = b;
            if (state->payload_pos == sizeof(state->payload)) {
                uint8_t checksum = SERIAL_GPS_TYPE;
                for (uint8_t i = 0; i < sizeof(state->payload) - 1u; i++)
                    checksum ^= state->payload[i];
                if (checksum == state->payload[sizeof(state->payload) - 1u]) {
                    connected = K5VIEWER_SetGps(
                        KEYBOARD_ReadI32Le(state->payload),
                        KEYBOARD_ReadI32Le(state->payload + 4));
                }
                KEYBOARD_ResetProtocolParser(state);
            }
            break;

        default:
            KEYBOARD_ResetProtocolParser(state);
            break;
    }

    return connected;
}
#endif

#define GPIOx               GPIOB
#define PIN_MASK_COLS       (LL_GPIO_PIN_6 | LL_GPIO_PIN_5 | LL_GPIO_PIN_4 | LL_GPIO_PIN_3)
#define PIN_COLS            GPIO_MAKE_PIN(GPIOx, PIN_MASK_COLS)
#define PIN_COL(n)          GPIO_MAKE_PIN(GPIOx, 1u << (6 - (n)))

#define PIN_MASK_ROWS       (LL_GPIO_PIN_15 | LL_GPIO_PIN_14 | LL_GPIO_PIN_13 | LL_GPIO_PIN_12)
#define PIN_MASK_ROW(n)     (1u << (15 - (n)))

static inline uint32_t read_rows()
{
    return PIN_MASK_ROWS & LL_GPIO_ReadInputPort(GPIOx);
}

static const KEY_Code_t keyboard[5][4] = {
    {   // Zero col
        // Set to zero to handle special case of nothing pulled down
        KEY_SIDE1, 
        KEY_SIDE2, 

        // Duplicate to fill the array with valid values
        KEY_INVALID, 
        KEY_INVALID, 
    },
    {   // First col
        KEY_MENU, 
        KEY_1, 
        KEY_4, 
        KEY_7, 
    },
    {   // Second col
        KEY_UP, 
        KEY_2 , 
        KEY_5 , 
        KEY_8 , 
    },
    {   // Third col
        KEY_DOWN, 
        KEY_3   , 
        KEY_6   , 
        KEY_9   , 
    },
    {   // Fourth col
        KEY_EXIT, 
        KEY_STAR, 
        KEY_0   , 
        KEY_F   , 
    }
};

KEY_Code_t KEYBOARD_Poll(void)
{
#ifdef ENABLE_FEAT_F4HWN_K5VIEWER
    // Serial-injected key: hold it for SHORT or LONG polls depending on press type,
    // so the debounce counter in app.c reaches the right threshold:
    //   - Short: key_debounce_10ms (2)  → ProcessKey(key, true, false)
    //   - Long:  key_repeat_delay_10ms (40) → ProcessKey(key, true, true)
    // Once the hold count is exhausted we clear it — next call returns KEY_INVALID,
    // which triggers the release path in app.c naturally.
    if (gKeyFromSerial != KEY_INVALID) {
        KEY_Code_t injected  = gKeyFromSerial;
        uint8_t    threshold = gSerialKeyLong ? SERIAL_KEY_LONG_POLLS : SERIAL_KEY_SHORT_POLLS;
        if (++gSerialKeyHoldCount >= threshold) {
            gKeyFromSerial      = KEY_INVALID;
            gSerialKeyHoldCount = 0;
            gSerialKeyLong      = 0;
        }
        return injected;
    }
#endif

    return KEYBOARD_PollHardware();
}

// Clavier physique seul (K1 Remote : la sortie de secours du furtif profond ne doit
// pas pouvoir être masquée par des touches envoyées par le téléphone).
KEY_Code_t KEYBOARD_PollHardware(void)
{
    KEY_Code_t Key = KEY_INVALID;

    // Scan all 5 columns - never break early to avoid GPIO state issues
    for (unsigned int j = 0; j < 5; j++)
    {
        uint32_t reg;
        uint32_t match_count = 0;  // Count consecutive matching reads

        // Set all columns high first
        GPIO_SetOutputPin(PIN_COLS);

        // Clear the specific column we are selecting
        if (j > 0)
        {
            GPIO_ResetOutputPin(PIN_COL(j - 1));
        }

        // Debounce: Read rows multiple times and look for stable reads
        // CRITICAL FIX #1: Proper debounce logic (replaces confusing i *= syntax)
        // CRITICAL FIX #2: Increased delay from 1µs to 10µs for real keyboard bounce capture
        // CRITICAL FIX #3: Clear match_count if reads differ (noise detection)
        reg = 0;
        for (unsigned int k = 0; k < 8; k++)
        {
            SYSTICK_DelayUs(10);  // FIX #2: Increased from 1µs to 10µs
            uint32_t reg2 = read_rows();
            
            // FIX #3: Clear match counter if values don't match
            if (reg2 != reg)
            {
                match_count = 0;
                reg = reg2;
            }
            else
            {
                match_count++;
            }
            
            // Success: We have 3 consecutive matching reads = stable signal
            if (match_count >= 2)
            {
                break;  // Debounce complete for this column
            }
        }

        // FIX #1: Do NOT break on noise - continue scanning all columns
        // Only skip key detection if debounce failed, but GPIO state is cleaned
        if (match_count < 2)
        {
            // Debounce failed (too much noise), but continue to next column
            // This prevents GPIO from staying in invalid state and blocking other columns
            continue;
        }

        // Debounce successful, check which row is pressed in this column
        for (unsigned int i = 0; i < 4; i++)
        {
            if (!(reg & PIN_MASK_ROW(i)))
            {
                Key = keyboard[j][i];
                break;
            }
        }

        if (Key != KEY_INVALID)
        {
            break;  // Found a valid key, stop scanning
        }
    }

    // CRITICAL FIX #4: Always clean up GPIO state - set all columns high at end
    // This ensures GPIO pins are in a known state even if function exited early due to noise
    GPIO_SetOutputPin(PIN_COLS);

    return Key;
}

KEY_Code_t KEYBOARD_GetKey(void)
{
    KEY_Code_t btn = KEYBOARD_Poll();
    if (btn == KEY_INVALID && GPIO_IsPttPressed())
    {
        btn = KEY_PTT;
    }
    return btn;
}

void HideFKeyIcon(void) {
    gWasFKeyPressed       = false;
    gUpdateStatus         = true;
}
