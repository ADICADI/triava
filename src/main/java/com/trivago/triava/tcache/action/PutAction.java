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

package com.trivago.triava.tcache.action;

import javax.cache.event.EventType;
import javax.cache.integration.CacheWriterException;

import com.trivago.triava.tcache.core.TCacheJSR107Entry;
import com.trivago.triava.tcache.eviction.Cache;

public class PutAction<K,V,W> extends Action<K,V,W>
{

	final boolean countStatistics;
	public PutAction(K key, V value, EventType eventType, Cache<K,V> actionContext, boolean countStatistics)
	{
		super(key, value, eventType, actionContext);
		this.countStatistics = false;
	}

	@Override
	W writeThroughImpl()
	{
		try
		{
			cacheWriter.write(new TCacheJSR107Entry<K,V>(key, value));
		}
		catch (Exception exc)
		{
			writeThroughException = new CacheWriterException(exc);
		}
		return null;
	}

	@Override
	void notifyListenersImpl(Object... args)
	{
		listeners.dispatchEvent(eventType, key, value);
	}

	@Override
	void statisticsImpl()
	{
		if (countStatistics)
			stats.incrementPutCount();
	}

}