package com.vyoog.api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** VYB-0906: puts {@link AccessInterceptor} in front of every API handler. */
@Configuration
public class AccessWebConfig implements WebMvcConfigurer {

    private final AccessInterceptor interceptor;

    public AccessWebConfig(AccessInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/**");
    }
}
