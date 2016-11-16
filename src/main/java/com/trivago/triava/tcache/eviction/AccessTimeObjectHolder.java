/*********************************************************************************
 * Copyright 2009-present trivago GmbH
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

import java.io.Serializable;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import javax.cache.CacheException;

import com.trivago.triava.tcache.CacheWriteMode;
import com.trivago.triava.tcache.expiry.Constants;
import com.trivago.triava.tcache.expiry.TCacheExpiryPolicy;
import com.trivago.triava.tcache.util.Serializing;

/**
 * Represents a Cache entry with associated metadata.
 * This cache entry is valid as long as data != null
 * 
 * @param <V> The value type
 */
public final class AccessTimeObjectHolder<V> implements TCacheHolder<V>
{
	@SuppressWarnings("rawtypes") // AccessTimeObjectHolder<V> would be incompatible with AccessTimeObjectHolder.class
	AtomicIntegerFieldUpdater<AccessTimeObjectHolder> useCountAFU = AtomicIntegerFieldUpdater.newUpdater(AccessTimeObjectHolder.class, "useCount");

	final static int SERIALIZATION_MASK = 0b11;
	final static int SERIALIZATION_NONE = 0b00;
	final static int SERIALIZATION_SERIALIZABLE = 0b01;
	final static int SERIALIZATION_EXTERNIZABLE = 0b10;

	final static int STATE_MASK = 0b0100000;
	final static int STATE_INCOMPLETE = 0b0000000;
	final static int STATE_COMPLETE = 0b0100000;

	// offset #field-size
	// 0 #12
	// Object header
	// 12 #4 
	private volatile Object data; // Holds either a V instance, or serialized data, e.g. byte[]
	// 16 #4
	private int inputDate; // in milliseconds relative to baseTimeMillis 
	// 20 #4
	private int lastAccess = 0; // in milliseconds relative to baseTimeMillis
	// 24 #4
	private int maxIdleTime = 0;  // in milliseconds or seconds
	// 28 #4
	private int maxCacheTime = 0; // in milliseconds or seconds
	// 32 #4
	private volatile int useCount = 0;
	// 36
	/**
	 * Bit 0,1: Serialization mode. 00=Not serialized, 01=Serializable, 10=Externizable
	 */
	byte flags;
	// 37
	
	/**
	 * Construct a holder. The holder will be incomplete and not accessible by cache users, until you call {@link #complete(long, long)}
	 * 
	 * @param value The value to store in this holder
	 * @param writeMode The CacheWriteMode that defines how to serialize the data
	 * @throws CacheException when there is a problem serializing the value
	 */
	public AccessTimeObjectHolder(V value, CacheWriteMode writeMode) throws CacheException
	{
		try
		{
//			long start = System.nanoTime();
			switch (writeMode)
			{
				case Identity:
					flags = SERIALIZATION_NONE;
					this.data = value;
					break;
				case Serialize:
					if (value instanceof Serializable)
					{
						flags = SERIALIZATION_SERIALIZABLE;
						byte[] valueAsBytearray = Serializing.toBytearray(value);
						this.data = valueAsBytearray;
						break;
					}
				case Intern:
					flags = SERIALIZATION_NONE;
					//this.data = interner.get(value);
				default:
					throw new UnsupportedOperationException("CacheWriteMode not supported: " + writeMode);
			}
//			
//			long duration = System.nanoTime() - start;
//			if (duration > 1_000_000)
//			{
//				System.out.println("Slow serializing. value=" + value + ", ms=" + duration / 1_000_000d);
//			}
		}
		catch (Exception exc)
		{
			throw new CacheException("Cannot serialize cache value for writeMode " + writeMode, exc);
		}

	}

	public AccessTimeObjectHolder(V value, long maxIdleTimeMillis, long maxCacheTimeSecs, CacheWriteMode writeMode) throws CacheException
	{
		this(value, writeMode);
		complete(maxIdleTimeMillis, maxCacheTimeSecs);
	}
	
	void complete(long maxIdleTimeMillis, long maxCacheTimeMillis)
	{
		this.maxIdleTime = SecondsOrMillis.fromMillisToInternal(maxIdleTimeMillis);
		this.maxCacheTime = SecondsOrMillis.fromMillisToInternal(maxCacheTimeMillis);
		flags |= STATE_COMPLETE;
		setInputDate();
		setLastAccessTime();
	}
	
	void complete(long maxCacheTimeSecs)
	{
		this.maxCacheTime = SecondsOrMillis.fromMillisToInternal(maxCacheTimeSecs);
		flags |= STATE_COMPLETE;
		setInputDate();
		setLastAccessTime();
	}

