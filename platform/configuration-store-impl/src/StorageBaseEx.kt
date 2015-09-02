/*
 * Copyright 2000-2015 JetBrains s.r.o.
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
package com.intellij.configurationStore

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.impl.stores.StateStorageBase
import com.intellij.openapi.util.registry.Registry
import org.jdom.Element
import java.io.Closeable

abstract class StorageBaseEx<T : Any> : StateStorageBase<T>() {
  fun <S : Any> createGetSession(component: PersistentStateComponent<S>, componentName: String, stateClass: Class<S>, reload: Boolean = false) = StateGetter(component, componentName, getStorageData(reload), stateClass, this)

  /**
   * serializedState is null if state equals to default (see XmlSerializer.serializeIfNotDefault)
   */
  abstract fun archiveState(storageData: T, componentName: String, serializedState: Element?)
}

class StateGetter<S : Any, T : Any>(private val component: PersistentStateComponent<S>, private val componentName: String, private val storageData: T, private val stateClass: Class<S>, private val storage: StorageBaseEx<T>) : Closeable {
  var serializedState: Element? = null

  fun getState(mergeInto: S? = null): S? {
    LOG.assertTrue(serializedState == null)

    serializedState = storage.getState(storageData, component, componentName)
    if (serializedState != null) {
      //System.out.println("open $componentName to read state, ${hashCode()} $storage, ${Thread.currentThread()}")
    }
    return storage.deserializeState(serializedState, stateClass, mergeInto)
  }

  override fun close() {
    if (serializedState == null) {
      return
    }

    //System.out.println("close $componentName to read state, ${hashCode()} $storage, ${Thread.currentThread()}")

    val stateAfterLoad: S?
    try {
      stateAfterLoad = if (ApplicationManager.getApplication().isUnitTestMode() || Registry.`is`("use.loaded.state.as.existing", false)) component.getState() else null
    }
    catch(e: Throwable) {
      LOG.error("Cannot get state after load", e)
      stateAfterLoad = null
    }

    val serializedStateAfterLoad = if (stateAfterLoad == null) {
      serializedState
    }
    else {
      serializeState(stateAfterLoad)?.normalizeRootName()
    }

    storage.archiveState(storageData, componentName, serializedStateAfterLoad)
  }
}