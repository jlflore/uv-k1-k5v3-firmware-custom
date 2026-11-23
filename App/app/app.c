/* Copyright 2023 Dual Tachyon
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

#include <assert.h>
#include <stdint.h>
#include <string.h>

#include "app/action.h"

#ifdef ENABLE_AIRCOPY
    #include "app/aircopy.h"
#endif
#ifdef ENABLE_FEAT_F4HWN_BEAM
    #include "app/beam.h"
#endif
#include "app/app.h"
#include "app/chFrScanner.h"
#include "app/dtmf.h"
#ifdef ENABLE_FLASHLIGHT
    #include "app/flashlight.h"
#endif
#ifdef ENABLE_FMRADIO_EMBEDDED
    #include "app/fm.h"
#endif
#include "app/generic.h"
#include "app/main.h"
#include "app/menu.h"
#ifdef ENABLE_FEAT_F4HWN_RXTX_LOG
    #include "app/rxtx_log.h"
#endif
#include "app/scanner.h"
#if defined(ENABLE_UART) || defined(ENABLE_USB)
    #include "app/uart.h"
    #include "scheduler.h"
#endif
#include "py32f0xx.h"
#include "audio.h"
#include "board.h"
#ifdef ENABLE_FEAT_F4HWN_SLEEP
    // #include "bsp/dp32g030/pwmplus.h"
#endif
#include "driver/backlight.h"
#ifdef ENABLE_FMRADIO_EMBEDDED
    #include "driver/bk1080.h"
#endif
#include "driver/bk4819.h"
#include "driver/gpio.h"
#include "driver/keyboard.h"
#include "driver/st7565.h"
#include "driver/system.h"
#include "dtmf.h"
#include "external/printf/printf.h"
#include "frequencies.h"
#include "functions.h"
#include "helper/battery.h"
#include "misc.h"
#include "radio.h"
#include "settings.h"
#include "app/k1r_upcode.h"

#if defined(ENABLE_OVERLAY)
    #include "sram-overlay.h"
#endif
#include "ui/battery.h"
#include "ui/helper.h"
#include "ui/inputbox.h"
#include "ui/main.h"
#include "ui/menu.h"
#include "ui/status.h"
#include "ui/ui.h"
#include "ui/welcome.h"

#ifdef ENABLE_FEAT_F4HWN_K5VIEWER
    #include "k5viewer.h"
#endif

#ifdef ENABLE_FEAT_F4HWN_FULL_WATCH
void K1R_DtmfChar(char c);   // K1R : code DTMF reçu, transmis à l'appli (r26)

static void FullWatchPromoteCurrentBackground(void);
#endif

static bool flagSaveVfo;
static bool flagSaveSettings;
static bool flagSaveChannel;

#ifdef ENABLE_FEAT_F4HWN_SLEEP
static KEY_Code_t gSleepWakeKey = KEY_INVALID;
#endif

#ifdef ENABLE_FEAT_F4HWN_LOGO_SAV
static bool gScreenSaverDisplayed;
static uint8_t gScreenSaverTick;
static uint16_t gMatrixRandom = 0xACE1;
static uint8_t gMatrixHeads[32];
static uint8_t gMatrixSpeeds[32];
static KEY_Code_t gScreenSaverWakeKey = KEY_INVALID;
#endif

static void ProcessKey(KEY_Code_t Key, bool bKeyPressed, bool bKeyHeld);


void (*const ProcessKeysFunctions[])(KEY_Code_t Key, bool bKeyPressed, bool bKeyHeld) = {
    [DISPLAY_MAIN] = &MAIN_ProcessKeys,
    [DISPLAY_MENU] = &MENU_ProcessKeys,
    [DISPLAY_SCANNER] = &SCANNER_ProcessKeys,

#ifdef ENABLE_FMRADIO_EMBEDDED
    [DISPLAY_FM] = &FM_ProcessKeys,
#elif defined(ENABLE_FMRADIO)
    /* The modal FM overlay handles its own keys. Keep the enum slot populated
       for configurations where DISPLAY_FM is the final display entry. */
    [DISPLAY_FM] = &MAIN_ProcessKeys,
#endif

#ifdef ENABLE_AIRCOPY
    [DISPLAY_AIRCOPY] = &AIRCOPY_ProcessKeys,
#endif

#ifdef ENABLE_FEAT_F4HWN_RXTX_LOG
    [DISPLAY_RXTX_LOG] = &RXTX_LOG_ProcessKeys,
#endif
};

static_assert(ARRAY_SIZE(ProcessKeysFunctions) == DISPLAY_N_ELEM);

#ifdef ENABLE_FEAT_F4HWN_LOGO_SAV
static uint8_t ScreenSaverRandom(void)
{
    gMatrixRandom = (uint16_t)(gMatrixRandom * 2053u + 13849u);
    return (uint8_t)(gMatrixRandom >> 8);
}

static void ScreenSaverSetPixel(uint8_t x, uint8_t y, bool fill)
{
    const uint8_t pattern = 1u << (y & 7u);
    
    uint8_t *target = (y < 8) ? &gStatusLine[x] : &gFrameBuffer[(y >> 3) - 1][x];

    if (fill)
        *target |= pattern;
    else
        *target &= (uint8_t)~pattern;
}

static void ScreenSaverDrawMatrixGlyph(uint8_t x, int16_t y, uint8_t glyph)
{
    static const uint8_t glyphs[][3] = {
        {0x1F, 0x11, 0x1F},
        {0x15, 0x1F, 0x15},
        {0x1D, 0x15, 0x17},
        {0x17, 0x15, 0x1D},
        {0x1F, 0x04, 0x1F},
        {0x1B, 0x15, 0x1B},
        {0x0E, 0x11, 0x0E},
        {0x11, 0x0A, 0x04}
    };

    for (uint8_t dx = 0; dx < 3; dx++) {
        uint8_t pixels = glyphs[glyph & 7u][dx];
        for (uint8_t dy = 0; dy < 5; dy++) {
            const int16_t py = y + dy;
            if ((pixels & (1u << dy)) && py >= 0 && py < LCD_HEIGHT)
                ScreenSaverSetPixel(x + dx, (uint8_t)py, true);
        }
    }
}

static void ScreenSaverRenderMatrix(bool reset)
{
    if (reset) {
        for (uint8_t i = 0; i < ARRAY_SIZE(gMatrixHeads); i++) {
            gMatrixHeads[i]  = ScreenSaverRandom() % 112u;
            gMatrixSpeeds[i] = 1u + (ScreenSaverRandom() % 3u);
        }
    } else {
        for (uint8_t i = 0; i < ARRAY_SIZE(gMatrixHeads); i++) {
            gMatrixHeads[i] += gMatrixSpeeds[i];
            if (gMatrixHeads[i] > 112u)
                gMatrixHeads[i] = ScreenSaverRandom() & 0x0Fu;
        }
    }

    UI_StatusClear();
    UI_DisplayClear();

    for (uint8_t col = 0; col < ARRAY_SIZE(gMatrixHeads); col++) {
        const uint8_t x = col * 4u;
        for (uint8_t trail = 0; trail < 8; trail++) {
            const int16_t y = (int16_t)gMatrixHeads[col] - ((int16_t)trail * 7);
            const uint8_t glyph = (uint8_t)(col + trail + gMatrixHeads[col]);

            if (trail == 0) {
                ScreenSaverDrawMatrixGlyph(x, y, glyph);
            } else if ((trail < 4) || ((glyph & 1u) != 0)) {
                ScreenSaverDrawMatrixGlyph(x, y, glyph);
            }
        }
    }

    ST7565_BlitStatusLine();
    ST7565_BlitFullScreen();
}

static void ScreenSaverScrollLogoLine(uint8_t *line)
{
    const uint8_t first = line[0];

    memmove(line, line + 1, LCD_WIDTH - 1);
    line[LCD_WIDTH - 1] = first;
}

static void ScreenSaverRenderLogoPlus(bool reset)
{
    if (reset) {
        UI_DisplayLogo();
        return;
    }

    ScreenSaverScrollLogoLine(gStatusLine);
    for (uint8_t line = 0; line < FRAME_LINES; line++)
        ScreenSaverScrollLogoLine(gFrameBuffer[line]);

    ST7565_BlitStatusLine();
    ST7565_BlitFullScreen();
}

static void ScreenSaverUpdateViewer(void)
{
#ifdef ENABLE_FEAT_F4HWN_K5VIEWER
    K5VIEWER_Update(false);
#endif
}

static bool ScreenSaverCanDisplay(bool modal)
{
    if (gSetting_set_sav == SET_SAV_OFF ||
        gEeprom.BACKLIGHT_TIME == 0 ||
        gEeprom.BACKLIGHT_TIME >= 61 ||
        gScreenSaverDisplayed ||
#ifdef ENABLE_FEAT_F4HWN_SLEEP
        gWakeUp ||
#endif
        gCurrentFunction == FUNCTION_TRANSMIT ||
        FUNCTION_IsRx() ||
        gPttIsPressed
#ifdef ENABLE_FMRADIO_EMBEDDED
        || (gFM_ScanState != FM_SCAN_OFF && !gFM_FoundFrequency)
#endif
#ifdef ENABLE_FEAT_F4HWN_BEAM
        || gBeamActive
#endif
        )
    {
        return false;
    }

    if (modal || gScreenToDisplay == DISPLAY_MAIN)
        return true;

#ifdef ENABLE_FMRADIO_EMBEDDED
    if (gScreenToDisplay == DISPLAY_FM)
        return true;
#endif

    return false;
}

static void ScreenSaverTryDisplay(bool modal)
{
    if (!ScreenSaverCanDisplay(modal))
        return;

    if (gSetting_set_sav == SET_SAV_LOGO)
        UI_DisplayLogo();
    else if (gSetting_set_sav == SET_SAV_LOGO_PLUS)
        ScreenSaverRenderLogoPlus(true);
    else if (gSetting_set_sav == SET_SAV_MATRIX)
        ScreenSaverRenderMatrix(true);

    gScreenSaverDisplayed = true;
    gScreenSaverTick = 0;
    gUpdateDisplay = false;
    gUpdateStatus = false;
    ScreenSaverUpdateViewer();
}

static void ScreenSaverExit(void)
{
    if (gScreenSaverDisplayed) {
        gScreenSaverDisplayed = false;
        gUpdateDisplay = true;
        gUpdateStatus = true;
    }
}

static bool ScreenSaverAnimate(void)
{
    if (!gScreenSaverDisplayed)
        return false;

    if (gSetting_set_sav == SET_SAV_MATRIX) {
        if (++gScreenSaverTick >= 8u) {
            gScreenSaverTick = 0;
            ScreenSaverRenderMatrix(false);
            return true;
        }
    } else if (gSetting_set_sav == SET_SAV_LOGO_PLUS) {
        if (++gScreenSaverTick >= 16u) {
            gScreenSaverTick = 0;
            ScreenSaverRenderLogoPlus(false);
            return true;
        }
    }

    return false;
}
#endif

bool APP_IsScreenSaverDisplayed(void)
{
#ifdef ENABLE_FEAT_F4HWN_LOGO_SAV
    return gScreenSaverDisplayed;
#else
    return false;
#endif
}

/* Modal foreground loops (resident tools and overlay apps) bypass APP_Update()
 * and therefore also bypass the normal 10 ms fade and 500 ms BLTime service.
 * Keep that service resident so every overlay app gets identical timing without
 * extending the app ABI.  Only selected modal screens opt into the saver; the
 * others still fade from BLMax to BLMin when BLTime expires. */
void APP_ModalBacklightTick(bool allowScreenSaver)
{
    if (gNextTimeslice) {
        gNextTimeslice = false;
        BACKLIGHT_Update();

#ifdef ENABLE_FEAT_F4HWN_LOGO_SAV
        if (ScreenSaverAnimate())
            ScreenSaverUpdateViewer();
#endif
    }

    if (!gNextTimeslice_500ms)
        return;
    gNextTimeslice_500ms = false;

    if (gBacklightCountdown_500ms > 0 &&
        gEeprom.BACKLIGHT_TIME < 61 &&
        --gBacklightCountdown_500ms == 0)
        BACKLIGHT_TurnOff();

#ifdef ENABLE_FEAT_F4HWN_LOGO_SAV
    if (allowScreenSaver && gBacklightCountdown_500ms == 0)
        ScreenSaverTryDisplay(true);
#else
    (void)allowScreenSaver;
#endif
}

void APP_ModalScreenSaverExit(void)
{
#ifdef ENABLE_FEAT_F4HWN_LOGO_SAV
    ScreenSaverExit();
#endif
}

static void CheckForIncoming(void)
{
    if (!g_SquelchLost)
        return;          // squelch is closed

#ifdef ENABLE_FMRADIO_EMBEDDED
    // FM scan in progress: ignore main-channel RX so scanning is not interrupted.
    // Normal FM listening (FM_SCAN_OFF) still yields to channel signals as before.
    if (gFmRadioMode && gFM_ScanState != FM_SCAN_OFF)
        return;
#endif

#ifdef ENABLE_FEAT_F4HWN_LOGO_SAV
    ScreenSaverExit();
#endif

    // squelch is open

    if (gScanStateDir == SCAN_OFF)
    {   // not RF scanning
        if (gEeprom.DUAL_WATCH == DUAL_WATCH_OFF)
        {   // dual watch is disabled

            #ifdef ENABLE_NOAA
                if (gIsNoaaMode)
                {
                    gNOAA_Countdown_10ms = NOAA_countdown_3_10ms;
                    gScheduleNOAA        = false;
                }
            #endif

            if (gCurrentFunction != FUNCTION_INCOMING)
            {
                FUNCTION_Select(FUNCTION_INCOMING);
                //gUpdateDisplay = true;
            }

            return;
        }

        // dual watch is enabled and we're RX'ing a signal

        if (gRxReceptionMode != RX_MODE_NONE)
        {
            if (gCurrentFunction != FUNCTION_INCOMING)
            {
                FUNCTION_Select(FUNCTION_INCOMING);
                //gUpdateDisplay = true;
            }
            return;
        }

        gDualWatchCountdown_10ms = dual_watch_count_after_rx_10ms;
        gScheduleDualWatch       = false;

        // let the user see DW is not active
        gDualWatchActive = false;
        gUpdateStatus    = true;
    }
    else
    {   // RF scanning
        if (gRxReceptionMode != RX_MODE_NONE)
        {
            if (gCurrentFunction != FUNCTION_INCOMING)
            {
                FUNCTION_Select(FUNCTION_INCOMING);
                //gUpdateDisplay = true;
            }
            return;
        }

        gScanPauseDelayIn_10ms = scan_pause_delay_in_3_10ms;
        gScheduleScanListen    = false;
    }

    gRxReceptionMode = RX_MODE_DETECTED;

    if (gCurrentFunction != FUNCTION_INCOMING)
    {
        FUNCTION_Select(FUNCTION_INCOMING);
        //gUpdateDisplay = true;
    }
}

static void HandleIncoming(void)
{
    if (!g_SquelchLost) {   // squelch is closed
#ifdef ENABLE_DTMF_CALLING
        if (gDTMF_RX_index > 0)
            DTMF_clear_RX();
#endif
        if (gCurrentFunction != FUNCTION_FOREGROUND) {
            FUNCTION_Select(FUNCTION_FOREGROUND);
            gUpdateDisplay = true;
        }
        return;
    }

    bool bFlag = (gScanStateDir == SCAN_OFF && gCurrentCodeType == CODE_TYPE_OFF);

#ifdef ENABLE_NOAA
    if (IS_NOAA_CHANNEL(gRxVfo->CHANNEL_SAVE) && gNOAACountdown_10ms > 0) {
        gNOAACountdown_10ms = 0;
        bFlag               = true;
    }
#endif

    if (g_CTCSS_Lost && gCurrentCodeType == CODE_TYPE_CONTINUOUS_TONE) {
        bFlag       = true;
        gFoundCTCSS = false;
    }

    if (g_CDCSS_Lost && gCDCSSCodeType == CDCSS_POSITIVE_CODE
        && (gCurrentCodeType == CODE_TYPE_DIGITAL || gCurrentCodeType == CODE_TYPE_REVERSE_DIGITAL))
    {
        gFoundCDCSS = false;
    }
    else if (!bFlag)
        return;

#ifdef ENABLE_DTMF_CALLING
    if (gScanStateDir == SCAN_OFF && (gRxVfo->DTMF_DECODING_ENABLE || gSetting_KILLED)) {

        // DTMF DCD is enabled
        DTMF_HandleRequest();
        if (gDTMF_CallState == DTMF_CALL_STATE_NONE) {
            if (gRxReceptionMode != RX_MODE_DETECTED) {
                return;
            }
            gDualWatchCountdown_10ms = dual_watch_count_after_1_10ms;
            gScheduleDualWatch       = false;

            gRxReceptionMode = RX_MODE_LISTENING;

            // let the user see DW is not active
            gDualWatchActive = false;
            gUpdateStatus    = true;

            gUpdateDisplay = true;
            return;
        }
    }
#endif

#ifdef ENABLE_FMRADIO_EMBEDDED
    // Defensive: do not leave FM scan for a main-channel signal.
    if (gFmRadioMode && gFM_ScanState != FM_SCAN_OFF)
        return;
#endif

    APP_StartListening(gMonitor ? FUNCTION_MONITOR : FUNCTION_RECEIVE);
}

