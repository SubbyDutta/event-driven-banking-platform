"""End-to-end webhook smoke test. Signs a Razorpay-style webhook payload with
the configured secret, POSTs it to the bank's /api/payment/webhook, and reports
the response. No external deps — stdlib only."""
import hashlib
import hmac
import json
import sys
import urllib.request
import urllib.error

SECRET = b"test_webhook_secret_smoke_123"
URL = "http://localhost:8080/api/payment/webhook"


def post(body: bytes, signature: str | None) -> tuple[int, str]:
    headers = {"Content-Type": "application/json"}
    if signature is not None:
        headers["X-Razorpay-Signature"] = signature
    req = urllib.request.Request(URL, data=body, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status, resp.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def sign(body: bytes) -> str:
    return hmac.new(SECRET, body, hashlib.sha256).hexdigest()


def make_body(payment_id: str, amount_paise: int, username: str, event: str = "payment.captured", omit_username: bool = False) -> bytes:
    notes: dict[str, str] = {} if omit_username else {"username": username}
    payload = {
        "event": event,
        "payload": {
            "payment": {
                "entity": {
                    "id": payment_id,
                    "amount": amount_paise,
                    "currency": "INR",
                    "status": "captured",
                    "notes": notes,
                }
            }
        },
    }
    return json.dumps(payload, separators=(",", ":")).encode()


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: test_webhook.py <username>")
        return 2
    username = sys.argv[1]

    print(f"=== TEST C: valid signed payment.captured for {username}, INR2500 ===")
    body = make_body("pay_smoke_C_001", 250_000, username)
    sig = sign(body)
    code, resp = post(body, sig)
    print(f"  HTTP {code}: {resp}")

    print(f"\n=== TEST D: replay of same payment_id -- idempotent skip ===")
    code, resp = post(body, sig)
    print(f"  HTTP {code}: {resp}")

    print(f"\n=== TEST E: signed event=refund.created -- 200 ignored, no credit ===")
    body_e = make_body("pay_smoke_E_001", 99_999, username, event="refund.created")
    code, resp = post(body_e, sign(body_e))
    print(f"  HTTP {code}: {resp}")

    print(f"\n=== TEST F: signed payment.captured with no notes.username -- 422 ===")
    body_f = make_body("pay_smoke_F_001", 50_000, username, omit_username=True)
    code, resp = post(body_f, sign(body_f))
    print(f"  HTTP {code}: {resp}")

    print(f"\n=== TEST G: fresh payment_id, valid signature -- credits another INR500 ===")
    body_g = make_body("pay_smoke_G_001", 50_000, username)
    code, resp = post(body_g, sign(body_g))
    print(f"  HTTP {code}: {resp}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
