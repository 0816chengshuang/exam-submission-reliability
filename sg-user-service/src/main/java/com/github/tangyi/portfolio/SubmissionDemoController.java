package com.github.tangyi.portfolio;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Profile;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@RestController
@Profile("dev")
@ConditionalOnProperty(name = "portfolio.demo.enabled", havingValue = "true")
public class SubmissionDemoController {

    @GetMapping(value = "/submission-demo", produces = MediaType.TEXT_HTML_VALUE)
    public String page() throws IOException {
        try (var stream = new ClassPathResource("portfolio/submission-demo.html").getInputStream()) {
            return StreamUtils.copyToString(stream, StandardCharsets.UTF_8);
        }
    }
}