static void HandleReceive(void)
{
    #define END_OF_RX_MODE_SKIP 0
    #define END_OF_RX_MODE_END  1
    #define END_OF_RX_MODE_TTE  2

    uint8_t Mode = END_OF_RX_MODE_SKIP;

    if (gFlagTailNoteEliminationComplete)
    {
        Mode = END_OF_RX_MODE_END;
        goto Skip;
    }

    if (gScanStateDir != SCAN_OFF && IS_FREQ_CHANNEL(gNextMrChannel))
    { // we are scanning in the frequency mode
        if (g_SquelchLost)
            return;

        Mode = END_OF_RX_MODE_END;
        goto Skip;
    }

    switch (gCurrentCodeType)
    {
        default:
        case CODE_TYPE_OFF:
            break;

        case CODE_TYPE_CONTINUOUS_TONE:
        case CODE_TYPE_DIGITAL:
        case CODE_TYPE_REVERSE_DIGITAL:
            if ((gFoundCTCSS && gFoundCTCSSCountdown_10ms == 0) || (gFoundCDCSS && gFoundCDCSSCountdown_10ms == 0))
            {
                gFoundCTCSS = false;
                gFoundCDCSS = false;
                Mode        = END_OF_RX_MODE_END;
                goto Skip;
            }
            break;
    }

    if (g_SquelchLost)
    {
        #ifdef ENABLE_NOAA
            if (!gEndOfRxDetectedMaybe && !IS_NOAA_CHANNEL(gRxVfo->CHANNEL_SAVE))
        #else
            if (!gEndOfRxDetectedMaybe)
        #endif
        {
            switch (gCurrentCodeType)
            {
                case CODE_TYPE_OFF:
                    if (gEeprom.SQUELCH_LEVEL)
                    {
                        if (g_CxCSS_TAIL_Found)
                        {
                            Mode               = END_OF_RX_MODE_TTE;
                            g_CxCSS_TAIL_Found = false;
                        }
                    }
                    break;

                case CODE_TYPE_CONTINUOUS_TONE:
                    if (g_CTCSS_Lost)
                    {
                        gFoundCTCSS = false;
                    }
                    else
                    if (!gFoundCTCSS)
                    {
                        gFoundCTCSS               = true;
                        gFoundCTCSSCountdown_10ms = 100;   // 1 sec
                    }

                    if (g_CxCSS_TAIL_Found)
                    {
                        Mode               = END_OF_RX_MODE_TTE;
                        g_CxCSS_TAIL_Found = false;
                    }
                    break;

                case CODE_TYPE_DIGITAL:
                case CODE_TYPE_REVERSE_DIGITAL:
                    if (g_CDCSS_Lost && gCDCSSCodeType == CDCSS_POSITIVE_CODE)
                    {
                        gFoundCDCSS = false;
                    }
                    else
                    if (!gFoundCDCSS)
                    {
                        gFoundCDCSS               = true;
                        gFoundCDCSSCountdown_10ms = 100;   // 1 sec
                    }

                    if (g_CxCSS_TAIL_Found)
                    {
                        if (BK4819_GetCTCType() == 1)
                            Mode = END_OF_RX_MODE_TTE;

                        g_CxCSS_TAIL_Found = false;
                    }

                    break;
            }
        }
    }
    else
        Mode = END_OF_RX_MODE_END;

    if (!gEndOfRxDetectedMaybe         &&
         Mode == END_OF_RX_MODE_SKIP   &&
         gNextTimeslice40ms            &&
         gEeprom.TAIL_TONE_ELIMINATION &&
         (gCurrentCodeType == CODE_TYPE_DIGITAL || gCurrentCodeType == CODE_TYPE_REVERSE_DIGITAL) &&
         BK4819_GetCTCType() == 1)
        Mode = END_OF_RX_MODE_TTE;
    else
        gNextTimeslice40ms = false;

Skip:
    switch (Mode)
    {
        case END_OF_RX_MODE_SKIP:
            break;

        case END_OF_RX_MODE_END:
            RADIO_SetupRegisters(true);

            #ifdef ENABLE_NOAA
                if (IS_NOAA_CHANNEL(gRxVfo->CHANNEL_SAVE))
                    gNOAACountdown_10ms = 300;         // 3 sec
            #endif

            gUpdateDisplay = true;

            if (gScanStateDir != SCAN_OFF)
            {

                /*
                switch (gEeprom.SCAN_RESUME_MODE)
                {
                    case SCAN_RESUME_TO:
                        break;

                    case SCAN_RESUME_CO:
                        gScanPauseDelayIn_10ms = scan_pause_delay_in_7_10ms;
                        gScheduleScanListen    = false;
                        break;

                    case SCAN_RESUME_SE:
                        CHFRSCANNER_Stop();
                        break;
                }
                */

                if(gEeprom.SCAN_RESUME_MODE < 81)
                {
                    if(gEeprom.SCAN_RESUME_MODE == 0)
                    {
                        CHFRSCANNER_Stop();
                    }
                    else
                    {
                        gScanPauseDelayIn_10ms = gEeprom.SCAN_RESUME_MODE * (250 / 10); // 250ms
                        gScheduleScanListen    = false;
                    }
                }

                /*
                if(gEeprom.SCAN_RESUME_MODE < 2)
                {
                    gScanPauseDelayIn_10ms = scan_pause_delay_in_6_10ms + (scan_pause_delay_in_6_10ms * 24 * gEeprom.SCAN_RESUME_MODE);
                    gScheduleScanListen    = false;

                }
                else if(gEeprom.SCAN_RESUME_MODE == 2)
                {
                    CHFRSCANNER_Stop();
                }
                */

                /*
                switch (gEeprom.SCAN_RESUME_MODE)
                {
                    case 0:
                        gScanPauseDelayIn_10ms = scan_pause_delay_in_6_10ms;
                        gScheduleScanListen    = false;
                        break;

                    case 1:
                        gScanPauseDelayIn_10ms = scan_pause_delay_in_2_10ms * 5;
                        gScheduleScanListen    = false;
                        break;

                    case 26:
                        CHFRSCANNER_Stop();
                        break;

                    //default:
                    //    gScanPauseDelayIn_10ms = scan_pause_delay_in_5_10ms * (gEeprom.SCAN_RESUME_MODE - 1) * 5;
                    //    break;
                }
                */
            }

            break;

        case END_OF_RX_MODE_TTE:
            if (gEeprom.TAIL_TONE_ELIMINATION) {
                AUDIO_AudioPathOff();

                gTailNoteEliminationCountdown_10ms = 20;
                gFlagTailNoteEliminationComplete   = false;
                gEndOfRxDetectedMaybe = true;
                gEnableSpeaker        = false;
            }
            break;
    }
}

static void HandlePowerSave()
{
    if (!gRxIdleMode) {
        CheckForIncoming();
    }
}

static void (*HandleFunction_fn_table[])(void) = {
    [FUNCTION_FOREGROUND] = &CheckForIncoming,
    [FUNCTION_TRANSMIT] = &FUNCTION_NOP,
    [FUNCTION_MONITOR] = &FUNCTION_NOP,
    [FUNCTION_INCOMING] = &HandleIncoming,
    [FUNCTION_RECEIVE] = &HandleReceive,
    [FUNCTION_POWER_SAVE] = &HandlePowerSave,
    [FUNCTION_BAND_SCOPE] = &FUNCTION_NOP,
};

static_assert(ARRAY_SIZE(HandleFunction_fn_table) == FUNCTION_N_ELEM);

static void HandleFunction(void)
{
    HandleFunction_fn_table[gCurrentFunction]();
}

void APP_StartListening(FUNCTION_Type_t function)
{
#ifdef ENABLE_FEAT_F4HWN_LOGO_SAV
    ScreenSaverExit();
#endif

#ifdef ENABLE_FEAT_F4HWN_RX_TX_TIMER
    gRxTimerCountdown_500ms = 7200;
#endif

#ifdef ENABLE_DTMF_CALLING
    if (gSetting_KILLED)
        return;
#endif

#ifdef ENABLE_FMRADIO_EMBEDDED
    if (gFmRadioMode)
        BK1080_Init0();
#endif

#ifdef ENABLE_FEAT_F4HWN_FULL_WATCH
    FullWatchPromoteCurrentBackground();
#endif
    const unsigned int vfo = gEeprom.RX_VFO;

    // clear the other vfo's rssi level (to hide the antenna symbol)
    gVFO_RSSI_bar_level[!vfo] = 0;

    AUDIO_AudioPathOn();
    gEnableSpeaker = true;

    if (gSetting_backlight_on_tx_rx & BACKLIGHT_ON_TR_RX) {
        BACKLIGHT_TurnOn();
    }

    if (gScanStateDir != SCAN_OFF)
        CHFRSCANNER_Found();

#ifdef ENABLE_NOAA
    if (IS_NOAA_CHANNEL(gRxVfo->CHANNEL_SAVE) && gIsNoaaMode) {
        gRxVfo->CHANNEL_SAVE        = gNoaaChannel + NOAA_CHANNEL_FIRST;
        gRxVfo->pRX->Frequency      = NoaaFrequencyTable[gNoaaChannel];
        gRxVfo->pTX->Frequency      = NoaaFrequencyTable[gNoaaChannel];
        gEeprom.ScreenChannel[vfo] = gRxVfo->CHANNEL_SAVE;

        gNOAA_Countdown_10ms        = 500;   // 5 sec
        gScheduleNOAA               = false;
    }
#endif

    if (gScanStateDir == SCAN_OFF &&
        gEeprom.DUAL_WATCH != DUAL_WATCH_OFF)
    {   // not scanning, dual watch is enabled

        //gDualWatchCountdown_10ms = dual_watch_count_after_2_10ms;

        const bool isMainTxDualRx =
        (gEeprom.DUAL_WATCH != DUAL_WATCH_OFF) &&
        (gEeprom.CROSS_BAND_RX_TX != CROSS_BAND_OFF);

        // Use a short hold only for MAIN TX DUAL RX, keep legacy hold otherwise
        gDualWatchCountdown_10ms = isMainTxDualRx
            ? dual_watch_count_after_2_10ms / 4 // Short timer = 420 / 4 ...
            : dual_watch_count_after_2_10ms;

        gScheduleDualWatch       = false;

        // when crossband is active only the main VFO should be used for TX
        if(gEeprom.CROSS_BAND_RX_TX == CROSS_BAND_OFF)
            gRxVfoIsActive = true;

        // let the user see DW is not active
        gDualWatchActive = false;
        gUpdateStatus    = true;
    }

    BK4819_SetRxAudioGain();

#ifdef ENABLE_VOICE
    if (gVoiceWriteIndex == 0)       // AM/FM RX mode will be set when the voice has finished
#endif
        RADIO_SetModulation(gRxVfo->Modulation);  // no need, set it now

    FUNCTION_Select(function);

#ifdef ENABLE_FEAT_F4HWN_RXTX_LOG
    RXTX_LOG_BeginRx(gRxVfo, function);
#endif

#ifdef ENABLE_FMRADIO_EMBEDDED
    if (function == FUNCTION_MONITOR || gFmRadioMode)
#else
    if (function == FUNCTION_MONITOR)
#endif
    {   // squelch is disabled
        if (gScreenToDisplay != DISPLAY_MENU)     // 1of11 .. don't close the menu
            GUI_SelectNextDisplay(DISPLAY_MAIN);
    }
    else
        gUpdateDisplay = true;

    gUpdateStatus = true;
}

uint32_t APP_SetFreqByStepAndLimits(VFO_Info_t *pInfo, int8_t direction, uint32_t lower, uint32_t upper)
{
    uint32_t Frequency = FREQUENCY_RoundToStep(pInfo->freq_config_RX.Frequency + (direction * pInfo->StepFrequency), pInfo->StepFrequency);

#ifdef ENABLE_FEAT_F4HWN
    if (Frequency > upper)
#else
    if (Frequency >= upper)
#endif
        Frequency =  lower;

    else if (Frequency < lower)
        Frequency = FREQUENCY_RoundToStep(upper - pInfo->StepFrequency, pInfo->StepFrequency);

    return Frequency;
}

uint32_t APP_SetFrequencyByStep(VFO_Info_t *pInfo, int8_t direction)
{
    return APP_SetFreqByStepAndLimits(pInfo, direction, frequencyBandTable[pInfo->Band].lower, frequencyBandTable[pInfo->Band].upper);
}

#ifdef ENABLE_NOAA
    static void NOAA_IncreaseChannel(void)
    {
        if (++gNoaaChannel > 9)
            gNoaaChannel = 0;
    }
#endif

#ifdef ENABLE_FEAT_F4HWN_FULL_WATCH
static VFO_Info_t gFullWatchPriorityVfo[2];
static VFO_Info_t *gFullWatchForegroundVfo;
static VFO_Info_t *gFullWatchBackgroundVfo[2];
static uint8_t    gFullWatchCurrentBackground = 0xFFu;
static uint8_t    gFullWatchBackgroundCount;
static uint8_t    gFullWatchSequenceIndex = 0xFFu;
static uint8_t    gFullWatchScrollPhase;

void APP_FullWatchReset(void)
{
    gFullWatchForegroundVfo = NULL;
    gFullWatchCurrentBackground = 0xFFu;
    gFullWatchBackgroundCount = 0;
    gFullWatchSequenceIndex = 0xFFu;
    gFullWatchScrollPhase = 0u;
}

static VFO_Info_t *FullWatchLoadPriority(uint8_t priority, uint16_t channel, const ChannelAttributes_t *attributes)
{
    ChannelScanDisplayInfo_t info;
    VFO_Info_t *vfo = &gFullWatchPriorityVfo[priority];

    if (!SETTINGS_FetchChannelScanDisplayInfo(channel, &info))
        return NULL;

    RADIO_InitInfo(vfo, channel, info.rx.Frequency);
    SETTINGS_ApplyChannelScanDisplayInfo(vfo, channel, &info);

    vfo->SCANLIST_PARTICIPATION = attributes->scanlist;
    vfo->Compander = attributes->compander;

    if (!gSetting_350EN &&
        vfo->pRX->Frequency >= 35000000 &&
        vfo->pRX->Frequency < 40000000)
        vfo->pRX->Frequency = 43300000;

    SETTINGS_FetchChannelName(vfo->Name, channel);
    RADIO_ConfigureSquelchAndOutputPower(vfo);
    return vfo;
}

static uint8_t FullWatchReplacementVfo(void)
{
    return gEeprom.TX_VFO ^ (gEeprom.CROSS_BAND_RX_TX != CROSS_BAND_OFF);
}

static void FullWatchInitialize(void)
{
    if (gFullWatchForegroundVfo != NULL)
        return;

    gFullWatchForegroundVfo = &gEeprom.VfoInfo[FullWatchReplacementVfo()];
    gFullWatchBackgroundCount = 0;

    for (uint8_t priority = 0; priority < 2; priority++)
    {
        const uint16_t channel = gEeprom.SCANLIST_PRIORITY_CH[priority];
        if (!IS_MR_CHANNEL(channel))
            continue;

        const ChannelAttributes_t *attributes = MR_GetChannelAttributes(channel);
        if (attributes == NULL ||
            attributes->band > BAND7_470MHz ||
            channel == gEeprom.VfoInfo[0].CHANNEL_SAVE ||
            channel == gEeprom.VfoInfo[1].CHANNEL_SAVE ||
            (priority == 1 && channel == gEeprom.SCANLIST_PRIORITY_CH[0]))
            continue;

        VFO_Info_t *vfo = FullWatchLoadPriority(priority, channel, attributes);
        if (vfo != NULL)
            gFullWatchBackgroundVfo[gFullWatchBackgroundCount++] = vfo;
    }

}

