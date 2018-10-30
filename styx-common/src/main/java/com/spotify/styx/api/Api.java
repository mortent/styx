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

import static com.spotify.styx.api.Middlewares.authValidator;
import static com.spotify.styx.api.Middlewares.clientValidator;
import static com.spotify.styx.api.Middlewares.exceptionAndRequestIdHandler;
import static com.spotify.styx.api.Middlewares.httpLogger;
import static com.spotify.styx.api.Middlewares.tracer;

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.googleapis.util.Utils;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.services.cloudresourcemanager.CloudResourceManager;
import com.google.api.services.iam.v1.Iam;
import com.google.api.services.iam.v1.IamScopes;
import com.spotify.apollo.Response;
import com.spotify.apollo.route.AsyncHandler;
import com.spotify.apollo.route.Route;
import io.opencensus.trace.Tracer;
import io.opencensus.trace.Tracing;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;
import okio.ByteString;

public final class Api {

  private static final Tracer tracer = Tracing.getTracer();
  
  private static final JsonFactory JSON_FACTORY = Utils.getDefaultJsonFactory();

  private static final HttpTransport HTTP_TRANSPORT;

  static {
    try {
      HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public enum Version {
    V3;

    public String prefix() {
      return "/api/" + name().toLowerCase();
    }

  }

  private Api() {
    throw new UnsupportedOperationException();
  }

  public static Stream<Route<AsyncHandler<Response<ByteString>>>> prefixRoutes(
      Collection<Route<AsyncHandler<Response<ByteString>>>> routes,
      Version... versions) {
    return Stream.of(versions)
        .flatMap(v -> routes.stream().map(route -> route.withPrefix(v.prefix())));
  }

  public static Stream<Route<AsyncHandler<Response<ByteString>>>> withCommonMiddleware(
      Stream<Route<AsyncHandler<Response<ByteString>>>> routes,
      Set<String> domainWhitelist,
      String service) {
    return withCommonMiddleware(routes, Collections::emptyList, domainWhitelist, service);
  }

  public static Stream<Route<AsyncHandler<Response<ByteString>>>> withCommonMiddleware(
      Stream<Route<AsyncHandler<Response<ByteString>>>> routes,
      Supplier<List<String>> clientBlacklistSupplier,
      Set<String> domainWhitelist,
      String service) {
    final GoogleIdTokenValidator validator = createGoogleIdTokenValidator(domainWhitelist, service);

    return routes.map(r -> r
        .withMiddleware(httpLogger(validator))
        .withMiddleware(authValidator(validator))
        .withMiddleware(clientValidator(clientBlacklistSupplier))
        .withMiddleware(exceptionAndRequestIdHandler())
        .withMiddleware(tracer(tracer, service)));
  }
  
  private static GoogleIdTokenValidator createGoogleIdTokenValidator(Set<String> domainWhitelist,
                                                                     String service) {
    final GoogleIdTokenVerifier idTokenVerifier = new GoogleIdTokenVerifier(HTTP_TRANSPORT, JSON_FACTORY);

    final GoogleCredential credential = getGoogleCredential();

    final CloudResourceManager cloudResourceManager =
        new CloudResourceManager.Builder(HTTP_TRANSPORT, JSON_FACTORY, credential)
            .setApplicationName(service)
            .build();

    final Iam iam = new Iam.Builder(
        HTTP_TRANSPORT, JSON_FACTORY, credential)
        .setApplicationName(service)
        .build();

    final GoogleIdTokenValidator validator =
        new GoogleIdTokenValidator(idTokenVerifier, cloudResourceManager, iam, domainWhitelist);
    try {
      validator.cacheProjects();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return validator;
  }
  
  private static GoogleCredential getGoogleCredential() {
    try {
      return GoogleCredential.getApplicationDefault().createScoped(IamScopes.all());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
