FROM docker:27-dind

RUN apk add --no-cache ca-certificates git python3 py3-yaml \
    && mkdir -p /opt/cdci-runner /work

COPY runner.py /opt/cdci-runner/runner.py
COPY entrypoint.sh /opt/cdci-runner/entrypoint.sh

ENV HOME=/home/cdci-runner \
    PYTHONDONTWRITEBYTECODE=1 \
    CDCI_RUNNER_WORKDIR=/work

ENTRYPOINT ["/bin/sh", "/opt/cdci-runner/entrypoint.sh"]