static void FullWatchAlternate(void)
{
    FullWatchInitialize();
    const uint8_t count = 2u + gFullWatchBackgroundCount;
    const uint8_t vfoBIndex = gFullWatchBackgroundCount > 1 ? 2u : 1u;
    const uint8_t replacementVfo = FullWatchReplacementVfo();

    if (gFullWatchSequenceIndex >= count)
    {
        const bool onVfoB = gRxVfo == &gEeprom.VfoInfo[1] ||
                            (gRxVfo == gFullWatchForegroundVfo && replacementVfo == 1);
        gFullWatchSequenceIndex = onVfoB ? vfoBIndex : 0;
    }

    if (++gFullWatchSequenceIndex >= count)
        gFullWatchSequenceIndex = 0;

    if (gFullWatchSequenceIndex == 0 || gFullWatchSequenceIndex == vfoBIndex)
    {
        gEeprom.RX_VFO = gFullWatchSequenceIndex == 0 ? 0 : 1;
        gRxVfo = gEeprom.RX_VFO == replacementVfo
            ? gFullWatchForegroundVfo
            : &gEeprom.VfoInfo[gEeprom.RX_VFO];
        gFullWatchCurrentBackground = 0xFFu;
        return;
    }

    // Only the four-slot cycle reaches background slot 1, at sequence index 3.
    const uint8_t background = gFullWatchSequenceIndex == 3u;
    gEeprom.RX_VFO = replacementVfo;
    gRxVfo = gFullWatchBackgroundVfo[background];
    gFullWatchCurrentBackground = background;
    gFullWatchScrollPhase = (gFullWatchScrollPhase + 1u) & 3u;
    UI_MAIN_UpdateFullWatchArrows();
}

VFO_Info_t *APP_GetFullWatchDisplayVfo(uint8_t vfo)
{
    const uint8_t replacementVfo = FullWatchReplacementVfo();

    if (gEeprom.DUAL_WATCH != DUAL_WATCH_FULL ||
        vfo != replacementVfo ||
        gFullWatchForegroundVfo == &gEeprom.VfoInfo[replacementVfo])
        return NULL;

    return gFullWatchForegroundVfo;
}

VFO_Info_t *const *APP_GetFullWatchBackgroundVfos(uint8_t *count)
{
    FullWatchInitialize();
    *count = gFullWatchBackgroundCount;
    return gFullWatchBackgroundVfo;
}

uint8_t APP_GetFullWatchScrollPhase(void)
{
    return gFullWatchScrollPhase;
}

static void FullWatchPromoteCurrentBackground(void)
{
    if (gEeprom.DUAL_WATCH != DUAL_WATCH_FULL ||
        gFullWatchForegroundVfo == NULL ||
        gRxReceptionMode == RX_MODE_NONE ||
        gScanStateDir != SCAN_OFF ||
        gCssBackgroundScan ||
        gFullWatchCurrentBackground >= gFullWatchBackgroundCount)
        return;

    const uint8_t background = gFullWatchCurrentBackground;
    VFO_Info_t *displaced = gFullWatchForegroundVfo;
    gFullWatchForegroundVfo = gFullWatchBackgroundVfo[background];
    gFullWatchBackgroundVfo[background] = displaced;
    gFullWatchCurrentBackground = 0xFFu;
    gEeprom.RX_VFO = FullWatchReplacementVfo();
    gRxVfo = gFullWatchForegroundVfo;
    gUpdateDisplay = true;
    gUpdateStatus = true;
}
#endif

static void DualwatchAlternate(void)
{
    #ifdef ENABLE_NOAA
#ifdef ENABLE_FEAT_F4HWN_FULL_WATCH
        if (gIsNoaaMode && gEeprom.DUAL_WATCH != DUAL_WATCH_FULL)
#else
        if (gIsNoaaMode)
#endif
        {
            if (!IS_NOAA_CHANNEL(gEeprom.ScreenChannel[0]) || !IS_NOAA_CHANNEL(gEeprom.ScreenChannel[1]))
                gEeprom.RX_VFO = (gEeprom.RX_VFO + 1) & 1;
            else
                gEeprom.RX_VFO = 0;

            gRxVfo = &gEeprom.VfoInfo[gEeprom.RX_VFO];

            if (IS_NOAA_CHANNEL(gEeprom.VfoInfo[0].CHANNEL_SAVE))
                NOAA_IncreaseChannel();
        }
        else
    #endif
    {
#ifdef ENABLE_FEAT_F4HWN_FULL_WATCH
        if (gEeprom.DUAL_WATCH == DUAL_WATCH_FULL)
            FullWatchAlternate();
        else
#endif
        {   // toggle between VFO's
            gEeprom.RX_VFO = !gEeprom.RX_VFO;
            gRxVfo         = &gEeprom.VfoInfo[gEeprom.RX_VFO];
        }

        if (!gDualWatchActive)
        {   // let the user see DW is active
            gDualWatchActive = true;
            gUpdateStatus    = true;
        }
    }

    RADIO_SetupRegisters(false);

    #ifdef ENABLE_NOAA
#ifdef ENABLE_FEAT_F4HWN_FULL_WATCH
        gDualWatchCountdown_10ms = (gIsNoaaMode && gEeprom.DUAL_WATCH != DUAL_WATCH_FULL)
            ? dual_watch_count_noaa_10ms
            : dual_watch_count_toggle_10ms;
#else
        gDualWatchCountdown_10ms = gIsNoaaMode ? dual_watch_count_noaa_10ms : dual_watch_count_toggle_10ms;
#endif
    #else
        gDualWatchCountdown_10ms = dual_watch_count_toggle_10ms;
    #endif
}

static void CheckRadioInterrupts(void)
{
    if (SCANNER_IsScanning())
        return;

    while (BK4819_ReadRegister(BK4819_REG_0C) & 1u) { // BK chip interrupt request
        // clear interrupts
        BK4819_WriteRegister(BK4819_REG_02, 0);
        // fetch interrupt status bits

        union {
            struct {
                uint16_t __UNUSED : 1;
                uint16_t fskRxSync : 1;
                uint16_t sqlLost : 1;
                uint16_t sqlFound : 1;
                uint16_t voxLost : 1;
                uint16_t voxFound : 1;
                uint16_t ctcssLost : 1;
                uint16_t ctcssFound : 1;
                uint16_t cdcssLost : 1;
                uint16_t cdcssFound : 1;
                uint16_t cssTailFound : 1;
                uint16_t dtmf5ToneFound : 1;
                uint16_t fskFifoAlmostFull : 1;
                uint16_t fskRxFinied : 1;
                uint16_t fskFifoAlmostEmpty : 1;
                uint16_t fskTxFinied : 1;
            };
            uint16_t __raw;
        } interrupts;

        interrupts.__raw = BK4819_ReadRegister(BK4819_REG_02);

        // 0 = no phase shift
        // 1 = 120deg phase shift
        // 2 = 180deg phase shift
        // 3 = 240deg phase shift
//      const uint8_t ctcss_shift = BK4819_GetCTCShift();
//      if (ctcss_shift > 0)
//          g_CTCSS_Lost = true;

        if (interrupts.dtmf5ToneFound) {    
            const char c = DTMF_GetCharacter(BK4819_GetDTMF_5TONE_Code()); // save the RX'ed DTMF character
            if (c != 0xff) {
                if (gCurrentFunction != FUNCTION_TRANSMIT) {
#ifdef ENABLE_FEAT_F4HWN_K5VIEWER
                    K1R_DtmfChar(c);
#endif
                    if (gSetting_live_DTMF_decoder) {
                        size_t len = strlen(gDTMF_RX_live);
                        if (len >= sizeof(gDTMF_RX_live) - 1) { // make room
                            memmove(&gDTMF_RX_live[0], &gDTMF_RX_live[1], sizeof(gDTMF_RX_live) - 1);
                            len--;
                        }
                        gDTMF_RX_live[len++]  = c;
                        gDTMF_RX_live[len]    = 0;
                        gDTMF_RX_live_timeout = DTMF_RX_live_timeout_500ms;  // time till we delete it
                        gUpdateDisplay        = true;
                    }

#ifdef ENABLE_DTMF_CALLING
                    if (gRxVfo->DTMF_DECODING_ENABLE || gSetting_KILLED) {
                        if (gDTMF_RX_index >= sizeof(gDTMF_RX) - 1) { // make room
                            memmove(&gDTMF_RX[0], &gDTMF_RX[1], sizeof(gDTMF_RX) - 1);
                            gDTMF_RX_index--;
                        }
                        gDTMF_RX[gDTMF_RX_index++] = c;
                        gDTMF_RX[gDTMF_RX_index]   = 0;
                        gDTMF_RX_timeout           = DTMF_RX_timeout_500ms;  // time till we delete it
                        gDTMF_RX_pending           = true;
                        
                        SYSTEM_DelayMs(3);//fix DTMF not reply@Yurisu
                        DTMF_HandleRequest();
                    }
#endif
                }
            }
        }

        if (interrupts.cssTailFound)
            g_CxCSS_TAIL_Found = true;

        if (interrupts.cdcssLost) {
            g_CDCSS_Lost = true;
            gCDCSSCodeType = BK4819_GetCDCSSCodeType();
        }

        if (interrupts.cdcssFound)
            g_CDCSS_Lost = false;

        if (interrupts.ctcssLost)
            g_CTCSS_Lost = true;

        if (interrupts.ctcssFound)
            g_CTCSS_Lost = false;

#ifdef ENABLE_VOX
        if (interrupts.voxLost) {
            g_VOX_Lost         = true;
            gVoxPauseCountdown = 10;

            if (gEeprom.VOX_SWITCH) {
                if (gCurrentFunction == FUNCTION_POWER_SAVE && !gRxIdleMode) {
                    gPowerSave_10ms            = power_save2_10ms;
                    gPowerSaveCountdownExpired = 0;
                }

                if (gEeprom.DUAL_WATCH != DUAL_WATCH_OFF && (gScheduleDualWatch || gDualWatchCountdown_10ms < dual_watch_count_after_vox_10ms)) {
                    gDualWatchCountdown_10ms = dual_watch_count_after_vox_10ms;
                    gScheduleDualWatch = false;

                    // let the user see DW is not active
                    gDualWatchActive = false;
                    gUpdateStatus    = true;
                }
            }
        }

        if (interrupts.voxFound) {
            g_VOX_Lost         = false;
            gVoxPauseCountdown = 0;
        }
#endif

        if (interrupts.sqlLost) {
            g_SquelchLost = true;
            BK4819_ToggleGpioOut(BK4819_GPIO6_PIN2_GREEN, true);
            #ifdef ENABLE_FEAT_F4HWN_RX_TX_TIMER
                gRxTimerCountdown_500ms = 7200;
            #endif
        }

        if (interrupts.sqlFound) {
            g_SquelchLost = false;
            BK4819_ToggleGpioOut(BK4819_GPIO6_PIN2_GREEN, false);
        }

#if defined(ENABLE_AIRCOPY) || defined(ENABLE_FEAT_F4HWN_BEAM)
        if (interrupts.fskFifoAlmostFull || interrupts.fskRxFinied)
        {
            uint8_t fskTarget = 0;

#ifdef ENABLE_FEAT_F4HWN_BEAM
            if (gBeamActive &&
                gBeamMode == BEAM_MODE_RX &&
                (gBeamStatus == BEAM_STATUS_RX_WAIT || gBeamStatus == BEAM_STATUS_ERROR))
                fskTarget = 2;
#endif
#ifdef ENABLE_AIRCOPY
            // Aircopy wins if stale state ever makes both receivers eligible.
            if (gScreenToDisplay == DISPLAY_AIRCOPY &&
                gAircopyState == AIRCOPY_TRANSFER && !AIRCOPY_UsesUart())
                fskTarget = 1;
#endif

            if (fskTarget != 0)
            {
                // BEAM uses fixed 36-word frames; AirCopy's expected length
                // depends on the role (DATA vs tiny ACK) and is set when RX is armed.
                unsigned int expectedWords = 36u;
#ifdef ENABLE_AIRCOPY
                if (fskTarget == 1)
                    expectedWords = gFskRxExpectedWords;
#endif
                const unsigned int wordsToRead = interrupts.fskRxFinied
                                               ? (gFSKWriteIndex < expectedWords ? expectedWords - gFSKWriteIndex : 0u)
                                               : 4u;
                for (unsigned int i = 0; i < wordsToRead; i++) {
                    const uint16_t word = BK4819_ReadRegister(BK4819_REG_5F);
                    if (gFSKWriteIndex < ARRAY_SIZE(g_FSK_Buffer))
                        g_FSK_Buffer[gFSKWriteIndex++] = word;
                }

#ifdef ENABLE_AIRCOPY
                if (fskTarget == 1)
                    AIRCOPY_StorePacket();
#endif
#ifdef ENABLE_FEAT_F4HWN_BEAM
                if (fskTarget == 2)
                    BEAM_StorePacket();
#endif
            }
        }
#endif
    }
}

void APP_EndTransmission(void)
{
    // back to RX mode
    RADIO_SendEndOfTransmission();

    gFlagEndTransmission = true;

    if (gMonitor) {
         //turn the monitor back on
        gFlagReconfigureVfos = true;
    }
}

void APP_HandleEndTransmission(void) {
    if (gFlagEndTransmission) {
        FUNCTION_Select(FUNCTION_FOREGROUND);
    }
    else {
        APP_EndTransmission();

        if (gEeprom.REPEATER_TAIL_TONE_ELIMINATION == 0)
            FUNCTION_Select(FUNCTION_FOREGROUND);
        else
            gRTTECountdown_10ms = gEeprom.REPEATER_TAIL_TONE_ELIMINATION * 10;
    }

    gFlagEndTransmission = false;
}

#ifdef ENABLE_VOX
static void HandleVox(void)
{
#ifdef ENABLE_DTMF_CALLING
    if (gSetting_KILLED)
        return;
#endif

    if (gVoxResumeCountdown == 0) {
        if (gVoxPauseCountdown)
            return;
    }
    else {
        g_VOX_Lost         = false;
        gVoxPauseCountdown = 0;
    }

#ifdef ENABLE_FMRADIO_EMBEDDED
    if (gFmRadioMode)
        return;
#endif

    if (gCurrentFunction == FUNCTION_RECEIVE || gCurrentFunction == FUNCTION_MONITOR)
        return;

    if (gScanStateDir != SCAN_OFF)
        return;

    if (gVOX_NoiseDetected) {
        if (g_VOX_Lost)
            gVoxStopCountdown_10ms = vox_stop_count_down_10ms;
        else if (gVoxStopCountdown_10ms == 0)
            gVOX_NoiseDetected = false;

        if (gCurrentFunction == FUNCTION_TRANSMIT && !gPttIsPressed && !gVOX_NoiseDetected) {
            APP_HandleEndTransmission();

            gUpdateStatus        = true;
            gUpdateDisplay       = true;
        }
        return;
    }

    if (g_VOX_Lost) {
        gVOX_NoiseDetected = true;

        if (gCurrentFunction == FUNCTION_POWER_SAVE)
            FUNCTION_Select(FUNCTION_FOREGROUND);

        if (gCurrentFunction != FUNCTION_TRANSMIT && !SerialConfigInProgress()) {
#ifdef ENABLE_DTMF_CALLING
            gDTMF_ReplyState = DTMF_REPLY_NONE;
#endif
            RADIO_PrepareTX();
            gUpdateDisplay = true;
        }
    }
}
#endif

