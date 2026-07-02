/**
 * Copyright © 2016-2026 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.service.security.auth.closepark;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.thingsboard.server.common.data.StringUtils;

import java.time.Duration;
import java.util.Optional;

@Service
@Slf4j
public class CloseParkPermissionService {

    private static final int MAX_TOKEN_LENGTH = 8192;

    private final String permissionCheckUrl;
    private final RestTemplate restTemplate;

    public CloseParkPermissionService(
            @Value("${security.closepark.permission_check_url}") String permissionCheckUrl,
            @Value("${security.closepark.connect_timeout_ms:5000}") long connectTimeoutMs,
            @Value("${security.closepark.read_timeout_ms:5000}") long readTimeoutMs) {
        this.permissionCheckUrl = permissionCheckUrl;
        this.restTemplate = new RestTemplateBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .readTimeout(Duration.ofMillis(readTimeoutMs))
                .build();
    }

    public Optional<PermissionCheckData> checkPermission(String token) {
        if (StringUtils.isBlank(token) || token.length() > MAX_TOKEN_LENGTH) {
            return Optional.empty();
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token.trim());
            ResponseEntity<PermissionCheckResponse> response = restTemplate.exchange(
                    permissionCheckUrl,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    PermissionCheckResponse.class);
            PermissionCheckResponse body = response.getBody();
            if (!response.getStatusCode().is2xxSuccessful()
                    || body == null
                    || body.code() != 200
                    || body.data() == null
                    || !body.data().validResult()) {
                return Optional.empty();
            }
            return Optional.of(body.data());
        } catch (RestClientException | IllegalArgumentException e) {
            log.warn("Failed to check ClosePark ThingsBoard permission: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public record PermissionCheckResponse(int code, String msg, PermissionCheckData data) {
    }

    public record PermissionCheckData(boolean validResult, Long userId, String nickName) {
    }

}
