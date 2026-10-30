FROM debian:bookworm-20261027-slim@sha256:a3bba7dc7dbcc1f37c2856c51fc9a7fac96608f50cb87b1b4e4d8b8c85ae988e

LABEL org.opencontainers.image.title="doc2TeX isolated source renderer" \
      org.opencontainers.image.version="26.2.6" \
      org.opencontainers.image.source="https://download.documentfoundation.org/libreoffice/stable/26.2.6/deb/x86_64/" \
      doc2tex.libreoffice.sha256="fd0e8f8f2408dd2e5b90286e60f3f97cf566ba441cd48cfc5bcc68067303e0bc"

ENV DEBIAN_FRONTEND=noninteractive LANG=C.UTF-8 LC_ALL=C.UTF-8

RUN rm -f /etc/apt/sources.list.d/debian.sources \
    && printf '%s\n' \
       'deb [check-valid-until=no] http://snapshot.debian.org/archive/debian/20261027T000000Z bookworm main' \
       'deb [check-valid-until=no] http://snapshot.debian.org/archive/debian-security/20261027T000000Z bookworm-security main' \
       > /etc/apt/sources.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
       libcairo2 libcups2 libdbus-1-3 libfontconfig1 libfreetype6 libglib2.0-0 \
       libglu1-mesa libice6 libnss3 libsm6 libx11-6 libxext6 libxinerama1 \
       libxrandr2 libxrender1 libxml2 libxslt1.1 \
       fonts-dejavu-core fonts-liberation2 fonts-crosextra-carlito \
       fonts-crosextra-caladea fonts-noto-core fonts-noto-cjk \
    && curl --fail --location --retry 3 \
       https://download.documentfoundation.org/libreoffice/stable/26.2.6/deb/x86_64/LibreOffice_26.2.6_Linux_x86-64_deb.tar.gz \
       --output /tmp/libreoffice.tar.gz \
    && echo 'fd0e8f8f2408dd2e5b90286e60f3f97cf566ba441cd48cfc5bcc68067303e0bc  /tmp/libreoffice.tar.gz' | sha256sum --check --strict \
    && mkdir /tmp/libreoffice \
    && tar -xzf /tmp/libreoffice.tar.gz -C /tmp/libreoffice \
    && apt-get install -y --no-install-recommends /tmp/libreoffice/*/DEBS/*.deb \
    && mkdir -p /usr/share/doc/doc2tex-layout-renderer \
    && cp -a /tmp/libreoffice/*/readmes /usr/share/doc/doc2tex-layout-renderer/ \
    && dpkg-query -W > /usr/share/doc/doc2tex-layout-renderer/package-versions.txt \
    && rm -rf /tmp/libreoffice /tmp/libreoffice.tar.gz /var/lib/apt/lists/*

RUN apt-get update \
    && apt-get install -y --no-install-recommends libx11-xcb1 libxcb-render0 libxcb-shm0 \
    && rm -rf /var/lib/apt/lists/*

RUN missing="$(find /opt/libreoffice26.2/program -maxdepth 1 -name '*.so' -exec ldd '{}' ';' 2>/dev/null | grep 'not found' | sort -u)"; \
    if [ -n "$missing" ]; then printf '%s\n' "$missing"; exit 1; fi; \
    /opt/libreoffice26.2/program/soffice --headless --version

WORKDIR /work
ENTRYPOINT ["/opt/libreoffice26.2/program/soffice"]
