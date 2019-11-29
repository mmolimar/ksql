/*
 * Copyright 2018 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.ksql.test.util.secure;

import com.google.errorprone.annotations.Immutable;
import java.util.Objects;

/**
 * Stores username & password.
 */
@Immutable
public final class Credentials {

  public final String username;
  public final String password;

  public Credentials(final String username, final String password) {
    this.username = Objects.requireNonNull(username, "username");
    this.password = Objects.requireNonNull(password, "password");
  }
}
