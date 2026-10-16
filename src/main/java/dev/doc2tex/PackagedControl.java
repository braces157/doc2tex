package dev.doc2tex;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PackagedControl {
    private final ConfigurableApplicationContext context;
    PackagedControl(ConfigurableApplicationContext context) { this.context = context; }

    @PostMapping("/api/system/stop")
    ResponseEntity<Void> stop(@RequestHeader(value = "X-Doc2tex-Control", required = false) String token) {
        if (!PackagedRuntime.packaged()) throw new ApiException(HttpStatus.NOT_FOUND, "Use Ctrl+C to stop the development server.");
        if (!PackagedRuntime.authorized(token)) throw new ApiException(HttpStatus.FORBIDDEN, "Use Stop doc2TeX.cmd from your app folder.");
        java.util.concurrent.CompletableFuture.delayedExecutor(1, java.util.concurrent.TimeUnit.SECONDS).execute(context::close);
        return ResponseEntity.accepted().build();
    }
}
