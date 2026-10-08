// ============================================================

// YouLong Crypto Core (Self-Implemented, Zero Dependencies)
// ============================================================






//


//


// ============================================================

#ifndef YOULONG_CRYPTO_CORE_H
#define YOULONG_CRYPTO_CORE_H

#include <stdint.h>
#include <stddef.h>
#include <string.h>

#ifdef __cplusplus
extern "C" {
#endif


static void secure_clear(void* p, size_t n) {
    volatile uint8_t* vp = (volatile uint8_t*)p;
    while (n--) *vp++ = 0;
}

// ============================================================

// ============================================================

// ----------------------------------------------------------------------------




//





// ----------------------------------------------------------------------------


// ============================================================
// 开源版密钥说明（重要）：
// 本文件是开源快照，SEED/HMAC_SALT 的掩码材料全部公开在 .rodata，
// "解密密钥"可由 APK 离线重算 —— 开源版的资产加密是"混淆级"而非
// "保密级"。若要在正式版提供真实资产保护，需改为 Android Keystore
// 托管密钥（运行时生成、加密存储），并同步修改 Java 侧 AssetsEncryptor。
// ============================================================
static const uint8_t MASK_ORIGIN[16] = {
    0x5A, 0x3C, 0xF1, 0x27, 0x8E, 0x4B, 0xD6, 0x0F,
    0x39, 0xA8, 0x7E, 0xC4, 0x15, 0x92, 0x6D, 0xB3
};


static const uint8_t SEED_KEY_CIPHER[16] = {
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
};


static const uint8_t HMAC_SALT_CIPHER[16] = {
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
};

// mask_byte(i) = MASK_ORIGIN[i] ^ MASK_ORIGIN[(i+7)%16] ^ 0x3D
static uint8_t mask_byte(int i) {
    return (uint8_t)(MASK_ORIGIN[i] ^ MASK_ORIGIN[(i + 7) % 16] ^ 0x3D);
}


static const uint8_t* get_seed_key() {
    static uint8_t buf[16];
    for (int i = 0; i < 16; i++) {
        buf[i] = (uint8_t)(SEED_KEY_CIPHER[i] ^ mask_byte(i));
    }
    return buf;
}

static const uint8_t* get_hmac_salt() {
    static uint8_t buf[16];
    for (int i = 0; i < 16; i++) {
        buf[i] = (uint8_t)(HMAC_SALT_CIPHER[i] ^ mask_byte(i));
    }
    return buf;
}

// ============================================================

// ============================================================
typedef struct {
    uint32_t state[8];
    uint64_t bitlen;
    uint8_t buffer[64];
    size_t buflen;
} sha256_context;

static const uint32_t SHA256_K[64] = {
    0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
    0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
    0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
    0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
    0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
    0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
    0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
    0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2
};

static uint32_t rotr32(uint32_t x, int n) { return (x >> n) | (x << (32 - n)); }

static void sha256_transform(sha256_context* ctx, const uint8_t* block) {
    uint32_t w[64];
    for (int i = 0; i < 16; i++) {
        w[i] = ((uint32_t)block[i * 4] << 24) | ((uint32_t)block[i * 4 + 1] << 16) |
               ((uint32_t)block[i * 4 + 2] << 8) | (uint32_t)block[i * 4 + 3];
    }
    for (int i = 16; i < 64; i++) {
        uint32_t s0 = rotr32(w[i - 15], 7) ^ rotr32(w[i - 15], 18) ^ (w[i - 15] >> 3);
        uint32_t s1 = rotr32(w[i - 2], 17) ^ rotr32(w[i - 2], 19) ^ (w[i - 2] >> 10);
        w[i] = w[i - 16] + s0 + w[i - 7] + s1;
    }
    uint32_t a = ctx->state[0], b = ctx->state[1], c = ctx->state[2], d = ctx->state[3];
    uint32_t e = ctx->state[4], f = ctx->state[5], g = ctx->state[6], h = ctx->state[7];
    for (int i = 0; i < 64; i++) {
        uint32_t S1 = rotr32(e, 6) ^ rotr32(e, 11) ^ rotr32(e, 25);
        uint32_t ch = (e & f) ^ (~e & g);
        uint32_t t1 = h + S1 + ch + SHA256_K[i] + w[i];
        uint32_t S0 = rotr32(a, 2) ^ rotr32(a, 13) ^ rotr32(a, 22);
        uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
        uint32_t t2 = S0 + maj;
        h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2;
    }
    ctx->state[0] += a; ctx->state[1] += b; ctx->state[2] += c; ctx->state[3] += d;
    ctx->state[4] += e; ctx->state[5] += f; ctx->state[6] += g; ctx->state[7] += h;
}

static void sha256_init(sha256_context* ctx) {
    ctx->state[0] = 0x6a09e667; ctx->state[1] = 0xbb67ae85;
    ctx->state[2] = 0x3c6ef372; ctx->state[3] = 0xa54ff53a;
    ctx->state[4] = 0x510e527f; ctx->state[5] = 0x9b05688c;
    ctx->state[6] = 0x1f83d9ab; ctx->state[7] = 0x5be0cd19;
    ctx->bitlen = 0; ctx->buflen = 0;
}

static void sha256_update(sha256_context* ctx, const uint8_t* data, size_t len) {
    for (size_t i = 0; i < len; i++) {
        ctx->buffer[ctx->buflen++] = data[i];
        if (ctx->buflen == 64) {
            sha256_transform(ctx, ctx->buffer);
            ctx->bitlen += 512;
            ctx->buflen = 0;
        }
    }
}

static void sha256_final(sha256_context* ctx, uint8_t out[32]) {
    uint64_t bitlen = ctx->bitlen + (uint64_t)ctx->buflen * 8;
    ctx->buffer[ctx->buflen++] = 0x80;
    if (ctx->buflen > 56) {
        while (ctx->buflen < 64) ctx->buffer[ctx->buflen++] = 0;
        sha256_transform(ctx, ctx->buffer);
        ctx->buflen = 0;
    }
    while (ctx->buflen < 56) ctx->buffer[ctx->buflen++] = 0;
    for (int i = 0; i < 8; i++) {
        ctx->buffer[63 - i] = (uint8_t)(bitlen >> (8 * i));
    }
    sha256_transform(ctx, ctx->buffer);
    for (int i = 0; i < 8; i++) {
        out[i * 4]     = (uint8_t)(ctx->state[i] >> 24);
        out[i * 4 + 1] = (uint8_t)(ctx->state[i] >> 16);
        out[i * 4 + 2] = (uint8_t)(ctx->state[i] >> 8);
        out[i * 4 + 3] = (uint8_t)(ctx->state[i]);
    }
}

// ============================================================

// ============================================================
static const uint8_t SBOX[256] = {
    0x63,0x7c,0x77,0x7b,0xf2,0x6b,0x6f,0xc5,0x30,0x01,0x67,0x2b,0xfe,0xd7,0xab,0x76,
    0xca,0x82,0xc9,0x7d,0xfa,0x59,0x47,0xf0,0xad,0xd4,0xa2,0xaf,0x9c,0xa4,0x72,0xc0,
    0xb7,0xfd,0x93,0x26,0x36,0x3f,0xf7,0xcc,0x34,0xa5,0xe5,0xf1,0x71,0xd8,0x31,0x15,
    0x04,0xc7,0x23,0xc3,0x18,0x96,0x05,0x9a,0x07,0x12,0x80,0xe2,0xeb,0x27,0xb2,0x75,
    0x09,0x83,0x2c,0x1a,0x1b,0x6e,0x5a,0xa0,0x52,0x3b,0xd6,0xb3,0x29,0xe3,0x2f,0x84,
    0x53,0xd1,0x00,0xed,0x20,0xfc,0xb1,0x5b,0x6a,0xcb,0xbe,0x39,0x4a,0x4c,0x58,0xcf,
    0xd0,0xef,0xaa,0xfb,0x43,0x4d,0x33,0x85,0x45,0xf9,0x02,0x7f,0x50,0x3c,0x9f,0xa8,
    0x51,0xa3,0x40,0x8f,0x92,0x9d,0x38,0xf5,0xbc,0xb6,0xda,0x21,0x10,0xff,0xf3,0xd2,
    0xcd,0x0c,0x13,0xec,0x5f,0x97,0x44,0x17,0xc4,0xa7,0x7e,0x3d,0x64,0x5d,0x19,0x73,
    0x60,0x81,0x4f,0xdc,0x22,0x2a,0x90,0x88,0x46,0xee,0xb8,0x14,0xde,0x5e,0x0b,0xdb,
    0xe0,0x32,0x3a,0x0a,0x49,0x06,0x24,0x5c,0xc2,0xd3,0xac,0x62,0x91,0x95,0xe4,0x79,
    0xe7,0xc8,0x37,0x6d,0x8d,0xd5,0x4e,0xa9,0x6c,0x56,0xf4,0xea,0x65,0x7a,0xae,0x08,
    0xba,0x78,0x25,0x2e,0x1c,0xa6,0xb4,0xc6,0xe8,0xdd,0x74,0x1f,0x4b,0xbd,0x8b,0x8a,
    0x70,0x3e,0xb5,0x66,0x48,0x03,0xf6,0x0e,0x61,0x35,0x57,0xb9,0x86,0xc1,0x1d,0x9e,
    0xe1,0xf8,0x98,0x11,0x69,0xd9,0x8e,0x94,0x9b,0x1e,0x87,0xe9,0xce,0x55,0x28,0xdf,
    0x8c,0xa1,0x89,0x0d,0xbf,0xe6,0x42,0x68,0x41,0x99,0x2d,0x0f,0xb0,0x54,0xbb,0x16
};

static uint8_t xtime(uint8_t x) {
    return (uint8_t)((x << 1) ^ ((x & 0x80) ? 0x1b : 0x00));
}

static void aes256_key_expansion(const uint8_t* key, uint8_t* rk /* 240B */) {
    for (int i = 0; i < 8; i++) {
        rk[i * 4] = key[i * 4]; rk[i * 4 + 1] = key[i * 4 + 1];
        rk[i * 4 + 2] = key[i * 4 + 2]; rk[i * 4 + 3] = key[i * 4 + 3];
    }
    uint8_t rcon = 1;
    for (int i = 8; i < 60; i++) {
        uint8_t t[4];
        for (int j = 0; j < 4; j++) t[j] = rk[(i - 1) * 4 + j];
        if (i % 8 == 0) {
            uint8_t tmp = t[0]; t[0] = t[1]; t[1] = t[2]; t[2] = t[3]; t[3] = tmp; // RotWord
            for (int j = 0; j < 4; j++) t[j] = SBOX[t[j]];                          // SubWord
            t[0] ^= rcon;
            rcon = xtime(rcon);
        } else if (i % 8 == 4) {
            for (int j = 0; j < 4; j++) t[j] = SBOX[t[j]];
        }
        for (int j = 0; j < 4; j++) rk[i * 4 + j] = (uint8_t)(rk[(i - 8) * 4 + j] ^ t[j]);
    }
}

static void aes_add_round_key(uint8_t* state, const uint8_t* rk) {
    for (int i = 0; i < 16; i++) state[i] ^= rk[i];
}

static void aes_sub_bytes(uint8_t* state) {
    for (int i = 0; i < 16; i++) state[i] = SBOX[state[i]];
}

static void aes_shift_rows(uint8_t* s) {
    uint8_t t;
    t = s[1];  s[1] = s[5];  s[5] = s[9];  s[9] = s[13];  s[13] = t;  
    t = s[2];  s[2] = s[10]; s[10] = t;                                 
    t = s[6];  s[6] = s[14]; s[14] = t;
    t = s[15]; s[15] = s[11]; s[11] = s[7]; s[7] = s[3]; s[3] = t;      
}

static void aes_mix_columns(uint8_t* s) {
    for (int c = 0; c < 4; c++) {
        uint8_t* p = s + c * 4;
        uint8_t a0 = p[0], a1 = p[1], a2 = p[2], a3 = p[3];
        uint8_t t = (uint8_t)(a0 ^ a1 ^ a2 ^ a3);
        p[0] = (uint8_t)(a0 ^ t ^ xtime((uint8_t)(a0 ^ a1)));
        p[1] = (uint8_t)(a1 ^ t ^ xtime((uint8_t)(a1 ^ a2)));
        p[2] = (uint8_t)(a2 ^ t ^ xtime((uint8_t)(a2 ^ a3)));
        p[3] = (uint8_t)(a3 ^ t ^ xtime((uint8_t)(a3 ^ a0)));
    }
}

static void aes256_encrypt_block(const uint8_t key[32], const uint8_t in[16], uint8_t out[16]) {
    uint8_t rk[240];
    aes256_key_expansion(key, rk);
    uint8_t state[16];
    memcpy(state, in, 16);
    aes_add_round_key(state, rk);
    for (int round = 1; round <= 13; round++) {
        aes_sub_bytes(state);
        aes_shift_rows(state);
        aes_mix_columns(state);
        aes_add_round_key(state, rk + round * 16);
    }
    aes_sub_bytes(state);
    aes_shift_rows(state);
    aes_add_round_key(state, rk + 14 * 16);
    memcpy(out, state, 16);
    secure_clear(rk, sizeof(rk));
    secure_clear(state, sizeof(state));
}

// ============================================================






// ============================================================
static void gcm_mul(uint8_t z[16], const uint8_t x[16], const uint8_t y[16]) {
    uint8_t v[16];
    memcpy(v, x, 16);
    memset(z, 0, 16);
    for (int i = 0; i < 128; i++) {
        int ybit = (y[i >> 3] >> (7 - (i & 7))) & 1;   
        if (ybit) {
            for (int j = 0; j < 16; j++) z[j] ^= v[j];
        }
        uint8_t top = (uint8_t)(v[15] & 1);            // V127 = LSB = byte15 bit0
        
        uint8_t carry = 0;
        for (int j = 0; j < 16; j++) {
            uint8_t low_bit = (uint8_t)(v[j] & 1);
            v[j] = (uint8_t)((v[j] >> 1) | (carry << 7));
            carry = low_bit;
        }
        if (top) v[0] ^= 0xE1; 
    }
    secure_clear(v, sizeof(v));
}

// ============================================================


// ============================================================
static void derive_key(const uint8_t* certFp, int fpLen, uint8_t out[32]) {
    const uint8_t* seed = get_seed_key();
    const uint8_t* salt = get_hmac_salt();

    sha256_context ctx;
    sha256_init(&ctx);
    sha256_update(&ctx, seed, 16);
    sha256_update(&ctx, salt, 16);
    if (certFp != NULL && fpLen > 0) {
        sha256_update(&ctx, certFp, (size_t)fpLen);
    }
    sha256_final(&ctx, out);

    
    secure_clear((void*)seed, 16);
    secure_clear((void*)salt, 16);
}

// ============================================================




// ============================================================
static int aes_gcm_decrypt(const uint8_t* key, const uint8_t* iv,
                           const uint8_t* ciphertext, int ctLen,
                           uint8_t* plaintext, int ptBufLen) {
    if (ctLen < 16) return -1;
    int encLen = ctLen - 16;
    if (encLen > ptBufLen) return -2;

    uint8_t h[16];                       // H = E_K(0^128)
    uint8_t zero[16] = {0};
    aes256_encrypt_block(key, zero, h);

    uint8_t j0[16];                      // J0 = IV || 0x00000001
    memcpy(j0, iv, 12);
    j0[12] = 0; j0[13] = 0; j0[14] = 0; j0[15] = 1;

    
    uint8_t x[16] = {0};
    uint8_t tmp[16];
    int full = encLen / 16;
    for (int i = 0; i < full; i++) {
        for (int j = 0; j < 16; j++) x[j] ^= ciphertext[i * 16 + j];
        gcm_mul(tmp, x, h);
        memcpy(x, tmp, 16);
    }
    int rem = encLen % 16;
    if (rem > 0) {
        for (int j = 0; j < rem; j++) x[j] ^= ciphertext[full * 16 + j];
        gcm_mul(tmp, x, h);
        memcpy(x, tmp, 16);
    }
    
    uint8_t lenblock[16] = {0};
    uint64_t cbits = (uint64_t)encLen * 8;
    for (int i = 0; i < 8; i++) lenblock[8 + i] = (uint8_t)(cbits >> (8 * (7 - i)));
    for (int j = 0; j < 16; j++) x[j] ^= lenblock[j];
    gcm_mul(tmp, x, h);
    memcpy(x, tmp, 16);

    // tag' = E_K(J0) XOR GHASH
    uint8_t tagcalc[16];
    aes256_encrypt_block(key, j0, tagcalc);
    for (int j = 0; j < 16; j++) tagcalc[j] ^= x[j];

    
    uint8_t diff = 0;
    for (int j = 0; j < 16; j++) diff |= (uint8_t)(tagcalc[j] ^ ciphertext[encLen + j]);
    if (diff != 0) {
        secure_clear(h, sizeof(h));
        secure_clear(x, sizeof(x));
        secure_clear(tagcalc, sizeof(tagcalc));
        return -3; 
    }

    
    uint8_t counter[16];
    memcpy(counter, j0, 16);
    int pos = 0;
    while (pos < encLen) {
        for (int j = 15; j >= 12; j--) {
            counter[j] = (uint8_t)(counter[j] + 1);
            if (counter[j] != 0) break;
        }
        uint8_t ks[16];
        aes256_encrypt_block(key, counter, ks);
        int n = encLen - pos;
        if (n > 16) n = 16;
        for (int j = 0; j < n; j++) plaintext[pos + j] = (uint8_t)(ciphertext[pos + j] ^ ks[j]);
        pos += n;
        secure_clear(ks, sizeof(ks));
    }

    secure_clear(h, sizeof(h));
    secure_clear(x, sizeof(x));
    secure_clear(counter, sizeof(counter));
    secure_clear(tagcalc, sizeof(tagcalc));
    return encLen;
}

#ifdef __cplusplus
}
#endif

#endif // YOULONG_CRYPTO_CORE_H
