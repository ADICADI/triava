/*********************************************************************************
 * Copyright 2016-present trivago GmbH
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **********************************************************************************/

package com.trivago.triava.tcache.eviction;

import com.trivago.triava.tcache.AccessTimeObjectHolder;

public class Holders<V>
{
	public final AccessTimeObjectHolder<V> newHolder;
	public final AccessTimeObjectHolder<V> oldHolder;
	public final AccessTimeObjectHolder<V> effectiveHolder;
	
	public Holders(AccessTimeObjectHolder<V> newHolder,  AccessTimeObjectHolder<V> oldHolder, AccessTimeObjectHolder<V> effectiveHolder)
	{
		this.newHolder = newHolder;
		this.oldHolder = oldHolder;
		this.effectiveHolder = effectiveHolder;
	}
}
