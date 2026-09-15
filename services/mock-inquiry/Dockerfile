FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt && useradd --create-home inquiry
COPY mock_inquiry ./mock_inquiry
USER inquiry
EXPOSE 8092
CMD ["uvicorn", "mock_inquiry.main:app", "--host", "0.0.0.0", "--port", "8092", "--workers", "1"]
