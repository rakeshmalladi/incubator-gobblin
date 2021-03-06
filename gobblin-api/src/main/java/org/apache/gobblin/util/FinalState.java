/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.gobblin.util;

import org.apache.gobblin.configuration.State;


/**
 * Interface for constructs that can report their final state.
 */
public interface FinalState {

  /**
   * Called by tasks after all work for the construct has been executed. The construct can return a state
   * describing a summary of its execution / final state.
   * @return a {@link org.apache.gobblin.configuration.State} with summary of execution / final state of the construct.
   */
  public State getFinalState();

}
