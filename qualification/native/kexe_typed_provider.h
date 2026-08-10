#ifndef KEXE_TYPED_PROVIDER_H
#define KEXE_TYPED_PROVIDER_H

#include <stdint.h>

struct kexe_context_v3;

struct kexe_typed_string_v1 {
  const uint8_t *bytes;
  uint64_t length;
};

/* A provider receives validated typed values. These two functions are the
 * complete host-value surface exposed to an external provider: borrow one
 * UTF-8 request and allocate one UTF-8 result in the loader-owned arena. */
int kexe_typed_string_view(struct kexe_context_v3 *context, int64_t handle,
                           struct kexe_typed_string_v1 *out);
int64_t kexe_typed_string_new(struct kexe_context_v3 *context,
                              const uint8_t *bytes, uint64_t length);

/* Called before the loader forks and installs its syscall sandbox. Providers
 * may initialize a cryptographic library here, but must not retain ambient
 * filesystem, network, process, clock, or randomness authority. */
int kexe_external_typed_cap_provider_init(void);

/* Return a loader-owned typed value. Any malformed result is rejected again
 * by the loader before it crosses into guest code. */
int64_t kexe_external_typed_cap_provider(struct kexe_context_v3 *context,
                                         uint64_t id,
                                         uint64_t request_kind,
                                         uint64_t result_kind,
                                         int64_t request);

#endif
