package com.trivago.triava.tcache.eviction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.trivago.triava.annotations.TriavaCandidate;
import com.trivago.triava.annotations.TriavaCandidate.JavaPackage;
import com.trivago.triava.tcache.JamPolicy;
import com.trivago.triava.tcache.core.Builder;
import com.trivago.triava.tcache.core.EvictionInterface;
import com.trivago.triava.tcache.statistics.SlidingWindowCounter;
import com.trivago.triava.tcache.statistics.TCacheStatisticsInterface;

/**
 * A size limited Cache, that evicts elements asynchronously in the background.
 * The element to be evicted are chosen by subclasses, by implementing {@link #evicvtionComparator()}.
 * 
 * @author cesken
 *
 * @param <T>
 */
public class CacheLimit<T> extends Cache<T>
{
	/**
	 * FREE_PERCENTAGE defines how much elements to free.
	 * Percentage relates to evictionStartAt. 
	 */
	private static final int FREE_PERCENTAGE = 20;
	private static final float EVICTION_SPACE_PERCENT = 20; // TODO cesken Move to 10%. Tests with bb show that it is OK.
	
	// --- FEATURE_ExtraParEvictionSpace START ----------
	private static final boolean FEATURE_ExtraParEvictionSpace = false;
	private static final int EVICTION_SPACE_PER_WRITER = 2500;
	private static final int MAXIMUM_EVICTION_SPACE_FOR_WRITERS = 200000;
	// --- FEATURE_ExtraParEvictionSpace END ----------

	private static final boolean LOG_INTERNAL_DATA = true;
	private static final boolean LOG_INTERNAL_EXTENDED_DATA = false;

	protected EvictionInterface<Object, T> evictionClass = null;

//	@ObjectSizeCalculatorIgnore
	private volatile transient EvictionThread evictor = null;
	
	protected final AtomicLong evictionCount  = new AtomicLong();	
	private int counterEvictionsRounds = 0;
	private AtomicInteger  counterEvictionsHalts = new AtomicInteger();
	private SlidingWindowCounter evictionRateCounter = new SlidingWindowCounter(60, 1);
	
	private final Object evictionNotifierDone = new Object();
	
	// 1 element in the blocking queue is likely enough, but using 2 should definitely decouple reads and writes
	private final BlockingQueue<Boolean> evictionNotifierQ = new LinkedBlockingQueue<>(2);

	public CacheLimit(String id, long maxIdleTime, long maxCacheTime, int expectedElements)
	{
		super(id, maxIdleTime, maxCacheTime, expectedElements);
		 // Start eviction Thread directly, so there is no ramp-up time when the first Eviction will be triggered
		ensureEvictionThreadIsRunning(); 
	}

	public CacheLimit(Builder builder)
	{
		super(builder);
		if (builder.getEvictionClass() == null)
		{
			throw new IllegalArgumentException("evictionClass must not be null in an evicting Cache");
		}
		this.evictionClass = builder.getEvictionClass();
	}

	// *** VALUES BELOW ARE FIXED AT CONSTRUCTION. See evictionExtraSpace(builder) ************************  
	private int userDataElements; // SET DURING  CONSTRUCTION
	private int blockStartAt; // SET DURING  CONSTRUCTION
	private int evictUntilAtLeast; // SET DURING  CONSTRUCTION
	private int evictNormallyElements; // SET DURING  CONSTRUCTION
	// *** VALUES ABOVE ARE FIXED AT CONSTRUCTION. See evictionExtraSpace(builder) ************************  


	/**
	 * Returns whether the Cache is full. Due to the concurrency nature of the ConcurrentHashMap, we declare
	 * the Cache already full when it reaches maxFillRate (normally 99%).
	 * 
	 * @return
	 */
	protected boolean isFull()
	{
		int size = objects.size();
		boolean full = size >= userDataElements;
//		if (LOG_INTERNAL_DATA && LOG_INTERNAL_EXTENDED_DATA && full)
//		{
//			logger.info("isFull: size=" + size + ", userDataElements=" +  userDataElements);
//		}
		
		return full;
	}
	