void APP_Update(void)
{
#ifdef ENABLE_FEAT_F4HWN_K5VIEWER
    // Parse incoming packets on every tick so serial keys are never missed,
    // regardless of whether the screen needs redrawing.
    K5VIEWER_ParseInput();
#endif

#ifdef ENABLE_VOICE
    if (gFlagPlayQueuedVoice) {
            AUDIO_PlayQueuedVoice();
            gFlagPlayQueuedVoice = false;
    }
#endif

#ifdef ENABLE_USB
    if (UART_IsCommandAvailable(UART_PORT_VCP)) {
        // SCHEDULER_Disable();
        UART_HandleCommand(UART_PORT_VCP);
        // SCHEDULER_Enable();
    }
#endif

#ifdef ENABLE_FEAT_F4HWN
    if (gCurrentFunction == FUNCTION_TRANSMIT && (gTxTimeoutReachedAlert || SerialConfigInProgress()))
    {
        if(gSetting_set_tot >= 2)
        {
            if (gEeprom.BACKLIGHT_TIME == 0) {
                if (gBlinkCounter == 0 || gBlinkCounter == 250)
                {
                    GPIO_TogglePin(GPIO_PIN_FLASHLIGHT);
                }
            }
            else
            {
                if (gBlinkCounter == 0)
                {
                    //BACKLIGHT_TurnOn();
                    BACKLIGHT_SetBrightness(gEeprom.BACKLIGHT_MAX);
                }
                else if(gBlinkCounter == 15000)
                {
                    //BACKLIGHT_TurnOff();
                    BACKLIGHT_SetBrightness(gEeprom.BACKLIGHT_MIN);
                }
            }
        }

        gBlinkCounter++;

        if(
            (gSetting_set_tot == 3 && gEeprom.BACKLIGHT_TIME != 0 && gBlinkCounter > 74000) || 
            (gSetting_set_tot == 3 && gEeprom.BACKLIGHT_TIME == 0 && gBlinkCounter > 79000) || 
            (gSetting_set_tot != 3 && gBlinkCounter > 76000)
            ) // try to calibrate 10 times
        {
            gBlinkCounter = 0;

            if(gSetting_set_tot == 1 || gSetting_set_tot == 3)
            {
                BK4819_DisableScramble();
                BK4819_PlaySingleTone(gTxTimeoutToneAlert, 30, 1, true);
                gTxTimeoutToneAlert += 100;
            }
        }
    }
#endif

    if (gCurrentFunction == FUNCTION_TRANSMIT && (gTxTimeoutReached || SerialConfigInProgress()))
    {   // transmitter timed out or must de-key
        gTxTimeoutReached = false;

#ifdef ENABLE_FEAT_F4HWN
        if(gBacklightCountdown_500ms > 0 || gEeprom.BACKLIGHT_TIME == 61)
        {
            //BACKLIGHT_TurnOn();
            BACKLIGHT_SetBrightness(gEeprom.BACKLIGHT_MAX);
        }

        gTxTimeoutReachedAlert = false;
        gTxTimeoutToneAlert = 800;

        if (gSetting_set_ptt_session) // Improve OnePush if TOT
        {
            if(gPttOnePushCounter == 1)
            {
                gPttOnePushCounter = 3;
            }
            else if(gPttOnePushCounter == 2)
            {
                ProcessKey(KEY_PTT, false, false);
                gPttIsPressed = false;
                gPttOnePushCounter = 0;
                gPttWasReleased = true;
                //if (gKeyReading1 != KEY_INVALID)
                //  gPttWasReleased = true;
            }
            #if defined(ENABLE_FEAT_F4HWN_CTR) || defined(ENABLE_FEAT_F4HWN_INV)
            ST7565_ContrastAndInv();
            #endif
        }
#endif

        APP_EndTransmission();

        AUDIO_PlayBeep(BEEP_880HZ_60MS_TRIPLE_BEEP);

        RADIO_SetVfoState(VFO_STATE_TIMEOUT);

        GUI_DisplayScreen();
    }

    if (gReducedService)
        return;

    if (gCurrentFunction != FUNCTION_TRANSMIT)
        HandleFunction();

#ifdef ENABLE_FMRADIO_EMBEDDED
//  if (gFmRadioCountdown_500ms > 0)
    if (gFmRadioMode && gFmRadioCountdown_500ms > 0)    // 1of11
        return;
#endif

#ifdef ENABLE_VOICE
    if (!SCANNER_IsScanning() && gScanStateDir != SCAN_OFF && gScheduleScanListen && !gPttIsPressed && gVoiceWriteIndex == 0
        && !UI_MAIN_ShouldHoldScanResume())
#else
    if (!SCANNER_IsScanning() && gScanStateDir != SCAN_OFF && gScheduleScanListen && !gPttIsPressed
        && !UI_MAIN_ShouldHoldScanResume())
#endif
    {   // scanning
        CHFRSCANNER_ContinueScanning();
    }

#ifdef ENABLE_NOAA
#ifdef ENABLE_VOICE
        if (gEeprom.DUAL_WATCH == DUAL_WATCH_OFF && gIsNoaaMode && gScheduleNOAA && gVoiceWriteIndex == 0)
#else
        if (gEeprom.DUAL_WATCH == DUAL_WATCH_OFF && gIsNoaaMode && gScheduleNOAA)
#endif
        {
            NOAA_IncreaseChannel();
            RADIO_SetupRegisters(false);

            gNOAA_Countdown_10ms = 7;      // 70ms
            gScheduleNOAA        = false;
        }
#endif

    // toggle between the VFO's if dual watch is enabled
    if (!SCANNER_IsScanning()
        && gEeprom.DUAL_WATCH != DUAL_WATCH_OFF
        && gScheduleDualWatch
        && gScanStateDir == SCAN_OFF
        && !gPttIsPressed
        && gCurrentFunction != FUNCTION_POWER_SAVE
#ifdef ENABLE_FEAT_F4HWN_BEAM
        && !gBeamActive
#endif
#ifdef ENABLE_VOICE
        && gVoiceWriteIndex == 0
#endif
#ifdef ENABLE_FMRADIO_EMBEDDED
        && !gFmRadioMode
#endif
#ifdef ENABLE_DTMF_CALLING
        && gDTMF_CallState == DTMF_CALL_STATE_NONE
#endif
    ) {
        DualwatchAlternate();    // advance to the next watched slot

        if (gRxVfoIsActive && gScreenToDisplay == DISPLAY_MAIN) {
            GUI_SelectNextDisplay(DISPLAY_MAIN);
        }

        gRxVfoIsActive     = false;
        gScanPauseMode     = false;
        gRxReceptionMode   = RX_MODE_NONE;
        gScheduleDualWatch = false;
    }

#ifdef ENABLE_FMRADIO_EMBEDDED
    if (gScheduleFM && gFM_ScanState != FM_SCAN_OFF && !FUNCTION_IsRx()) {
        // switch to FM radio mode
        FM_Play();
        gScheduleFM = false;
    }
#endif

#ifdef ENABLE_VOX
    if (gEeprom.VOX_SWITCH)
        HandleVox();
#endif

    if (gSchedulePowerSave) {
        if (gPttIsPressed
            || gKeyBeingHeld
            || gEeprom.BATTERY_SAVE == 0
            || gScanStateDir != SCAN_OFF
            || gCssBackgroundScan
            || gScreenToDisplay != DISPLAY_MAIN
#ifdef ENABLE_FMRADIO_EMBEDDED
            || gFmRadioMode
#endif
#ifdef ENABLE_DTMF_CALLING
            || gDTMF_CallState != DTMF_CALL_STATE_NONE
#endif
#ifdef ENABLE_NOAA
            || (gIsNoaaMode && (IS_NOAA_CHANNEL(gEeprom.ScreenChannel[0]) || IS_NOAA_CHANNEL(gEeprom.ScreenChannel[1])))
#endif
        ) {
            gBatterySaveCountdown_10ms = battery_save_count_10ms;
        } else {
            FUNCTION_Select(FUNCTION_POWER_SAVE);
        }

        gSchedulePowerSave = false;
    }

    if (gPowerSaveCountdownExpired && gCurrentFunction == FUNCTION_POWER_SAVE
#ifdef ENABLE_VOICE
        && gVoiceWriteIndex == 0
#endif
    ) {
        static bool goToSleep;
        // wake up, enable RX then go back to sleep
        if (gRxIdleMode)
        {
            BK4819_Conditional_RX_TurnOn_and_GPIO6_Enable();

#ifdef ENABLE_VOX
            if (gEeprom.VOX_SWITCH)
                BK4819_EnableVox(gEeprom.VOX1_THRESHOLD, gEeprom.VOX0_THRESHOLD);
#endif

            if (gEeprom.DUAL_WATCH != DUAL_WATCH_OFF &&
                gScanStateDir == SCAN_OFF &&
                !gCssBackgroundScan
#ifdef ENABLE_FEAT_F4HWN_BEAM
                && !gBeamActive
#endif
            )
            {   // dual watch mode, advance to the next watched slot
                DualwatchAlternate();
                goToSleep = false;
            }

            FUNCTION_Init();

            gPowerSave_10ms = power_save1_10ms; // come back here in a bit
            gRxIdleMode     = false;            // RX is awake
        }
        else if (
#ifdef ENABLE_FEAT_F4HWN_BEAM
            !gBeamActive &&
#endif
        (gEeprom.DUAL_WATCH == DUAL_WATCH_OFF || gScanStateDir != SCAN_OFF || gCssBackgroundScan || goToSleep))
        {   // dual watch mode off or scanning or rssi update request
            // go back to sleep

#ifdef ENABLE_FEAT_F4HWN_SLEEP
            gPowerSave_10ms = gEeprom.BATTERY_SAVE * (gWakeUp ? 200 : 10); // deep sleep now indexed on BatSav
#else
            gPowerSave_10ms = gEeprom.BATTERY_SAVE * 10;
#endif
            gRxIdleMode     = true;
            goToSleep = false;

            BK4819_DisableVox();
            BK4819_Sleep();
            BK4819_ToggleGpioOut(BK4819_GPIO0_PIN28_RX_ENABLE, false);

            // Authentic device checked removed

        }
        else
#ifdef ENABLE_FEAT_F4HWN_BEAM
        if (!gBeamActive)
#endif
        {
            // advance to the next watched slot
            DualwatchAlternate();
            gPowerSave_10ms   = power_save1_10ms;
            goToSleep = true;
        }

        gPowerSaveCountdownExpired = false;
    }
}

void StopTransmitting(void) {
    ProcessKey(KEY_PTT, false, false);
    gPttIsPressed = false;
    if (gKeyReading1 != KEY_INVALID)
        gPttWasReleased = true;

    #ifdef ENABLE_FEAT_F4HWN
        #if defined(ENABLE_FEAT_F4HWN_CTR) || defined(ENABLE_FEAT_F4HWN_INV)
        ST7565_ContrastAndInv();
        #endif
    #endif
}

// called every 10ms
#ifdef ENABLE_FEAT_F4HWN_K5VIEWER
void K1R_SetStealth(bool on);   // plus bas (furtif profond K1 Remote)
#endif

void CheckKeys(void)
{
#ifdef ENABLE_DTMF_CALLING
    if(gSetting_KILLED){
        return;
    }
#endif

#ifdef ENABLE_AIRCOPY
    if (gScreenToDisplay == DISPLAY_AIRCOPY && gAircopyState == AIRCOPY_TRANSFER){
        return;
    }
#endif

// -------------------- PTT ------------------------
    const bool serialConfigInProgress = SerialConfigInProgress();

#ifdef ENABLE_FEAT_F4HWN
    const bool isPressed = GPIO_IsPttPressed() && !serialConfigInProgress;
#else
    const bool isPressed = !GPIO_CheckBit(&GPIOC->DATA, GPIOC_PIN_PTT) && !serialConfigInProgress;
#endif

#ifdef ENABLE_FEAT_F4HWN_K5VIEWER
    // K1 Remote : le PTT à distance fonctionne toujours en « maintenir pour émettre »,
    // même si la radio est en PTT « une pression » (SetPTT ou touche latérale PTT).
    static bool remoteClassic = false;
    if (gRemotePttActive && !remoteClassic) {
        remoteClassic = true;
        gPttOnePushCounter = 0;
    } else if (remoteClassic && !gRemotePttActive && !gPttIsPressed) {
        remoteClassic = false;
    }
#endif

#ifdef ENABLE_FEAT_F4HWN
    if (gSetting_set_ptt_session
#ifdef ENABLE_FEAT_F4HWN_K5VIEWER
        && !remoteClassic
#endif
       )
    {
        if (serialConfigInProgress)
        {
            gPttDebounceCounter = 0;

            if (gPttOnePushCounter > 0 || gPttIsPressed)
            {
                StopTransmitting();
                gPttOnePushCounter = 0;
            }
        }
        else if ((isPressed && (gPttOnePushCounter == 0 || gPttOnePushCounter == 2)) ||
                 (!isPressed && (gPttOnePushCounter == 1 || gPttOnePushCounter == 3)))
        {
            if (++gPttDebounceCounter >= 3)
            {
                gPttDebounceCounter = 0;
                
                if (gPttOnePushCounter == 0)
                {   // start transmitting
                    boot_counter_10ms   = 0;
                    gPttIsPressed       = true;
                    gPttOnePushCounter = 1;
                    ProcessKey(KEY_PTT, true, false);
                } 
                else if (gPttOnePushCounter == 3)
                {   // stop transmitting
                    StopTransmitting();
                    gPttOnePushCounter = 0;
                } 
                else
                    gPttOnePushCounter++;
            }
        } 
        else
            gPttDebounceCounter = 0;

        //gDebug = gPttOnePushCounter;
    } 
    else 
#endif
    {
        if (gPttIsPressed)
        {
            if (!isPressed)
            {   // PTT released or serial comms config in progress
                if (++gPttDebounceCounter >= 3 || serialConfigInProgress)   // 30ms
                {   // stop transmitting
                    gPttDebounceCounter = 0;
                    StopTransmitting();
                }
            } 
            else 
                gPttDebounceCounter = 0;
        }
        else if (isPressed)
        {   // PTT pressed
            if (++gPttDebounceCounter >= 3)     // 30ms
            {   // start transmitting
                boot_counter_10ms   = 0;
                gPttDebounceCounter = 0;
                gPttIsPressed       = true;
                ProcessKey(KEY_PTT, true, false);
            }
        }
        else
            gPttDebounceCounter = 0;
    }

// --------------------- OTHER KEYS ----------------------------

    // scan the hardware keys
    KEY_Code_t Key = KEYBOARD_Poll();

#ifdef ENABLE_FEAT_F4HWN_K5VIEWER
    // Sortie de secours du furtif profond, sans téléphone : une touche physique (hors PTT)
    // maintenue 3 s rallume l'afficheur. Une touche envoyée par l'appli ne dure jamais
    // plus de 0,4 s : seul un appui réel peut déclencher cette sortie.
    {
        // Touche dédiée : EXIT, lue sur le clavier physique (les touches du téléphone ne peuvent
        // ni la masquer ni la simuler). En furtif profond, EXIT physique ne fait rien d'autre :
        // on la retire du traitement normal pour ne déclencher aucune autre fonction.
        static uint16_t stealthHold_10ms = 0;
        if (gK1R_Stealth && KEYBOARD_PollHardware() == KEY_EXIT) {
            if (gKeyFromSerial == KEY_INVALID)
                Key = KEY_INVALID;
            if (++stealthHold_10ms >= 200) {
                stealthHold_10ms = 0;
                K1R_SetStealth(false);
            }
        } else {
            stealthHold_10ms = 0;
        }
    }
#endif

    if (Key != KEY_INVALID) // any key pressed
        boot_counter_10ms = 0;   // cancel boot screen/beeps if any key pressed

    if (gKeyReading0 != Key) // new key pressed
    {

        if (gKeyReading0 != KEY_INVALID && Key != KEY_INVALID)
            ProcessKey(gKeyReading1, false, gKeyBeingHeld);  // key pressed without releasing previous key

        gKeyReading0     = Key;
        gDebounceCounter = 0;
        return;
    }

    gDebounceCounter++;

    if (gDebounceCounter == key_debounce_10ms) // debounced new key pressed
    {
        if (Key == KEY_INVALID) //all non PTT keys released
        {
            if (gKeyReading1 != KEY_INVALID) // some button was pressed before
            {
                ProcessKey(gKeyReading1, false, gKeyBeingHeld); // process last button released event
                gKeyReading1 = KEY_INVALID;
            }
        }
        else // process new key pressed
        {
            gKeyReading1 = Key;
            ProcessKey(Key, true, false);
        }

        gKeyBeingHeld = false;
        return;
    }

    if (gDebounceCounter < key_repeat_delay_10ms || Key == KEY_INVALID) // the button is not held long enough for repeat yet, or not really pressed
        return;

    if (gDebounceCounter == key_repeat_delay_10ms) //initial key repeat with longer delay
    {
        if (Key != KEY_PTT)
        {
            gKeyBeingHeld = true;
            ProcessKey(Key, true, true); // key held event
        }
    }
    else //subsequent fast key repeats
    {
        if (Key == KEY_UP || Key == KEY_DOWN) // fast key repeats for up/down buttons
        {
            gKeyBeingHeld = true;
            if ((gDebounceCounter % key_repeat_10ms) == 0)
                ProcessKey(Key, true, true); // key held event
        }

        if (gDebounceCounter < 0xFFFF)
            return;

        gDebounceCounter = key_repeat_delay_10ms+1;
    }
}

// Furtif complet K1 Remote (lu aussi par st7565.c et bk4819.c, d'où une définition hors option)
volatile bool gK1R_Stealth = false;

#ifdef ENABLE_FEAT_F4HWN_K5VIEWER
// =====================================================================
// K1 Remote — commandes directes envoyées par le téléphone
//   01 n lo hi : réglage n° n du menu (liste « All ») = valeur (lo | hi << 8)
//   02 f0 f1 f2 f3 : fréquence de la ligne active, en pas de 10 Hz (mode fréquence)
//   03 c… : code DTMF « UP » (menu 45 UPCode), 1 à 8 caractères 0-9 A-D * #
//   04 : présence (réponse « K1R » + version)
// Réponse : AA 55 09 00 len [op, état, …] 0A ; état 0 = fait.
// =====================================================================
#include "app/menu.h"
#include "k5viewer.h"
#include "driver/py25q16.h"
#include "fw_assets.h"
#include "ui/menu.h"

