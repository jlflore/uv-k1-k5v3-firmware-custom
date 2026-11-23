/* K1R r30 — copie bornée du code d'appel (contrat 0x0A v1.3).
 * Fichier sans dépendance : il est aussi compilé et testé sur PC par la CI
 * (tools/test_k1r_upcode.c). */
#ifndef K1R_UPCODE_H
#define K1R_UPCODE_H

#include <stddef.h>
#include <stdint.h>

#define K1R_UPCODE_MAX 16

/* Copie dans out les octets du code jusqu'au premier 0x00 ou 0xFF (exclu), au plus
 * min(size, 16). Le champ source n'a pas de terminateur garanti : jamais de strlen.
 * Renvoie le nombre d'octets copiés (0 à 16). */
static inline uint8_t K1R_CopyUpCode(const char *src, size_t size, uint8_t *out)
{
    uint8_t len = 0;
    while (len < size && len < K1R_UPCODE_MAX) {
        const uint8_t ch = (uint8_t)src[len];
        if (ch == 0x00 || ch == 0xFF)
            break;
        out[len++] = ch;
    }
    return len;
}

#endif
