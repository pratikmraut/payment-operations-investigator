from dataclasses import dataclass
import os
from pathlib import Path

SERVICE_ROOT = Path(__file__).resolve().parents[1]
PROJECT_ROOT = SERVICE_ROOT.parent.parent


@dataclass(frozen=True)
class Settings:
    service_key: str
    knowledge_path: Path
    checkpoint_path: Path
    retrieval_mode: str = "lexical"
    ollama_base_url: str = "http://127.0.0.1:11434"
    ollama_model: str = "qwen3:4b-instruct"
    ollama_embed_model: str = "nomic-embed-text:v1.5"
    model_timeout_seconds: float = 180.0
    model_context_tokens: int = 4096
    model_threads: int = 4
    tool_output_tokens: int = 384
    synthesis_output_tokens: int = 384
    uat_context_tokens: int = 32768
    uat_output_tokens: int = 1400
    uat_model_timeout_seconds: float = 300.0
    uat_model_keep_alive_seconds: int = 1800
    uat_model: str | None = None
    vector_db_url: str | None = None
    vector_namespace: str = "payment-runbooks"
    case_knowledge_timeout_seconds: float = 60.0
    case_model_keep_alive_seconds: int = 1800

    def __post_init__(self):
        if (isinstance(self.case_model_keep_alive_seconds, bool)
                or not isinstance(self.case_model_keep_alive_seconds, int)
                or not 0 <= self.case_model_keep_alive_seconds <= 3600):
            raise ValueError("POI_CASE_MODEL_KEEP_ALIVE_SECONDS must be an integer from 0 through 3600")
        if (isinstance(self.case_knowledge_timeout_seconds, bool)
                or not isinstance(self.case_knowledge_timeout_seconds, (int, float))
                or not 1 <= self.case_knowledge_timeout_seconds <= 120):
            raise ValueError("POI_CASE_KNOWLEDGE_TIMEOUT_SECONDS must be from 1 through 120")
        if isinstance(self.model_threads, bool) or not isinstance(self.model_threads, int) or not 1 <= self.model_threads <= 32:
            raise ValueError("POI_MODEL_THREADS must be an integer from 1 through 32")
        for value, name, lower, upper in (
            (self.uat_context_tokens, "POI_UAT_CONTEXT_TOKENS", 4096, 131072),
            (self.uat_output_tokens, "POI_UAT_OUTPUT_TOKENS", 256, 8192),
        ):
            if isinstance(value, bool) or not isinstance(value, int) or not lower <= value <= upper:
                raise ValueError(f"{name} must be an integer from {lower} through {upper}")
        if self.uat_output_tokens >= self.uat_context_tokens:
            raise ValueError("POI_UAT_OUTPUT_TOKENS must be smaller than POI_UAT_CONTEXT_TOKENS")
        if (isinstance(self.uat_model_timeout_seconds, bool)
                or not isinstance(self.uat_model_timeout_seconds, (int, float))
                or not 0 < self.uat_model_timeout_seconds <= 900):
            raise ValueError("POI_UAT_MODEL_TIMEOUT_SECONDS must be a number greater than 0 and at most 900")
        if (isinstance(self.uat_model_keep_alive_seconds, bool)
                or not isinstance(self.uat_model_keep_alive_seconds, int)
                or not 0 <= self.uat_model_keep_alive_seconds <= 3600):
            raise ValueError("POI_UAT_MODEL_KEEP_ALIVE_SECONDS must be an integer from 0 through 3600")
        if self.uat_model is not None and (not isinstance(self.uat_model, str)
                                           or not self.uat_model.strip()
                                           or len(self.uat_model) > 200):
            raise ValueError("POI_UAT_MODEL must be a nonblank model name of at most 200 characters when configured")

    @property
    def uat_model_name(self) -> str:
        return self.uat_model if self.uat_model is not None else self.ollama_model

    @classmethod
    def from_env(cls):
        mode = os.getenv("POI_RETRIEVAL_MODE", "lexical")
        if mode not in {"lexical", "hybrid"}:
            raise ValueError("POI_RETRIEVAL_MODE must be lexical or hybrid")
        return cls(
            service_key=os.getenv("POI_SERVICE_KEY", ""),
            knowledge_path=Path(os.getenv("POI_KNOWLEDGE_PATH", str(PROJECT_ROOT / "data/knowledge/runbooks.json"))),
            checkpoint_path=Path(os.getenv("POI_CHECKPOINT_PATH", str(SERVICE_ROOT / "runtime/checkpoints.sqlite"))),
            retrieval_mode=mode,
            ollama_base_url=os.getenv("OLLAMA_BASE_URL", "http://127.0.0.1:11434"),
            ollama_model=os.getenv("OLLAMA_MODEL", "qwen3:4b-instruct"),
            ollama_embed_model=os.getenv("OLLAMA_EMBED_MODEL", "nomic-embed-text:v1.5"),
            model_timeout_seconds=float(os.getenv("POI_MODEL_TIMEOUT_SECONDS", "180")),
            model_context_tokens=int(os.getenv("POI_MODEL_CONTEXT_TOKENS", "4096")),
            model_threads=int(os.getenv("POI_MODEL_THREADS", "4")),
            tool_output_tokens=int(os.getenv("POI_TOOL_OUTPUT_TOKENS", "384")),
            synthesis_output_tokens=int(os.getenv("POI_SYNTHESIS_OUTPUT_TOKENS", "384")),
            uat_context_tokens=int(os.getenv("POI_UAT_CONTEXT_TOKENS", "32768")),
            uat_output_tokens=int(os.getenv("POI_UAT_OUTPUT_TOKENS", "1400")),
            uat_model_timeout_seconds=float(os.getenv("POI_UAT_MODEL_TIMEOUT_SECONDS", "300")),
            uat_model_keep_alive_seconds=int(os.getenv("POI_UAT_MODEL_KEEP_ALIVE_SECONDS", "1800")),
            uat_model=os.getenv("POI_UAT_MODEL") or None,
            vector_db_url=os.getenv("POI_VECTOR_DB_URL") or None,
            vector_namespace=os.getenv("POI_VECTOR_NAMESPACE", "payment-runbooks"),
            case_knowledge_timeout_seconds=float(os.getenv("POI_CASE_KNOWLEDGE_TIMEOUT_SECONDS", "60")),
            case_model_keep_alive_seconds=int(os.getenv("POI_CASE_MODEL_KEEP_ALIVE_SECONDS", "1800")),
        )