	protected boolean isOverfull()
	{
		// maxElements = expectedElements from the configuration. NOT how we sized the ConcurrentMap. 
		int size = objects.size();
		boolean full = size >= blockStartAt;
		if (full)
		{
			if (LOG_INTERNAL_DATA && logInternalExtendedData())
				logger.info("Overfull [" + id() +"]. currentSize=" +  size + ", blockStartAt="+ blockStartAt);
		}
		
		return full;
	}


//	@SuppressWarnings("unused")
	private static final boolean logInternalExtendedData()
	{
		return LOG_INTERNAL_EXTENDED_DATA;
	}

	/**
	 * Returns a size factor that accommodates the maxFillRate. Required, as maxFillRate defines the start of
	 * eviction, and thus we cannot actually hold so many elements.
	 * 
	 * 0                          Empty
	 * evictUntilAt         Lower mark
	 * evictionStartAt    Upper mark
	 * blockStartAt        Block mark
	 * 
	 * @return
	 */
	@Override
	protected int evictionExtraSpace(Builder<Object,T> builder)
	{
		double factor = EVICTION_SPACE_PERCENT / 100D; //  20/100d = 0.2
		userDataElements = builder.getExpectedMapSize();
		
		final int parallelityEvictionSpace;
		if (FEATURE_ExtraParEvictionSpace)
		{
			/**
			 * This can take a significant amount of extra memory, especially for small sized caches which contain
			 * big elements. Example would be a Session cache, with (e.g.) 2000 Elements, each sized 500KB => 1GB size.
			 * If we would allow the below number, it would allow 2500*14 = 35000 more elements => 17,5GB.
			 * This is clearly undesirable. Thus the feature is currently off.
			 * 
			 * It may likely be more desirable to let "factor" grow, e.g. by 1% per 5 Threads (max 20%) 
			 */
			int evictionSpacePerWriter = builder.getConcurrencyLevel() * EVICTION_SPACE_PER_WRITER;
			parallelityEvictionSpace = Math.min(evictionSpacePerWriter, MAXIMUM_EVICTION_SPACE_FOR_WRITERS);
		}
		else
		{
			parallelityEvictionSpace = 0;
		}

		long normalEvictionSpace = (long)(userDataElements * factor);
		long extraEvictionSpace = Math.max(normalEvictionSpace, parallelityEvictionSpace);
		long plannedSizeLong = userDataElements + extraEvictionSpace;
		int plannedSize = (int)Math.min(plannedSizeLong, Integer.MAX_VALUE); 
		
		blockStartAt = plannedSize;
		evictNormallyElements = (int)((double)plannedSize * FREE_PERCENTAGE / 100D);
		evictUntilAtLeast = plannedSize - evictNormallyElements;
		if (LOG_INTERNAL_DATA)
		{
			logger.info("Cache eviction tuning [" + id() +"]. Size=" + userDataElements + ", BLOCK=" + blockStartAt + ", evictToPos=" + evictUntilAtLeast + ", normal-evicting=" + evictNormallyElements);
		}
		
		return blockStartAt - userDataElements;
	}

	/**
	 * Determine how many elements to remove. The count is n% of {@link #maxElements} plus any overflow.
	 * Overflow is not commonly seen. It may occur if the size() method of underlying data structure is not
	 * exact. Or if {@link #ensureFreeCapacity()} is implemented asynchronously. Or if
	 * {@link #ensureFreeCapacity()} is cleaning not always, but only on occasion.
	 *
	 * @return
	 */
	protected int elementsToRemove()
	{
		int currentElements = objects.size();
		int removeTargetPos = currentElements - evictNormallyElements;
		if (removeTargetPos <= evictUntilAtLeast)
		{
			// We will end below acceptable border => OK. Evict normal number of elements
			return evictNormallyElements;			
		}
		
		// else: Make sure we make room for at least until evictUntilAtLeast
		int removeCount1 = currentElements - evictUntilAtLeast;
		
		return removeCount1 < 0 ? 0 : removeCount1;
	}