	/**
	 * Returns whether the holder is valid. It must be non-null and not expired.
	 * @param holder The holder to check
	 * @return true if the holder is valid
	 */
	static public boolean isValid(AccessTimeObjectHolder<?> holder)
	{
		return holder != null && !holder.isInvalid();
	}
	
	/**
	 * Releases all references to objects that this holder holds. This makes sure that the data object can be
	 * collected by the GC, even if the holder would still be referenced by someone.
	 * <p>
	 *     This is the end-of-life for the instance. The data field is now null, which means the entry is
	 *     invalid. Even if another thread has a reference to this holder, he cannot access the data field.
	 *     any longer.
	 * </p>
	 * @return true, if the call has released the holder. false, if the holder was already released before.
	 */
	protected boolean release()
	{
		synchronized (this)
		{
			boolean releasedData = data != null;
			data = null;
			// SAE-150 Inform the caller whether he has released the holder. Hint: Other threads may
			//         have called this concurrently, e.g. two deletes, expiration and/or eviciton.
			return releasedData;
		}
	}
	
	public void setMaxIdleTime(int idleTime, TimeUnit timeUnit)
	{
		maxIdleTime = SecondsOrMillis.fromMillisToInternal(timeUnit.toMillis(idleTime));
	}
	
	// TODO Change to void again. Even better: return the calculated maxIdleTime and let the caller call the regular #complete() method 
	boolean setMaxIdleTimeAsUpdateOrCreation(boolean updated, TCacheExpiryPolicy expiryPolicy, AccessTimeObjectHolder<V> oldHolder)
	{
		long tmpIdleTimeMillis = updated ? expiryPolicy.getExpiryForUpdate() : expiryPolicy.getExpiryForCreation();
		if (tmpIdleTimeMillis == Constants.EXPIRY_NOCHANGE)
		{
			this.maxIdleTime = oldHolder.maxIdleTime;
		}
		else
		{
			this.maxIdleTime = SecondsOrMillis.fromMillisToInternal(tmpIdleTimeMillis);
			if (maxIdleTime == 20)
			{
				return false; // true;
			}
		}
		return false;
	}


	/**
	 * Prolongs the maxIdleTime by the given idleTime. 0 means immediate expiration, -1 means to not change anything, any positive value is the prolongation in seconds.
	 * @param idleTimeMillis The time for prolong in milliseconds. See description for the special values 0 and -1.
	 */
	public void updateMaxIdleTime(long idleTimeMillis)
	{
		if (idleTimeMillis == 0)
		{
			this.maxIdleTime = 0; // invalidate immediately
		}
		if (idleTimeMillis > 0)
		{
			// Prolong time: 
			// 1) Find out how long we currently live in the Cache
			long cacheDurationMillis = currentTimeMillisEstimate() - getInputDate();
			// 2) Prolong by idleTimeSecs.
			try
			{
				long newIdleTimeMillis = cacheDurationMillis + idleTimeMillis;
				if (newIdleTimeMillis < cacheDurationMillis)
				{
					newIdleTimeMillis = Constants.EXPIRY_MAX; // overrun
				}
				this.maxIdleTime = SecondsOrMillis.fromMillisToInternal(newIdleTimeMillis);				
			}
			catch (Exception exc)
			{
				System.out.println("updateMaxIdleTime() failed idleTimeMillis=" +idleTimeMillis + ", getInputDate()=" +getInputDate() + ", cacheDurationMillis=" + cacheDurationMillis); 
			}
//			int newMaxIdleTarget = (int)Math.min(  (cacheDurationMillis/1000) + idleTimeSecs, (long)Integer.MAX_VALUE);
//			this.maxIdleTime = newMaxIdleTarget;
		}
		// -1 => No change
	}


	/**
	 * 
	 */
	public long getExpirationTime()
	{
		return SecondsOrMillis.fromInternalToMillis(maxIdleTime);
	}

	public V get()
	{
		setLastAccessTime();
		return peek();
	}

	/**
	 * Return the data, without modifying statistics or any other metadata like access time.
	 * 
	 * TODO Check whether we should check validity here via {@link #isInvalid()}
	 * 
	 * @return The value of this holder 
	 */
	@SuppressWarnings("unchecked") 
	public V peek()
	{
		int serializationMode = flags & SERIALIZATION_MASK;
		try
		{
			switch (serializationMode)
			{
				case SERIALIZATION_NONE:
					return (V)data;
				case SERIALIZATION_SERIALIZABLE:
					Object dataRef = data; // defensive copy
					return dataRef != null ? (V)Serializing.fromBytearray((byte[])(dataRef)) : null;
				case SERIALIZATION_EXTERNIZABLE:
				default:
					throw new UnsupportedOperationException("Serialization type is not supported: " + serializationMode);

			}
		}
		catch (Exception exc)
		{
			throw new CacheException("Cannot serialize cache value for serialization type " + serializationMode, exc);
		}
	}

