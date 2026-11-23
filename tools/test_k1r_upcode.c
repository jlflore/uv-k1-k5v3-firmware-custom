/* Test PC de K1R_CopyUpCode (contrat 0x0A v1.3, revue r30). Compilé et lancé par la CI. */
#include <stdio.h>
#include <string.h>
#include "../App/app/k1r_upcode.h"

static int fails = 0;
static void check(const char *what, const char *src, size_t size, const char *want, uint8_t wantLen)
{
    uint8_t out[32];
    memset(out, 0xEE, sizeof(out));
    const uint8_t n = K1R_CopyUpCode(src, size, out);
    if (n != wantLen || memcmp(out, want, wantLen) != 0 || out[wantLen] != 0xEE) {
        printf("ÉCHEC %s : %u octets\n", what, n);
        fails++;
    } else printf("ok   %s\n", what);
}

int main(void)
{
    char full[17]; memset(full, '7', 16); full[16] = '9';          // 16 caractères sans terminateur
    check("16 caractères sans terminateur", full, 16, "7777777777777777", 16);
    check("champ plus grand que 16 : borné", full, 17, "7777777777777777", 16);
    char empty[16] = { 0 };
    check("code vide", empty, 16, "", 0);
    char erased[16]; memset(erased, 0xFF, 16);
    check("mémoire effacée (0xFF)", erased, 16, "", 0);
    char mid[16] = { 'A', '1', 0, '5', '5' };
    check("terminateur en position 2", mid, 16, "A1", 2);
    char ff[16] = { 'B', '2', '3', (char)0xFF, '4' };
    check("0xFF en position 3", ff, 16, "B23", 3);
    check("champ de 8 octets", full, 8, "77777777", 8);
    return fails ? 1 : 0;
}
