/* Provider-capable native tender.
 *
 * The measured default loader is included unchanged so its sandbox, limits,
 * parser, and context ABI stay one source of truth. Its main is renamed and a
 * provider-selecting main below differs only at the typed-capability callback
 * and pre-sandbox provider initialization points. This file has its own
 * runtime identity; it must never masquerade as tools/kexe_loader.c. */
#define main kexe_default_loader_main
#include "kexe_loader.c"
#undef main

#include "kexe_typed_provider.h"

#define KEXE_PROVIDER_FUEL UINT64_C(1048576)

int kexe_typed_string_view(struct kexe_context_v3 *context, int64_t handle,
                           struct kexe_typed_string_v1 *out) {
  if (context == NULL || context->version != 3 || out == NULL) return -1;
  int64_t offset = checked_pair_get(context, handle, 0);
  int64_t length = checked_pair_get(context, handle, 1);
  if (length < 0) return -1;
  const uint8_t *bytes = resolve_string_bytes(context, offset, length);
  if (!valid_utf8(bytes, (uint64_t)length)) return -1;
  out->bytes = bytes;
  out->length = (uint64_t)length;
  return 0;
}

int64_t kexe_typed_string_new(struct kexe_context_v3 *context,
                              const uint8_t *bytes, uint64_t length) {
  struct kexe_shared_v3 *shared = (struct kexe_shared_v3 *)context;
  if (context == NULL || context->version != 3 ||
      (bytes == NULL && length != 0) || length > (uint64_t)INT64_MAX ||
      !valid_utf8(bytes, length) || length > KEXE_STRING_POOL_BYTES ||
      shared->string_pool_used > KEXE_STRING_POOL_BYTES - length) {
    raise(SIGILL);
    return 0;
  }
  uint64_t offset = shared->string_pool_used;
  if (length != 0) memcpy(shared->string_pool + offset, bytes, (size_t)length);
  shared->string_pool_used += length;
  return checked_pair_new(context, -((int64_t)offset) - 1, (int64_t)length);
}

static int64_t checked_external_typed_cap_call(
    struct kexe_context_v3 *context, uint64_t id, uint64_t request_kind,
    uint64_t result_kind, int64_t request) {
  if (context == NULL || context->version != 3 || id > 255 ||
      !(context->allow[id / 64] & (UINT64_C(1) << (id % 64))) ||
      request_kind != result_kind ||
      !valid_typed_value(context, request_kind, request)) {
    raise(SIGILL);
    return 0;
  }
  int64_t result = kexe_external_typed_cap_provider(
      context, id, request_kind, result_kind, request);
  if (!valid_typed_value(context, result_kind, result)) {
    raise(SIGILL);
    return 0;
  }
  return result;
}

