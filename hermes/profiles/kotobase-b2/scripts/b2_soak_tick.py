#!/usr/bin/env python3
"""B2 bytes plane soak/storage watch - kotobase-b2 bot tick."""
import json, os, sys, time, urllib.request, hashlib, subprocess

LEDGER = os.path.join(os.path.dirname(os.path.abspath(__file__)), "b2_storage_ledger.jsonl")
CERT_SHA_PREFIX = "1a0ada098b0d3f05"
SPOT_CID = "bafkreia2blnatcynh4ct2qdbkcxyph4g4hr3of6w3tiboxc4vb76mkiii4"
CF_ACCOUNT = "4da88288dc30d9ee257f319d3c33ecf0"
UA = "kotobase-b2-soak/1.0"

def now_iso():
    return time.strftime("%Y-%m-%dT%H:%M:%S+09:00")

def http_json(url, timeout=30):
    try:
        req = urllib.request.Request(url, headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read())
    except Exception as e:
        return {"_error": str(e)}

def measure_meta():
    d = http_json("https://ipfs.kotobase.net/_app/meta")
    if "_error" in d:
        return {"b2_primary_origin": "unmeasured", "b2_configured": "unmeasured", "err": d["_error"]}
    return {"b2_primary_origin": d.get("b2_primary_origin"),
            "b2_configured": d.get("b2_configured"),
            "version": d.get("version")}

def measure_spot():
    try:
        req = urllib.request.Request(f"https://{SPOT_CID}.ipfs.kotobase.net/",
                                     headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=90) as r:
            sha = hashlib.sha256(r.read()).hexdigest()[:16]
        return {"sha": sha, "ok": sha == CERT_SHA_PREFIX}
    except Exception as e:
        return {"sha": "unmeasured", "ok": False, "err": str(e)}

def measure_r2_ops():
    token_path = os.path.expanduser("~/.wrangler/config/default.toml")
    try:
        token = None
        with open(token_path) as f:
            for line in f:
                if line.startswith("oauth_token"):
                    token = line.split("=",1)[1].strip().strip('"')
        if not token:
            return {"class_a_24h": "unmeasured", "class_b_24h": "unmeasured", "err": "no oauth token"}
        q = """
        { viewer { accounts(filter: {accountTag: "%s"}) {
            r2OperationsAdaptiveGroups(limit: 2,
              filter: {datetime_geq: "%s", datetime_leq: "%s"}) {
              sum { requests }
              dimensions { actionType }
            } } } }
        """ % (CF_ACCOUNT, time.strftime("%Y-%m-%dT00:00:00Z", time.gmtime(time.time()-86400)),
               time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()))
        req = urllib.request.Request("https://api.cloudflare.com/client/v4/graphql",
            data=json.dumps({"query": q}).encode(),
            headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=30) as r:
            data = json.loads(r.read())
        if data.get("errors"):
            return {"class_a_24h": "unmeasured", "class_b_24h": "unmeasured",
                    "err": data["errors"][0].get("message","?")}
        rows = data["data"]["viewer"]["accounts"][0]["r2OperationsAdaptiveGroups"]
        a = sum(r["sum"]["requests"] or 0 for r in rows if r["dimensions"]["actionType"] in
                ("PutObject","CopyObject","CreateMultipartUpload","UploadPart","CompleteMultipartUpload"))
        b = sum(r["sum"]["requests"] or 0 for r in rows if r["dimensions"]["actionType"] in
                ("GetObject","HeadObject","ListObjects","ListBuckets"))
        return {"class_a_24h": a, "class_b_24h": b}
    except Exception as e:
        return {"class_a_24h": "unmeasured", "class_b_24h": "unmeasured", "err": str(e)}

def measure_rclone():
    try:
        r = subprocess.run(["bash","-c","ps aux | grep '[r]clone copy' | wc -l"],
                           capture_output=True, text=True, timeout=15)
        return int(r.stdout.strip())
    except Exception:
        return -1

def main():
    tick = {
        "t": now_iso(),
        "meta": measure_meta(),
        "spot": measure_spot(),
        "r2_ops": measure_r2_ops(),
        "rclone_procs": measure_rclone(),
    }
    with open(LEDGER, "a") as f:
        f.write(json.dumps(tick, ensure_ascii=False) + "\n")
    meta = tick["meta"]; spot = tick["spot"]; ops = tick["r2_ops"]
    anom = []
    if meta.get("b2_primary_origin") is False:
        anom.append("b2_primary_origin=false")
    if meta.get("b2_configured") is False:
        anom.append("b2_configured=false")
    if spot.get("ok") is False and spot.get("sha") != "unmeasured":
        anom.append("spot_sha_mismatch")
    print("b2 tick: primary=%s configured=%s spot_sha=%s r2_ops_a=%s r2_ops_b=%s rclone=%d anomaly=%s"
          % (meta.get("b2_primary_origin","unmeasured"), meta.get("b2_configured","unmeasured"),
             spot.get("sha","unmeasured"), ops.get("class_a_24h","unmeasured"),
             ops.get("class_b_24h","unmeasured"), tick["rclone_procs"],
             ("none" if not anom else "; ".join(anom))))

if __name__ == "__main__":
    main()
