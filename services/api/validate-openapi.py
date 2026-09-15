"""Read-only checks for the source-derived public API document; standard library only.

Checks strict JSON syntax, internal references, controller path/method coverage,
session/CSRF/idempotency declarations, and selected contract invariants. This is
not a replacement for a complete OpenAPI specification validator or runtime tests.
"""

import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "services/api/src/main/java/dev/pratik/poi"


def unique_object(pairs):
    result = {}
    for name, value in pairs:
        if name in result:
            raise ValueError(f"Duplicate JSON key: {name}")
        result[name] = value
    return result


def main():
    document = json.loads(
        (ROOT / "docs/openapi.json").read_text(encoding="utf-8"),
        object_pairs_hook=unique_object,
        parse_constant=lambda value: (_ for _ in ()).throw(
            ValueError(f"Non-JSON numeric constant: {value}")
        ),
    )
    assert document["openapi"] == "3.1.0"
    checks = ["strict JSON syntax and unique object keys"]

    def resolve(reference):
        assert reference.startswith("#/"), f"Unexpected external reference: {reference}"
        current = document
        for part in reference[2:].split("/"):
            current = current[part.replace("~1", "/").replace("~0", "~")]
        return current

    reference_count = 0

    def walk(value):
        nonlocal reference_count
        if isinstance(value, dict):
            if "$ref" in value:
                resolve(value["$ref"])
                reference_count += 1
            if "required" in value and isinstance(value["required"], list):
                assert len(set(value["required"])) == len(value["required"])
                assert set(value["required"]) <= set(value.get("properties", {}))
            if "minimum" in value and "maximum" in value:
                assert value["minimum"] <= value["maximum"]
            for child in value.values():
                walk(child)
        elif isinstance(value, list):
            for child in value:
                walk(child)

    walk(document)
    checks.append(f"{reference_count} internal references resolve; schema invariants")

    controller_operations = set()
    for controller in (SOURCE / "ApiController.java", SOURCE / "AuthController.java"):
        source = controller.read_text(encoding="utf-8")
        base = re.search(r'@RequestMapping\("([^\"]+)"\)', source).group(1)
        mappings = re.findall(
            r'@(Get|Post|Put|Patch|Delete)Mapping\(\s*(?:value\s*=\s*)?"([^\"]+)"',
            source,
        )
        controller_operations.update((base + route, verb.lower()) for verb, route in mappings)

    operation_ids = set()
    documented_operations = set()
    public_routes = {("/api/health", "get"), ("/api/auth/login", "post")}
    for path, item in document["paths"].items():
        for method, operation in item.items():
            if method not in {"get", "post", "put", "patch", "delete", "head", "options"}:
                continue
            documented_operations.add((path, method))
            assert operation["operationId"] not in operation_ids
            operation_ids.add(operation["operationId"])
            assert "200" in operation["responses"]
            parameters = [
                resolve(parameter["$ref"]) if "$ref" in parameter else parameter
                for parameter in operation.get("parameters", [])
            ]
            placeholders = set(re.findall(r"\{([^}]+)\}", path))
            actual_path_parameters = {p["name"] for p in parameters if p["in"] == "path"}
            assert placeholders == actual_path_parameters, (path, actual_path_parameters)
            assert all(p["required"] for p in parameters if p["in"] == "path")
            if (path, method) in public_routes:
                assert operation.get("security") == []
            else:
                assert operation.get("security", document["security"]) == [{"SessionCookie": []}]
                assert "401" in operation["responses"]
                assert operation["x-roles"]
                if method == "post":
                    assert any(p["name"] == "X-CSRF-Token" and p["required"] for p in parameters)
                    assert "403" in operation["responses"]

    assert documented_operations == controller_operations, {
        "missing": sorted(controller_operations - documented_operations),
        "extra": sorted(documented_operations - controller_operations),
    }
    checks.append("all controller paths/methods covered with unique operation IDs")
    checks.append("path parameters, public exceptions, session security and POST CSRF declarations")

    cookie = document["components"]["securitySchemes"]["SessionCookie"]
    assert (cookie["type"], cookie["in"], cookie["name"]) == ("apiKey", "cookie", "POI_SESSION")
    assert "name: POI_SESSION" in (ROOT / "services/api/src/main/resources/application.yml").read_text()
    schemas = document["components"]["schemas"]
    java_limit = int(re.search(r"MAX_SAFE_INTEGER = ([\d_]+)L", (SOURCE / "JsonSupport.java").read_text()).group(1).replace("_", ""))
    assert schemas["MinorAmount"]["minimum"] == -java_limit
    assert schemas["MinorAmount"]["maximum"] == java_limit == 2**53 - 1
    assert schemas["CaseAmount"]["minimum"] == 0 and schemas["CaseAmount"]["maximum"] == java_limit

    service = (SOURCE / "InvestigationService.java").read_text()
    for java_name, schema_name in [("ACTIONS", "Action"), ("OUTCOMES", "Outcome")]:
        source_values = re.findall(r'"([^\"]+)"', re.search(rf"{java_name}\s*=\s*Set\.of\((.*?)\);", service, re.S).group(1))
        assert set(source_values) == set(schemas[schema_name]["enum"])
    review = document["paths"]["/api/cases/{id}/decisions"]["post"]
    assert review["x-roles"] == ["REVIEWER"]
    assert any(p.get("$ref", "").endswith("/IdempotencyKey") for p in review["parameters"])
    assert document["components"]["parameters"]["IdempotencyKey"]["schema"]["pattern"] == "^[A-Za-z0-9._:-]{8,200}$"
    for request in ["LoginRequest", "InvestigateRequest", "DecisionRequest"]:
        assert schemas[request]["additionalProperties"] is False
    assert "action" not in schemas["DecisionRequest"]["properties"]
    assert {"case", "investigations", "decisions", "audit", "mode", "datasetVersion"} == set(schemas["Export"]["required"])
    assert not {"idempotencyKey", "requestHash"} & set(schemas["ReviewDecision"]["properties"])
    checks.append("money boundaries, source enums, reviewer/idempotency and request/export invariants")
    print(json.dumps({"status": "PASS", "paths": len(document["paths"]), "operations": len(documented_operations), "schemas": len(schemas), "checks": checks}, indent=2))


if __name__ == "__main__":
    main()
