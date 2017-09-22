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

package com.trivago.triava.tcache.statistics;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Implementation for StatisticsCalculator, that implements all statistics, using Atomic counters.
 * @author cesken
 * @deprecated Use {@link LongAdderStatisticsCalculator} instead. This class will be removed in triava 2.0
 *
 */
@Deprecated
public class StandardStatisticsCalculator implements StatisticsCalculator, java.io.Serializable
{
	private static final long serialVersionUID = -4811885483869803392L;
	
	private final AtomicLong cacheHitCount  = new AtomicLong();
	private final AtomicLong cacheMissCount = new AtomicLong();
	private final AtomicLong cachePutCount = new AtomicLong();
	private final AtomicLong cacheRemoveCount = new AtomicLong();
	private final AtomicLong cacheDropCount = new AtomicLong();

	private final AtomicLong cacheHitCountPrevious  = new AtomicLong();
	private final AtomicLong cacheMissCountPrevious = new AtomicLong();

	@Override
	public HitAndMissDifference tick()
	{
		long hitDifference = 0;
		long missDifference = 0;

		long cacheHitsPrevious   = cacheHitCountPrevious.get();
		long cacheMissesPrevious = cacheMissCountPrevious.get();
		
		long cacheHitsCurrent   = cacheHitCount.get();
		long cacheMissesCurrent = cacheMissCount.get();
		
		hitDifference = cacheHitsCurrent - cacheHitsPrevious;
		missDifference = cacheMissesCurrent - cacheMissesPrevious;

		// -2- Make current values the previous ones
		cacheHitCountPrevious.set(cacheHitsCurrent);
		cacheMissCountPrevious.set(cacheMissesCurrent);

		return new HitAndMissDifference(hitDifference, missDifference);
	}
	
	@Override
	public void clear()
	{
		cacheHitCount.set(0);
		cacheMissCount.set(0);
		cachePutCount.set(0);
		cacheRemoveCount.set(0);
		cacheDropCount.set(0);
		cacheHitCountPrevious.set(0);
		cacheMissCountPrevious.set(0);
	}

	
	/**
	 * @return the cacheHitCount
	 */
	 @Override
	 public long getHitCount()
	{
		return cacheHitCount.get();
	}

	/**
	 * @return the cacheMissCount
	 */
	 @Override
	 public long getMissCount()
	{
		return cacheMissCount.get();
	}

	/**
	 * @return the cachePutCount
	 */
	 @Override
	public long getPutCount()
	{
		return cachePutCount.get();
	}

	@Override
	public void incrementHitCount()
	{
		cacheHitCount.incrementAndGet();
	}

	@Override
	public void incrementMissCount()
	{
		cacheMissCount.incrementAndGet();
	}

	@Override
	public void incrementPutCount()
	{
		cachePutCount.incrementAndGet();
	}

	@Override
	public void incrementDropCount()
	{
		cacheDropCount.incrementAndGet();
	}

	@Override
	public long getDropCount()
	{
		return cacheDropCount.get();
	}

	@Override
	public void incrementRemoveCount()
	{
		cacheRemoveCount.incrementAndGet();
	}

	@Override
	public void incrementRemoveCount(int removedCount)
	{
		cacheRemoveCount.addAndGet(removedCount);
	}

	@Override
	public long getRemoveCount()
	{
		return cacheRemoveCount.get();
	}

	@Override
	public String toString()
	{
		return "StandardStatisticsCalculator [cacheHitCount=" + cacheHitCount + ", cacheMissCount=" + cacheMissCount
				+ ", cachePutCount=" + cachePutCount + ", cacheRemoveCount=" + cacheRemoveCount + ", cacheDropCount="
				+ cacheDropCount + "]";
	}

	
}
