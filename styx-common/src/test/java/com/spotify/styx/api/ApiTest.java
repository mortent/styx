/*-
 * -\-\-
 * Spotify styx
 * --
 * Copyright (C) 2017 Spotify AB
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

import static java.util.stream.Collectors.toList;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.google.common.collect.ImmutableList;
import com.spotify.apollo.Response;
import com.spotify.apollo.route.AsyncHandler;
import com.spotify.apollo.route.Route;
import com.spotify.styx.util.ClassEnforcer;
import java.util.List;
import okio.ByteString;
import org.junit.Test;

public class ApiTest {

  private static final List<Route<AsyncHandler<Response<ByteString>>>> ROUTES = ImmutableList.of(
      Route.create("GET", "/foo", rc -> null),
      Route.create("GET", "/bar", rc -> null));

  @Test
  public void shouldNotBeConstructable() throws ReflectiveOperationException {
    assertThat(ClassEnforcer.assertNotInstantiable(Api.class), is(true));
  }

  @Test
  public void prefixShouldWork() {
    assertEquals("/api/v3", Api.Version.V3.prefix());
  }

  @Test
  public void shouldPrefixRoutes() {
    final List<String> prefixedUris = Api.prefixRoutes(ROUTES, Api.Version.V3)
        .map(Route::uri)
        .collect(toList());
    assertThat(prefixedUris, is(ImmutableList.of("/api/v3/foo", "/api/v3/bar")));
  }
}
