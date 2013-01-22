/*
 * Copyright (C) 2011-2012 scalapenos.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.scalapenos.riak
package converters

import scala.util._


trait BasicRiakValueConverters {
  implicit def identityRiakValueConverter = new RiakValueConverter[RiakValue] {
    def read(riakValue: RiakValue): Try[RiakValue] = Success(riakValue)
    def write(riakValue: RiakValue): RiakValue = riakValue
  }

  implicit def stringRiakValueConverter = new RiakValueConverter[String] {
    def read(riakValue: RiakValue): Try[String] = Success(riakValue.value)
    def write(obj: String): RiakValue = RiakValue(obj)
  }
}

object BasicRiakValueConverters extends BasicRiakValueConverters
