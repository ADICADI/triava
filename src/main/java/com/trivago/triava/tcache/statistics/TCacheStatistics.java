package com.trivago.triava.tcache.statistics;

import com.trivago.triava.tcache.eviction.Cache;



/**
 * Hold statistics for tCache, for example number of elements and hit count.
 */
public class TCacheStatistics implements TCacheStatisticsInterface
{
	private String id;
	private float hitRatio; 
	/**
	 * @return the hitRatio
	 */
	public float getHitRatio()
	{
		return hitRatio;
	}

	private long hitCount;
	private long missCount;
	private long putCount;
	private long dropCount;
	private long evictionCount;
	private long elementCount;
	private long evictionRounds;
	private long evictionHalts;
	private long evictionRate;


	/**
	 * Creates empty Statistics.
	 * @param id The ID of the cache instance 
	 */
	public TCacheStatistics(String id)
	{
		this.setId(id);
	}

	
	public long getHitCount()
	{
		return hitCount;
	}

	@Override
	public void setHitCount(long hitCount)
	{
		this.hitCount = hitCount;
	}

	public long getMissCount()
	{
		return missCount;
	}

	@Override
	public void setMissCount(long missCount)
	{
		this.missCount = missCount;
	}

	public long getPutCount()
	{
		return putCount;
	}

	@Override
	public void setPutCount(long putCount)
	{
		this.putCount = putCount;
	}

	public long getEvictionCount()
	{
		return evictionCount;
	}

	@Override
	public void setEvictionCount(long evictionCount)
	{
		this.evictionCount = evictionCount;
	}
	
	public long getEvictionRate()
	{
		return evictionRate;
	}

	public void setEvictionRate(long evictionRate)
	{
		this.evictionRate = evictionRate;
	}

	@Override
	public void setHitRatio(float hitRatio)
	{
		this.hitRatio = hitRatio;
	}

	@Override
	public void setElementCount(long count)
	{
		elementCount = count;
	}

	public long getElementCount()
	{
		return elementCount;
	}

	public long getDropCount()
	{
		return dropCount;
	}

	public void setDropCount(long dropCount)
	{
		this.dropCount = dropCount;
	}


	@Override
	public String toString()
	{
		StringBuilder builder = new StringBuilder();
		builder.append("Statistics [id=");
		builder.append(id);
		builder.append(", hitRatio=");
		builder.append(hitRatio);
		builder.append(", hitCount=");
		builder.append(hitCount);
		builder.append(", missCount=");
		builder.append(missCount);
		builder.append(", putCount=");
		builder.append(putCount);
		builder.append(", dropCount=");
		builder.append(dropCount);
		builder.append(", evictionCount=");
		builder.append(evictionCount);
		builder.append(", evictionRounds=");
		builder.append(evictionRounds);
		builder.append(", evictionHalts=");
		builder.append(evictionHalts);
		builder.append(", elementCount=");
		builder.append(elementCount);
		builder.append("]");
		return builder.toString();
	}

	@Override
	public void setEvictionRounds(long count)
	{
		this.evictionRounds = count;
	}

	/**
	 * @return the evictionRounds
	 */
	public long getEvictionRounds()
	{
		return evictionRounds;
	}

	@Override
	public void setEvictionHalts(long count)
	{
		evictionHalts  = count;
	}

	/**
	 * @return the evictionHalts
	 */
	public long getEvictionHalts()
	{
		return evictionHalts;
	}

	public String getId()
	{
		return id;
	}

	public void setId(String id)
	{
		this.id = id;
	}	

	
}
