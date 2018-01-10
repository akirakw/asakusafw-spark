/*
 * Copyright 2011-2018 Asakusa Framework Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.asakusafw.spark.compiler.broadcast

import scala.reflect.ClassTag

import org.apache.spark.broadcast.Broadcast

class MockBroadcast[T: ClassTag](id: Int, value: T) extends Broadcast[T](id) {

  override protected def getValue(): T = value

  override protected def doUnpersist(blocking: Boolean): Unit = {}

  override protected def doDestroy(blocking: Boolean): Unit = {}
}
