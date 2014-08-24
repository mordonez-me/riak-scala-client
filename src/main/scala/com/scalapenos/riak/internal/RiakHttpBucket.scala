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
package internal


private[riak] final class RiakHttpBucket(helper: RiakHttpClientHelper, server: RiakServerInfo, val name: String, val resolver: RiakConflictsResolver, val bucketType:Option[RiakBucketType]) extends RiakBucket {
  def fetch(key: String) = helper.fetch(server, name, bucketType, key, resolver)
  def fetch(index: RiakIndex) = helper.fetch(server, name, bucketType, index, resolver)
  def fetch(indexRange: RiakIndexRange) = helper.fetch(server, name, bucketType, indexRange, resolver)

  def store(key: String, value: RiakValue) = helper.store(server, name, bucketType, key, value, resolver)
  def storeAndFetch(key: String, value: RiakValue) = helper.storeAndFetch(server, name, bucketType, key, value, resolver)

  def delete(key: String) = helper.delete(server, name, bucketType, key)

  def getProperties = helper.getBucketProperties(server, name, bucketType)
  def setProperties(newProperties: Set[RiakBucketProperty[_]]) = helper.setBucketProperties(server, name, bucketType, newProperties)

  def search(query:RiakSearchQuery) = helper.searchInBucket(server, this, query, resolver)
  def searchInBucketType(query:RiakSearchQuery) = helper.searchInBucketType(server, this, query, resolver)

  def setSearchIndex(riakSearchIndex: RiakSearchIndex) = helper.setBucketSearchIndex(server, name, bucketType, riakSearchIndex)
}
