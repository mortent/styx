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
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.googleapis.util.Utils;
import com.google.api.client.http.HttpTransport;
import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Objects;

public class GoogleIdTokenValidator {
  private static final HttpTransport HTTP_TRANSPORT;
  
  static {
    try {
      HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private final GoogleIdTokenVerifier idTokenVerifier;

  GoogleIdTokenValidator() {
    this(new GoogleIdTokenVerifier(HTTP_TRANSPORT, Utils.getDefaultJsonFactory()));
  }

  @VisibleForTesting
  GoogleIdTokenValidator(GoogleIdTokenVerifier idTokenVerifier) {
    this.idTokenVerifier = Objects.requireNonNull(idTokenVerifier, "idTokenVerifier");
  }

  private GoogleIdToken verifyIdToken(String token) {
    try {
      return idTokenVerifier.verify(token);
    } catch (GeneralSecurityException e) {
      return null; // will be treated as an invalid token
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  GoogleIdToken validate(String token) {
    // TODO: validate the account belongs to GCP org
    return verifyIdToken(token);
  }
}
