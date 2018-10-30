/*-
 * -\-\-
 * Spotify Styx API Service
 * --
 * Copyright (C) 2016 Spotify AB
 * --
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * -/-/-
 */

package com.spotify.styx.api;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.services.cloudresourcemanager.CloudResourceManager;
import com.google.api.services.cloudresourcemanager.model.ListProjectsResponse;
import com.google.api.services.cloudresourcemanager.model.Project;
import com.google.api.services.iam.v1.Iam;
import com.google.api.services.iam.v1.model.ServiceAccount;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class GoogleIdTokenValidator {
  
  private static final Logger logger = LoggerFactory.getLogger(GoogleIdTokenValidator.class);

  private static final Pattern EMAIL_PATTERN = Pattern.compile("^.+@(.+)$");

  private static final Pattern SERVICE_ACCOUNT_PATTERN =
      Pattern.compile("^.+@(.+)\\.iam\\.gserviceaccount\\.com$");
  
  private static final long VALIDATED_EMAIL_CACHE_SIZE = 1000;

  private final GoogleIdTokenVerifier idTokenVerifier;

  private final CloudResourceManager cloudResourceManager;

  private final Iam iam;
  
  private final Set<String> domainWhitelist;

  private final Set<String> projectCache = Sets.newConcurrentHashSet();
  
  private final Cache<String, String> validatedEmailCache  = CacheBuilder.newBuilder()
      .maximumSize(VALIDATED_EMAIL_CACHE_SIZE)
      .build();

  @VisibleForTesting
  GoogleIdTokenValidator(GoogleIdTokenVerifier idTokenVerifier,
                         CloudResourceManager cloudResourceManager,
                         Iam iam,
                         Set<String> domainWhitelist) throws IOException {
    this.idTokenVerifier = Objects.requireNonNull(idTokenVerifier, "idTokenVerifier");
    this.cloudResourceManager =
        Objects.requireNonNull(cloudResourceManager, "cloudResourceManager");
    this.iam = Objects.requireNonNull(iam, "iam");
    this.domainWhitelist = Objects.requireNonNull(domainWhitelist, "domainWhitelist");

    cacheProjects();
    logger.info("project cache loaded");
  }

  GoogleIdToken validate(String token) {
    final GoogleIdToken googleIdToken = verifyIdToken(token);
    if (googleIdToken == null) {
      return null;
    }

    final String email = googleIdToken.getPayload().getEmail();

    if (checkDomainWhitelist(email)) {
      return googleIdToken;
    }

    if (validatedEmailCache.getIfPresent(email) != null) {
      return googleIdToken;
    }

    try {
      final String projectId = checkProject(email);
      if (projectId != null) {
        validatedEmailCache.put(email, projectId);
        return googleIdToken;
      }
      return null;
    } catch (IOException e) {
      logger.info("cannot validate {}", email);
      return null;
    }
  }

  private GoogleIdToken verifyIdToken(String token) {
    try {
      return idTokenVerifier.verify(token);
    } catch (GeneralSecurityException e) {
      return null;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private void cacheProjects() throws IOException {
    final CloudResourceManager.Projects.List request = cloudResourceManager.projects().list();

    ListProjectsResponse response;
    do {
      response = request.execute();
      if (response.getProjects() == null) {
        continue;
      }
      for (Project project : response.getProjects()) {
        projectCache.add(project.getProjectId());
      }
      request.setPageToken(response.getNextPageToken());
    } while (response.getNextPageToken() != null);
  }
  
  private boolean checkDomainWhitelist(String email) {
    final Matcher matcher = EMAIL_PATTERN.matcher(email);
    final String domain = matcher.group(1);
    if (domain != null) {
      logger.debug("check domain {} in whitelist", domain);
      return domainWhitelist.contains(domain);
    }
    return false;
  }
  
  private String checkProject(String email) throws IOException {
    final Matcher matcher = SERVICE_ACCOUNT_PATTERN.matcher(email);
    String projectId = matcher.group(1);

    if (projectId == null) {
      // no projectId, could be GCE default
      logger.debug("{} doesn't contain project id, try getting its project", email);
      projectId = getProjectIdOfServiceAccount(email);
    }

    if (projectId == null) {
      return null;
    }

    if (projectCache.contains(projectId)) {
      logger.debug("hit cache for project id {}", projectId);
      return projectId;
    } else if (checkProjectId(projectId)) {
      projectCache.add(projectId);
      return projectId;
    }

    return null;
  }
  
  private String getProjectIdOfServiceAccount(String email) throws IOException {
    try {
      final ServiceAccount serviceAccount =
          iam.projects().serviceAccounts().get("projects/-/serviceAccounts/" + email).execute();
      return serviceAccount.getProjectId();
    } catch (GoogleJsonResponseException e) {
      if (e.getStatusCode() == 404) {
        logger.debug("service account {} doesn't exist", email, e);
        return null;
      }

      logger.info("cannot get project id for service account {}", email, e);
      return null;
    }
  }
  
  private boolean checkProjectId(String projectId) throws IOException {
    try {
      cloudResourceManager.projects().get(projectId).execute();
      return true;
    } catch (GoogleJsonResponseException e) {
      if (e.getStatusCode() == 404) {
        logger.debug("project {} doesn't exist", projectId, e);
        return false;
      }

      logger.info("cannot get project with id {}", projectId, e);
      return false;
    }
  }
}
