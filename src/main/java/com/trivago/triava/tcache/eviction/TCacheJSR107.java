/*********************************************************************************
 * Copyright 2015-present trivago GmbH
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import javax.cache.CacheException;
import javax.cache.CacheManager;
import javax.cache.configuration.CacheEntryListenerConfiguration;
import javax.cache.configuration.CompleteConfiguration;
import javax.cache.configuration.Configuration;
import javax.cache.event.EventType;
import javax.cache.integration.CacheLoader;
import javax.cache.integration.CacheLoaderException;
import javax.cache.integration.CacheWriter;
import javax.cache.integration.CacheWriterException;
import javax.cache.integration.CompletionListener;
import javax.cache.processor.EntryProcessor;
import javax.cache.processor.EntryProcessorException;
import javax.cache.processor.EntryProcessorResult;

import com.trivago.triava.tcache.action.Action;
import com.trivago.triava.tcache.action.ActionRunner;
import com.trivago.triava.tcache.action.DeleteAction;
import com.trivago.triava.tcache.action.DeleteOnValueAction;
import com.trivago.triava.tcache.action.PutAction;
import com.trivago.triava.tcache.action.WriteBehindActionRunner;
import com.trivago.triava.tcache.action.WriteThroughActionRunner;
import com.trivago.triava.tcache.core.Builder;
import com.trivago.triava.tcache.core.NopCacheWriter;
import com.trivago.triava.tcache.core.TCacheConfigurationBean;
import com.trivago.triava.tcache.core.TCacheEntryIterator;
import com.trivago.triava.tcache.core.TCacheJSR107Entry;
import com.trivago.triava.tcache.core.TCacheJSR107MutableEntry;
import com.trivago.triava.tcache.event.ListenerCollection;
import com.trivago.triava.tcache.eviction.Cache.AccessTimeObjectHolder;
import com.trivago.triava.tcache.statistics.TCacheStatisticsBean;
import com.trivago.triava.tcache.statistics.TCacheStatisticsBean.StatisticsAveragingMode;

/**
 * A Java Caching implementation.
 * 
 * - Read-Through Caching  : 
 * - Write-Through Caching : CHECKED
 * - Statistics            :
 * - Event sending         :
 * - Consistency table     :
 * - ExpiryPolicy table    :
 *  
 * 
 * @author cesken
 *
 * @param <K> The Key type
 * @param <V> The Value type
 */
public class TCacheJSR107<K, V> implements javax.cache.Cache<K, V>
{
	final Cache<K,V> tcache;
	final CacheManager cacheManager;
	final TCacheConfigurationBean<K,V> configurationBean;

	TCacheJSR107(Cache<K,V> tcache, CacheManager cacheManager)
	{
		this.tcache = tcache;
		this.cacheManager = cacheManager;
		this.configurationBean = new TCacheConfigurationBean<K,V>(tcache);
	}
	
	@Override
	public void clear()
	{
		throwISEwhenClosed();
		tcache.clear();	
	}

	@Override
	public void close()
	{
		tcache.shutdown();
	}

	@Override
	public boolean containsKey(K key)
	{
		throwISEwhenClosed();
		return tcache.containsKey(key);
	}

	@Override
	public void deregisterCacheEntryListener(CacheEntryListenerConfiguration<K, V> listenerConfiguration)
	{
		throwISEwhenClosed();
		
		tcache.listeners.deregisterCacheEntryListener(listenerConfiguration);
	}

	@Override
	public V get(K key)
	{
		throwISEwhenClosed();

		return tcache.get(key);
	}

	@Override
	public Map<K, V> getAll(Set<? extends K> keys)
	{
		throwISEwhenClosed();

		Map<K, V> result = new HashMap<>(keys.size());
		for (K key : keys)
		{
			V value = tcache.get(key);
			if (value != null)
			{
				result.put(key, value);
			}
		}
		
		return result;
	}

	@Override
	public V getAndPut(K key, V value)
	{
		throwISEwhenClosed();

		AccessTimeObjectHolder<V> holder = tcache.putToMap(key, value, tcache.getDefaultMaxIdleTime(), tcache.cacheTimeSpread(), false, false);
		
		final EventType eventType;
		final V result;
		if (holder == null)
		{
			eventType = EventType.CREATED;
			result = null;
		}
		else
		{
			eventType = EventType.UPDATED;
			result = holder.peek(); // TCK-WORK JSR107 check statistics effect
		}
		
		tcache.actionDispatcher.write(key, value, eventType);
		return result;
	}

