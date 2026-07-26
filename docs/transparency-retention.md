# Transparency, key rotation, and receipt retention

Kotobase transparency checkpoints bind an append-only receipt root, tree size,
key id, monotonically increasing key epoch, prior checkpoint CID, and distinct
operations/auditor witnesses. Unknown or expired key epochs, rollback, missing
witnesses, and signature drift fail closed.

Receipt retention is class based. A legal hold always wins. Expired encrypted
receipts are crypto-shredded only after a newer witnessed checkpoint exists;
plain receipts become deletable under the same condition. This prevents key
retirement or physical deletion from erasing the last externally committed
audit state.

The production policy is
`qualification/transparency-policy.edn`; executable evidence is in
`test/kotobase/transparency_log_test.clj`.