// Révision des ajouts K1 Remote (r1 PTT, r2 écran en émission, r3 réglages directs, r4 modèle/version,
// r5 retrait de l'écran en émission, r6 diagnostic, r7 écran affiché pour l'aide, r8 enregistrement immédiat des réglages directs, r9 PTT à distance aussi en mode une pression, r10 écran complet à la connexion, r11 origine de la « programmation PC » dans le diagnostic, r12 registres BK4819 dans le diagnostic, r13 nom court + correction du débordement d'écran au démarrage, r14 affichage borné sans casser les positions sur la ligne suivante)
#ifndef K1R_REVISION
#define K1R_REVISION "31"
#endif

// Enregistre tout de suite ce qu'une commande directe a changé, comme le fait
// ProcessKey() après chaque touche (étiquette Skip). Sans cela, le réglage restait
// en attente jusqu'à la touche suivante : un passage A/B l'appliquait alors à
// l'autre ligne et la ligne réglée revenait à son ancien état.
extern volatile uint16_t gK1R_LastCmdId;
extern volatile uint8_t  gK1R_LastCmdPort;

// Furtif complet (op 07) : afficheur LCD éteint et voyants coupés. L'image continue d'être
// calculée et envoyée à l'appli. Sécurité : sans nouvelles de l'appli pendant 6 s, l'afficheur
// se rallume tout seul (une radio sans aucun affichage et sans téléphone serait inutilisable).
static uint8_t gK1R_LinkCountdown_500ms = 0;

void K1R_LinkAlive(void)
{
    gK1R_LinkCountdown_500ms = 12;
}

// Appelé toutes les 500 ms (boucle principale, et aussi depuis le spectre)
/* Code DTMF reçu (identifiant de l'appelant) : capté quels que soient l'écran affiché, le
 * verrou du clavier, la double veille, le furtif ou le réglage D Live, puis envoyé à l'appli
 * une seconde après le dernier caractère : AA 55 09 00 len [08 00 n code…] 0A. */
static char    k1rDtmf[12];
static uint8_t k1rDtmfLen = 0;
static uint8_t k1rDtmfIdle = 0;

void K1R_DtmfChar(char c)
{
    if (k1rDtmfLen < sizeof(k1rDtmf)) k1rDtmf[k1rDtmfLen++] = c;
    k1rDtmfIdle = 0;
}

void K1R_Tick500ms(void)
{
    if (k1rDtmfLen > 0 && ++k1rDtmfIdle >= 2) {
        uint8_t d[3 + sizeof(k1rDtmf)] = { 0x08, 0, k1rDtmfLen };
        for (uint8_t i = 0; i < k1rDtmfLen; i++) d[3 + i] = (uint8_t)k1rDtmf[i];
        K5VIEWER_SendReply(d, (uint8_t)(3 + k1rDtmfLen));
        k1rDtmfLen = 0;
    }
    if (gK1R_Stealth && gK1R_LinkCountdown_500ms > 0 && --gK1R_LinkCountdown_500ms == 0)
        K1R_SetStealth(false);   // liaison perdue : l'afficheur revient
}

void K1R_SetStealth(bool on)
{
    gK1R_Stealth = on;
    if (on) {
        BK4819_ToggleGpioOut(BK4819_GPIO5_PIN1_RED, false);
        BK4819_ToggleGpioOut(BK4819_GPIO6_PIN2_GREEN, false);
        BACKLIGHT_TurnOff();         // matériel forcé à 0 tant que le furtif dure
        K1R_LinkAlive();
    } else {
        // Voyants recalculés selon l'état réel (et non l'ancien état) : rouge en émission,
        // vert si le squelch est ouvert ; éclairage normal rétabli
        BK4819_ToggleGpioOut(BK4819_GPIO5_PIN1_RED, gCurrentFunction == FUNCTION_TRANSMIT);
        BK4819_ToggleGpioOut(BK4819_GPIO6_PIN2_GREEN, g_SquelchLost);   // même règle que l'interruption squelch
        BACKLIGHT_TurnOn();
    }
    ST7565_ApplyDisplayOnOff();
}

static void K1R_Commit(void)
{
    if (gRequestSaveSettings) {
        SETTINGS_SaveSettings();
        gRequestSaveSettings = false;
        gUpdateStatus        = true;
    }

    if (gRequestSaveVFO) {
        gRequestSaveVFO = false;
        SETTINGS_SaveVfoIndices();
    }

    if (gRequestSaveChannel > 0) {
        SETTINGS_SaveChannel(gTxVfo->CHANNEL_SAVE, gEeprom.TX_VFO, gTxVfo, gRequestSaveChannel);
        if (!SCANNER_IsScanning() && gVfoConfigureMode == VFO_CONFIGURE_NONE)
            gVfoConfigureMode = VFO_CONFIGURE;
        gRequestSaveChannel = 0;
    }

    if (gVfoConfigureMode != VFO_CONFIGURE_NONE) {
        if (gFlagResetVfos) {
            RADIO_ConfigureChannel(0, gVfoConfigureMode);
            RADIO_ConfigureChannel(1, gVfoConfigureMode);
        }
        else
            RADIO_ConfigureChannel(gEeprom.TX_VFO, gVfoConfigureMode);

        gFlagReconfigureVfos = true;
        gVfoConfigureMode    = VFO_CONFIGURE_NONE;
        gFlagResetVfos       = false;
    }

    if (gFlagReconfigureVfos) {
#ifdef ENABLE_FEAT_F4HWN_FULL_WATCH
        APP_FullWatchReset();
#endif
        RADIO_SelectVfos();
        RADIO_SetupRegisters(true);
        gVFO_RSSI_bar_level[0] = 0;
        gVFO_RSSI_bar_level[1] = 0;
        gFlagReconfigureVfos   = false;
    }

    gUpdateDisplay = true;
}

/* =====================================================================
 *  K1R r28 : commande 0x09 « état de la radio », format v1 (32 octets).
 *  Contrat figé le 23 novembre 2026 (projet Radios, contrat-etat-radio-0x09-v1).
 *  Lecture seule. Toutes les valeurs sont traduites vers les énumérations du protocole :
 *  l'appli ne dépend jamais des valeurs internes de F4HWN. Valeur inconnue : 0xFF (octet),
 *  0xFFFF (16 bits), 0xFFFFFFFF (32 bits). Exécuté dans la boucle principale, comme toute
 *  modification de l'état radio : l'instantané est cohérent sans verrou.
 * ===================================================================== */
static void K1R_StatusLine(uint8_t *o, uint8_t i)
{
    const VFO_Info_t *v = &gEeprom.VfoInfo[i];
    const uint16_t ch = gEeprom.ScreenChannel[i];
    uint16_t num = 0xFFFF;
    uint8_t kind;
    if (IS_MR_CHANNEL(ch))        { kind = 0; num = (uint16_t)(ch - MR_CHANNEL_FIRST + 1); }
    else if (IS_FREQ_CHANNEL(ch)) { kind = 1; num = (uint16_t)(ch - FREQ_CHANNEL_FIRST + 1); }
#ifdef ENABLE_NOAA
    else if (IS_NOAA_CHANNEL(ch)) { kind = 2; num = (uint16_t)(ch - NOAA_CHANNEL_FIRST + 1); }
#endif
    else {                        // type inconnu : bloc à ignorer par l'appli
        memset(o, 0, 12);
        o[0] = 0xFF;
        return;
    }
    const uint32_t f = v->freq_config_RX.Frequency;          // déjà en unités de 10 Hz
    uint8_t mod;
    switch (v->Modulation) {
        case MODULATION_FM:  mod = 0; break;
        case MODULATION_AM:  mod = 1; break;
        case MODULATION_USB: mod = 2; break;
        default:             mod = (v->Modulation < MODULATION_UKNOWN) ? 3 : 0xFF; break;
    }
    uint8_t width = 0xFF;                                      // AM : filtre propre à l'AM
    if (v->Modulation != MODULATION_AM) {
        if (v->CHANNEL_BANDWIDTH == BANDWIDTH_WIDE) width = 0;
        else if (v->CHANNEL_BANDWIDTH == BANDWIDTH_NARROW) {
            width = 1;
#ifdef ENABLE_FEAT_F4HWN_NARROWER
            if (gSetting_set_nfm) width = 2;
#endif
        }
    }
    const uint8_t power = (v->OUTPUT_POWER <= OUTPUT_POWER_HIGH) ? (uint8_t)v->OUTPUT_POWER : 0xFF;
    const uint8_t flags = (v->RX_ONLY ? 0x01 : 0) | (v->TX_LOCK ? 0x02 : 0) |
        ((v->TX_OFFSET_FREQUENCY_DIRECTION != 0 && v->TX_OFFSET_FREQUENCY != 0) ? 0x04 : 0) |
        (v->FrequencyReverse ? 0x08 : 0);
    uint8_t list = 0;
    if (kind == 0)
        list = (v->SCANLIST_PARTICIPATION <= SCAN_LIST_MODE_ALL) ? v->SCANLIST_PARTICIPATION : 0xFF;

    o[0] = kind;
    o[1] = (uint8_t)num;  o[2] = (uint8_t)(num >> 8);
    o[3] = (uint8_t)f;    o[4] = (uint8_t)(f >> 8);  o[5] = (uint8_t)(f >> 16);  o[6] = (uint8_t)(f >> 24);
    o[7] = power; o[8] = mod; o[9] = width; o[10] = flags; o[11] = list;
}

/* Sous-canal (r29, format 2) : type 0 aucun, 1 CTCSS, 2 DCS N, 3 DCS I, 0xFF inconnu ;
 * valeur CTCSS en dixièmes de Hz, DCS = code octal pris comme entier (D023 → 0x0013).
 * L'indice est vérifié AVANT toute lecture de table. */
static void K1R_StatusTone(uint8_t *o, const FREQ_Config_t *fc)
{
    uint8_t  type = 0xFF;
    uint16_t val  = 0xFFFF;
    switch (fc->CodeType) {
        case CODE_TYPE_OFF:
            type = 0; val = 0;
            break;
        case CODE_TYPE_CONTINUOUS_TONE:
            type = 1;
            if (fc->Code < ARRAY_SIZE(CTCSS_Options)) val = CTCSS_Options[fc->Code];
            break;
        case CODE_TYPE_DIGITAL:
        case CODE_TYPE_REVERSE_DIGITAL:
            type = (fc->CodeType == CODE_TYPE_DIGITAL) ? 2 : 3;
            if (fc->Code < DCS_OPTION_COUNT) val = DCS_GetOption(fc->Code);
            break;
        default:
            break;
    }
    o[0] = type;
    o[1] = (uint8_t)val; o[2] = (uint8_t)(val >> 8);
}

/* Construit la réponse 0x09 au format 2 (48 octets) ; les 32 premiers ont le sens du format 1.
 * Contexte explicite : inSpectrum = appelé depuis la boucle du spectre (APP_RunSpectrum). */
void K1R_BuildStatus(uint8_t *d, bool inSpectrum)
{
    memset(d, 0, 48);
    d[0] = 0x09; d[1] = 0; d[2] = 2;
    d[3] = gEeprom.TX_VFO ? 1 : 0;

    uint8_t flags = 0;
    if (gEeprom.KEY_LOCK) flags |= 0x01;
    if (gEeprom.DUAL_WATCH != DUAL_WATCH_OFF) flags |= 0x02;
    if (gEeprom.CROSS_BAND_RX_TX != CROSS_BAND_OFF) flags |= 0x04;
#ifdef ENABLE_FEAT_F4HWN_NARROWER
    if (gSetting_set_nfm) flags |= 0x08;
#endif
    if (gK1R_Stealth) flags |= 0x10;
#ifdef ENABLE_FEAT_F4HWN_SLEEP
    if (gWakeUp) flags |= 0x20;
#endif
    d[4] = flags;

    uint8_t fn;
    switch (gCurrentFunction) {
        case FUNCTION_FOREGROUND:
        case FUNCTION_RECEIVE:    fn = 0; break;               // veille, squelch fermé
        case FUNCTION_TRANSMIT:   fn = 1; break;
        case FUNCTION_INCOMING:   fn = 2; break;               // signal reçu
        case FUNCTION_MONITOR:    fn = 3; break;
        case FUNCTION_POWER_SAVE: fn = 4; break;
        case FUNCTION_BAND_SCOPE: fn = 5; break;
        default:                  fn = 0xFF; break;
    }
    if ((fn == 0 || fn == 4) && gScanStateDir != SCAN_OFF)
        fn = 5;                                                // balayage en cours
    if (inSpectrum)
        fn = 5;                                                // appli spectre : information sûre
    d[5] = fn;

    K1R_StatusLine(&d[6], 0);
    K1R_StatusLine(&d[18], 1);

    // Ligne en réception : d'après l'adresse réelle de la ligne écoutée (gRxVfo), pas RX_VFO,
    // que la veille étendue et les applis en surimpression peuvent rendre trompeur.
    uint8_t rx = 0xFF;
    if (!inSpectrum && (fn == 2 || fn == 3)) {
        if (gRxVfo == &gEeprom.VfoInfo[0]) rx = 0;
        else if (gRxVfo == &gEeprom.VfoInfo[1]) rx = 1;
#ifdef ENABLE_FEAT_F4HWN_FULL_WATCH
        else if (gRxVfo != NULL && (gRxVfo == gFullWatchForegroundVfo ||
                 gRxVfo == gFullWatchBackgroundVfo[0] || gRxVfo == gFullWatchBackgroundVfo[1])) rx = 2;
#endif
    }
    d[30] = rx;
    d[31] = 0;

    // ---- Format 2 ----
    // Octet 32 : bit 0 réservé (radio FM : appli en surimpression dans Fusion, non observable),
    //            bit 1 = appli spectre active.
    d[32] = inSpectrum ? 0x02 : 0x00;
    for (uint8_t i = 0; i < 2; i++) {
        uint8_t *o = &d[33 + i * 6];
        if (d[6 + i * 12] == 0xFF) {            // ligne de type inconnu : sous-canaux inconnus
            o[0] = 0xFF; o[1] = 0xFF; o[2] = 0xFF; o[3] = 0xFF; o[4] = 0xFF; o[5] = 0xFF;
            continue;
        }
        K1R_StatusTone(&o[0], &gEeprom.VfoInfo[i].freq_config_RX);
        K1R_StatusTone(&o[3], &gEeprom.VfoInfo[i].freq_config_TX);
    }
    // 45 à 47 : réservés, 0
}

static void K1R_SendStatus(void)
{
    uint8_t d[48];
    K1R_BuildStatus(d, false);
    K5VIEWER_SendReply(d, sizeof(d));
}

/* r30 — op 0x0A « lire des réglages » (contrat K1R-contrat-lecture-reglages-0x0A.md v1.2).
 * Lecture seule : ne touche ni gSubMenuSelection, ni gMenuCursor, ni gScanUseCssResult ;
 * aucune écriture, aucune reconfiguration radio, aucun rafraîchissement d'écran.
 * Liste blanche fermée de 12 réglages (contrat v2.0, r31) ; tout autre menu répond s = 3, len = 0.
 * Renvoie le nombre d'octets de valeur écrits dans out (au plus 16), ou -1 si non lisible. */
static int8_t K1R_ReadSetting(uint8_t id, uint8_t *out)
{
    // Réglages de ligne lus sur le VFO annoncé dans la réponse (v = TX_VFO), et non via gTxVfo :
    // même indice pour l'identifiant renvoyé et pour les valeurs (revue r30, point a).
    const VFO_Info_t *vfo = &gEeprom.VfoInfo[gEeprom.TX_VFO < 2 ? gEeprom.TX_VFO : 0];
    uint16_t v;
    switch (id) {
        case MENU_SQL:        v = gEeprom.SQUELCH_LEVEL; break;
        // r31 : éclairage (témoin « Écran éteint » de l'appli). ABR lu comme le menu l'affiche :
        // le réglage d'origine si l'éclairage est surchargé temporairement (gBackLight).
#ifdef ENABLE_FEAT_F4HWN
        case MENU_ABR:        v = gBackLight ? gBacklightTimeOriginal : gEeprom.BACKLIGHT_TIME; break;
#else
        case MENU_ABR:        v = gEeprom.BACKLIGHT_TIME; break;
#endif
        case MENU_ABR_ON_TX_RX: v = gSetting_backlight_on_tx_rx; break;
        case MENU_BEEP:       v = gEeprom.BEEP_CONTROL; break;
#ifdef ENABLE_FEAT_F4HWN_SLEEP
        case MENU_SET_OFF:    v = gSetting_set_off; break;            // veille (SetOff, minutes)
#endif
        case MENU_D_ST:       v = gEeprom.DTMF_SIDE_TONE; break;
        case MENU_D_PRE:      v = gEeprom.DTMF_PRELOAD_TIME / 10; break;  // même unité que le menu
#ifdef ENABLE_FEAT_F4HWN_NARROWER
        case MENU_SET_NFM:    v = gSetting_set_nfm; break;
#endif
        case MENU_STEP:       v = FREQUENCY_GetSortedIdxFromStepIdx(vfo->STEP_SETTING); break;
        case MENU_PTT_ID:     v = (uint16_t)vfo->DTMF_PTT_ID_TX_MODE; break;
        case MENU_D_LIVE_DEC: v = gSetting_live_DTMF_decoder; break;
        case MENU_UPCODE:
            return (int8_t)K1R_CopyUpCode(gEeprom.DTMF_UP_CODE, sizeof(gEeprom.DTMF_UP_CODE), out);
        default:
            return -1;
    }
    out[0] = (uint8_t)v;
    out[1] = (uint8_t)(v >> 8);
    return 2;
}

