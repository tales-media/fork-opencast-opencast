ARG IMAGE_BASE=default

ARG FFMPEG_VERSION="latest"
FROM rg.nl-ams.scw.cloud/shio-solutions/tales-media/fork/ffmpeg:${FFMPEG_VERSION} as build-ffmpeg


FROM --platform=${BUILDPLATFORM}  docker.io/library/eclipse-temurin:17-jdk AS base-build
FROM --platform=${TARGETPLATFORM} docker.io/library/eclipse-temurin:17-jre AS base-target
LABEL org.opencontainers.image.base.name="docker.io/library/eclipse-temurin:17-jre"

FROM base-target AS base-default-runtime
FROM base-default-runtime AS base-default-dev

# Adapted from:
#   https://gitlab.com/nvidia/container-images/cuda/-/blob/master/dist/12.4.1/ubuntu2204/base/Dockerfile
#   https://gitlab.com/nvidia/container-images/cuda/-/blob/master/dist/12.4.1/ubuntu2204/runtime/Dockerfile
# BSD-3-Clause License: https://gitlab.com/nvidia/container-images/cuda/-/blob/master/LICENSE
FROM base-target AS base-nvidia-cuda-runtime-amd64
ENV NVARCH x86_64
FROM base-target AS base-nvidia-cuda-runtime-arm64
ENV NVARCH sbsa
FROM base-nvidia-cuda-runtime-${TARGETARCH} AS base-nvidia-cuda-runtime
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      ca-certificates \
      curl \
      gnupg2 \
 && rm -rf /var/lib/apt/lists/*

RUN curl -fsSL "https://developer.download.nvidia.com/compute/cuda/repos/ubuntu2204/${NVARCH}/3bf863cc.pub" | gpg --dearmor -o /etc/apt/keyrings/nvidia-cuda-keyring.gpg \
 && echo "deb [signed-by=/etc/apt/keyrings/nvidia-cuda-keyring.gpg] https://developer.download.nvidia.com/compute/cuda/repos/ubuntu2204/${NVARCH} /" > /etc/apt/sources.list.d/nvidia-cuda.list

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      cuda-compat-12-4 \
      cuda-cudart-12-4 \
      cuda-libraries-12-4 \
      cuda-nvtx-12-4 \
      libcublas-12-4 \
      libcusparse-12-4 \
      libnccl2 \
      libnpp-12-4 \
 && rm -rf /var/lib/apt/lists/*

ENV NVIDIA_REQUIRE_CUDA        "cuda>=12.4 brand=tesla,driver>=470,driver<471 brand=unknown,driver>=470,driver<471 brand=nvidia,driver>=470,driver<471 brand=nvidiartx,driver>=470,driver<471 brand=geforce,driver>=470,driver<471 brand=geforcertx,driver>=470,driver<471 brand=quadro,driver>=470,driver<471 brand=quadrortx,driver>=470,driver<471 brand=titan,driver>=470,driver<471 brand=titanrtx,driver>=470,driver<471 brand=tesla,driver>=525,driver<526 brand=unknown,driver>=525,driver<526 brand=nvidia,driver>=525,driver<526 brand=nvidiartx,driver>=525,driver<526 brand=geforce,driver>=525,driver<526 brand=geforcertx,driver>=525,driver<526 brand=quadro,driver>=525,driver<526 brand=quadrortx,driver>=525,driver<526 brand=titan,driver>=525,driver<526 brand=titanrtx,driver>=525,driver<526 brand=tesla,driver>=535,driver<536 brand=unknown,driver>=535,driver<536 brand=nvidia,driver>=535,driver<536 brand=nvidiartx,driver>=535,driver<536 brand=geforce,driver>=535,driver<536 brand=geforcertx,driver>=535,driver<536 brand=quadro,driver>=535,driver<536 brand=quadrortx,driver>=535,driver<536 brand=titan,driver>=535,driver<536 brand=titanrtx,driver>=535,driver<536"
ENV NVIDIA_VISIBLE_DEVICES     "all"
ENV NVIDIA_DRIVER_CAPABILITIES "compute,utility"

ENV PATH            "/usr/local/cuda/bin:${PATH}"
ENV LD_LIBRARY_PATH "/usr/local/cuda/lib:/usr/local/cuda/lib64:/usr/local/cuda/compat:${LD_LIBRARY_PATH}"


# Adapted from: https://gitlab.com/nvidia/container-images/cuda/-/blob/master/dist/12.4.1/ubuntu2204/devel/Dockerfile
# BSD-3-Clause License: https://gitlab.com/nvidia/container-images/cuda/-/blob/master/LICENSE
FROM base-nvidia-cuda-runtime AS base-nvidia-cuda-dev
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      cuda-command-line-tools-12-4 \
      cuda-cudart-dev-12-4 \
      cuda-libraries-dev-12-4 \
      cuda-minimal-build-12-4 \
      cuda-nsight-compute-12-4 \
      cuda-nvml-dev-12-4 \
      libcublas-dev-12-4 \
      libcusparse-dev-12-4 \
      libnccl-dev \
      libnpp-dev-12-4 \
 && rm -rf /var/lib/apt/lists/*

ENV LIBRARY_PATH /usr/local/cuda/lib64/stubs:/usr/local/cuda/compat


FROM base-${IMAGE_BASE}-runtime AS base-runtime
FROM base-${IMAGE_BASE}-dev AS base-dev


FROM base-dev AS build-whisper-cpp
ARG WHISPER_CPP_VERSION="master"
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      ccache \
      cmake \
      g++ \
      gcc \
      git \
      libc-dev \
      make
RUN mkdir -p /tmp/whisper.cpp
WORKDIR /tmp/whisper.cpp
RUN git clone https://github.com/ggerganov/whisper.cpp.git . \
 && git checkout "$WHISPER_CPP_VERSION"
ARG IMAGE_BASE=default
RUN cmake -B build \
      -DBUILD_SHARED_LIBS=OFF \
      -DGGML_CPU=ON \
      -DGGML_CPU_ARM_ARCH=native \
      -DGGML_CUDA=$(if [ "${IMAGE_BASE}" = "nvidia-cuda" ]; then echo ON; else echo OFF; fi) \
      -DGGML_NATIVE=OFF \
      -DWHISPER_BUILD_EXAMPLES=ON \
      -DWHISPER_BUILD_SERVER=OFF \
      -DWHISPER_BUILD_TESTS=OFF \
 && cmake --build build --config Release -j $(nproc) \
 && sed -i 's#models_path=.*$#models_path=/usr/share/whisper.cpp/models/#' models/download-ggml-model.sh
RUN mkdir -p out \
 && mv build/bin/whisper-cli         out/whisper.cpp \
 && mv models/download-ggml-model.sh out/whisper.cpp-model-download


FROM scratch AS build-opencast
ARG OPENCAST_DISTRIBUTION
ADD build/opencast-dist-${OPENCAST_DISTRIBUTION}-*.tar.gz /


FROM base-runtime

ENV OPENCAST_HOME            "/opencast"
ENV OPENCAST_DATA            "/data"
ENV OPENCAST_CUSTOM_CONFIG   "/etc/opencast"
ENV OPENCAST_USER            "opencast"
ENV OPENCAST_GROUP           "opencast"
ENV OPENCAST_UHOME           "/home/opencast"
ENV OPENCAST_UID             "800"
ENV OPENCAST_GID             "800"
ENV WHISPER_CPP_MODELS       "/usr/share/whisper.cpp/models"
ENV OPENCAST_CONFIG          "${OPENCAST_HOME}/etc"
ENV OPENCAST_SCRIPTS         "${OPENCAST_HOME}/docker/scripts"
ENV OPENCAST_STAGE_BASE_HOME "${OPENCAST_HOME}/docker/stage/base"
ENV OPENCAST_STAGE_OUT_HOME  "${OPENCAST_HOME}/docker/stage/out"

RUN groupadd --system -g "${OPENCAST_GID}" "${OPENCAST_GROUP}" \
 && useradd --system -M -N -g "${OPENCAST_GROUP}" -d "${OPENCAST_UHOME}" -u "${OPENCAST_UID}" "${OPENCAST_USER}" \
 && mkdir -p "${OPENCAST_DATA}" "${OPENCAST_UHOME}" "${WHISPER_CPP_MODELS}" \
 && chown -R "${OPENCAST_USER}:${OPENCAST_GROUP}" "${OPENCAST_DATA}" "${OPENCAST_UHOME}" "${WHISPER_CPP_MODELS}"

# libgomp1 is a dependency of whisper.cpp. It is also installed as a dependency of Tesseract, but we want it to be an
# explicitly installed package.
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
      libgomp1 \
      netcat-openbsd \
      openssl \
      rsync \
      tzdata \
      xmlstarlet \
 && rm -rf /var/lib/apt/lists/*

COPY --from=build-ffmpeg       /usr/local/bin/ff*      /usr/local/bin/
COPY --from=build-whisper-cpp  /tmp/whisper.cpp/out/*  /usr/local/bin/
COPY --from=build-opencast     opencast-dist-*         "${OPENCAST_HOME}"
COPY oci/rootfs /

ARG OPENCAST_REPO="https://github.com/tales-media/fork-opencast-opencast.git"
ARG OPENCAST_DISTRIBUTION
ARG VERSION=unkown
ARG BUILD_DATE=unkown
ARG GIT_COMMIT=unkown

ENV OPENCAST_REPO         "${OPENCAST_REPO}"
ENV OPENCAST_VERSION      "${VERSION}"
ENV OPENCAST_DISTRIBUTION "${OPENCAST_DISTRIBUTION}"

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

RUN whisper.cpp-model-download small

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