int main(int argc, char **argv) {
  if (argc < 6 || argc > 11) {
    fprintf(stderr, "usage: kexe-provider-loader <raw-code> <offset> <arity> <x86_64|aarch64> <allow-csv|-> [i64 ...]\n");
    return 2;
  }
  uint64_t offset;
  if (parse_u64(argv[2], &offset) != 0) return 2;
  unsigned long arity;
  if (parse_ulong_decimal(argv[3], &arity) != 0 || arity > 5 ||
      argc != (int)(6 + arity)) return 2;
  const char *isa = argv[4];
  if (strcmp(isa, "x86_64") != 0 && strcmp(isa, "aarch64") != 0) return 2;
  FILE *file = fopen(argv[1], "rb");
  if (!file) fail("open");
  if (fseek(file, 0, SEEK_END) != 0) fail("seek");
  long length = ftell(file);
  if (length <= 0 || offset >= (uint64_t)length) {
    fprintf(stderr, "kexe-provider-loader: invalid code length or offset\n");
    return 2;
  }
  rewind(file);

  long pagesize = sysconf(_SC_PAGESIZE);
  size_t mapped = ((size_t)length + (size_t)pagesize - 1) & ~((size_t)pagesize - 1);
  void *memory = mmap(NULL, mapped, PROT_READ | PROT_WRITE,
                      MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
  if (memory == MAP_FAILED) fail("mmap RW");
  if (fread(memory, 1, (size_t)length, file) != (size_t)length) fail("read");
  if (fclose(file) != 0) fail("close");
  if (mprotect(memory, mapped, PROT_READ | PROT_EXEC) != 0) fail("mprotect RX");
  __builtin___clear_cache((char *)memory, (char *)memory + length);

  int64_t args[6] = {0, 0, 0, 0, 0, 0};
  for (unsigned long i = 0; i < arity; i++)
    if (parse_i64(argv[6 + i], &args[i]) != 0) return 2;

  struct kexe_shared_v3 *shared =
      mmap(NULL, sizeof(*shared), PROT_READ | PROT_WRITE,
           MAP_SHARED | MAP_ANONYMOUS, -1, 0);
  if (shared == MAP_FAILED) fail("mmap shared execution state");
  memset(shared, 0, sizeof(*shared));
  shared->context.version = 3;
  shared->context.fuel = KEXE_PROVIDER_FUEL;
  shared->context.cap_call = checked_cap_call;
  shared->context.pair_new = checked_pair_new;
  shared->context.pair_first = checked_pair_first;
  shared->context.pair_second = checked_pair_second;
  shared->context.kgraph_assert = checked_kgraph_assert;
  shared->context.kgraph_get = checked_kgraph_get;
  shared->context.kgraph_count = checked_kgraph_count;
  shared->context.kgraph_entity_at = checked_kgraph_entity_at;
  shared->context.string_equal = checked_string_equal;
  shared->context.string_concat = checked_string_concat;
  shared->context.string_substring = checked_string_substring;
  shared->context.string_code_point_at = checked_string_code_point_at;
  shared->context.typed_cap_call = checked_external_typed_cap_call;
  shared->context.vector_new_empty = checked_vector_new_empty;
  shared->context.vector_conj = checked_vector_conj;
  shared->context.vector_count = checked_vector_count;
  shared->context.vector_at = checked_vector_at;
  shared->context.vector_assoc = checked_vector_assoc;
  shared->context.vector_drop = checked_vector_drop;
  shared->context.code_base = (const uint8_t *)memory;
  shared->context.code_length = (uint64_t)length;
  if (parse_allow(argv[5], shared->context.allow) != 0) return 2;
  if (kexe_external_typed_cap_provider_init() != 0) {
    fprintf(stderr, "kexe-provider-loader: provider initialization failed\n");
    return 2;
  }
  int structured_report = getenv("KEXE_STRUCTURED_REPORT") != NULL;

  pid_t child = fork();
  if (child < 0) fail("fork");
  if (child > 0) {
    int child_status = supervise(child);
    if (structured_report) write_supervisor_report(shared, child_status);
    if (munmap(shared, sizeof(*shared)) != 0) fail("supervisor shared munmap");
    if (munmap(memory, mapped) != 0) fail("supervisor munmap");
    return child_status;
  }

  supervised_pid = -1;
  alarm(0);
  install_limits();
  install_syscall_sandbox();
  int64_t result;
  if (strcmp(isa, "x86_64") == 0) {
    kexe_fn6 fn = (kexe_fn6)((uint8_t *)memory + offset);
    result = fn(args[0], args[1], args[2], args[3], args[4],
                (int64_t)(uintptr_t)&shared->context);
  } else {
    kexe_fn8 fn = (kexe_fn8)((uint8_t *)memory + offset);
    result = fn(args[0], args[1], args[2], args[3], args[4], 0, 0,
                (int64_t)(uintptr_t)&shared->context);
  }
  shared->result = result;
  shared->completed = 1;
  if (!structured_report) write_i64(result);
  if (munmap(memory, mapped) != 0) fail("munmap");
  if (munmap(shared, sizeof(*shared)) != 0) fail("shared munmap");
  _exit(0);
}
