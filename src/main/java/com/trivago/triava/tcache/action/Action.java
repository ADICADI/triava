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
import javax.cache.integration.CacheWriter;
import javax.cache.integration.CacheWriterException;

import com.trivago.triava.tcache.event.ListenerCollection;
import com.trivago.triava.tcache.eviction.Cache;
import com.trivago.triava.tcache.statistics.StatisticsCalculator;

public abstract class Action<K,V,W>
{
	final K key;
	final V value;
	EventType eventType;
	final CacheWriter<K, V> cacheWriter;
	final ListenerCollection<K, V> listeners;
	final StatisticsCalculator stats;
	
	// Result
	CacheWriterException writeThroughException = null;
	
	Action(K key, V value, EventType eventType, Cache<K,V> actionContext)
	{
		this.key = key;
		this.value = value;
		this.setEventType(eventType);
		
		// actionContext. It is currently taken directly from the Cache
		this.cacheWriter = actionContext.cacheWriter();
		this.listeners = actionContext.listeners();
		this.stats = actionContext.statisticsCalculator();
	}
	
	
	public W writeThrough()
	{
		if (cacheWriter == null)
			return null;
		return writeThroughImpl();

	}
	
	public void notifyListeners(Object... args)
	{
		if (listeners != null)
			notifyListenersImpl(args);
	}
	
	public void statistics()
	{
		if (stats != null)
		{
			statisticsImpl();
		}
	}

	abstract W writeThroughImpl();
	abstract void notifyListenersImpl(Object... args);
	abstract void statisticsImpl();
	
	public void close() throws CacheWriterException
	{
		if (writeThroughException != null)
		{
			throw writeThroughException;
		}
	}



	public void setEventType(EventType eventType)
	{
		this.eventType = eventType;
	}


	public boolean successful()
	{
		return writeThroughException == null;
	}


}
