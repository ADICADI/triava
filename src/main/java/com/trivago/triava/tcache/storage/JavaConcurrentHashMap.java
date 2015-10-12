package com.trivago.triava.tcache.storage;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.trivago.triava.tcache.core.Builder;
import com.trivago.triava.tcache.core.StorageBackend;
import com.trivago.triava.tcache.eviction.TCacheHolder;

/**
 * Implements a storage that uses Java's ConcurrentHashMap.
 *  
 * @author cesken
 *
 * @param <K>
 * @param <V>
 */
public class JavaConcurrentHashMap<K,V> implements StorageBackend<K, V>
{
	@Override
	public ConcurrentMap<K, TCacheHolder<V>> createMap(Builder<K,V> builder, double evictionMapSizeFactor)
	{
		double loadFactor = 0.75F;
		int requiredMapSize = (int) (builder.getExpectedMapSize() / loadFactor) + (int)evictionMapSizeFactor;
		return new ConcurrentHashMap<>(requiredMapSize, (float) loadFactor,
				builder.getMapConcurrencyLevel());
	}

}
