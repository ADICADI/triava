package com.trivago.triava.time;

import java.util.concurrent.TimeUnit;

import com.trivago.triava.logging.TriavaLogger;
import com.trivago.triava.logging.TriavaNullLogger;

/**
 * A TimeSource that provides the value of System.currentTimeMillis() in the given millisecond precision.
 * Target use case for this class is to reduce the overhead of System.currentTimeMillis() or
 * System.nanoTime(), when being called very frequently (thousands or million times per second).
 * <p>
 * This implementation uses a background thread to regularly fetch the current time from
 * System.currentTimeMillis(). The value is cached and returned in all methods returning a time. Caching is
 * helpful, as fetching the time includes a potentially expensive crossing of the operating system boundary
 * (calling native, levering privilege, register swapping, ...). If you call it thousands or millions of times
 * per second, this has a very noticeable effect. For these cases the throughput can be massively increased
 * (100% or even 1000%).
 * <p>
 * Users of this class must be aware that all calls within the update interval will return exactly the same
 * time. For example a Cache implementation that uses this class will have more entries with the identical
 * incoming timestamp. This can have an effect on expiration or time-based eviction strategies like LRU.
 * 
 * @author cesken
 *
 */
public class EstimatorTimeSource extends Thread implements TimeSource
{
	final int UPDATE_INTERVAL_MS;
	private volatile long millisEstimate = System.currentTimeMillis();
	private volatile long secondsEstimate = millisEstimate / 1000;

	private volatile boolean running; // volatile. Modified via cancel() from a different thread
	private final TriavaLogger logger;

	/**
	 * Creates a TimeSource with the given update interval.
	 * 
	 * @param updateIntervalMillis
	 *            Update interval, which defines the approximate precision
	 */
	public EstimatorTimeSource(int updateIntervalMillis)
	{
		this(updateIntervalMillis, new TriavaNullLogger());
	}

	/**
	 * Creates a TimeSource with the given update interval.
	 * 
	 * @param updateIntervalMillis
	 *            Update interval, which defines the approximate precision
	 * @param logger
	 *            A non-null triava logger
	 */
	public EstimatorTimeSource(int updateIntervalMillis, TriavaLogger logger)
	{
		this.logger = logger;
		this.UPDATE_INTERVAL_MS = updateIntervalMillis;
		setName("MillisEstimatorThread-" + UPDATE_INTERVAL_MS + "ms");
		setPriority(Thread.MAX_PRIORITY);
		setDaemon(true);
		start();

	}

	public void run()
	{
		logger.info("MillisEstimatorThread " + this.getName() + " has entered run()");
		this.running = true;
		while (running)
		{
			try
			{
				sleep(UPDATE_INTERVAL_MS);
				millisEstimate = System.currentTimeMillis();
				secondsEstimate = millisEstimate / 1000;
			}
			catch (InterruptedException ex)
			{
			}
		}
		logger.info("MillisEstimatorThread " + this.getName() + " is leaving run()");
	}

	private void cancel()
	{
		this.running = false;
		this.interrupt();
	}

	@Override
	public void shutdown()
	{
		cancel();

	}

	@Override
	public long time(TimeUnit tu)
	{
		if (tu == TimeUnit.SECONDS)
			return secondsEstimate;
		else if (tu == TimeUnit.MILLISECONDS)
			return millisEstimate;
		else
			return tu.convert(millisEstimate, TimeUnit.MILLISECONDS);
	}

	@Override
	public long seconds()
	{
		return secondsEstimate;
	}

	@Override
	public long millis()
	{
		return millisEstimate;
	}

}