	private EvictionThread ensureEvictionThreadIsRunning()
	{
		EvictionThread eThread = evictor;
		if (eThread != null)
		{
			// This is a fast path for the normal case. Eviction thread is running.
			// No locks are in this part.
			// already running
			return eThread;
		}

		synchronized (this)
		{
			if (evictor == null)
			{
				eThread = new EvictionThread("CacheEvictionThread-" + id());
				eThread.setPriority(Thread.MIN_PRIORITY);
				eThread.setDaemon(true);
				eThread.setUncaughtExceptionHandler(eThread);
				eThread.start();
				evictor = eThread;
				logger.info(id() + " Eviction Thread started");
			}
			else
			{
				eThread = evictor;
			}
		}

		return eThread;
	}

	class EvictionThread extends Thread implements Thread.UncaughtExceptionHandler
	{
		volatile boolean running = true;
		volatile boolean evictionIsRunning = false;
		
		public EvictionThread(String name)
		{
			super(name);
		}

		@Override
		public void run()
		{
			while (running)
			{

				try
				{
					evictionIsRunning = false;
					evictionNotifierQ.take();  // wait
					evictionNotifierQ.clear();  // get rid of further notifications (if any)
					// --- clear() must be before evictionIsRunning = true;
					evictionIsRunning = true;
//					if (LOG_INTERNAL_DATA && logInternalExtendedData())
//						System.out.println("Evicting");
					evict();
					
					synchronized (evictionNotifierDone)
					{
						evictionIsRunning = false;
						evictionNotifierDone.notifyAll();
					}
				}
				catch (InterruptedException e)
				{
					logger.info(id() + " Eviction Thread interrupted");
					// Ignore: If someone wants to cancel this thread, "running" will also be false
				}
				catch (Exception e)
				{
					logger.error(id() + " Eviction Thread error", e);
				}
				finally
				{
					evictionIsRunning = false;
				}

			} // while running

			logger.info(id() + " Eviction Thread ended");
		}

		/**
		 * Evict overflow elements from this Cache. The number of elements is determined by elementsToRemove()
		 */
		protected void evict()
		{
			counterEvictionsRounds++;
			evictWithFreezer();
//			evictOld();
//			evictH();
		}
		
		protected void evictWithFreezer()
		{
			int i=0;
			Set<Entry<Object, Cache.AccessTimeObjectHolder<T>>> entrySet = objects.entrySet();
			int size = entrySet.size();
			// ###A###
//			System.out.println("Start to evict with size=" + size);
			
			ArrayList<HolderFreezer<Object,T>> toCheckL = new ArrayList<>(size);
			// ###B###
			for (Entry<Object, AccessTimeObjectHolder<T>> entry : entrySet)
			{
				if (i == size)
				{
					break; // Skip new elements that came in between ###A### and ###B###
				}

				Object key = entry.getKey();
				AccessTimeObjectHolder<T> holder = entry.getValue();
				long frozenValue = evictionClass.getFreezeValue(key, holder);
				HolderFreezer<Object,T> frozen = new HolderFreezer<>(key, holder, frozenValue);

				toCheckL.add(i, frozen);
				i++;
			}

			@SuppressWarnings("unchecked")
			HolderFreezer<Object, T>[] toCheck = toCheckL.toArray(new HolderFreezer[0]);
			Arrays.sort(toCheck, evictionClass.evicvtionComparator());			

			int removedCount = 0;
//			int notRemovedCount1 = 0;
			int elemsToRemove = elementsToRemove();
			for (HolderFreezer<Object, T> entryToRemove : toCheck)
			{
				Object oldValue = remove(entryToRemove.getKey()); // ***POS_2***
				if (oldValue != null)
				{
					/**
					 * This code path guarantees that the cache entry was removed by us (the EvictionThread).
					 * This means we count only what has not "magically" disappeared between ***POS_1*** and
					 * ***POS_2***. Actually the reasons for disappearing are not magical at all: Most notably
					 * objects can disappear because they expire, see the CleanupThread in the base class.
					 * Also if someone calls #remove(), the entry can disappear.
					 */
					++removedCount;
					if (removedCount >= elemsToRemove)
						break;
				}
//				else
//				{
//					notRemovedCount1++;
//				}
			}
			
			evictionCount.addAndGet(removedCount);			
			evictionRateCounter.registerEvents(MillisEstimatorThread.secondsEstimate, removedCount);
			
//			if (LOG_INTERNAL_DATA && logInternalExtendedData())
//				System.out.println("removed=" +removedCount + ", notRemoved=" + notRemovedCount1);
			
			//
			// long afterFree = ObjectSizeCalculator.getObjectSize(this);
			// long saved = beforeFree - afterFree;
			//
			// logger.debug("Cache " + this + " requires " + ObjectSizeCalculator.getObjectSize(this) +
			// "byte (we just LFU-cleaned " + saved + "byte");
		}


