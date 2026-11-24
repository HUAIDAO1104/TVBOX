FROM python:3.12-slim
WORKDIR /app
RUN pip install --no-cache-dir gunicorn==27.0.1 \
    && useradd --system --uid 10001 --create-home pdd \
    && mkdir /data && chown pdd /data
COPY service.py .
USER pdd
ENV PDD_ACCOUNT_DB=/data/account.sqlite
VOLUME ["/data"]
EXPOSE 8787
CMD ["gunicorn", "--bind", "0.0.0.0:8787", "--umask", "0077", "--workers", "2", "--threads", "2", "service:create_app()"]
