/*
 * Copyright (C) 2012-2013 Age Mooij (http://scalapenos.com)
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

class RiakClientSpec extends RiakClientSpecification{

  "A RiakClient" should {
    "support calling the Riak ping API" in {
      client.ping.await should beTrue
    }

    "get the list of buckets" in {
      client.getBuckets().await must beAnInstanceOf[List[RiakBucket]]
    }
  }

}