	@Override
	public V getAndRemove(K key)
	{
		throwISEwhenClosed();

		V oldValue = tcache.remove(key);
		boolean removed = oldValue != null;
		// TCK CHALLENGE oldValue needs to be passed as (old)Value, otherwise NPE at org.jsr107.tck.event.CacheListenerTest$MyCacheEntryEventFilter.evaluate(CacheListenerTest.java:344)
		tcache.actionDispatcher.delete(key, oldValue, removed);
		
		if (removed)
		{
			tcache.statisticsCalculator.incrementHitCount();
		}
		else
		{
			tcache.statisticsCalculator.incrementMissCount();
			// remvoveCOunt already done in tcache.remove(key);
		}

		return oldValue;
	}

	@Override
	public V getAndReplace(K key, V value)
	{
		throwISEwhenClosed();

		Action<K, V, Object> action = new PutAction<K, V, Object>(key, value, EventType.UPDATED, tcache, false);
		V oldValue = null;
		if (actionRunnerWriteBehind.preMutate(action))
		{
			oldValue = tcache.getAndReplace(key, value);
			boolean replaced = oldValue != null;
			actionRunnerWriteBehind.postMutate(action, replaced);
		}
		action.close();
		return oldValue;				
	}

	@Override
	public CacheManager getCacheManager()
	{
		return cacheManager;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <C extends Configuration<K, V>> C getConfiguration(Class<C> clazz)
	{
		Builder<K, V> builder = tcache.builder;

		if (clazz.isAssignableFrom(javax.cache.configuration.Configuration.class))
		{
			return (C)builder;
		}
		if (clazz.isAssignableFrom(CompleteConfiguration.class))
		{
			return (C)builder;
		}
		
		throw new IllegalArgumentException("Unsupported configuration class: " + clazz.toString());
	}

	@Override
	public String getName()
	{
		return tcache.id();
	}

	@Override
	public <T> T invoke(K key, EntryProcessor<K, V, T> entryProcessor, Object... args) throws EntryProcessorException
	{
		throwISEwhenClosed();
		if (entryProcessor == null)
		{
			// TCK CHALLENGE: While not explicitly mentioned in the Javadocs, the TCK requires a NullPointerException.
			// This makes sense, but should be added to the Javadocs.
			throw new NullPointerException("entryProcessor is null");
		}
		
		// -1- Load if not present via Loader
		AccessTimeObjectHolder<V> holder = tcache.getFromMap(key);

		// -2- Run EntryProcessor
		V value = holder != null ? holder.peek() : null; // Create surrogate "null" if not existing (JSR107)
		TCacheJSR107MutableEntry<K, V> me = new TCacheJSR107MutableEntry<K, V>(key, value);
		try
		{
			T result = processEntryProcessor(entryProcessor, me, args);
			return result;
		}
		catch (EntryProcessorException epe)
		{
			// Do not wrap EntryProcessorException, to pass the JSR107 TCK. This is a likely a TCK bug. See https://github.com/jsr107/jsr107tck/issues/85
			throw epe;
		}
		catch (Exception exc)
		{
			throw new EntryProcessorException(exc);
		}

	}

	@Override
	public <T> Map<K, EntryProcessorResult<T>> invokeAll(Set<? extends K> keys, EntryProcessor<K, V, T> entryProcessor,
			Object... args)
	{
		throwISEwhenClosed();
		if (entryProcessor == null)
		{
			// TCK CHALLENGE: While not explicitly mentioned in the Javadocs, the TCK requires a NullPointerException.
			// This makes sense, but should be added to the Javadocs.
			throw new NullPointerException("entryProcessor is null");
		}
		Map<K, EntryProcessorResult<T>> resultMap = new HashMap<>();
		for (K key : keys)
		{
			try
			{
				AccessTimeObjectHolder<V> holder = tcache.objects.get(key);
				V value = holder != null ? holder.peek() : null; // Create surrogate "null" if not existing
				TCacheJSR107MutableEntry<K, V> me = new TCacheJSR107MutableEntry<K, V>(key, value);
				// TODO CacheWriter behavior is
				// here not JSR107 compliant.
				// writeAll() must be called
				// instead of individual write()
				// calls
				T result = processEntryProcessor(entryProcessor, me, args);
				if (result != null)
				{
					resultMap.put(key, new EntryProcessorResultTCache<T>(result));
				}
			}
			catch (Exception exc)
			{
				resultMap.put(key, new EntryProcessorResultTCache<T>(exc));
			}
		}
		
		return resultMap;
	}

	/**
	 * Process the given EntryProcessor and apply the change (delete or setValue) requested by that
	 * EntryProcessor. Mutable changes have no direct impact, but are be applied after after the
	 * EntryProcessor has returned, but before this method returns.
	 * 
	 * @param entryProcessor The entry processor to execute
	 * @param me The MutableEntry
	 * @param args The client arguments
	 * @return The result from the EntryProcessor
	 */
	private <T> T processEntryProcessor(EntryProcessor<K, V, T> entryProcessor, TCacheJSR107MutableEntry<K, V> me, Object... args)
	{
		T result = entryProcessor.process(me, args);
		K key;
		V value;
		switch (me.operation())
		{
			case REMOVE:
				key = me.getKey();
				remove(key);
				break;
			case SET:
				key = me.getKey();
				value = me.getValue();
				put(key, value);
				break;
			case REMOVE_WRITE_THROUGH:
				key = me.getKey();
				remove(key, false);
				break;
			default:
				break;
		}
		return result;
	}

	private static class EntryProcessorResultTCache<T> implements EntryProcessorResult<T>
	{
		Object result;
		
		EntryProcessorResultTCache(T result)
		{
			this.result = result;
		}

		EntryProcessorResultTCache(Exception exc)
		{
			if (exc instanceof EntryProcessorException)
			{
				// Do not wrap EntryProcessorException, to pass the JSR107 TCK. This is a likely a TCK bug. See https://github.com/jsr107/jsr107tck/issues/85
				this.result = exc;
			}
			else
			{
				this.result = new EntryProcessorException(exc);
			}
		}

		@Override
		public T get() throws EntryProcessorException
		{
			if (result instanceof EntryProcessorException)
				throw (EntryProcessorException)result;
			else
			{
				@SuppressWarnings("unchecked")
				T ret = (T)result;
				return ret;
			}
		}
		
	}
	
	@Override
	public boolean isClosed()
	{
		return tcache.isClosed();
	}

	@Override
	public Iterator<javax.cache.Cache.Entry<K, V>> iterator()
	{
		throwISEwhenClosed();

		TCacheEntryIterator<K, V> it = new TCacheEntryIterator<K,V>(this.tcache, tcache.objects);
		return it;
	}

	// TODO #loadAll() must be implemented ASYNCHRONOUSLY according to JSR107 Spec
	@Override
	public void loadAll(Set<? extends K> keys, boolean replaceExistingValues, CompletionListener listener)
	{
		throwISEwhenClosed();

		for (K key : keys) // implicit null check on keys => will throw NPE
		{
			tcache.verifyKeyNotNull(key);
		}
		
		CacheLoader<K, V> loader = tcache.loader;

		if (loader == null)
		{
			if (listener != null)
				listener.onException(new CacheException("Cache does not support loadAll as no CacheLoader is defined: " + this.getName()));
			return;
		}

		try
		{
			Map<K, V> loadedEntries = null;
			try
			{
				if (replaceExistingValues)
				{
					loadedEntries = loader.loadAll(keys);
				}
				else
				{
					final Set<K> finalKeys = new HashSet<>();
					
					// Only a single Thread may iterate keys (may be a not thread-safe Set)
					for (K key : keys)
					{
						if (!containsKey(key))
						{
							finalKeys.add(key);
						}
					}
	
					loadedEntries = loader.loadAll(finalKeys);
				}
			}
			catch (Exception exc)
			{
				// Wrap loader Exceptions in CacheLoaderExcpeption. The TCK requires it, but it is possibly a TCK bug.

				// TODO Check back after clarifying whether this requirement is a TCK bug:
				// https://github.com/jsr107/jsr107tck/issues/99
				String message = "CacheLoader " + tcache.id() + " failed to load keys";
				throw new CacheLoaderException(message + " This is a wrapped exception. See https://github.com/jsr107/jsr107tck/issues/99", exc);
			}
			
			if (loadedEntries != null)
			{
				Map<K, V> cleanedEntries = new HashMap<>(2*loadedEntries.size());
				for (java.util.Map.Entry<K, V> entry: loadedEntries.entrySet())
				{
					K key = entry.getKey();
					V value = entry.getValue();
					if (key == null || value == null)
						continue; //invalid => do not load
					
					cleanedEntries.put(key, value);
				}
				
				putAll(cleanedEntries, false);
			}
			
			if (listener != null)
				listener.onCompletion();
		}
		catch (Exception exc)
		{
			if (listener != null)
				listener.onException(exc);
		}
		
		

	}

	ActionRunner actionRunner = new WriteThroughActionRunner(); // TODO Move to constructor
	ActionRunner actionRunnerWriteBehind = new WriteBehindActionRunner(); // TODO Move to constructor

	@Override
	public void put(K key, V value)
	{
		throwISEwhenClosed();

		Action<K,V,Object> action = new PutAction<>(key, value, EventType.CREATED, tcache, false);

		if (actionRunner.preMutate(action))
		{
			AccessTimeObjectHolder<V> holder = tcache.putToMap(key, value, tcache.getDefaultMaxIdleTime(),
					tcache.cacheTimeSpread(), false, false);
	
			EventType eventType = holder == null ? EventType.CREATED : EventType.UPDATED;
			action.setEventType(eventType);
			actionRunner.postMutate(action, true);	
		}
		action.close();
	}

	@Override
	public void putAll(Map<? extends K, ? extends V> entries)
	{
		putAll(entries, true);
	}
	
	private void putAll(Map<? extends K, ? extends V> entries, boolean callWriter)
	{
		throwISEwhenClosed();

		if (entries.isEmpty())
			return;

		verifyEntriesNotNull(entries);

		// -1- Run Writer ---
		// Hint: A CacheWriter dictates what to put for batch operations. If it fails to put, we must also not add it locally.
		// This is a requirement from JSR107 and possibly meant to keep the Cache consistent.
		// Thus we must execute the CacheWriter first

		CacheWriterException cacheWriterException = null;
		Collection<javax.cache.Cache.Entry<? extends K, ? extends V>> writerEntries = null;
		
		CacheWriter<K, V> writer = tcache.cacheWriter;
		if (writer == null || writer instanceof NopCacheWriter)
		{
			callWriter = false;
		}
		if (callWriter)
		{
			try
			{
				writerEntries = new ArrayList<>(entries.size());
				
				for (java.util.Map.Entry<? extends K, ? extends V> entry : entries.entrySet())
				{
					K key = entry.getKey();
					V value = entry.getValue();
					writerEntries.add(new TCacheJSR107Entry<K,V>(key, value));
				}
				writer.writeAll(writerEntries);
			}
			catch (Exception exc)
			{
				// Q uoting the JavaDocs of CacheWriterException:
				//    "A Caching Implementation must wrap any {@link Exception} thrown by a {@link CacheWriter} in this exception".
				cacheWriterException = new CacheWriterException(exc);
			}
		}

		// -2- Write to Cache -----------------------------------

		ListenerCollection<K, V> listeners = tcache.listeners;
		Map<K,V> createdEntries = listeners.hasListenerFor(EventType.CREATED) ? new HashMap<K,V>() : null;
		Map<K,V> updatedEntries = listeners.hasListenerFor(EventType.UPDATED) ? new HashMap<K,V>() : null;

		final boolean anyListenerInterested = createdEntries != null || updatedEntries != null;
		// Future directions: Micro-Benchmark this loop. Optimize it if necessary with a
		// "no-writer-nor-listener" version that contains no "if" checks
		
		final Map<? extends K, ? extends V> finalEntries;
		if (writerEntries != null)
		{
			finalEntries = new HashMap<>(entries);
			for (javax.cache.Cache.Entry<? extends K, ? extends V> writerEntry : writerEntries)
			{
				K key = writerEntry.getKey();
				if (finalEntries.containsKey(key))
				{
					finalEntries.remove(key);
				}
			}
		}
		else
		{
			finalEntries = entries;
		}
		
		
		for (java.util.Map.Entry<? extends K, ? extends V> entry : finalEntries.entrySet())
		{
			K key = entry.getKey();
			V value = entry.getValue();

			AccessTimeObjectHolder<V> holder = tcache.putToMap(key, value, tcache.getDefaultMaxIdleTime(), tcache.cacheTimeSpread(), false, false);

			if (anyListenerInterested)
			{
				Map<K, V> mapToAdd = (holder == null) ? createdEntries : updatedEntries;
				if (mapToAdd != null) // mapToAdd could be null here, if anyListenerInterested == true, but
										// only one (iow: CREATED xor UPDATED)
					mapToAdd.put(key, value);
			}
		}

		// -2- Notify Listeners -----------------------------------

		if (createdEntries != null)
			tcache.listeners.dispatchEvents(createdEntries, EventType.CREATED, false);
		if (updatedEntries != null)
			tcache.listeners.dispatchEvents(updatedEntries, EventType.UPDATED, false);

		if (cacheWriterException != null)
		{
			throw cacheWriterException;
		}
	}

	private void verifyEntriesNotNull(Map<? extends K, ? extends V> entries)
	{
		// JSR107 spec clarification needed. The TCK check PutTest.putAll_NullKey() requires a total failure, and disallows partial success.
		// This is not explicitly in the Specs. Until this clarification is done, we do an explicit key and value null check before starting to put values in the cache.
		for (java.util.Map.Entry<? extends K, ? extends V> entry : entries.entrySet())
		{
			K key = entry.getKey();
			V value = entry.getValue();
			tcache.verifyKeyAndValueNotNull(key, value);
		}
	}

	private void verifyKeysNotNull(Set<? extends K> keys)
	{
		// JSR107 spec clarification needed. The TCK check removeAll_1arg_NullKey(org.jsr107.tck.RemoveTest)
		// requires a total failure, and disallows partial success.
		// This is not explicitly in the Specs. Until this clarification is done, we do an explicit key and
		// value null check before starting to put values in the cache.
		for (K key : keys)
		{
			tcache.verifyKeyNotNull(key);
		}
	}

	@Override
	public boolean putIfAbsent(K key, V value)
	{
		throwISEwhenClosed();

		V oldValue = tcache.putIfAbsent(key, value);
		// For JSR107 putIfAbsent() should return whether a value was set.
		// As tcache.putIfAbsent() has the semantics of ConcurrentMap#putIfAbsent(), we can check for
		// oldValue == null.
		boolean added = oldValue == null;

		if (added)
		{
			tcache.actionDispatcher.write(key, value, EventType.CREATED);
		}

		return added;
	}

	@Override
	public void registerCacheEntryListener(CacheEntryListenerConfiguration<K, V> listenerConfiguration)
	{
		throwISEwhenClosed();
		
		tcache.listeners.registerCacheEntryListener(listenerConfiguration);
	}


	/**
	 * Returns normally with no side effects if this cache is open. Throws IllegalStateException if it is
	 * closed.
	 */
	private void throwISEwhenClosed()
	{
		if (isClosed())
			throw new IllegalStateException("Cache already closed: " + tcache.id());
	}

	@Override
	public boolean remove(K key)
	{
		return remove(key, true);
	}

	boolean remove(K key, boolean mutateLocal)
	{
		throwISEwhenClosed();
		tcache.verifyKeyNotNull(key);
		
		return removeInternal(key, null, mutateLocal);
	}

	
	@Override
	public boolean remove(K key, V value)
	{
		throwISEwhenClosed();
		tcache.verifyKeyAndValueNotNull(key, value);

		return removeInternal(key, value, true);
	}

	/**
	 * Internal remove code. Handles both 1-arg and 2-arg remove() methods.
	 * @param key
	 * @param value
	 * @param mutateLocal
	 * @return
	 */
	boolean removeInternal(K key, V value, boolean mutateLocal)
	{
		
		boolean mustWriteThrough = true;
		if (value != null)
		{
			// 2-arg remove()
			/**
			 * 
			// TCK challenge
			// RemoveTest.shouldWriteThroughRemove_SpecificEntry() mandates that we only write through, if we happen to have the
			// (key,value) combination in the local cache. This can lead to non-deterministic behavior if the local cache has evicted
			// that Cache entry. It is also inconsistent with all other methods: Usually the write-through is always done, and the local
			// Cache get mutated for the successfully written-through entries. But here the local Cache is inspected first.
			 * 
			 * The reason could be an omission in the CacheWriter Interface: It simply does not have a write(key,value) method. To be discussed
			 */
			
			V valueInCache = tcache.get(key);
			mustWriteThrough = value.equals(valueInCache);
		}
		
		final Action<K,V,Object> action = mustWriteThrough ? new DeleteAction<>(key, tcache) : new DeleteOnValueAction<>(key, tcache);

		boolean removed = false;
		if (actionRunner.preMutate(action))
		{
			if (mutateLocal)
			{
				if (value == null)
				{
					// 1-arg remove()
					V oldValue = tcache.remove(key);
					removed = oldValue != null;
					actionRunner.postMutate(action, removed, oldValue);
				}
				else
				{
					// 2-arg remove()
					removed = tcache.remove(key, value);
					actionRunner.postMutate(action, removed, value);
				}
			}
			else
			{
				// Only Write-Through
				actionRunner.postMutate(action, false);
			}
		}

		action.close();
		
		// JSR107 Return whether a value was removed
		return removed;
	}

	@Override
	public void removeAll()
	{
		throwISEwhenClosed();
		
		removeAll(tcache.objects.keySet());
	}

	@Override
	public void removeAll(Set<? extends K> keys)
	{
		throwISEwhenClosed();
		if (keys.isEmpty())
		{
			// If keys are empty, the CacheWriter must not be called => do an explicit check
			return;
		}
		verifyKeysNotNull(keys);

		CacheWriterException cacheWriterException = null;

		// A CacheWriter dictates what to delete for batch operations. If it fails to delete, we must keep it
		// also locally.
		// This is a requirement from JSR107 and possibly meant to keep the Cache consistent.
		Set<? extends K> keysFromCacheWriter = null;
		CacheWriter<K, V> writer = tcache.cacheWriter;
		boolean callWriter = !(writer instanceof NopCacheWriter);

		try
		{
			if (callWriter)
			{
				keysFromCacheWriter = new HashSet<>(keys);
				writer.deleteAll(keysFromCacheWriter);
			}
		}
		catch (Exception exc)
		{
			// Quoting the JavaDocs of CacheWriterException:
			// "A Caching Implementation must wrap any {@link Exception} thrown by a {@link CacheWriter} in
			// this exception".
			cacheWriterException = new CacheWriterException(exc);
		}

		for (K key : keys)
		{
			if (keysFromCacheWriter != null)
			{
				if (keysFromCacheWriter.contains(key))
				{
					// keysFromCacheWriter are the failed deletes. We must skip to delete locally as well.
					continue;
				}
			}
			V oldValue = tcache.remove(key);
			boolean removed = oldValue != null;
			if (removed)
			{
				// Future direction: This could be optimized to do dispatchEvents(Event-List).
				// To be done with the next major refactoring, as event lists need to be supported until the
				// very bottom of the call-stack, namely until ListenerCacheEventManager.
				tcache.listeners.dispatchEvent(EventType.REMOVED, key, null, oldValue);
			}
		}

		if (cacheWriterException != null)
		{
			throw cacheWriterException;
		}
	}

	@Override
	public boolean replace(K key, V value)
	{
		throwISEwhenClosed();
		tcache.verifyKeyAndValueNotNull(key, value);
		
		V oldHolder = getAndReplace(key, value);
		boolean replaced = (oldHolder != null);
		
		return replaced;
	}

	@Override
	public boolean replace(K key, V oldValue, V newValue)
	{
		throwISEwhenClosed();
		
		boolean replaced = tcache.replace(key, oldValue, newValue);
		if (replaced)
			tcache.actionDispatcher.write(key, newValue, null);

		return replaced;
	}

	@Override
	public <T> T unwrap(Class<T> clazz)
	{
		if (!(clazz.isAssignableFrom(Cache.class) || clazz.isAssignableFrom(TCacheJSR107.class)))
			throw new IllegalArgumentException("Cannot unwrap Cache to unsupported Class " + clazz);
		
		@SuppressWarnings("unchecked")
		T cacheCasted = (T)tcache;
		return cacheCasted;
	}

	public Object getCacheConfigMBean()
	{
		return configurationBean;
	}

	public Object getCacheStatisticsMBean()
	{
		return new TCacheStatisticsBean(tcache, tcache.statisticsCalculator, StatisticsAveragingMode.JSR107);
	}

}