	private void setLastAccessTime()
	{
		lastAccess = (int)(currentTimeMillisEstimate() - Cache.baseTimeMillis);
	}
	
	private long currentTimeMillisEstimate()
	{
		return Cache.millisEstimator.millis();
	}
	
	/**
	 * @return the lastAccess
	 */
	public long getLastAccess()
	{
		return Cache.baseTimeMillis + lastAccess;
	}

	public int getUseCount()
	{
		return useCount;
	}

	public void incrementUseCount()
	{
		useCountAFU.incrementAndGet(this);
	}

	private void setInputDate()
	{
		inputDate = (int)(currentTimeMillisEstimate() - Cache.baseTimeMillis);
	}

	public long getInputDate()
	{
		return Cache.baseTimeMillis + inputDate;
	}
	
	public boolean isInvalid()
	{
		return isInvalid(false);
	}
	
	public boolean isInvalid(boolean debug)
	{	
		if (data == null)
		{
			return true; // Dropped, e.g. via expiration
		}

		// -1- Check completeness
		boolean incomplete = (flags & STATE_MASK) == STATE_INCOMPLETE;
		if (incomplete)
		{
			if (debug) System.out.println("Dropped because incomplete: flags=" + flags + ": " + data);
			return true;
		}
		
		// -2- Check cache time
		long millisNow = currentTimeMillisEstimate();
		long expDurationMillis = SecondsOrMillis.fromInternalToMillis(maxCacheTime);
		if (expDurationMillis > 0L)
		{
			long cacheDurationMillis = millisNow - getInputDate();
			// SRT-23661 maxCacheTime explicitly converted to long, to avoid overrun due to "1000*"
			if (cacheDurationMillis > expDurationMillis) 
			{
				if (debug) System.out.println("Dropped because expired: millisNow=" + millisNow + ", maxCacheTime" + maxCacheTime + ", expDurationMillis" + expDurationMillis + "< cacheDurationMillis" + cacheDurationMillis);
				return true;
			}
		}
		
		// -3- Check idle time
		long idleDurationMillis = SecondsOrMillis.fromInternalToMillis(maxIdleTime);
		if (idleDurationMillis == 0)
		{
			if (debug) System.out.println("Dropped because idle0: millisNow=" + millisNow + ", maxCacheTime" + maxCacheTime + ", expDurationMillis" + expDurationMillis + "< cacheDurationMillis" + idleDurationMillis);
			return true;
		}

		long lastAccess = getLastAccess();
		long idleSince = millisNow - lastAccess;

		// SRT-23661 maxIdleTime explicitly converted to long, to avoid overrun due to "1000*"
		if (idleSince > idleDurationMillis)
		{
			if (debug) System.out.println("Dropped because idle: millisNow=" + millisNow + ", maxCacheTime" + maxCacheTime + ", idleDurationMillis" + idleDurationMillis + "< idleSince" + idleSince + ", lastAccess=" + lastAccess);
			return true;
		}
		return false;
	}


	public void setExpireUntil(int maxDelay, TimeUnit timeUnit, Random random)
	{
		int maxDelaySecs = (int)timeUnit.toSeconds(maxDelay);
		long delayMillis = 1000l * random.nextInt(maxDelaySecs);
		
		long expDurationMillis = SecondsOrMillis.fromInternalToMillis(maxCacheTime);
		if (expDurationMillis == 0 || delayMillis < expDurationMillis)
		{
			// holder.maxCacheTime was not set (never expires), or new value is smaller => use it 
			maxCacheTime = SecondsOrMillis.fromMillisToInternal(delayMillis);
		}
		// else: Keep delay, as holder will already expire sooner than delaySecs.
	}


	/**
	 * Limits the given long value to values between [0, Long.MAX_VALUE].
	 * Values < 0 will be adjusted to 0,.
	 * <p>
	 * Implementation note: This method is not public. To do Unit tests, copy this method to CacheTest after changing it. 
	 *
	 * @param value The value to limit
	 * @return The adjusted value
	 */
	static long limitToPositiveLong(long value)
	{
		return value < 0 ? 0 : value;
	}

	@Override
	public String toString()
	{
		return "AccessTimeObjectHolder [dataPresent=" + (data != null) + ", inputDate=" + inputDate + ", lastAccess=" + lastAccess
				+ ", maxIdleTime=" + maxIdleTime + ", maxCacheTime=" + maxCacheTime + ", useCount=" + useCount
				+ ", flags=" + flags + "]";
	}
}
