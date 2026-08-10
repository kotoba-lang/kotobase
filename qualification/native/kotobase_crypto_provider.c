#include "kexe_typed_provider.h"

#include <openssl/crypto.h>
#include <openssl/evp.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static EVP_PKEY *signing_key;

#define BLOCK_ENTRY_LIMIT 40
#define BLOCK_KEY_LIMIT 256
#define BLOCK_VALUE_LIMIT 8192

struct block_entry {
  uint8_t key[BLOCK_KEY_LIMIT];
  size_t key_length;
  uint8_t value[BLOCK_VALUE_LIMIT];
  size_t value_length;
};

static struct block_entry block_entries[BLOCK_ENTRY_LIMIT];
static size_t block_entry_count;

static int load_block_fixture(void) {
  const char *path = getenv("KOTOBASE_BLOCK_PROVIDER_FILE");
  char line[BLOCK_KEY_LIMIT + BLOCK_VALUE_LIMIT + 4];
  if (path == NULL || *path == '\0') return 0;
  FILE *input = fopen(path, "rb");
  if (input == NULL) return -1;
  while (fgets(line, sizeof(line), input) != NULL) {
    char *tab;
    char *end;
    size_t key_length;
    size_t value_length;
    if (block_entry_count == BLOCK_ENTRY_LIMIT) {
      fclose(input);
      return -1;
    }
    tab = strchr(line, '\t');
    if (tab == NULL) {
      fclose(input);
      return -1;
    }
    *tab = '\0';
    end = tab + 1 + strlen(tab + 1);
    while (end > tab + 1 && (end[-1] == '\n' || end[-1] == '\r')) --end;
    *end = '\0';
    key_length = strlen(line);
    value_length = strlen(tab + 1);
    if (key_length == 0 || key_length > BLOCK_KEY_LIMIT ||
        value_length > BLOCK_VALUE_LIMIT) {
      fclose(input);
      return -1;
    }
    memcpy(block_entries[block_entry_count].key, line, key_length);
    block_entries[block_entry_count].key_length = key_length;
    memcpy(block_entries[block_entry_count].value, tab + 1, value_length);
    block_entries[block_entry_count].value_length = value_length;
    block_entry_count++;
  }
  return fclose(input) == 0 ? 0 : -1;
}

static int hex_nibble(uint8_t value) {
  if (value >= '0' && value <= '9') return (int)(value - '0');
  if (value >= 'a' && value <= 'f') return (int)(value - 'a') + 10;
  return -1;
}

static int decode_request(const struct kexe_typed_string_v1 *request,
                          uint8_t *out, size_t capacity, size_t *length) {
  if (request->length < 4 || memcmp(request->bytes, "hex:", 4) != 0 ||
      ((request->length - 4) & 1u) != 0) return -1;
  uint64_t decoded = (request->length - 4) / 2;
  if (decoded > capacity) return -1;
  for (uint64_t index = 0; index < decoded; index++) {
    int high = hex_nibble(request->bytes[4 + index * 2]);
    int low = hex_nibble(request->bytes[5 + index * 2]);
    if (high < 0 || low < 0) return -1;
    out[index] = (uint8_t)((high << 4) | low);
  }
  *length = (size_t)decoded;
  return 0;
}

static void encode_hex(const uint8_t *bytes, size_t length, uint8_t *out) {
  static const uint8_t alphabet[] = "0123456789abcdef";
  for (size_t index = 0; index < length; index++) {
    out[index * 2] = alphabet[bytes[index] >> 4];
    out[index * 2 + 1] = alphabet[bytes[index] & 15u];
  }
}

int kexe_external_typed_cap_provider_init(void) {
  static const uint8_t seed[32] = {
    0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
    0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10,
    0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18,
    0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f, 0x20
  };
  if (load_block_fixture() != 0) return -1;
  if (!OPENSSL_init_crypto(OPENSSL_INIT_NO_LOAD_CONFIG, NULL)) return -1;
  signing_key = EVP_PKEY_new_raw_private_key(EVP_PKEY_ED25519, NULL,
                                              seed, sizeof(seed));
  return signing_key == NULL ? -1 : 0;
}

int64_t kexe_external_typed_cap_provider(struct kexe_context_v3 *context,
                                         uint64_t id,
                                         uint64_t request_kind,
                                         uint64_t result_kind,
                                         int64_t request_handle) {
  struct kexe_typed_string_v1 request;
  uint8_t message[4096];
  size_t message_length = 0;
  if (request_kind != 1 || result_kind != 1 ||
      kexe_typed_string_view(context, request_handle, &request) != 0) {
    raise(SIGILL);
    return 0;
  }

  if (id == 14) {
    for (size_t index = 0; index < block_entry_count; index++) {
      const struct block_entry *entry = &block_entries[index];
      if (entry->key_length == request.length &&
          memcmp(entry->key, request.bytes, request.length) == 0)
        return kexe_typed_string_new(context, entry->value,
                                     entry->value_length);
    }
    raise(SIGILL);
    return 0;
  }

  if (decode_request(&request, message, sizeof(message), &message_length) != 0) {
    raise(SIGILL);
    return 0;
  }

  if (id == 3) {
    uint8_t digest[32];
    unsigned int digest_length = 0;
    uint8_t result[64];
    if (!EVP_Digest(message, message_length, digest, &digest_length,
                    EVP_sha256(), NULL) || digest_length != sizeof(digest)) {
      raise(SIGILL);
      return 0;
    }
    encode_hex(digest, sizeof(digest), result);
    return kexe_typed_string_new(context, result, sizeof(result));
  }

  if (id == 1) {
    EVP_MD_CTX *signing = EVP_MD_CTX_new();
    uint8_t public_key[32];
    size_t public_key_length = sizeof(public_key);
    uint8_t signature[64];
    size_t signature_length = sizeof(signature);
    uint8_t result[193];
    int ok = signing != NULL &&
      EVP_PKEY_get_raw_public_key(signing_key, public_key,
                                  &public_key_length) == 1 &&
      public_key_length == sizeof(public_key) &&
      EVP_DigestSignInit(signing, NULL, NULL, NULL, signing_key) == 1 &&
      EVP_DigestSign(signing, signature, &signature_length,
                     message, message_length) == 1 &&
      signature_length == sizeof(signature);
    EVP_MD_CTX_free(signing);
    if (!ok) {
      raise(SIGILL);
      return 0;
    }
    encode_hex(public_key, sizeof(public_key), result);
    result[64] = ':';
    encode_hex(signature, sizeof(signature), result + 65);
    return kexe_typed_string_new(context, result, sizeof(result));
  }

  raise(SIGILL);
  return 0;
}
