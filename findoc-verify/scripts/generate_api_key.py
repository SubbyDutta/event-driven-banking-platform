"""CLI: mint a new API key.

Usage:
    python -m scripts.generate_api_key --label "subby-java" --org "SubbyBank" \
        --scopes submit,admin --rate-limit 120
"""
from __future__ import annotations

import argparse
import asyncio
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from dotenv import load_dotenv  # noqa: E402
load_dotenv(ROOT / ".env")

from src.db.session import SessionLocal  # noqa: E402
from src.services.apikey_service import create_api_key  # noqa: E402


_VALID_SCOPES = {"submit", "admin", "admin_global"}


async def _main(label: str, org: str, scopes: list[str], rate_limit: int) -> None:
    async with SessionLocal() as session:
        key_id, raw = await create_api_key(session, label, org, scopes=scopes, rate_limit_per_min=rate_limit)
        await session.commit()
    print("=" * 60)
    print("API key minted")
    print(f"  id           : {key_id}")
    print(f"  label        : {label}")
    print(f"  org          : {org}")
    print(f"  scopes       : {scopes or '(none — acts as all-scopes back-compat)'}")
    print(f"  rate limit   : {rate_limit}/min")
    print(f"  key          : {raw}")
    print("=" * 60)
    print("Store this key now — it cannot be recovered later.")


def _parse_scopes(raw: str) -> list[str]:
    if not raw:
        return []
    out = [s.strip() for s in raw.split(",") if s.strip()]
    bad = [s for s in out if s not in _VALID_SCOPES]
    if bad:
        raise SystemExit(f"invalid scope(s): {bad}. Valid: {sorted(_VALID_SCOPES)}")
    return out


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--label", required=True, help="human-readable label")
    parser.add_argument("--org", required=True, help="organisation name")
    parser.add_argument(
        "--scopes",
        default="",
        help="comma-separated scopes: submit,admin,admin_global (empty = back-compat all-scopes)",
    )
    parser.add_argument("--rate-limit", type=int, default=60, help="requests per minute (default 60)")
    args = parser.parse_args()
    asyncio.run(_main(args.label, args.org, _parse_scopes(args.scopes), args.rate_limit))


if __name__ == "__main__":
    main()
