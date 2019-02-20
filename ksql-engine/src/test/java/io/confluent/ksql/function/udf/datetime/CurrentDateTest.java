/*
 * Copyright 2018 Confluent Inc.
 *
 * Licensed under the Confluent Community License; you may not use this file
 * except in compliance with the License.  You may obtain a copy of the License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.ksql.function.udf.datetime;

import org.junit.Before;
import org.junit.Test;
import java.time.LocalDate;

import static org.junit.Assert.assertEquals;

public class CurrentDateTest {

  private CurrentDate udf;

  @Before
  public void setUp() {
    udf = new CurrentDate();
  }

  @Test
  public void shouldGetTheCurrentDate() {
    final int now = ((int) LocalDate.now().toEpochDay());
    final int result = udf.currentDate();

    assertEquals(now, result);
  }

}