		public void shutdown()
		{
			running = false;
			evictionIsRunning = false;
			this.interrupt();
		}

		/**
		 * This is called, should the EvictionThread should go down on an unexpected (uncaught) Exception.
		 */
		@Override
		public void uncaughtException(Thread thread, Throwable throwable)
		{
			logger.error("EvictionThread Thread " + thread + " died because uncatched Exception", throwable);

			// We must make sure that evictor will be recreated on demand
			evictionIsRunning = false;
			evictor = null;
		}

//		@edu.umd.cs.findbugs.annotations.SuppressWarnings(value = { "RV_RETURN_VALUE_IGNORED_BAD_PRACTICE" }, justification = "evictionNotifierQ.offer() is just to notify. If queue is full, then notification has already been sent.")
		public void trigger()
		{
			if (evictionIsRunning)
			{
				// Fast-path if the Eviction Thread already is running. No locks in this code path.
				return;
			}
			
			evictionNotifierQ.offer(Boolean.TRUE);
		}
	}

	private synchronized String stopEvictor(long millis)
	{
		String errorMsg = null;
		CacheLimit<T>.EvictionThread evictorRef = evictor;
		if ( evictorRef != null )
		{
			evictorRef.shutdown();
			if (millis > 0)
			{
				if (! joinSimple(evictorRef, millis, 0) )
				{
					errorMsg = "Shutting down Eviction Thread FAILED";
				}
			}
		}
		
		return errorMsg;
	}
	
	/**
	 * Shuts down the Eviction thread.
	 */
	@Override
	public void shutdown()
	{
		super.shutdown();
		
		String errorMsg = stopEvictor(MAX_SHUTDOWN_WAIT_MILLIS);
		if (errorMsg != null)
		{
			logger.error("Shutting down Evictor for Cache " + id() + " FAILED. Reason: " + errorMsg);
		}
		else
		{
			logger.info("Shutting down Evictor for Cache " + id() + " OK");
		}
	}


	/**
	 * Frees entries, if the Cache is near full.
	 * 
	 * @return true, if free capacity can be granted.
	 */
	@Override
	protected boolean ensureFreeCapacity()
	{
		if (!isFull())
			return true;

		EvictionThread evictionThread = ensureEvictionThreadIsRunning();
		evictionThread.trigger();
		

		if (isOverfull())
		{
			counterEvictionsHalts.incrementAndGet();
			if ( jamPolicy == JamPolicy.DROP)
			{
				// Even when dropping, make sure the evictor will make some space for the next put(). 
				evictionThread = ensureEvictionThreadIsRunning();
				evictionThread.trigger();
				return false;
			}			
		}
		
		// JamPolicy.WAIT
		while (isOverfull())
		{
			try
			{
				synchronized (evictionNotifierDone)
				{
					if (evictionThread.evictionIsRunning)
					{
						evictionNotifierDone.wait();
					}
				}
				evictionThread = ensureEvictionThreadIsRunning();
				evictionThread.trigger();
			}
			catch (InterruptedException e)
			{
				// ignore, and continue waiting
			}
		}
		
		return true;
	}

	@Override
	protected TCacheStatisticsInterface fillCacheStatistics(TCacheStatisticsInterface cacheStatistic)
	{
		cacheStatistic.setEvictionCount(evictionCount.get());
		cacheStatistic.setEvictionRounds(counterEvictionsRounds);
		cacheStatistic.setEvictionHalts(counterEvictionsHalts.get());
		cacheStatistic.setEvictionRate(evictionRateCounter.getRateTotal(MillisEstimatorThread.secondsEstimate));
		
		return super.fillCacheStatistics(cacheStatistic);
	}

}
