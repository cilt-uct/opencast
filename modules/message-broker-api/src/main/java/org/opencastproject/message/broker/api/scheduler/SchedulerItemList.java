/**
 * Licensed to The Apereo Foundation under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 *
 * The Apereo Foundation licenses this file to you under the Educational
 * Community License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License
 * at:
 *
 *   http://opensource.org/licenses/ecl2.txt
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *
 */

package org.opencastproject.message.broker.api.scheduler;

import org.opencastproject.message.broker.api.MessageItem;

import java.io.Serializable;

public class SchedulerItemList implements MessageItem, Serializable {
  private final String id;
  private final SchedulerItem[] items;

  public static SchedulerItemList singleton(final String id, final SchedulerItem item) {
    return new SchedulerItemList(id, new SchedulerItem[] {item});
  }

  public SchedulerItemList(final String id, final SchedulerItem[] items) {
    this.id = id;
    this.items = items;
  }

  public SchedulerItem[] getItems() {
    return items;
  }

  @Override
  public String getId() {
    return id;
  }
}
