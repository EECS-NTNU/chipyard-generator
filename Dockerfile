FROM docker.io/library/eclipse-temurin:17-jdk-jammy AS sbt-dist
COPY project/build.properties /tmp/build.properties
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/* \
    && sbt_version="$(sed -n 's/^sbt.version=//p' /tmp/build.properties)" \
    && curl -fsSL "https://github.com/sbt/sbt/releases/download/v${sbt_version}/sbt-${sbt_version}.tgz" -o /tmp/sbt.tgz \
    && tar -xzf /tmp/sbt.tgz -C /opt \
    && rm /tmp/sbt.tgz

FROM mcr.microsoft.com/devcontainers/java:17-bookworm AS dev
USER root
COPY --from=sbt-dist /opt/sbt /opt/sbt
ENV PATH="/opt/sbt/bin:${PATH}"
USER vscode

FROM sbt-dist AS generator-build
WORKDIR /build
ENV PATH="/opt/sbt/bin:${PATH}"
ENV JDK_JAVA_OPTIONS="-Dsbt.server.autostart=false"
COPY build.sbt ./
COPY project/build.properties project/build.properties
COPY cli/presolve cli/presolve
COPY cli/src/ cli/src/
COPY plugin/src/main/ plugin/src/main/
COPY resolver/src/main/ resolver/src/main/
RUN sbt -batch publishLocal cli/stage

FROM sbt-dist AS chipyard-toolchain
ARG MINIFORGE_VERSION=25.3.1-0
COPY cli/src/main/resources/chipyard-revision /tmp/chipyard-revision
RUN test "$(uname -m)" = x86_64 \
    && apt-get update \
    && apt-get install -y --no-install-recommends bzip2 file g++ git make patch xz-utils \
    && rm -rf /var/lib/apt/lists/* \
    && curl -fsSL "https://github.com/conda-forge/miniforge/releases/download/${MINIFORGE_VERSION}/Miniforge3-${MINIFORGE_VERSION}-Linux-x86_64.sh" -o /tmp/miniforge.sh \
    && bash /tmp/miniforge.sh -b -p /opt/miniforge \
    && rm /tmp/miniforge.sh
ENV CONDA_EXE=/opt/miniforge/bin/conda
ENV PATH="/opt/miniforge/bin:${PATH}"
WORKDIR /opt/chipyard
RUN git init \
    && git remote add origin https://github.com/ucb-bar/chipyard.git \
    && git fetch --depth=1 origin "$(cat /tmp/chipyard-revision)" \
    && git checkout --detach FETCH_HEAD \
    && ./build-setup.sh riscv-tools --skip-submodules --skip-ctags --skip 5 --skip-firesim --skip-marshal --skip-clean \
    && test -f .conda-env/riscv-tools/lib/libfesvr.a \
    && .conda-env/bin/java -version 2>&1 | grep -E 'version "20[."]' \
    && .conda-env/bin/verilator --version | grep -E '^Verilator 5\.022([[:space:]]|$)' \
    && .conda-env/riscv-tools/bin/firtool --version | grep -E 'CIRCT firtool-1\.75\.0([[:space:]]|$)'

FROM docker.io/library/eclipse-temurin:17-jdk-jammy AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates git openssh-client \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir /cache /workspace \
    && chown 1000:1000 /cache /workspace
COPY --from=sbt-dist /opt/sbt /opt/sbt
COPY --from=generator-build /build/cli/target/stage/ /opt/presolve/
COPY --from=generator-build /root/.ivy2/local/ /opt/presolve/ivy-local/
COPY --from=chipyard-toolchain /opt/chipyard/.conda-env/ /opt/chipyard/.conda-env/
COPY containers/sbt-repositories /opt/presolve/sbt-repositories
ENV PATH="/opt/presolve:/opt/sbt/bin:${PATH}" \
    HOME=/cache \
    XDG_CACHE_HOME=/cache \
    CHIPYARD_TOOLCHAIN_PREFIX=/opt/chipyard/.conda-env \
    JDK_JAVA_OPTIONS="-Duser.home=/cache -Dsbt.repository.config=/opt/presolve/sbt-repositories -Dsbt.override.build.repos=true -Dsbt.server.autostart=false"
WORKDIR /workspace
USER 1000:1000
ENTRYPOINT ["/opt/presolve/presolve"]
CMD ["--help"]
