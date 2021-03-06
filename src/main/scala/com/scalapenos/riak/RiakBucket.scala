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

import scala.concurrent.{ExecutionContext, Future}
import internal._


trait RiakBucket extends RiakBucketBasicProperties with RiakSearch {

  /**
   * Every bucket has a default RiakConflictsResolver that will be used when resolving
   * conflicts during fetches and stores (when returnBody is true).
   */
  def resolver: RiakConflictsResolver

  def bucketType: RiakBucketType

  def fetch(key: String): Future[Option[RiakValue]]

  def fetch(index: String, value: String): Future[List[RiakValue]] = fetch(RiakIndex(index, value))
  def fetch(index: String, value: Int): Future[List[RiakValue]] = fetch(RiakIndex(index, value))
  def fetch(index: RiakIndex): Future[List[RiakValue]]

  def fetch(index: String, start: String, end: String): Future[List[RiakValue]] = fetch(RiakIndexRange(index, start, end))
  def fetch(index: String, start: Int, end: Int): Future[List[RiakValue]] = fetch(RiakIndexRange(index, start, end))
  private[riak] def fetch(index: RiakIndexRange): Future[List[RiakValue]]


  def store[T: RiakMarshaller](key: String, value: T): Future[Unit] = store(key, RiakValue(value))
  def store[T: RiakMarshaller](key: String, meta: RiakMeta[T]): Future[Unit] = store(key, RiakValue(meta))
  def store(key: String, value: RiakValue): Future[Unit]

  def storeAndFetch[T: RiakMarshaller](key: String, value: T): Future[RiakValue] = storeAndFetch(key, RiakValue(value))
  def storeAndFetch[T: RiakMarshaller](key: String, meta: RiakMeta[T]): Future[RiakValue] = storeAndFetch(key, RiakValue(meta))
  def storeAndFetch(key: String, value: RiakValue): Future[RiakValue]

  def delete(key: String): Future[Unit]

  def getKeys():Future[List[String]]
  def getKeysStream():RiakStream[List[String]]

  def search(query:RiakSearchQuery):Future[RiakSearchResult]


}

