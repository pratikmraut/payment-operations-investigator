from contextlib import asynccontextmanager
from datetime import date
import hmac
import uuid

from fastapi import Depends, FastAPI, Header, Query
from fastapi.responses import JSONResponse

from .config import Settings
from .errors import InvalidModelResult, ProviderUnavailable, SnapshotConflict, UatModelBusy, UatModelTimeout
from .graph import InvestigatorEngine
from .models import InvestigationRequest, InvestigationResult, RetrieveRequest
from .retrieval import KnowledgeStore
from .uat_answer import UatAnswerEngine, UatAnswerRequest, UatAnswerResponse
from .case_answer import CaseAnswerEngine, CaseAnswerResponse
from .case_rag import CaseRagEngine
from .case_knowledge import CaseKnowledgeSearch, KnowledgeSearchRequest, KnowledgeSearchResponse, KnowledgeSearchTimeout


def create_app(settings: Settings | None = None, engine: InvestigatorEngine | None = None, uat_engine: UatAnswerEngine | None = None, case_engine: CaseAnswerEngine | None = None, knowledge_search: CaseKnowledgeSearch | None = None):
    configuration = settings or Settings.from_env()
    knowledge = engine.knowledge if engine else KnowledgeStore(configuration)

    @asynccontextmanager
    async def lifespan(app):
        app.state.engine = engine or InvestigatorEngine(configuration, knowledge)
        app.state.uat_engine = uat_engine or UatAnswerEngine(configuration, lock=app.state.engine.lock)
        app.state.case_engine = case_engine or CaseRagEngine(configuration, lock=app.state.engine.lock)
        app.state.knowledge_search = knowledge_search or CaseKnowledgeSearch(configuration, lock=app.state.engine.lock)
        yield
        if engine is None:
            app.state.engine.close()

    app = FastAPI(title="Payment Operations Investigator", version="0.1.0", lifespan=lifespan)

    def service_auth(x_service_key: str | None = Header(default=None)):
        if not configuration.service_key:
            from fastapi import HTTPException
            raise HTTPException(status_code=503, detail="POI_SERVICE_KEY is not configured")
        if x_service_key is None or not hmac.compare_digest(x_service_key.encode("utf-8"), configuration.service_key.encode("utf-8")):
            from fastapi import HTTPException
            raise HTTPException(status_code=401, detail="Invalid service credentials")

    @app.exception_handler(ProviderUnavailable)
    async def provider_error(request, exception):
        return JSONResponse(status_code=503, content={"code": "PROVIDER_UNAVAILABLE", "message": str(exception), "requestId": str(uuid.uuid4())})

    @app.exception_handler(UatModelTimeout)
    async def uat_timeout(request, exception):
        return JSONResponse(status_code=504, content={"code": "UAT_MODEL_TIMEOUT", "message": str(exception), "requestId": str(uuid.uuid4())})

    @app.exception_handler(KnowledgeSearchTimeout)
    async def knowledge_timeout(request, exception):
        return JSONResponse(status_code=504, content={"code": "CASE_KNOWLEDGE_TIMEOUT", "message": str(exception), "requestId": str(uuid.uuid4())})

    @app.exception_handler(UatModelBusy)
    async def uat_busy(request, exception):
        return JSONResponse(status_code=503, content={"code": "UAT_MODEL_BUSY", "message": str(exception), "requestId": str(uuid.uuid4())})

    @app.exception_handler(InvalidModelResult)
    async def invalid_output(request, exception):
        return JSONResponse(status_code=422, content={"code": "INVALID_MODEL_RESULT", "message": str(exception), "requestId": str(uuid.uuid4())})

    @app.exception_handler(SnapshotConflict)
    async def conflicting_snapshot(request, exception):
        return JSONResponse(status_code=409, content={"code": "SNAPSHOT_CONFLICT", "message": str(exception), "requestId": str(uuid.uuid4())})

    @app.get("/health")
    def health():
        return {"status": "UP", "service": "investigator", "supportedModes": ["replay", "ollama"], "retrievalMode": configuration.retrieval_mode, "vectorStorage": "disabled" if configuration.retrieval_mode == "lexical" else "postgresql-pgvector" if configuration.vector_db_url else "in-memory", "liveModelConfigured": configuration.ollama_model, "limitations": ["Health does not assert an Ollama server/model or vector database is available.", "SQLite graph execution is serialized for this local deployment."]}

    @app.post("/investigate", response_model=InvestigationResult, dependencies=[Depends(service_auth)])
    def investigate(body: InvestigationRequest):
        return app.state.engine.run(body)

    @app.post("/uat/answer", response_model=UatAnswerResponse, response_model_exclude_unset=True, dependencies=[Depends(service_auth)])
    def uat_answer(body: UatAnswerRequest):
        return app.state.uat_engine.run(body)

    @app.post("/case/answer", response_model=CaseAnswerResponse, response_model_exclude_unset=True, dependencies=[Depends(service_auth)])
    def case_answer(body: UatAnswerRequest):
        return app.state.case_engine.run(body)

    @app.post("/retrieve", dependencies=[Depends(service_auth)])
    def retrieve(body: RetrieveRequest):
        return {"items": knowledge.retrieve(body.query, body.tenantId, body.policyDate, body.limit, scope=body.policy_scope())}

    @app.post("/case/knowledge-search", response_model=KnowledgeSearchResponse, dependencies=[Depends(service_auth)])
    def search_case_knowledge(body: KnowledgeSearchRequest):
        return app.state.knowledge_search.run(body)

    @app.get("/knowledge", dependencies=[Depends(service_auth)])
    def list_knowledge(tenantId: str = Query(min_length=1, max_length=100, pattern=r"^[A-Za-z0-9_-]+$")):
        return {"items": knowledge.all_for_tenant(tenantId)}

    return app


app = create_app()