static void K1R_ReadSettings(const uint8_t *c, uint8_t n)
{
    // Pire cas : 4 + 7 × (3 + 16) = 137 octets (7 demandes du code d'appel)
    uint8_t r[4 + 7 * (3 + K1R_UPCODE_MAX)];
    _Static_assert(sizeof(r) >= 4 + 7 * (3 + 16), "tampon 0x0A trop petit pour le pire cas");
    r[0] = 0x0A;
    const uint8_t count = (n >= 2) ? c[1] : 0;
    if (n < 2 || count < 1 || count > 7 || n != (uint8_t)(count + 2)) {
        r[1] = 1;                                   // trame mal formée : exactement 2 octets
        K5VIEWER_SendReply(r, 2);
        return;
    }
    uint8_t menus = 0;
    while (menus < 0xFE && MenuListId[menus] != 0xFF) menus++;   // borné, comme l'op 01
    r[1] = 0;
    r[2] = gEeprom.TX_VFO < 2 ? gEeprom.TX_VFO : 0; // VFO des réglages de ligne (STEP, PTT ID)
    r[3] = count;
    uint8_t off = 4;
    for (uint8_t i = 0; i < count; i++) {
        const uint8_t p = c[2 + i];
        uint8_t val[16];
        int8_t len = -1;
        uint8_t st;
        if (p < 1 || p > menus) st = 2;
        else {
            len = K1R_ReadSetting(MenuListId[p - 1], val);
            st = (len < 0) ? 3 : 0;
        }
        if (st != 0) len = 0;
        if ((uint16_t)off + 3 + (uint16_t)len > sizeof(r)) {
            // Impossible par construction (assertion ci-dessus). Jamais de réponse qui annoncerait
            // n entrées sans les contenir : on répond « commande non traitée » sur 2 octets.
            r[1] = 1;
            K5VIEWER_SendReply(r, 2);
            return;
        }
        r[off++] = p;
        r[off++] = st;
        r[off++] = (uint8_t)len;
        for (int8_t k = 0; k < len; k++) r[off++] = val[k];
    }
    K5VIEWER_SendReply(r, off);
}

static void K1R_RunCommand(void)
{
    uint8_t c[9], n = 0, status = 0;
    if (!KEYBOARD_TakeCommand(c, &n))
        return;
    K1R_LinkAlive();

    switch (c[0]) {
        case 0x01: {
            if (n < 4) { status = 1; break; }
            uint8_t count = 0;
            while (count < 0xFE && MenuListId[count] != 0xFF) count++;   // borné
            if (c[1] < 1 || c[1] > count) { status = 2; break; }
            const uint8_t id = MenuListId[c[1] - 1];
            // Sécurité : jamais par commande directe ce qui efface la radio, la calibre, change
            // les bandes autorisées en émission ou touche aux mémoires (réservé au clavier).
            switch (id) {
                case MENU_RESET:   case MENU_F_LOCK:  case MENU_350EN:
#ifdef ENABLE_F_CAL_MENU
                case MENU_F_CALI:
#endif
#ifndef ENABLE_FEAT_F4HWN
                case MENU_200TX:   case MENU_350TX:   case MENU_500TX:
#endif
                // MENU_MEM_CH (enregistrer la ligne active dans un canal) est permis depuis r27 :
                // c'est exactement ce que font les touches MENU 1 5, déjà pilotables à distance,
                // et la configuration d'équipe doit rester discrète.
                case MENU_DEL_CH:  case MENU_MEM_NAME:
                case MENU_TX_LOCK:          // contournerait le plan de bandes en émission
                case MENU_BATCAL:           // étalonnage de la batterie
#ifdef ENABLE_FEAT_F4HWN_MULTIBOOT
                case MENU_SET_CFG:          // changement de banque de configuration (canaux)
#endif
                    status = 3;
                    break;
                default:
                    break;
            }
            if (status) break;
            gSubMenuSelection = (int32_t)(c[2] | (c[3] << 8));   // seulement après autorisation
            MENU_AcceptSettingId(id);
            // Réglage direct : rien ne « sort du menu », il faut appliquer soi-même ce que la
            // radio applique d'habitude à la sortie (éclairage, inversion/contraste de l'écran).
            switch (id) {
                case MENU_ABR:
                case MENU_ABR_MIN:
                case MENU_ABR_MAX:
                    BACKLIGHT_TurnOn();      // ABR = 0 : éteint tout de suite, sinon niveau MAX
                    break;
                #ifdef ENABLE_FEAT_F4HWN
                case MENU_SET_INV:
                case MENU_SET_CTR:
                    ST7565_ContrastAndInv();
                    ST7565_ApplyDisplayOnOff();   // la réinitialisation ne doit pas rallumer en furtif
                    break;
                #endif
                default:
                    break;
            }
            gFlagReconfigureVfos = true;
            gUpdateDisplay = true;
            break;
        }
        case 0x02: {
            if (n < 5) { status = 1; break; }
            if (!IS_FREQ_CHANNEL(gTxVfo->CHANNEL_SAVE)) { status = 3; break; }   // ligne en mode canal
            uint32_t f = (uint32_t)c[1] | ((uint32_t)c[2] << 8) | ((uint32_t)c[3] << 16) | ((uint32_t)c[4] << 24);
            if (f < frequencyBandTable[0].lower || f > frequencyBandTable[BAND_N_ELEM - 1].upper ||
                (f >= BX4819_band1.upper && f < BX4819_band2.lower)) { status = 4; break; }
            const uint8_t Vfo = gEeprom.TX_VFO;
            const FREQUENCY_Band_t band = FREQUENCY_GetBand(f);
            if (gTxVfo->Band != band) {
                gTxVfo->Band               = band;
                gEeprom.ScreenChannel[Vfo] = band + FREQ_CHANNEL_FIRST;
                gEeprom.FreqChannel[Vfo]   = band + FREQ_CHANNEL_FIRST;
                SETTINGS_SaveVfoIndices();
                RADIO_ConfigureChannel(Vfo, VFO_CONFIGURE_RELOAD);
            }
            // Fréquence exacte : pas d'arrondi au pas (le téléphone choisit)
            gTxVfo->freq_config_RX.Frequency = f;
            gRequestSaveChannel = 1;
            gUpdateDisplay = true;
            break;
        }
        case 0x03: {
            char code[16];
            memset(code, 0, sizeof(code));
            if (n < 2) { status = 1; break; }
            for (uint8_t i = 1; i < n && i <= 8; i++) {
                const char ch = (char)c[i];
                if (!((ch >= '0' && ch <= '9') || (ch >= 'A' && ch <= 'D') || ch == '*' || ch == '#')) { status = 5; break; }
                code[i - 1] = ch;
            }
            if (status) break;
            memcpy(gEeprom.DTMF_UP_CODE, code, sizeof(gEeprom.DTMF_UP_CODE));
            PY25Q16_WriteBuffer(0x00A0F8 + 0x18, code, sizeof(code), false);
            break;
        }
        case 0x07:
            // Furtif complet : 07 1 = afficheur et voyants éteints, 07 0 = rétablis
            if (n < 2) { status = 1; break; }
            K1R_SetStealth(c[1] != 0);
            break;
        case 0x04:
            K5VIEWER_ForceFullFrame();     // connexion de l'appli : écran complet tout de suite
            break;
        case 0x06: {
            // Écran affiché (pour l'aide contextuelle de l'appli) :
            // 06 0 écran niveauMenu sousMenu n°(liste All) sélLo sélHi nom[7]
            uint8_t d[15] = { 0x06, 0, (uint8_t)gScreenToDisplay, 0xFF, gIsInSubMenu, 0, 0, 0 };
            if (gScreenToDisplay == DISPLAY_MENU) {
#ifdef ENABLE_FEAT_F4HWN_MENU_CAT
                d[3] = gMenuLevel;
                if (gMenuLevel == MENU_LEVEL_ITEMS)
#else
                d[3] = 1;
#endif
                {
                    const uint8_t idx = UI_MENU_GetMenuIdx(UI_MENU_GetCurrentMenuId());
                    d[5] = idx + 1;
                    d[6] = (uint8_t)gSubMenuSelection;
                    d[7] = (uint8_t)(gSubMenuSelection >> 8);
                    char name[MENU_NAME_SIZE];
                    FW_ASSETS_STRCPY(name, MenuListName, idx);
                    memcpy(&d[8], name, MENU_NAME_SIZE);
                }
            }
            K5VIEWER_SendReply(d, sizeof(d));
            return;
        }
        case 0x09:
            K1R_SendStatus();      // état de la radio, contrat 0x09 v1 (r28)
            return;
        case 0x0A:
            K1R_ReadSettings(c, n);   // lecture de réglages, contrat 0x0A v2.0 (r31)
            return;
        case 0x05: {
            // Diagnostic : état d'émission vu par la radio
            const uint32_t f = gTxVfo->pTX->Frequency;
            uint8_t d[43] = { 0x05, 0,
                (uint8_t)VfoState[0], (uint8_t)VfoState[1], gSetting_F_LOCK, gTxVfo->TX_LOCK,
                (uint8_t)gSerialConfigCountDown_500ms,
#ifdef ENABLE_TX1750
                gTx1750Active,
#else
                0,
#endif
                (uint8_t)gCurrentFunction, gBatteryDisplayLevel,
                (uint8_t)f, (uint8_t)(f >> 8), (uint8_t)(f >> 16), (uint8_t)(f >> 24),
                (uint8_t)gTxVfo->Modulation, gEeprom.TX_TIMEOUT_TIMER,
                gEeprom.KEY_1_SHORT_PRESS_ACTION, gEeprom.KEY_1_LONG_PRESS_ACTION,
                gEeprom.KEY_2_SHORT_PRESS_ACTION, gEeprom.KEY_2_LONG_PRESS_ACTION,
                gEeprom.KEY_M_LONG_PRESS_ACTION, gSetting_set_ptt_session,
                gEeprom.DTMF_SIDE_TONE, (uint8_t)gTxVfo->DTMF_PTT_ID_TX_MODE,
                !GPIO_IsInputPinSet(GPIO_PIN_PTT), gRemotePttActive, gPttIsPressed,
                (uint8_t)gTxTimerCountdown_500ms,
                (uint8_t)gK1R_LastCmdId, (uint8_t)(gK1R_LastCmdId >> 8), gK1R_LastCmdPort,
                0,0, 0,0, 0,0, 0,0, 0,0, 0,0 };
            // Registres du BK4819 (puce radio) : générateur de tonalité, sortie audio, chaîne TX
            static const uint8_t regs[6] = { 0x70, 0x71, 0x47, 0x30, 0x50, 0x31 };
            for (uint8_t i = 0; i < 6; i++) {
                const uint16_t v = BK4819_ReadRegister((BK4819_REGISTER_t)regs[i]);
                d[31 + 2 * i] = (uint8_t)v;
                d[32 + 2 * i] = (uint8_t)(v >> 8);
            }
            K5VIEWER_SendReply(d, sizeof(d));
            return;
        }
        default:
            status = 9;
    }

    if (status == 0 && (c[0] == 0x01 || c[0] == 0x02 || c[0] == 0x03))
        K1R_Commit();

    // Présence : « K1R », version du protocole, modèle (selon SetNav : 0 = UV-K1, 1 = UV-K5 V3),
    // puis la version du firmware (ex. « v7.0.0 »)
    // 24 octets : « v7.0.0 r23 » (10 caractères) dépassait l'ancien tampon de 16 → « r2 »
    uint8_t reply[24] = { c[0], status, 'K', '1', 'R', 6, gEeprom.SET_NAV ? 1 : 0 };   // protocole 6 : 0x0A + éclairage (r31) ; 5 : op 0x0A (r30) ; 4 : 0x09 format 2 (r29)
    uint8_t len = 2;
    if (c[0] == 0x04) {
        len = 7;
        for (const char *v = VERSION_STRING_2 " r" K1R_REVISION; *v && len < sizeof(reply); v++) reply[len++] = (uint8_t)*v;
    }
    K5VIEWER_SendReply(reply, len);
}
#endif

void APP_TimeSlice10ms(void)
{
    gNextTimeslice = false;

#ifdef ENABLE_FEAT_F4HWN_K5VIEWER
    K1R_RunCommand();
#endif

    SETTINGS_SaveVfoIndicesFlush();

#ifdef ENABLE_FEAT_F4HWN_RXTX_LOG
    RXTX_LOG_Task10ms();
#endif

    BACKLIGHT_Update();

    gFlashLightBlinkCounter++;

#ifdef ENABLE_UART
    if (UART_IsCommandAvailable(UART_PORT_UART)) {
        // SCHEDULER_Disable();
        UART_HandleCommand(UART_PORT_UART);
        // SCHEDULER_Enable();
    }
#endif

    if (gReducedService)
        return;

    UI_MAIN_TimeSlice10ms();   // scan-list name hold countdown (10 ms resolution)

    if (gCurrentFunction != FUNCTION_POWER_SAVE || !gRxIdleMode)
        CheckRadioInterrupts();
#ifdef ENABLE_FEAT_F4HWN_ACTION_PICKER
    if (gActionPickerKey != 0 && FUNCTION_IsRx()) {
        gActionPickerKey = 0;
        gUpdateDisplay = true;
    }
#endif


    if (gCurrentFunction == FUNCTION_TRANSMIT)
    {   // transmitting
#if defined(ENABLE_AUDIO_BAR) && !defined(ENABLE_FEAT_F4HWN_AUDIO_SCOPE)
        if (gSetting_mic_bar && (gFlashLightBlinkCounter % (150 / 10)) == 0) // once every 150ms
            UI_DisplayAudioBar();
#endif
    }

#ifdef ENABLE_FEAT_F4HWN_AUDIO_SCOPE
    if (gSetting_mic_bar && (gFlashLightBlinkCounter % (20 / 10)) == 0) // once every 20ms
        // Sample audio amplitude and refresh display during TX only (FM RX has no usable audio register)
        UI_DisplayAudioScope();
#endif

    bool gUpdateDisplayCurrent = gUpdateDisplay;
    bool gUpdateStatusCurrent  = gUpdateStatus;

    if (gUpdateDisplayCurrent) {
        gUpdateDisplay = false;
    }

#ifdef ENABLE_FEAT_F4HWN_LOGO_SAV
#ifdef ENABLE_FEAT_F4HWN_K5VIEWER
    bool screenSaverRendered = false;
#endif

    if (gScreenSaverDisplayed) {
        if (gUpdateDisplayCurrent) {
            gUpdateDisplayCurrent = false;
        } else if (gUpdateStatusCurrent) {
            gUpdateStatusCurrent = false;
            gUpdateStatus = false;
        }

        if (ScreenSaverAnimate()) {
#ifdef ENABLE_FEAT_F4HWN_K5VIEWER
            screenSaverRendered = true;
#endif
        }
    }
#endif

    if (gUpdateDisplayCurrent) {
        GUI_DisplayScreen();
    }

    if (gUpdateStatusCurrent) {
        UI_DisplayStatus();
    }

    #ifdef ENABLE_FEAT_F4HWN_K5VIEWER
    if (gUpdateDisplayCurrent || gUpdateStatusCurrent
#ifdef ENABLE_FEAT_F4HWN_LOGO_SAV
        || screenSaverRendered
#endif
        ) {
        K5VIEWER_Update(false);
    } else if (K5VIEWER_HasPendingStateChange()) {
        K5VIEWER_Update(false);
    }
    #endif

    // Skipping authentic device checks

#ifdef ENABLE_FMRADIO_EMBEDDED
    if (gFmRadioMode && gFmRadioCountdown_500ms > 0)   // 1of11
        return;
#endif

#if !defined(ENABLE_FEAT_F4HWN) || defined(ENABLE_FEAT_F4HWN_RESCUE_OPS)
    #ifdef ENABLE_FLASHLIGHT
        FlashlightTimeSlice();
    #endif
#endif

#ifdef ENABLE_VOX
    if (gVoxResumeCountdown > 0)
        gVoxResumeCountdown--;

    if (gVoxPauseCountdown > 0)
        gVoxPauseCountdown--;
#endif

    if (gCurrentFunction == FUNCTION_TRANSMIT) {
        // repeater tail tone elimination
        if (gRTTECountdown_10ms > 0) {
            if (--gRTTECountdown_10ms == 0) {
                //if (gCurrentFunction != FUNCTION_FOREGROUND)
                    FUNCTION_Select(FUNCTION_FOREGROUND);

                gUpdateStatus  = true;
                gUpdateDisplay = true;
            }
        }
    }

#ifdef ENABLE_FMRADIO_EMBEDDED
    if (gFmRadioMode && gFM_RestoreCountdown_10ms > 0) {
        if (--gFM_RestoreCountdown_10ms == 0) { 
            FM_Start(); // switch back to FM radio mode
            GUI_SelectNextDisplay(DISPLAY_FM);
        }
    }
#endif


    SCANNER_TimeSlice10ms();

#if defined(ENABLE_SCAN_RANGES) && defined(ENABLE_FEAT_F4HWN_SCAN_SUBAUDIBLE) && ENABLE_FEAT_F4HWN_SCAN_SUBAUDIBLE
    CHFRSCANNER_UpdateCssDetection();
#endif

#ifdef ENABLE_AIRCOPY
    if (gScreenToDisplay == DISPLAY_AIRCOPY && gAircopyState == AIRCOPY_TRANSFER) {
        if (!AIRCOPY_SendMessage()) {
            GUI_DisplayScreen();
        }
    }
#endif

    CheckKeys();
}

