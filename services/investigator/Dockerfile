FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt && useradd --create-home worker
COPY investigator ./investigator
RUN mkdir -p /app/runtime && chown -R worker:worker /app
USER worker
ENV POI_CHECKPOINT_PATH=/app/runtime/checkpoints.sqlite
EXPOSE 8091
CMD ["uvicorn", "investigator.main:app", "--host", "0.0.0.0", "--port", "8091", "--workers", "1"]
