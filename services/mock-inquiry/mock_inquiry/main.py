"""A bounded local mock of our proposed inquiry contract; not an Oracle API."""
from __future__ import annotations

from contextlib import asynccontextmanager
from dataclasses import dataclass
import json
import os
from pathlib import Path
import re
import secrets

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

REFERENCE_IDS = tuple(f"MOCK-NEFT-{number}" for number in range(1001, 1005))
SCOPE = {
    "referenceType": "PAYMENT_REFERENCE",
    "deploymentId": "SYNTHETIC-OBPM",
    "hostCode": "DEMO-HOST",
    "branchCode": "DEMO-BRANCH",
}
TOP_LEVEL_FIELDS = {
    "schemaVersion", "dataClassification", "snapshotId", "mappingVersion", "extractedAt",
    "source", "payment", "queueRecords", "externalRequestAttempts", "messages",
    "accountingEntries", "sourceCoverage",
}


@dataclass(frozen=True)
class Settings:
    data_dir: Path
    service_key: str

    @classmethod
    def from_environment(cls) -> "Settings":
        default_data = (Path(__file__).resolve().parent / "../../../data/obpm/inquiry").resolve()
        return cls(
            Path(os.environ.get("POI_INQUIRY_DATA_DIR", str(default_data))),
            os.environ.get("POI_INQUIRY_SERVICE_KEY", ""),
        )


def load_catalog(settings: Settings) -> dict[str, dict]:
    if len(settings.service_key) < 16:
        raise ValueError("POI_INQUIRY_SERVICE_KEY must contain at least 16 characters.")
    catalog = {}
    for reference in REFERENCE_IDS:
        # Files are selected only from a fixed startup catalog, never a request path.
        raw = (settings.data_dir / f"{reference}.json").read_bytes()
        if len(raw) > 131_072:
            raise ValueError("Inquiry fixture exceeds the supported size.")
        record = json.loads(raw)
        if not isinstance(record, dict) or set(record) != TOP_LEVEL_FIELDS:
            raise ValueError("Inquiry fixture has unsupported envelope fields.")
        if (record.get("schemaVersion") != "neft-inquiry-v1"
                or record.get("dataClassification") != "SYNTHETIC"
                or record.get("mappingVersion") != "original-synthetic-inquiry-v1"):
            raise ValueError("Only the original synthetic inquiry contract is supported.")
        source = record.get("source", {})
        if source != {
            "deploymentId": "SYNTHETIC-OBPM", "releaseFamily": "14.7",
            "exactMaintenanceRelease": None, "hostCode": "DEMO-HOST", "branchCode": "DEMO-BRANCH",
        }:
            raise ValueError("Inquiry fixture source is outside the fixed synthetic scope.")
        payment = record.get("payment", {})
        if (payment.get("sourcePaymentId") != reference or "amountMinor" in payment
                or payment.get("rail") != "NEFT" or payment.get("direction") != "OUTBOUND"
                or payment.get("currency") != "INR"
                or not isinstance(payment.get("sourceAmountDecimal"), str)):
            raise ValueError("Inquiry fixture payment identity or amount contract is invalid.")
        catalog[reference] = record
    return catalog


def error(status: int, code: str, message: str) -> JSONResponse:
    return JSONResponse(status_code=status, content={"error": {"code": code, "message": message}})


def create_app(settings: Settings | None = None) -> FastAPI:
    @asynccontextmanager
    async def lifespan(app: FastAPI):
        configured = settings or Settings.from_environment()
        app.state.settings = configured
        app.state.catalog = load_catalog(configured)
        yield

    app = FastAPI(title="Synthetic NEFT inquiry", version="1.0.0", lifespan=lifespan,
                  docs_url=None, redoc_url=None, openapi_url=None)

    @app.middleware("http")
    async def no_cache(request: Request, call_next):
        response = await call_next(request)
        response.headers["Cache-Control"] = "no-store"
        response.headers["X-Content-Type-Options"] = "nosniff"
        return response

    @app.get("/health")
    async def health():
        return {"status": "UP", "service": "synthetic-neft-inquiry", "mode": "SYNTHETIC"}

    @app.get("/inquiry/v1/neft/payments/{reference}")
    async def inquire(reference: str, request: Request):
        keys = request.headers.getlist("x-service-key")
        supplied = keys[0] if len(keys) == 1 else ""
        configured = request.app.state.settings
        if not secrets.compare_digest(supplied.encode("utf-8"), configured.service_key.encode("utf-8")):
            return error(401, "UNAUTHORIZED", "A valid inquiry service key is required.")
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,99}", reference):
            return error(400, "INVALID_REFERENCE", "Use a supported payment reference.")
        actual = list(request.query_params.multi_items())
        if len(actual) != len(SCOPE) or dict(actual) != SCOPE:
            return error(400, "INVALID_SCOPE", "Use the fixed synthetic payment-reference scope.")
        record = request.app.state.catalog.get(reference)
        if record is None:
            return error(404, "PAYMENT_NOT_FOUND", "No payment matches this reference in the permitted scope.")
        return JSONResponse(content=record)

    return app


app = create_app()
