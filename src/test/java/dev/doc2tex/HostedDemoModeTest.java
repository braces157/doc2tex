package dev.doc2tex;

import static org.junit.jupiter.api.Assertions.*;

import java.net.HttpCookie;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.forward-headers-strategy=framework",
                "doc2tex.hosting.allowed-hosts=doc2tex.tech,www.doc2tex.tech",
                "doc2tex.hosting.demo-mode=true",
                "doc2tex.hosting.trust-proxy-ip=true",
                "doc2tex.hosting.uploads-per-window=2",
                "doc2tex.hosting.previews-per-window=2",
                "doc2tex.hosting.max-jobs=3",
                "doc2tex.hosting.max-jobs-per-session=2",
                "doc2tex.hosting.retention-minutes=60",
                "doc2tex.tectonic=${java.io.tmpdir}/doc2tex-no-tectonic-${random.uuid}",
                "doc2tex.data-dir=${java.io.tmpdir}/doc2tex-hosted-demo-${random.uuid}"
        })
class HostedDemoModeTest {
    @LocalServerPort int port;
    @Autowired JobStore store;
    @Autowired AppProperties appProperties;

    private RestClient client() {
        return RestClient.builder()
                .baseUrl("http://127.0.0.1:" + port)
                .requestFactory(new JdkClientHttpRequestFactory())
                .build();
    }

