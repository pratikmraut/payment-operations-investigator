import { createServer } from "node:http";
import { spawn } from "node:child_process";
import { existsSync, mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { randomUUID } from "node:crypto";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../../../..");
const jar = resolve(
  root,
  "services/api/target/payment-operations-api-0.1.0.jar",
);
if (!existsSync(jar))
  throw new Error(
    "Build the API first: mvn -f services/api/pom.xml package -DskipTests",
  );
const key = "isolated-browser-acceptance-only";
let stopping = false;
const caseJobs = new Map();
const deletedCases = new Set();
// An explicit test double for the provider boundary. It never contacts Ollama,
// retrieves private guidance, or represents a model accuracy evaluation.
const worker = createServer(async (req, res) => {
  res.setHeader("Content-Type", "application/json");
  if (
    req.url === "/__e2e_shutdown" &&
    req.method === "POST" &&
    req.headers["x-test-key"] === key
  ) {
    stopping = true;
    res.end("{}");
    api.kill();
    worker.close();
    return;
  }
  if (req.url === "/health")
    return res.end(
      JSON.stringify({ status: "UP", service: "browser-test-double" }),
    );
  if (
    !["/case/answer", "/case/preflight", "/case/jobs/submit", "/case/jobs/status", "/case/jobs/cancel", "/case/jobs/forget-case"].includes(req.url) ||
    req.method !== "POST" ||
    req.headers["x-service-key"] !== key
  ) {
    res.statusCode = 404;
    return res.end("{}");
  }
  let raw = "";
  for await (const part of req) {
    raw += part;
    if (raw.length > 1_000_000) {
      res.statusCode = 413;
      return res.end("{}");
    }
  }
  const body = JSON.parse(raw);
  if (req.url === "/case/preflight") return res.end(JSON.stringify({
    schemaVersion: "case-context-readiness-v1", ready: true, modelChecked: false,
    documentCount: body.documents.length, serializedBytes: 1000, requiredBudget: 3000,
    contextLimit: 32768, outputAndFramingReserve: 2000,
    method: "utf8-byte-upper-bound", promptHash: "b".repeat(64),
  }));
  if (req.url.startsWith("/case/jobs/")) {
    const scope = JSON.stringify([body.tenantId, body.caseId]);
    const id = JSON.stringify([body.tenantId, body.caseId, body.jobId]);
    if (req.url.endsWith("/forget-case")) {
      for (const item of caseJobs.values()) if (item.tenantId === body.tenantId && item.caseId === body.caseId && ["QUEUED", "RUNNING"].includes(item.status)) {
        res.statusCode = 409; return res.end(JSON.stringify({ detail: { code: "CASE_WORKER_CASE_ACTIVE" } }));
      }
      deletedCases.add(scope);
      for (const [key, item] of caseJobs) if (item.tenantId === body.tenantId && item.caseId === body.caseId) caseJobs.delete(key);
      return res.end(JSON.stringify({ ...body, forgotten: true }));
    }
    if (deletedCases.has(scope)) {
      res.statusCode = 409; return res.end(JSON.stringify({ detail: { code: "CASE_WORKER_CASE_DELETED" } }));
    }
    let item = caseJobs.get(id);
    if (!item && req.url.endsWith("/cancel")) {
      item = { tenantId: body.tenantId, caseId: body.caseId, jobId: body.jobId, inputHash: body.inputHash,
        status: "CANCELLED", cancellationRequested: true };
      caseJobs.set(id, item);
    }
    if (item && item.inputHash !== body.inputHash) {
      res.statusCode = 409; return res.end(JSON.stringify({ detail: { code: "CASE_WORKER_JOB_CONFLICT" } }));
    }
    if (!item && req.url.endsWith("/submit")) {
      const hold = body.input.question.includes("Keep this synthetic job waiting");
      item = { tenantId: body.tenantId, caseId: body.caseId, jobId: body.jobId, inputHash: body.inputHash,
        status: hold ? "RUNNING" : "COMPLETED", cancellationRequested: false,
        ...(hold ? {} : { answer: syntheticAnswer(body.input) }) };
      caseJobs.set(id, item);
    }
    if (!item) {
      res.statusCode = 404; return res.end(JSON.stringify({ detail: { code: "CASE_WORKER_JOB_NOT_FOUND" } }));
    }
    if (req.url.endsWith("/cancel") && ["QUEUED", "RUNNING"].includes(item.status)) {
      item.status = "CANCELLED"; item.cancellationRequested = true;
    }
    return res.end(JSON.stringify(item));
  }
  res.end(JSON.stringify(syntheticAnswer(body)));
});

function syntheticAnswer(input) {
  const
    doc =
      input.documents.find((d) => d.id === "PAYMENT-ROW-1") ??
      input.documents[0];
  const text = "The supplied test records leave the payment outcome unknown.";
  return {
      answerId: "BROWSER-TEST-" + randomUUID(),
      question: input.question,
      snapshotId: input.snapshotId,
      evidenceHash: input.evidenceHash,
      answer: text,
      answerComposition: "joined-model-claims",
      mode: "model-generated",
      validation: "structure-and-source-membership-only",
      generatedAt: new Date().toISOString(),
      claims: [{ text, evidenceIds: [doc.id] }],
      unknowns: [
        "Beneficiary credit is not established by this synthetic fixture.",
      ],
      nextChecks: ["Obtain an independently verified outcome record."],
      citations: [doc],
      retrieval: { method: "browser-contract-test", documentIds: [doc.id] },
      model: {
        provider: "ollama",
        name: "test-double-no-inference",
        actualCalls: 1,
        promptTokens: 1,
        completionTokens: 1,
        durationMs: 1,
      },
      rag: {
        pipeline: "case-evidence-rag-v1",
        promptHash: "b".repeat(64),
        checks: [
          "source-membership",
          "literal-field-quotations",
          "required-unknowns-and-next-checks",
        ],
        claimSupports: [{ claimIndex: 0, claimType: "limitation", fields: [] }],
      },
    };
}
await new Promise((ok, fail) => {
  worker.once("error", fail);
  worker.listen(19093, "127.0.0.1", ok);
});
// Strip inherited application settings before starting an isolated in-memory DB.
const env = Object.fromEntries(
  Object.entries(process.env).filter(
    ([name]) =>
      !/^(POI_|SPRING_|SERVER_|OLLAMA_|JAVA_TOOL_OPTIONS$|JDK_JAVA_OPTIONS$|_JAVA_OPTIONS$)/i.test(
        name,
      ),
  ),
);
Object.assign(env, {
  SERVER_PORT: "19092",
  SERVER_ADDRESS: "127.0.0.1",
  POI_DB_URL:
    "jdbc:h2:mem:browser_acceptance;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
  POI_DB_USER: "sa",
  POI_DB_PASSWORD: "",
  POI_DEMO_PASSWORD: "demo-pass-local",
  POI_PAYMENT_DISCOVERY_MODE: "MOCK",
  POI_PAYMENT_DISCOVERY_BANK_ENABLED: "false",
  POI_CASE_EVIDENCE_API_ENABLED: "false",
  POI_OBPM_INQUIRY_ENABLED: "false",
  POI_WORKER_URL: "http://127.0.0.1:19093",
  POI_SERVICE_KEY: key,
  POI_FIXTURES: resolve(root, "data/fixtures/cases.json"),
  POI_OBPM_SAMPLES: resolve(root, "data/obpm/samples"),
});
const java = process.env.JAVA_HOME
  ? resolve(
      process.env.JAVA_HOME,
      "bin",
      process.platform === "win32" ? "java.exe" : "java",
    )
  : "java";
const javaArgs = [];
if (process.platform === "win32") {
  const socketDirectory = resolve(root, "runtime/browser-acceptance-sockets");
  mkdirSync(socketDirectory, { recursive: true });
  // Avoid Windows short-name TEMP aliases for the JDK selector's local sockets.
  javaArgs.push(`-Djdk.net.unixdomain.tmpdir=${socketDirectory}`);
}
javaArgs.push(
  "-jar",
  jar,
  "--spring.config.location=classpath:/application.yml",
);
const api = spawn(java, javaArgs, {
  cwd: root,
  env,
  stdio: ["ignore", "pipe", "pipe"],
  windowsHide: true,
});
api.stdout.pipe(process.stdout);
api.stderr.pipe(process.stderr);
api.on("error", (err) => {
  console.error(err.message);
  worker.close();
  process.exitCode = 1;
});
api.on("exit", (code) => {
  worker.close();
  process.exit(stopping ? 0 : (code ?? 1));
});
for (const signal of ["SIGINT", "SIGTERM"])
  process.on(signal, () => {
    stopping = true;
    api.kill();
    worker.close();
  });