void cancelUserInputModes(void)
{
    if (gDTMF_InputMode || gDTMF_InputBox_Index > 0)
    {
        DTMF_clear_input_box();
        gRequestDisplayScreen = DISPLAY_MAIN;
        gUpdateDisplay        = true;
    }

    if (gWasFKeyPressed || gKeyInputCountdown > 0 || gInputBoxIndex > 0)
    {
        HideFKeyIcon();

        gInputBoxIndex      = 0;
        gKeyInputCountdown  = 0;
        gUpdateDisplay      = true;
    }
}

// this is called once every 500ms
void APP_TimeSlice500ms(void)
{
    gNextTimeslice_500ms = false;
#ifdef ENABLE_FEAT_F4HWN_K5VIEWER
    K1R_Tick500ms();
#endif
#ifdef ENABLE_FEAT_F4HWN_ACTION_PICKER
    if (gActionPickerKey != 0 && gActionPickerTimeout_500ms > 0 &&
        --gActionPickerTimeout_500ms == 0)
    {
        gActionPickerKey = 0;
        gUpdateDisplay = true;
    }
#endif

    bool exit_menu = false;

    // Skipped authentic device check

    if (gKeypadLocked > 0)
        if (--gKeypadLocked == 0)
            gUpdateDisplay = true;

#ifdef ENABLE_FEAT_F4HWN_RXTX_LOG
    RXTX_LOG_Tick500ms();
#endif

#ifdef ENABLE_FEAT_F4HWN_RX_TX_TIMER
    if (gSetting_set_tmr && (gCurrentFunction == FUNCTION_TRANSMIT || FUNCTION_IsRx())) {
        const uint16_t timerCountdown = (gCurrentFunction == FUNCTION_TRANSMIT)
            ? gTxTimerCountdown_500ms
            : gRxTimerCountdown_500ms;

        if ((timerCountdown & 1u) != 0u)
            gUpdateStatus = true;
    }
#endif

    if (gKeyInputCountdown > 0)
    {
        if (--gKeyInputCountdown == 0)
        {

            if (IS_MR_CHANNEL(gTxVfo->CHANNEL_SAVE) && (gInputBoxIndex > 0 && gInputBoxIndex < 4)
#ifdef ENABLE_FMRADIO_EMBEDDED
                && (!gFmRadioMode)
#endif
                )
            {
                channelMoveSwitch();

                if (gBeepToPlay == BEEP_500HZ_60MS_DOUBLE_BEEP_OPTIONAL) {
                    AUDIO_PlayBeep(gBeepToPlay);
                }
            }

            cancelUserInputModes();
            gHasVfoBackup = false;
        }
    }

    if (gDTMF_RX_live_timeout > 0)
    {
        #ifdef ENABLE_RSSI_BAR
            if (center_line == CENTER_LINE_DTMF_DEC ||
                center_line == CENTER_LINE_NONE)  // wait till the center line is free for us to use before timing out
        #endif
        {
            if (--gDTMF_RX_live_timeout == 0)
            {
                if (gDTMF_RX_live[0] != 0)
                {
                    DTMF_clear_input_box_memory();
                    gUpdateDisplay   = true;
                }
            }
        }
    }

    if (gMenuCountdown > 0)
        if (--gMenuCountdown == 0)
            exit_menu = (gScreenToDisplay == DISPLAY_MENU); // exit menu mode

#ifdef ENABLE_DTMF_CALLING
    if (gDTMF_RX_timeout > 0)
        if (--gDTMF_RX_timeout == 0)
            DTMF_clear_RX();
#endif

    // Skipped authentic device check

#ifdef ENABLE_FMRADIO_EMBEDDED
    if (gFmRadioCountdown_500ms > 0)
    {
        gFmRadioCountdown_500ms--;
        if (gFmRadioMode)           // 1of11
            return;
    }
#endif

    const int m = UI_MENU_GetCurrentMenuId();

    if (gBacklightCountdown_500ms > 0 && !gAskToSave && !gCssBackgroundScan
        // don't turn off backlight if user is in backlight menu option
        && !(gScreenToDisplay == DISPLAY_MENU && (m == MENU_ABR || m == MENU_ABR_MAX || m == MENU_ABR_MIN))
        && --gBacklightCountdown_500ms == 0
        && gEeprom.BACKLIGHT_TIME < 61
    ) {
        BACKLIGHT_TurnOff();
#ifdef ENABLE_FEAT_F4HWN_LOGO_SAV
        ScreenSaverTryDisplay(false);
#endif
    }

#ifdef ENABLE_FEAT_F4HWN_SLEEP
    if (gSleepModeCountdown_500ms == gSetting_set_off * 120 && gWakeUp) {
        //ST7565_Init();
        ST7565_FixInterfGlitch();
#ifdef ENABLE_FEAT_F4HWN_LOGO_SAV
        ScreenSaverExit();
#endif
        BK4819_ToggleGpioOut(BK4819_GPIO5_PIN1_RED, false);
        gPowerSave_10ms = gEeprom.BATTERY_SAVE * 10;
        gWakeUp = false;
        gUpdateDisplay = true;
        gUpdateStatus = true;
    }

    if(gCurrentFunction != FUNCTION_TRANSMIT && !FUNCTION_IsRx()
    #ifdef ENABLE_AIRCOPY
        && gScreenToDisplay != DISPLAY_AIRCOPY
    #endif
    #ifdef ENABLE_FEAT_F4HWN_BEAM
        && !gBeamActive
    #endif
    )
    {
        if (gSleepModeCountdown_500ms > 0 && --gSleepModeCountdown_500ms == 0) {
            gBacklightCountdown_500ms = 0;
            gPowerSave_10ms = 1;
            gWakeUp = true;
#ifdef ENABLE_FEAT_F4HWN_LOGO_SAV
            ScreenSaverExit();
#endif
            // TODO:
            // PWM_PLUS0_CH0_COMP = 0;
            BACKLIGHT_SetBrightness(0);
            ST7565_ShutDown();
        }
        else if(gSleepModeCountdown_500ms != 0 && gSleepModeCountdown_500ms < 21 && gSetting_set_off != 0)
        {
            if(gSleepModeCountdown_500ms % 4 == 0)
            {
                // PWM_PLUS0_CH0_COMP = value[gEeprom.BACKLIGHT_MAX] * 4; // Max brightness
                BACKLIGHT_SetBrightness(gEeprom.BACKLIGHT_MAX);
            }
            else
            {
                // PWM_PLUS0_CH0_COMP = 0;
                BACKLIGHT_SetBrightness(0);
            }
        }
    }
    else
    {
        gSleepModeCountdown_500ms = gSetting_set_off * 120;
    }

    if (gWakeUp) {
        static uint8_t counter = 0;
        counter = (counter + 1) % 4;
        BK4819_ToggleGpioOut(BK4819_GPIO5_PIN1_RED, (counter == 0));
    }
#endif

    if (gReducedService)
    {
        BOARD_ADC_GetBatteryInfo(&gBatteryCurrentVoltage, &gBatteryCurrent);

        if (gBatteryCurrent > 500 || gBatteryCalibration[3] < gBatteryCurrentVoltage)
        {
            #ifdef ENABLE_OVERLAY
                overlay_FLASH_RebootToBootloader();
            #else
                NVIC_SystemReset();
            #endif
        }

        return;
    }

    gBatteryCheckCounter++;

    // Skipped authentic device check

    if (gCurrentFunction != FUNCTION_TRANSMIT)
    {

        if ((gBatteryCheckCounter & 1) == 0)
        {
#if defined(ENABLE_FEAT_F4HWN_OVERLAY_APPS) || defined(ENABLE_FEAT_F4HWN_FOXHUNT) || defined(ENABLE_FEAT_F4HWN_BEACON)
            BATTERY_Sample(true);
#else
            BOARD_ADC_GetBatteryInfo(&gBatteryVoltages[gBatteryVoltageIndex++], &gBatteryCurrent);
            if (gBatteryVoltageIndex > 3)
                gBatteryVoltageIndex = 0;
            BATTERY_GetReadings(true);
#endif
        }
    }

    // regular display updates (once every 2 sec) - if need be
    if ((gBatteryCheckCounter & 3) == 0)
    {
        if (gChargingWithTypeC || gSetting_battery_text > 0)
            gUpdateStatus = true;
        #ifdef ENABLE_SHOW_CHARGE_LEVEL
            if (gChargingWithTypeC)
                gUpdateDisplay = true;
        #endif
    }

    if (!gCssBackgroundScan && gScanStateDir == SCAN_OFF && !SCANNER_IsScanning()
#ifdef ENABLE_FMRADIO_EMBEDDED
        && (gFM_ScanState == FM_SCAN_OFF || gAskToSave)
#endif
#ifdef ENABLE_AIRCOPY
        && gScreenToDisplay != DISPLAY_AIRCOPY
#endif
#ifdef ENABLE_FEAT_F4HWN_BEAM
        && !gBeamActive
#endif
    ) {
        if (gEeprom.AUTO_KEYPAD_LOCK && gKeyLockCountdown > 0 && !gDTMF_InputMode
            && gScreenToDisplay != DISPLAY_MENU && --gKeyLockCountdown == 0)
        {
            gEeprom.KEY_LOCK = true;     // lock the keyboard
            gUpdateStatus = true;            // lock symbol needs showing
#ifdef ENABLE_FEAT_F4HWN_ACTION_PICKER
            gActionPickerKey = 0;
            gUpdateDisplay = true;
#endif
        }

        if (exit_menu) {
            gMenuCountdown = 0;
            gScanMixEditorActive = false;

            const int m = UI_MENU_GetCurrentMenuId();

            if (gScreenToDisplay == DISPLAY_MENU && (m == MENU_ABR || m == MENU_ABR_MAX || m == MENU_ABR_MIN)) {
                BACKLIGHT_TurnOn();
            }

            if (gInputBoxIndex > 0 || gDTMF_InputMode) {
                AUDIO_PlayBeep(BEEP_500HZ_60MS_DOUBLE_BEEP_OPTIONAL);
            }
/*
            if (SCANNER_IsScanning()) {
                BK4819_StopScan();

                RADIO_ConfigureChannel(0, VFO_CONFIGURE_RELOAD);
                RADIO_ConfigureChannel(1, VFO_CONFIGURE_RELOAD);

                RADIO_SetupRegisters(true);
            }
*/
            DTMF_clear_input_box();

            HideFKeyIcon();
            gInputBoxIndex   = 0;

            gAskToSave       = false;
            gAskToDelete     = false;

            gUpdateDisplay   = true;

            GUI_DisplayType_t disp = DISPLAY_INVALID;

#ifdef ENABLE_FMRADIO_EMBEDDED
            if (gFmRadioMode && ! FUNCTION_IsRx()) {
                disp = DISPLAY_FM;
            }
#endif

            if (disp == DISPLAY_INVALID
#ifdef ENABLE_NO_CODE_SCAN_TIMEOUT
                && !SCANNER_IsScanning()
#endif
            ) {
                disp = DISPLAY_MAIN;
            }

            if (disp != DISPLAY_INVALID) {
                GUI_SelectNextDisplay(disp);
            }
        }
    }

    if (!gPttIsPressed && gVFOStateResumeCountdown_500ms > 0 && --gVFOStateResumeCountdown_500ms == 0) {
            RADIO_SetVfoState(VFO_STATE_NORMAL);
#ifdef ENABLE_FMRADIO_EMBEDDED
        if (gFmRadioMode && !FUNCTION_IsRx()) {
            // switch back to FM radio mode
            FM_Start();
            GUI_SelectNextDisplay(DISPLAY_FM);
        }
#endif
    }

#ifdef ENABLE_FEAT_F4HWN_LOGO_SAV
    if (gBacklightCountdown_500ms == 0 &&
        gEeprom.BACKLIGHT_TIME > 0 &&
        gEeprom.BACKLIGHT_TIME < 61 &&
        !gAskToSave &&
        !gCssBackgroundScan)
    {
        ScreenSaverTryDisplay(false);
    }
#endif

    BATTERY_TimeSlice500ms();
    SCANNER_TimeSlice500ms();
    UI_MAIN_TimeSlice500ms();

#ifdef ENABLE_DTMF_CALLING
    if (gCurrentFunction != FUNCTION_TRANSMIT) {
        if (gDTMF_DecodeRingCountdown_500ms > 0) {
            // make "ring-ring" sound
            gDTMF_DecodeRingCountdown_500ms--;
            AUDIO_PlayBeep(BEEP_880HZ_200MS);
        }
    } else {
        gDTMF_DecodeRingCountdown_500ms = 0;
    }

    if (gDTMF_CallState  != DTMF_CALL_STATE_NONE && gCurrentFunction != FUNCTION_TRANSMIT
        && gCurrentFunction != FUNCTION_RECEIVE && gDTMF_auto_reset_time_500ms > 0
        && --gDTMF_auto_reset_time_500ms == 0)
    {
        gUpdateDisplay  = true;
        if (gDTMF_CallState == DTMF_CALL_STATE_RECEIVED && gEeprom.DTMF_auto_reset_time >= DTMF_HOLD_MAX) {
            gDTMF_CallState = DTMF_CALL_STATE_RECEIVED_STAY;     // keep message on-screen till a key is pressed
        } else {
            gDTMF_CallState = DTMF_CALL_STATE_NONE;
        }
    }

    if (gDTMF_IsTx && gDTMF_TxStopCountdown_500ms > 0 && --gDTMF_TxStopCountdown_500ms == 0) {
        gDTMF_IsTx     = false;
        gUpdateDisplay = true;
    }
#endif
}

#ifdef ENABLE_TX1750
static void TX1750_Off(void)
{
    AUDIO_AudioPathOff();
    gEnableSpeaker = false;

    RADIO_SendEndOfTransmission();

    gTx1750Active = false;

#ifdef ENABLE_VOX
    gVoxResumeCountdown = 80;
#endif

    SYSTEM_DelayMs(5);

    RADIO_SetupRegisters(true);

    if (gScreenToDisplay != DISPLAY_MENU)     // 1of11 .. don't close the menu
        gRequestDisplayScreen = DISPLAY_MAIN;
}
#endif