    private ResponseEntity<String> request(HttpMethod method, String path, Map<String, String> headers, Object body) {
        var spec = client().method(method).uri(path);
        spec.header("X-Forwarded-Host", headers.getOrDefault("X-Forwarded-Host", "doc2tex.tech"));
        spec.header("X-Forwarded-Proto", headers.getOrDefault("X-Forwarded-Proto", "https"));
        for (var entry : headers.entrySet()) {
            if (!entry.getKey().equals("X-Forwarded-Host") && !entry.getKey().equals("X-Forwarded-Proto")) {
                spec.header(entry.getKey(), entry.getValue());
            }
        }
        if (body != null) {
            if (body instanceof LinkedMultiValueMap<?, ?>) {
                spec.contentType(MediaType.MULTIPART_FORM_DATA).body(body);
            } else {
                spec.contentType(MediaType.APPLICATION_JSON).body(body);
            }
        }
        return spec.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
                .headers(res.getHeaders())
                .body(new String(res.getBody().readAllBytes())));
    }

    private String mintSession() {
        ResponseEntity<String> response = request(HttpMethod.GET, "/api/system", Map.of(), null);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertNotNull(cookies);
        String raw = cookies.stream()
                .filter(header -> header.startsWith(DemoSession.COOKIE_NAME + "="))
                .findFirst()
                .orElseThrow();
        assertTrue(raw.contains("HttpOnly"), raw);
        assertTrue(raw.contains("SameSite=Lax"), raw);
        assertTrue(raw.contains("Secure"), raw);
        return HttpCookie.parse(raw).getFirst().getValue();
    }

    private Job createOwnedJob(String sessionId, String filename) throws Exception {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Job queued = new Job(id, filename, "article", "QUEUED", "NOT_REQUESTED", "", "", now, now, 128, 0);
        DemoSession.set(sessionId);
        try {
            store.save(queued);
        } finally {
            DemoSession.clear();
        }
        // Simulate background worker saving status updates without an active HTTP DemoSession.
        Job completed = queued.withStatus("COMPLETED", "Ready");
        store.save(completed);
        Path output = store.directory(id).resolve("output");
        Files.createDirectories(output);
        Files.writeString(output.resolve("main.tex"), "\\documentclass{article}\\begin{document}Demo\\end{document}");
        store.versions().initialize(completed, store.directory(id), false);
        return completed;
    }

    @Test
    void hostedHostOriginAndCrossSiteNavigationRulesWorkWhileLocalModeStaysStrict() throws Exception {
        assertEquals(HttpStatus.OK, request(HttpMethod.GET, "/api/system", Map.of(), null).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, request(HttpMethod.GET, "/api/system",
                Map.of("X-Forwarded-Host", "evil.example"), null).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, request(HttpMethod.GET, "/api/system",
                Map.of("Origin", "https://evil.example"), null).getStatusCode());
        assertEquals(HttpStatus.OK, request(HttpMethod.GET, "/api/system",
                Map.of("Origin", "https://doc2tex.tech"), null).getStatusCode());

        ResponseEntity<String> nav = request(HttpMethod.GET, "/",
                Map.of("Sec-Fetch-Site", "cross-site", "Sec-Fetch-Mode", "navigate"), null);
        assertNotEquals(HttpStatus.FORBIDDEN, nav.getStatusCode());

        ResponseEntity<String> crossSiteApi = request(HttpMethod.GET, "/api/system",
                Map.of("Sec-Fetch-Site", "cross-site", "Sec-Fetch-Mode", "navigate"), null);
        assertEquals(HttpStatus.FORBIDDEN, crossSiteApi.getStatusCode());

        // Local-default LocalAccessFilter must still reject cross-site top-level navigation.
        LocalAccessFilter localFilter = new LocalAccessFilter();
        MockHttpServletRequest localReq = new MockHttpServletRequest("GET", "/");
        localReq.setServerName("127.0.0.1");
        localReq.addHeader("Sec-Fetch-Site", "cross-site");
        localReq.addHeader("Sec-Fetch-Mode", "navigate");
        MockHttpServletResponse localRes = new MockHttpServletResponse();
        localFilter.doFilter(localReq, localRes, new MockFilterChain());
        assertEquals(403, localRes.getStatus());
    }

    @Test
    void sessionIsolationProtectsJobsAcrossBrowsersAndRestarts() throws Exception {
        String sessionA = mintSession();
        String sessionB = mintSession();
        Job jobA = createOwnedJob(sessionA, "session-a-doc.docx");
        try {
            var listA = request(HttpMethod.GET, "/api/jobs",
                    Map.of(HttpHeaders.COOKIE, DemoSession.COOKIE_NAME + "=" + sessionA), null);
            assertEquals(HttpStatus.OK, listA.getStatusCode());
            assertTrue(listA.getBody().contains(jobA.id()));
            assertTrue(listA.getHeaders().getCacheControl() != null
                    && listA.getHeaders().getCacheControl().contains("no-store"));

            var libA = request(HttpMethod.GET, "/api/library",
                    Map.of(HttpHeaders.COOKIE, DemoSession.COOKIE_NAME + "=" + sessionA), null);
            assertEquals(HttpStatus.OK, libA.getStatusCode());
            assertTrue(libA.getBody().contains(jobA.id()));

            var getA = request(HttpMethod.GET, "/api/jobs/" + jobA.id(),
                    Map.of(HttpHeaders.COOKIE, DemoSession.COOKIE_NAME + "=" + sessionA), null);
            assertEquals(HttpStatus.OK, getA.getStatusCode());

            var sourceA = request(HttpMethod.GET, "/api/jobs/" + jobA.id() + "/source",
                    Map.of(HttpHeaders.COOKIE, DemoSession.COOKIE_NAME + "=" + sessionA), null);
            assertEquals(HttpStatus.OK, sourceA.getStatusCode());

            var listB = request(HttpMethod.GET, "/api/jobs",
                    Map.of(HttpHeaders.COOKIE, DemoSession.COOKIE_NAME + "=" + sessionB), null);
            assertEquals(HttpStatus.OK, listB.getStatusCode());
            assertFalse(listB.getBody().contains(jobA.id()));

            var libB = request(HttpMethod.GET, "/api/library",
                    Map.of(HttpHeaders.COOKIE, DemoSession.COOKIE_NAME + "=" + sessionB), null);
            assertEquals(HttpStatus.OK, libB.getStatusCode());
            assertTrue(libB.getBody().contains("\"total\":0"), libB.getBody());

            Map<String, String> cookieB = Map.of(HttpHeaders.COOKIE, DemoSession.COOKIE_NAME + "=" + sessionB);
            assertEquals(HttpStatus.NOT_FOUND, request(HttpMethod.GET, "/api/jobs/" + jobA.id(), cookieB, null).getStatusCode());
            assertEquals(HttpStatus.NOT_FOUND, request(HttpMethod.GET, "/api/jobs/" + jobA.id() + ";x=1", cookieB, null).getStatusCode());
            assertEquals(HttpStatus.NOT_FOUND, request(HttpMethod.GET, "/api/jobs/" + jobA.id() + "/source", cookieB, null).getStatusCode());
            assertEquals(HttpStatus.NOT_FOUND, request(HttpMethod.PATCH, "/api/jobs/" + jobA.id(), cookieB, Map.of("displayName", "hijack")).getStatusCode());
            assertEquals(HttpStatus.NOT_FOUND, request(HttpMethod.DELETE, "/api/jobs/" + jobA.id(), cookieB, null).getStatusCode());

            JobStore reloaded = new JobStore(appProperties);
            reloaded.load();
            assertTrue(reloaded.isOwnedBy(jobA.id(), sessionA));
            assertFalse(reloaded.isOwnedBy(jobA.id(), sessionB));
        } finally {
            if (store.exists(jobA.id())) store.delete(jobA.id());
        }
    }

    @Test
    void rateLimitsPreviewUploadsAndPerSessionCapacityUsingTrustedProxyIp() throws Exception {
        String session = mintSession();
        Job job = createOwnedJob(session, "rate-limit-doc.docx");
        try {
            for (int i = 0; i < 2; i++) {
                var allowed = request(HttpMethod.POST, "/api/jobs/" + job.id() + "/preview",
                        Map.of(HttpHeaders.COOKIE, DemoSession.COOKIE_NAME + "=" + session,
                                "CF-Connecting-IP", "198.51.100.10"), null);
                assertNotEquals(HttpStatus.TOO_MANY_REQUESTS, allowed.getStatusCode());
            }
            var previewBlocked = request(HttpMethod.POST, "/api/jobs/" + job.id() + "/preview",
                    Map.of(HttpHeaders.COOKIE, DemoSession.COOKIE_NAME + "=" + session,
                            "CF-Connecting-IP", "198.51.100.10"), null);
            assertEquals(HttpStatus.TOO_MANY_REQUESTS, previewBlocked.getStatusCode());
            assertNotNull(previewBlocked.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));

            var otherIpPreview = request(HttpMethod.POST, "/api/jobs/" + job.id() + "/preview",
                    Map.of(HttpHeaders.COOKIE, DemoSession.COOKIE_NAME + "=" + session,
                            "CF-Connecting-IP", "198.51.100.11"), null);
            assertNotEquals(HttpStatus.TOO_MANY_REQUESTS, otherIpPreview.getStatusCode());

            var invalidUpload = new LinkedMultiValueMap<String, Object>();
            invalidUpload.add("file", new ByteArrayResource("not-a-docx".getBytes()) {
                @Override public String getFilename() { return "notes.pdf"; }
            });
            invalidUpload.add("template", "article");
            for (int i = 0; i < 2; i++) {
                var attempt = request(HttpMethod.POST, "/api/jobs",
                        Map.of(HttpHeaders.COOKIE, DemoSession.COOKIE_NAME + "=" + session,
                                "CF-Connecting-IP", "198.51.100.20"), invalidUpload);
                assertEquals(HttpStatus.BAD_REQUEST, attempt.getStatusCode());
            }
            var uploadBlocked = request(HttpMethod.POST, "/api/jobs",
                    Map.of(HttpHeaders.COOKIE, DemoSession.COOKIE_NAME + "=" + session,
                            "CF-Connecting-IP", "198.51.100.20"), invalidUpload);
            assertEquals(HttpStatus.TOO_MANY_REQUESTS, uploadBlocked.getStatusCode());
            assertNotNull(uploadBlocked.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));

            String cappedSession = mintSession();
            Job first = createOwnedJob(cappedSession, "cap-1.docx");
            Job second = createOwnedJob(cappedSession, "cap-2.docx");
            try {
                var sessionCapBlocked = request(HttpMethod.POST, "/api/jobs",
                        Map.of(HttpHeaders.COOKIE, DemoSession.COOKIE_NAME + "=" + cappedSession,
                                "CF-Connecting-IP", "198.51.100.30"), invalidUpload);
                assertEquals(HttpStatus.TOO_MANY_REQUESTS, sessionCapBlocked.getStatusCode());
                assertTrue(sessionCapBlocked.getBody().contains("session limit"), sessionCapBlocked.getBody());
            } finally {
                if (store.exists(first.id())) store.delete(first.id());
                if (store.exists(second.id())) store.delete(second.id());
            }
        } finally {
            if (store.exists(job.id())) store.delete(job.id());
        }
    }

    @Test
    void cleanupExpiresOldIdleJobsAndEvictsOldestWhenAtCapacity(@TempDir Path tempDir) throws Exception {
        JobStore isolated = new JobStore(new AppProperties(tempDir.toString(), "", "", 1, 1, 10, 10));
        isolated.load();
        HostingProperties hosting = new HostingProperties("doc2tex.tech", true, true, 60, 3, 10, 10, 20, 10);
        DemoCleanupService cleanup = new DemoCleanupService(isolated, hosting);

        Instant now = Instant.now();
        Instant twoHoursAgo = now.minus(2, ChronoUnit.HOURS);
        Instant fiveMinutesAgo = now.minus(5, ChronoUnit.MINUTES);

        Job oldCompleted = new Job(UUID.randomUUID().toString(), "old.docx", "article", "COMPLETED",
                "NOT_REQUESTED", "", "", twoHoursAgo, twoHoursAgo, 100, 0);
        Job oldBusy = new Job(UUID.randomUUID().toString(), "busy.docx", "article", "CONVERTING",
                "NOT_REQUESTED", "", "", twoHoursAgo, twoHoursAgo, 100, 0);
        Job recentCompleted = new Job(UUID.randomUUID().toString(), "recent.docx", "article", "COMPLETED",
                "NOT_REQUESTED", "", "", fiveMinutesAgo, fiveMinutesAgo, 100, 0);

        isolated.save(oldCompleted);
        isolated.save(oldBusy);
        isolated.save(recentCompleted);

        assertEquals(1, cleanup.sweepExpired(now));
        assertFalse(isolated.exists(oldCompleted.id()));
        assertTrue(isolated.exists(oldBusy.id()));
        assertTrue(isolated.exists(recentCompleted.id()));

        Job newestCompleted = new Job(UUID.randomUUID().toString(), "newest.docx", "article", "COMPLETED",
                "NOT_REQUESTED", "", "", now, now, 100, 0);
        isolated.save(newestCompleted);
        assertEquals(3, isolated.totalCount());

        assertTrue(cleanup.makeRoomForUpload());
        assertEquals(2, isolated.totalCount());
        assertFalse(isolated.exists(recentCompleted.id()));
        assertTrue(isolated.exists(oldBusy.id()));
        assertTrue(isolated.exists(newestCompleted.id()));
    }
}
