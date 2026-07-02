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
package org.thingsboard.server.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.thingsboard.server.common.data.StringUtils;
import org.thingsboard.server.common.data.audit.ActionType;
import org.thingsboard.server.common.data.security.model.JwtPair;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.security.auth.closepark.CloseParkPermissionService;
import org.thingsboard.server.service.security.auth.closepark.CloseParkPermissionService.PermissionCheckData;
import org.thingsboard.server.service.security.auth.pat.ApiKeyAuthenticationProvider;
import org.thingsboard.server.service.security.auth.rest.RestAuthenticationDetails;
import org.thingsboard.server.service.security.model.SecurityUser;
import org.thingsboard.server.service.security.model.token.JwtTokenFactory;
import org.thingsboard.server.service.security.system.SystemSecurityService;

import java.util.Optional;

@RestController
@TbCoreComponent
@RequestMapping("/api/noauth/closepark")
@RequiredArgsConstructor
@Slf4j
public class CloseParkAuthController {

    private final CloseParkPermissionService permissionService;
    private final ApiKeyAuthenticationProvider apiKeyAuthenticationProvider;
    private final JwtTokenFactory tokenFactory;
    private final SystemSecurityService systemSecurityService;

    @Value("${security.closepark.enabled:false}")
    private boolean enabled;

    @Value("${security.closepark.api_key:}")
    private String apiKey;

    @PostMapping("/login")
    public ResponseEntity<JwtPair> login(@RequestBody CloseParkLoginRequest loginRequest,
                                         HttpServletRequest request) {
        if (!enabled || StringUtils.isBlank(apiKey) || loginRequest == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Optional<PermissionCheckData> permission = permissionService.checkPermission(loginRequest.token());
        if (permission.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            SecurityUser securityUser = apiKeyAuthenticationProvider.authenticate(apiKey);
            securityUser.setDisplayName(StringUtils.trimToNull(permission.get().nickName()));
            JwtPair tokenPair = tokenFactory.createTokenPair(securityUser);
            systemSecurityService.logLoginAction(securityUser, new RestAuthenticationDetails(request),
                    ActionType.LOGIN, "ClosePark", null);
            return ResponseEntity.ok(tokenPair);
        } catch (Exception e) {
            log.warn("ClosePark login failed for configured ThingsBoard API key: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }

    public record CloseParkLoginRequest(String token) {
    }

}
