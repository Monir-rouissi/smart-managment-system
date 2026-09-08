package com.smartmgmt;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;

/**
 * Spring Boot 4 no longer auto-applies spring-security-test's MockMvc
 * configurer, so an auto-configured MockMvc runs without the security filter
 * chain wiring that {@code @WithUserDetails} / {@code @WithMockUser} rely on
 * (every authenticated request came back 401). Applying it here restores that.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MockMvcSecurityConfiguration {

    @Bean
    MockMvcBuilderCustomizer securityMockMvcBuilderCustomizer() {
        return builder -> builder.apply(SecurityMockMvcConfigurers.springSecurity());
    }
}