static void ProcessKey(KEY_Code_t Key, bool bKeyPressed, bool bKeyHeld)
{
#ifdef ENABLE_FEAT_F4HWN_SLEEP
    if (gSleepWakeKey != KEY_INVALID) {
        if (Key == gSleepWakeKey && !bKeyPressed)
            gSleepWakeKey = KEY_INVALID;
        return;
    }

    if(gWakeUp)
    {
        if(bKeyPressed || Key == KEY_PTT)
        {
#ifdef ENABLE_FEAT_F4HWN_LOGO_SAV
            ScreenSaverExit();
#endif
            BACKLIGHT_TurnOn();

            if(Key != KEY_PTT)
            {
                gSleepWakeKey = Key;
                gBeepToPlay = BEEP_NONE;
                return;
            }
        }
        else if(Key != KEY_PTT)
        {
            Key = KEY_INVALID;
        }
    }
#endif

#ifdef ENABLE_FEAT_F4HWN_LOGO_SAV
    if (gScreenSaverWakeKey != KEY_INVALID) {
        if (Key == gScreenSaverWakeKey && !bKeyPressed)
            gScreenSaverWakeKey = KEY_INVALID;
        return;
    }

    if (bKeyPressed && gScreenSaverDisplayed) {
        ScreenSaverExit();
        BACKLIGHT_TurnOn();
        if (Key != KEY_PTT) {
            gScreenSaverWakeKey = Key;
            gBeepToPlay = BEEP_NONE;
            return;
        }
    }
#endif

    if (Key == KEY_EXIT && !BACKLIGHT_IsOn() && gEeprom.BACKLIGHT_TIME > 0)
    {   // just turn the light on for now so the user can see what's what
        BACKLIGHT_TurnOn();
        gBeepToPlay = BEEP_NONE;
        return;
    }

    if (gCurrentFunction == FUNCTION_POWER_SAVE)
        FUNCTION_Select(FUNCTION_FOREGROUND);

    gBatterySaveCountdown_10ms = battery_save_count_10ms;

    if (gEeprom.AUTO_KEYPAD_LOCK)
        gKeyLockCountdown = gEeprom.AUTO_KEYPAD_LOCK * 30;     // 15 seconds step

    if (!bKeyPressed) { // key released
        if (flagSaveVfo) {
            SETTINGS_SaveVfoIndices();
            flagSaveVfo = false;
        }

        if (flagSaveSettings) {
            SETTINGS_SaveSettings();
            flagSaveSettings = false;
        }

#ifdef ENABLE_FMRADIO_EMBEDDED
        if (gFlagSaveFM) {
            SETTINGS_SaveFM();
            gFlagSaveFM = false;
        }
#endif

        if (flagSaveChannel) {
            SETTINGS_SaveChannel(gTxVfo->CHANNEL_SAVE, gEeprom.TX_VFO, gTxVfo, flagSaveChannel);
            flagSaveChannel = false;

            if (!SCANNER_IsScanning() && gVfoConfigureMode == VFO_CONFIGURE_NONE)
                // gVfoConfigureMode is so as we don't wipe out previously setting this variable elsewhere
                gVfoConfigureMode = VFO_CONFIGURE;
        }
    }
    else { // key pressed or held
        const int m = UI_MENU_GetCurrentMenuId();
        if  (   //not when PTT and the backlight shouldn't turn on on TX
                !(Key == KEY_PTT && !(gSetting_backlight_on_tx_rx & BACKLIGHT_ON_TR_TX))
                // not in the backlight menu
                && !(gScreenToDisplay == DISPLAY_MENU && ( m == MENU_ABR || m == MENU_ABR_MAX || m == MENU_ABR_MIN))
            )
        {
            BACKLIGHT_TurnOn();
        }

        if (Key == KEY_EXIT && bKeyHeld
#ifdef ENABLE_FEAT_F4HWN_ACTION_PICKER
            && gActionPickerKey == 0
#endif
        ) { // exit key held pressed
            // clear the live DTMF decoder
            if (gDTMF_RX_live[0] != 0) {
                DTMF_clear_input_box_memory();
                gDTMF_RX_live_timeout = 0;
                gUpdateDisplay        = true;
            }

            // cancel user input
            cancelUserInputModes();
            gBeepToPlay = BEEP_1KHZ_60MS_OPTIONAL;

            if (gMonitor)
                ACTION_Monitor(); //turn off the monitor
#ifdef ENABLE_SCAN_RANGES
            gScanRangeStart = 0;
#endif
        }

        if (gScreenToDisplay == DISPLAY_MENU)       // 1of11
            gMenuCountdown = menu_timeout_500ms;

#ifdef ENABLE_DTMF_CALLING
        if (gDTMF_DecodeRingCountdown_500ms > 0) { // cancel the ringing
            gDTMF_DecodeRingCountdown_500ms = 0;

            AUDIO_PlayBeep(BEEP_1KHZ_60MS_OPTIONAL);

            if (Key != KEY_PTT) {
                gPttWasReleased = true;
                return;
            }
        }
#endif
    }

    bool lowBatPopup = gLowBattery && !gLowBatteryConfirmed &&  gScreenToDisplay == DISPLAY_MAIN;
#ifdef ENABLE_FEAT_F4HWN_ACTION_PICKER
    if (gActionPickerKey != 0 &&
        (gEeprom.KEY_LOCK || lowBatPopup || gScreenToDisplay != DISPLAY_MAIN))
        gActionPickerKey = 0;
    if (ACTION_PickerProcessKey(Key, bKeyPressed, bKeyHeld))
        goto Skip;
#endif


#ifdef ENABLE_FEAT_F4HWN // Disable PTT if KEY_LOCK
    bool lck_condition = (gEeprom.KEY_LOCK || lowBatPopup) && gCurrentFunction != FUNCTION_TRANSMIT;

    if((gSetting_set_lck & SET_LCK_PTT) == 0)
        lck_condition = lck_condition && Key != KEY_PTT;

    if (lck_condition)
#else
    if ((gEeprom.KEY_LOCK || lowBatPopup) && gCurrentFunction != FUNCTION_TRANSMIT && Key != KEY_PTT)
#endif
    {   // keyboard is locked or low battery popup

        // close low battery popup
        if(Key == KEY_EXIT && bKeyPressed && lowBatPopup) {
            gLowBatteryConfirmed = true;
            gUpdateDisplay = true;
            AUDIO_PlayBeep(BEEP_1KHZ_60MS_OPTIONAL);
            return;
        }

        // The ACTIONS restriction only applies when the keypad is actually
        // locked: this block is also entered for the low battery popup, where
        // action keys must keep working as before
        const bool passActionKey = ((gSetting_set_lck & SET_LCK_ACTIONS) == 0 || !gEeprom.KEY_LOCK) &&
                                   (Key == KEY_SIDE1 || Key == KEY_SIDE2 || (Key == KEY_MENU && bKeyHeld));

        if (Key == KEY_F) { // function/key-lock key
            if (!bKeyPressed)
                return;

            if (!bKeyHeld) { // keypad is locked, tell the user
                AUDIO_PlayBeep(BEEP_500HZ_60MS_DOUBLE_BEEP_OPTIONAL);
                gKeypadLocked  = 4;      // 2 seconds
                gUpdateDisplay = true;
                return;
            }
        }
        // KEY_MENU has a special treatment here, because we want to pass hold event to ACTION_Handle
        // but we don't want it to complain when initial press happens
        // we want to react on realese instead
        else if (!passActionKey)
        {
            if ((!bKeyPressed || bKeyHeld || (Key == KEY_MENU && bKeyPressed)) && // prevent released or held, prevent KEY_MENU pressed
                !(Key == KEY_MENU && !bKeyPressed))  // pass KEY_MENU released
                return;

            // keypad is locked, tell the user
            AUDIO_PlayBeep(BEEP_500HZ_60MS_DOUBLE_BEEP_OPTIONAL);
            gKeypadLocked  = 4;          // 2 seconds
            gUpdateDisplay = true;
            return;
        }
    }

    if (Key <= KEY_9 || Key == KEY_F) {
        //if (gScanStateDir != SCAN_OFF || gCssBackgroundScan) { // FREQ/CTCSS/DCS scanning
        if (gCssBackgroundScan) { // FREQ/CTCSS/DCS scanning
            if (bKeyPressed && !bKeyHeld)
                AUDIO_PlayBeep(BEEP_500HZ_60MS_DOUBLE_BEEP_OPTIONAL);
            return;
        }
    }

    bool bFlag = false;
    if (Key == KEY_PTT) {
        if (gPttWasPressed) {
            bFlag = bKeyHeld;
            if (!bKeyPressed) {
                bFlag          = true;
                gPttWasPressed = false;
            }
        }
    }
    else if (gPttWasReleased) {
        if (bKeyHeld)
            bFlag = true;
        if (!bKeyPressed) {
            bFlag           = true;
            gPttWasReleased = false;
        }
    }

#ifdef ENABLE_FEAT_F4HWN // For F + SIDE1 or F + SIDE2
    if (gWasFKeyPressed && (Key == KEY_PTT || Key == KEY_EXIT)) { 
#else
    if (gWasFKeyPressed && (Key == KEY_PTT || Key == KEY_EXIT || Key == KEY_SIDE1 || Key == KEY_SIDE2)) { 
#endif
        // cancel the F-key
        HideFKeyIcon();
    }

    if (bFlag) {
        goto Skip;
    }

    if (gCurrentFunction == FUNCTION_TRANSMIT) {
#ifdef ENABLE_TX1750
        if (!gTx1750Active)
#endif
        {
            char Code;

            if (Key == KEY_PTT) {
                GENERIC_Key_PTT(bKeyPressed);
                goto Skip;
            }

            if (Key == KEY_SIDE2) { // transmit 1750Hz tone
                Code = 0xFE;
            }
            else {
                Code = DTMF_GetCharacter(Key - KEY_0);
                if (Code == 0xFF)
                    goto Skip;
                // transmit DTMF keys
            }

            if (!bKeyPressed || bKeyHeld) {
                if (!bKeyPressed) {
                    AUDIO_AudioPathOff();

                    gEnableSpeaker = false;

                    BK4819_ExitDTMF_TX(false);

#ifndef ENABLE_FEAT_F4HWN
                    if (gCurrentVfo->SCRAMBLING_TYPE == 0 || !gSetting_ScrambleEnable)
                        BK4819_DisableScramble();
                    else
                        BK4819_EnableScramble(gCurrentVfo->SCRAMBLING_TYPE - 1);
#else
                        BK4819_DisableScramble();
#endif
                }
            }
            else {
                if (gEeprom.DTMF_SIDE_TONE) { // user will here the DTMF tones in speaker
                    AUDIO_AudioPathOn();
                    gEnableSpeaker = true;
                }

                BK4819_DisableScramble();

                if (Code == 0xFE)
                    BK4819_TransmitTone(gEeprom.DTMF_SIDE_TONE, 1750);
                else
                    BK4819_PlayDTMFEx(gEeprom.DTMF_SIDE_TONE, Code);
            }
        }
#ifdef ENABLE_TX1750
        else if (bKeyHeld != bKeyPressed) {
            TX1750_Off();

            if (gEeprom.REPEATER_TAIL_TONE_ELIMINATION == 0)
                FUNCTION_Select(FUNCTION_FOREGROUND);
            else
                gRTTECountdown_10ms = gEeprom.REPEATER_TAIL_TONE_ELIMINATION * 10;

            if (Key == KEY_PTT)
                gPttWasPressed  = true;
            else if (!bKeyHeld)
                gPttWasReleased = true;
        }
#endif
    }
    else if (
#ifdef ENABLE_FEAT_F4HWN_BEAM
            gBeamActive ||
#endif
            (gScreenToDisplay != DISPLAY_INVALID && (
            (Key != KEY_SIDE1 && Key != KEY_SIDE2)
#ifdef ENABLE_FEAT_F4HWN // For F + SIDE1 or F + SIDE2
            || (gWasFKeyPressed && (Key == KEY_SIDE1 || Key == KEY_SIDE2))
#endif
    ))) {
#ifdef ENABLE_FEAT_F4HWN_BEAM
        if (gBeamActive)
            BEAM_ProcessKeys(Key, bKeyPressed, bKeyHeld);
        else
#endif
        ProcessKeysFunctions[gScreenToDisplay](Key, bKeyPressed, bKeyHeld);
    }
    else if (!SCANNER_IsScanning()
#ifdef ENABLE_AIRCOPY
            && gScreenToDisplay != DISPLAY_AIRCOPY
#endif
    ) {
        ACTION_Handle(Key, bKeyPressed, bKeyHeld);
    }
    else if (!bKeyHeld && bKeyPressed) {
        gBeepToPlay = BEEP_500HZ_60MS_DOUBLE_BEEP_OPTIONAL;
    }

Skip:
    if (gBeepToPlay != BEEP_NONE) {
        AUDIO_PlayBeep(gBeepToPlay);
        gBeepToPlay = BEEP_NONE;
    }

    if (gFlagAcceptSetting) {
        gMenuCountdown = menu_timeout_500ms;

        MENU_AcceptSetting();

        gFlagRefreshSetting = true;
        gFlagAcceptSetting  = false;
    }

    if (gRequestSaveSettings) {
        if (!bKeyHeld)
            SETTINGS_SaveSettings();
        else
            flagSaveSettings = 1;
        gRequestSaveSettings = false;
        gUpdateStatus        = true;
    }

#ifdef ENABLE_FMRADIO_EMBEDDED
    if (gRequestSaveFM) {
        gRequestSaveFM = false;
        if (!bKeyHeld)
            SETTINGS_SaveFM();
        else
            gFlagSaveFM = true;
    }
#endif

    if (gRequestSaveVFO) {
        gRequestSaveVFO = false;
        if (!bKeyHeld)
            SETTINGS_SaveVfoIndices();
        else
            flagSaveVfo = true;
    }

    if (gRequestSaveChannel > 0) { // TODO: remove the gRequestSaveChannel, why use global variable for that??
        if ((!bKeyHeld && !bKeyPressed) || (UI_MENU_GetCurrentMenuId() != 0 && gScreenToDisplay == DISPLAY_MENU) || gScreenToDisplay == DISPLAY_SCANNER)
        {
            SETTINGS_SaveChannel(gTxVfo->CHANNEL_SAVE, gEeprom.TX_VFO, gTxVfo, gRequestSaveChannel);

            if (!SCANNER_IsScanning() && gVfoConfigureMode == VFO_CONFIGURE_NONE)
                // gVfoConfigureMode is so as we don't wipe out previously setting this variable elsewhere
                gVfoConfigureMode = VFO_CONFIGURE;
        }
        else { // this is probably so settings are not saved when up/down button is held and save is postponed to btn release
            flagSaveChannel = gRequestSaveChannel;

            if (gRequestDisplayScreen == DISPLAY_INVALID)
                gRequestDisplayScreen = DISPLAY_MAIN;
        }

        gRequestSaveChannel = 0;
    }

    if (gVfoConfigureMode != VFO_CONFIGURE_NONE) {
        if (gFlagResetVfos) {
            RADIO_ConfigureChannel(0, gVfoConfigureMode);
            RADIO_ConfigureChannel(1, gVfoConfigureMode);
        }
        else
            RADIO_ConfigureChannel(gEeprom.TX_VFO, gVfoConfigureMode);

        if (gRequestDisplayScreen == DISPLAY_INVALID)
            gRequestDisplayScreen = DISPLAY_MAIN;

        gFlagReconfigureVfos = true;
        gVfoConfigureMode    = VFO_CONFIGURE_NONE;
        gFlagResetVfos       = false;
    }

    if (gFlagReconfigureVfos) {
#ifdef ENABLE_FEAT_F4HWN_FULL_WATCH
        APP_FullWatchReset();
#endif
        RADIO_SelectVfos();

#ifdef ENABLE_NOAA
        RADIO_ConfigureNOAA();
#endif

        RADIO_SetupRegisters(true);

#ifdef ENABLE_DTMF_CALLING
        gDTMF_auto_reset_time_500ms = 0;
        gDTMF_CallState             = DTMF_CALL_STATE_NONE;
        gDTMF_TxStopCountdown_500ms = 0;
        gDTMF_IsTx                  = false;
#endif

        gVFO_RSSI_bar_level[0]      = 0;
        gVFO_RSSI_bar_level[1]      = 0;

        gFlagReconfigureVfos        = false;

        if (gMonitor)
            ACTION_Monitor();   // 1of11
    }

    if (gFlagRefreshSetting) {
        gFlagRefreshSetting = false;
        gMenuCountdown      = menu_timeout_500ms;

#ifdef ENABLE_FEAT_F4HWN_MENU_CAT
        if (gMenuLevel != MENU_LEVEL_CAT)
#endif
            MENU_ShowCurrentSetting();
    }

    if (gFlagPrepareTX) {
        RADIO_PrepareTX();
        gFlagPrepareTX = false;
    }

#ifdef ENABLE_VOICE
    if (gAnotherVoiceID != VOICE_ID_INVALID) {
        if (gAnotherVoiceID < 76)
            AUDIO_SetVoiceID(0, gAnotherVoiceID);
        AUDIO_PlaySingleVoice(false);
        gAnotherVoiceID = VOICE_ID_INVALID;
    }
#endif

    GUI_SelectNextDisplay(gRequestDisplayScreen);
    gRequestDisplayScreen = DISPLAY_INVALID;

    gUpdateDisplay = true;
}
