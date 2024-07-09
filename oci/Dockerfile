ARG FFMPEG_VERSION="latest"
FROM rg.nl-ams.scw.cloud/shio-solutions/tales-media/fork/ffmpeg:${FFMPEG_VERSION} as build-ffmpeg


FROM docker.io/library/eclipse-temurin:17-jdk AS build-whisper-cpp
ARG WHISPER_CPP_VERSION="master"
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      g++ \
      gcc \
      git \
      libc-dev \
      make
RUN mkdir -p /tmp/whisper.cpp
WORKDIR /tmp/whisper.cpp
RUN git clone https://github.com/ggerganov/whisper.cpp.git . \
 && git checkout "$WHISPER_CPP_VERSION"
RUN make -j \
 && sed -i 's#models_path=.*$#models_path=/usr/share/whisper.cpp/models/#' models/download-ggml-model.sh
RUN mkdir -p out \
 && mv main out/whisper.cpp \
 && mv quantize out/whisper.cpp-quantize \
 && mv server out/whisper.cpp-server \
 && mv models/download-ggml-model.sh out/whisper.cpp-model-download


FROM scratch AS build-opencast
ARG OPENCAST_DISTRIBUTION
ADD build/opencast-dist-${OPENCAST_DISTRIBUTION}-*.tar.gz /


FROM docker.io/library/eclipse-temurin:17-jdk
LABEL org.opencontainers.image.base.name="docker.io/library/eclipse-temurin:17-jdk"

ENV OPENCAST_HOME="/opencast" \
    OPENCAST_DATA="/data" \
    OPENCAST_CUSTOM_CONFIG="/etc/opencast" \
    OPENCAST_USER="opencast" \
    OPENCAST_GROUP="opencast" \
    OPENCAST_UHOME="/home/opencast" \
    OPENCAST_UID="800" \
    OPENCAST_GID="800" \
    WHISPER_CPP_MODELS="/usr/share/whisper.cpp/models"
ENV OPENCAST_CONFIG="${OPENCAST_HOME}/etc" \
    OPENCAST_SCRIPTS="${OPENCAST_HOME}/docker/scripts" \
    OPENCAST_STAGE_BASE_HOME="${OPENCAST_HOME}/docker/stage/base" \
    OPENCAST_STAGE_OUT_HOME="${OPENCAST_HOME}/docker/stage/out"

RUN groupadd --system -g "${OPENCAST_GID}" "${OPENCAST_GROUP}" \
 && useradd --system -M -N -g "${OPENCAST_GROUP}" -d "${OPENCAST_UHOME}" -u "${OPENCAST_UID}" "${OPENCAST_USER}" \
 && mkdir -p "${OPENCAST_DATA}" "${OPENCAST_UHOME}" "${WHISPER_CPP_MODELS}" \
 && chown -R "${OPENCAST_USER}:${OPENCAST_GROUP}" "${OPENCAST_DATA}" "${OPENCAST_UHOME}" "${WHISPER_CPP_MODELS}"

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      bc \
      ca-certificates \
      curl \
      fontconfig \
      fonts-dejavu \
      fonts-ebgaramond \
      fonts-ebgaramond-extra \
      fonts-freefont-ttf \
      fonts-liberation \
      fonts-linuxlibertine \
      fonts-open-sans \
      gosu \
      inotify-tools \
      jq \
      netcat-openbsd \
      openssl \
      rsync \
      tzdata \
      xmlstarlet \
 && rm -rf /var/lib/apt/lists/*

COPY --from=build-ffmpeg       /ff*                    /usr/local/bin/
COPY --from=build-whisper-cpp  /tmp/whisper.cpp/out/*  /usr/local/bin/
COPY --from=build-opencast     "opencast-dist-*"       "${OPENCAST_HOME}"
COPY oci/rootfs /

ARG OPENCAST_REPO="https://github.com/tales-media/fork-opencast-opencast.git"
ARG OPENCAST_DISTRIBUTION
ARG VERSION=unkown
ARG BUILD_DATE=unkown
ARG GIT_COMMIT=unkown

ENV OPENCAST_REPO=${OPENCAST_REPO} \
    OPENCAST_VERSION=${VERSION} \
    OPENCAST_DISTRIBUTION=${OPENCAST_DISTRIBUTION}

RUN if [ "${OPENCAST_DISTRIBUTION}" = "tales-media-allinone" ]; then \
      rm -f "${OPENCAST_CONFIG}/org.opencastproject.organization-mh_default_org.cfg-clustered"; \
    fi \
 && mv "${OPENCAST_CONFIG}/org.opencastproject.organization-mh_default_org.cfg-clustered" "${OPENCAST_CONFIG}/org.opencastproject.organization-mh_default_org.cfg" || true \
 && chown "${OPENCAST_USER}:${OPENCAST_GROUP}" "${OPENCAST_HOME}" "${OPENCAST_CONFIG}" \
 && chown -R "${OPENCAST_USER}:${OPENCAST_GROUP}" "${OPENCAST_HOME}/data" \
  \
 && mkdir -p "${OPENCAST_STAGE_BASE_HOME}" \
 && rsync -vrlog --chown=0:0 "${OPENCAST_CONFIG}" "${OPENCAST_STAGE_BASE_HOME}" \
  \
 && javac "${OPENCAST_SCRIPTS}/TryToConnectToDb.java" \
 && rm -rf /tmp/* "${OPENCAST_SCRIPTS}/TryToConnectToDb.java"

WORKDIR "${OPENCAST_HOME}"

EXPOSE 8080
VOLUME [ "${OPENCAST_DATA}" ]

LABEL maintainer="shio solutions GmbH <dev@shio.solutions>" \
      org.opencontainers.image.title="opencast-${OPENCAST_DISTRIBUTION}" \
      org.opencontainers.image.description="container image for the Opencast ${OPENCAST_DISTRIBUTION} distribution" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.vendor="shio solutions GmbH" \
      org.opencontainers.image.authors="shio solutions GmbH <dev@shio.solutions>" \
      org.opencontainers.image.licenses="ECL-2.0" \
      org.opencontainers.image.url="https://github.com/tales-media/fork-opencast-opencast/blob/${VERSION}/README.md" \
      org.opencontainers.image.documentation="https://github.com/tales-media/fork-opencast-opencast/blob/${VERSION}/README.md" \
      org.opencontainers.image.source="https://github.com/tales-media/fork-opencast-opencast" \
      org.opencontainers.image.created="${BUILD_DATE}" \
      org.opencontainers.image.revision="${GIT_COMMIT}"

HEALTHCHECK --timeout=10s CMD /docker-healthcheck.sh
ENTRYPOINT ["/docker-entrypoint.sh"]
CMD ["app:start"]
