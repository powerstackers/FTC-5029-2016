package org.swerverobotics.library.internal;

import android.util.Log;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.hardware.*;
import com.qualcomm.robotcore.hardware.usb.RobotUsbModule;
import com.qualcomm.robotcore.util.*;
import org.swerverobotics.library.*;
import org.swerverobotics.library.exceptions.*;
import org.swerverobotics.library.interfaces.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.*;
import static junit.framework.Assert.*;
import static org.swerverobotics.library.internal.Util.*;

/**
 * I2cDeviceClient is a utility class that makes it easy to read or write data to 
 * an instance of I2cDevice. There's a really whole lot of hard stuff this does for you
 *
 */
public final class I2cDeviceClient implements II2cDeviceClient, IOpModeStateTransitionEvents, Engagable
    {
    //----------------------------------------------------------------------------------------------
    // State
    //----------------------------------------------------------------------------------------------

    private final II2cDevice     i2cDevice;                  // the device we are talking to
    private       I2cController  controller;
    private       RobotUsbModule robotUsbModule;

    private       boolean       isHooked;                   // whether we are connected to the underling device or not
    private       boolean       isEngaged;                  // user's hooking *intent*
    private final AtomicInteger readerWriterPreventionCount;// used to be able to prevent new readers and writers
    private final ReadWriteLock readerWriterGate;           // used to be able to drain extant readers and writers
    private final AtomicInteger readerWriterCount;          // for debugging
    private       boolean       closing;                    //

    private final Callback      callback;                   // the callback object on which we actually receive callbacks
    private       boolean       loggingEnabled;             // whether we are to log to Logcat or not
    private       String        loggingTag;                 // what we annotate our logging with
    private final ElapsedTime   timeSinceLastHeartbeat;     // keeps track of our need for doing heartbeats

    private       byte[]        readCache;                  // the buffer into which reads are retrieved
    private       byte[]        writeCache;                 // the buffer that we write from
    private static final int    dibCacheOverhead = 4;       // this many bytes at start of writeCache are system overhead
    private static final int    ibActionFlag = 31;          // index of the action flag in our write cache
    private       Lock          readCacheLock;              // lock we must hold to look at readCache
    private       Lock          writeCacheLock;             // lock we must old to look at writeCache
    private final int           msCallbackLockWaitQuantum = 60;      // arbitrary, but long enough that we don't hit it in common usage, only in shutdown situations

    private final Object        engagementLock       = new Object();
    private final Object        concurrentClientLock = new Object(); // the lock we use to serialize against concurrent clients of us. Can't acquire this AFTER the callback lock.
    private final Object        callbackLock         = new Object(); // the lock we use to synchronize with our callback.

    private volatile ReadWindow          readWindow;                 // the set of registers to look at when we are in read mode. May be null, indicating no read needed
    private volatile ReadWindow          readWindowActuallyRead;     // the read window that was really read. readWindow will be a (possibly non-proper) subset of this
    private volatile ReadWindow          readWindowSentToController; // the read window we last issued to the controller module. May disappear before read() returns
    private volatile boolean             readWindowSentToControllerInitialized; // whether readWindowSentToController has valid data or not
    private volatile boolean             readWindowChanged;          // whether regWindow has changed since the hw cycle loop last took note
    private volatile long                nanoTimeReadCacheValid;     // the time on the System.nanoTime() clock at which the read cache was last set as valid
    private volatile READ_CACHE_STATUS   readCacheStatus;            // what we know about the contents of readCache
    private volatile WRITE_CACHE_STATUS  writeCacheStatus;           // what we know about the (payload) contents of writeCache
    private volatile MODE_CACHE_STATUS   modeCacheStatus;            // what we know about the first four bytes of writeCache (mostly a debugging aid)
    private volatile int                 iregWriteFirst;             // when writeCacheStatus is DIRTY, this is where we want to write
    private volatile int                 cregWrite;
    private volatile int                 msHeartbeatInterval;        // time between heartbeats; zero is 'none necessary'
    private volatile HeartbeatAction     heartbeatAction;            // the action to take when a heartbeat is needed. May be null.
    private volatile ExecutorService     heartbeatExecutor;          // used to schedule heartbeats when we need to read from the outside
    private volatile int                 hardwareCycleCount;         // number of callbacks that we've received

    /** Keeps track of what we know about about the state of 'readCache' */
    private enum READ_CACHE_STATUS
        {
        IDLE,                 // the read cache is quiescent; it doesn't contain valid data
        SWITCHINGTOREADMODE,  // a request to switch to read mode has been made
        QUEUED,               // an I2C read has been queued, but we've not yet seen valid data
        QUEUE_COMPLETED,      // a transient state only ever seen within the callback
        VALID_ONLYONCE,       // read cache data has valid data but can only be read once
        VALID_QUEUED;         // read cache has valid data AND a read has been queued

        boolean isValid()
            {
            return this==VALID_QUEUED || this==VALID_ONLYONCE;
            }
        boolean isQueued()
            {
            return this==QUEUED || this==VALID_QUEUED;
            }
        }

    /** Keeps track about what we know about the state of 'writeCache' */
    private enum WRITE_CACHE_STATUS
        {
        IDLE,               // write cache is quiescent
        DIRTY,              // write cache has changed bits that need to be pushed to module
        QUEUED,             // write cache is currently being written to module, not yet returned
        }

    /** Keeps track about what we know about the state of the first four bytes of the
     * write cache, which are used for requesting a mode switch.
     */
    private enum MODE_CACHE_STATUS
        {
        IDLE,               // mode byte are quiesent
        DIRTY,              // mode bytes have changed, and need to be pushed to the module
        QUEUED,             // mode bytes have been queued to the module, but not yet returned.
        }
    
    //----------------------------------------------------------------------------------------------
    // Construction
    //----------------------------------------------------------------------------------------------

    /**
     * Instantiate an I2cDeviceClient instance in the indicated device with the indicated
     * initial window of registers being read.
     *
     * @param context               the OpMode within which the creation is taking place
     * @param i2cDevice             the device we are to be a client of
     * @param i2cAddr8Bit           its 8 bit i2cAddress
     */
    public I2cDeviceClient(OpMode context, II2cDevice i2cDevice, int i2cAddr8Bit, boolean closeOnOpModeStop)
        {
        i2cDevice.setI2cAddr(i2cAddr8Bit);

        this.i2cDevice              = i2cDevice;
        this.controller             = i2cDevice.getI2cController();
        this.isEngaged              = false;
        this.closing                = false;
        this.isHooked               = false;
        this.readerWriterPreventionCount = new AtomicInteger(0);
        this.readerWriterGate       = new ReentrantReadWriteLock();
        this.readerWriterCount      = new AtomicInteger(0);
        this.callback               = new Callback();
        this.hardwareCycleCount     = 0;
        this.loggingEnabled         = false;
        this.loggingTag             = String.format("%s:i2cClient(%s)", SynchronousOpMode.LOGGING_TAG, i2cDevice.getConnectionInfo());;
        this.timeSinceLastHeartbeat = new ElapsedTime();
        this.timeSinceLastHeartbeat.reset();
        this.msHeartbeatInterval    = 0;
        this.heartbeatAction        = null;
        this.heartbeatExecutor      = null;

        this.readWindow             = null;

        if (this.controller instanceof RobotUsbModule)
            {
            this.robotUsbModule = (RobotUsbModule)this.controller;
            this.robotUsbModule.registerCallback(this.callback);
            }
        else
            throw new IllegalArgumentException("I2cController must also be a RobotUsbModule");

        this.i2cDevice.registerForPortReadyBeginEndCallback(this.callback);

        if (closeOnOpModeStop)
            RobotStateTransitionNotifier.register(context, this);
        }

    void attachToController()
    // All the state that we maintain that is tied to the state of our controller
        {
        this.readCache      = this.i2cDevice.getI2cReadCache();
        this.readCacheLock  = this.i2cDevice.getI2cReadCacheLock();
        this.writeCache     = this.i2cDevice.getI2cWriteCache();
        this.writeCacheLock = this.i2cDevice.getI2cWriteCacheLock();

        this.nanoTimeReadCacheValid = 0;
        this.readCacheStatus  = READ_CACHE_STATUS.IDLE;
        this.writeCacheStatus = WRITE_CACHE_STATUS.IDLE;
        this.modeCacheStatus  = MODE_CACHE_STATUS.IDLE;

        this.readWindowActuallyRead     = null;
        this.readWindowSentToController = null;
        this.readWindowSentToControllerInitialized = false;

        // So the callback will do it's thing to refresh based on the now-current window
        this.readWindowChanged = true;
        }

    @Override public boolean onUserOpModeStop()
        {
        synchronized (this.concurrentClientLock)
            {
            this.close();
            return true;
            }
        }

    @Override public boolean onRobotShutdown()
        {
        synchronized (this.concurrentClientLock)
            {
            this.close();
            return true;
            }
        }

    @Override public void setI2cAddr(int i2cAddr8Bit)
        {
        synchronized (this.engagementLock)
            {
            if (this.i2cDevice.getI2cAddr() != i2cAddr8Bit)
                {
                boolean wasArmed = this.isHooked;
                this.disengage();
                //
                this.i2cDevice.setI2cAddr(i2cAddr8Bit);
                //
                if (wasArmed) this.engage();
                }
            }
        }

    @Override public int getI2cAddr()
        {
        synchronized (this.engagementLock)
            {
            return this.i2cDevice.getI2cAddr();
            }
        }

    @Override public void engage()
        {
        // The engagement lock is distinct from the concurrentClientLock because we need to be
        // able to drain heartbeats while disarming, so can't own the concurrentClientLock then,
        // but we still need to be able to lock out engage() and disengage() against each other.
        // Locking order: armingLock > concurrentClientLock > callbackLock
        //
        synchronized (this.engagementLock)
            {
            this.isEngaged = true;
            adjustHooking();
            }
        }

    void hook()
        {
        // engagementLock is distinct from the concurrentClientLock because we need to be
        // able to drain heartbeats while disarming, so can't own the concurrentClientLock then,
        // but we still need to be able to lock out engage() and disengage() against each other.
        // Locking order: engagementLock > concurrentClientLock > callbackLock
        //
        synchronized (this.engagementLock)
            {
            if (!this.isHooked)
                {
                log(Log.VERBOSE, "hooking ...");
                //
                synchronized (this.callbackLock)
                    {
                    this.heartbeatExecutor = Executors.newSingleThreadExecutor();
                    this.i2cDevice.registerForI2cPortReadyCallback(this.callback);
                    }
                this.isHooked = true;
                //
                log(Log.VERBOSE, "... hooking complete");
                }
            }
        }

    /** adjust the hooking state to reflect the user's engagement intent */
    void adjustHooking()
        {
        synchronized (this.engagementLock)
            {
            if (!this.isHooked && this.isEngaged)
                this.hook();
            else if (this.isHooked && !this.isEngaged)
                this.unhook();
            }
        }

    @Override public boolean isEngaged()
        {
        return this.isEngaged;
        }

    @Override public boolean isArmed()
    // REVIEW: it might be better to have this askable directly of an I2cDevice rather
    // than having to dig under the covers to its controller
        {
        synchronized (this.engagementLock)
            {
            if (this.isHooked)
                {
                if (this.robotUsbModule != null)
                    return this.robotUsbModule.getArmingState() == RobotUsbModule.ARMINGSTATE.ARMED;
                }
            return false;
            }
        }

    @Override public void disengage()
        {
        synchronized (this.engagementLock)
            {
            this.isEngaged = false;
            this.adjustHooking();
            }
        }

    void unhook()
        {
        try {
            synchronized (this.engagementLock)
                {
                if (this.isHooked)
                    {
                    log(Log.VERBOSE, "unhooking ...");

                    // We can't hold the concurrent client lock while we drain the heartbeat
                    // as that might be doing an external top-level read. But the semantic of
                    // Executors guarantees us this call returns any actions we've scheduled
                    // have in fact been completed.
                    Util.shutdownAndAwaitTermination(this.heartbeatExecutor);

                    // Drain extant readers and writers
                    this.disableReadsAndWrites();
                    this.gracefullyDrainReadersAndWriters();

                    synchronized (this.callbackLock)
                        {
                        // There may be still data that needs to get out to the controller.
                        // Wait until that happens.
                        waitForWriteCompletionInternal();

                        // Now we know that the callback isn't executing, we can pull the
                        // rug out from under his use of the heartbeater
                        this.heartbeatExecutor = null;

                        // Finally, disconnect us from our I2cDevice
                        this.i2cDevice.deregisterForPortReadyCallback();
                        }

                    this.isHooked = false;
                    this.enableReadsAndWrites();

                    log(Log.VERBOSE, "...unhooking complete");
                    }
                }
            }
        catch (InterruptedException e)
            {
            handleCapturedInterrupt(e);
            }
        }

    void disableReadsAndWrites()
        {
        this.readerWriterPreventionCount.incrementAndGet();
        }
    void enableReadsAndWrites()
        {
        this.readerWriterPreventionCount.decrementAndGet();
        }
    boolean newReadsAndWritesAllowed()
        {
        return this.readerWriterPreventionCount.get() == 0;
        }

    void gracefullyDrainReadersAndWriters()
        {
        boolean interrupted = false;
        disableReadsAndWrites();

        for (;;)
            {
            try {
                // Note: we can't hold the concurrentClientLock or the callbackLock as
                // we attempt to take the readerWriterGate here, lest deadlock.
                if (readerWriterGate.writeLock().tryLock(20, TimeUnit.MILLISECONDS))
                    {
                    // gate acquired exclusively, so no extant readers or writers exist
                    readerWriterGate.writeLock().unlock();
                    break;
                    }
                else
                    {
                    // We timed out before hitting the lock. Run around the block again.
                    }
                }
            catch (InterruptedException e)
                {
                interrupted = true;
                }
            }

        if (interrupted)
            Thread.currentThread().interrupt();

        assertTrue(!BuildConfig.DEBUG || readerWriterCount.get()==0);
        enableReadsAndWrites();
        }

    void forceDrainReadersAndWriters()
        {
        boolean interrupted = false;
        boolean exitLoop = false;
        disableReadsAndWrites();

        for (;;)
            {
            synchronized (callbackLock)
                {
                // Set the write cache status to idle to kick anyone out of waiting for it to idle
                writeCacheStatus = WRITE_CACHE_STATUS.IDLE;

                // Lie and say that the data in the read cache is valid
                readCacheStatus = READ_CACHE_STATUS.VALID_QUEUED;
                readWindowChanged = false;
                assertTrue(!BuildConfig.DEBUG || readCacheIsValid());

                // Actually wake folk up
                callbackLock.notifyAll();
                }

            if (exitLoop)
                break;

            try {
                // Note: we can't hold the concurrentClientLock or the callbackLock as
                // we attempt to take the readerWriterGate here, lest deadlock.
                if (readerWriterGate.writeLock().tryLock(20, TimeUnit.MILLISECONDS))
                    {
                    // gate acquired exclusively, so no extant readers or writers exist
                    readerWriterGate.writeLock().unlock();
                    exitLoop = true;
                    }
                else
                    {
                    // We timed out before hitting the lock. Repoke the cache statuses
                    // and try again.
                    }
                }
            catch (InterruptedException e)
                {
                interrupted = true;
                }
            }

        if (interrupted)
            Thread.currentThread().interrupt();

        assertTrue(!BuildConfig.DEBUG || readerWriterCount.get()==0);
        enableReadsAndWrites();
        }

    //----------------------------------------------------------------------------------------------
    // HardwareDevice
    //----------------------------------------------------------------------------------------------
    
    public String getDeviceName()
        {
        return this.i2cDevice.getDeviceName();  
        }
    
    public String getConnectionInfo()
        {
        return this.i2cDevice.getConnectionInfo();
        }
    
    public int getVersion()
        {
        return this.i2cDevice.getVersion();
        }

    public void close()
        {
        this.closing = true;
        this.i2cDevice.deregisterForPortReadyBeginEndCallback();

        this.disengage();

        // We do NOT close() our i2cDevice, as we conceptually are a client of
        // the device, not it's owner.
        }

    //----------------------------------------------------------------------------------------------
    // Operations
    //----------------------------------------------------------------------------------------------

    /**
     * Sets the set of I2C device registers that we wish to read.
     */
    @Override public void setReadWindow(ReadWindow newWindow)
        {
        synchronized (this.concurrentClientLock)
            {
            synchronized (this.callbackLock)
                {
                if (this.readWindow != null && this.readWindow.isOkToRead() && this.readWindow.maySwitchToReadMode() && this.readWindow.sameAsIncludingMode(newWindow))
                    {
                    // What's there is good; we don't need to change anything
                    }
                else
                    {
                    // Remember the new window, but get a fresh copy so we can implement the read mode policy
                    setReadWindowInternal(newWindow.readableCopy());
                    assertTrue(!BuildConfig.DEBUG || (this.readWindow.isOkToRead() && this.readWindow.maySwitchToReadMode()));
                    }
                }
            }
        }

    /** locks must be externally taken */
    private void setReadWindowInternal(ReadWindow newWindow)
        {
        this.readWindow = newWindow;

        // Let others (specifically, the callback) know of the update
        this.readWindowChanged = true;
        }

    /**
     * Return the current register window.
     */
    @Override public ReadWindow getReadWindow()
        {
        synchronized (this.concurrentClientLock)
            {
            synchronized (this.callbackLock)
                {
                return this.readWindow;
                }
            }
        }

    /**
     * Ensure that the current register window covers the indicated set of registers.
     */
    @Override public void ensureReadWindow(ReadWindow windowNeeded, ReadWindow windowToSet)
        {
        synchronized (this.concurrentClientLock)
            {
            synchronized (this.callbackLock)
                {
                if (this.readWindow == null || !this.readWindow.containsWithSameMode(windowNeeded))
                    {
                    setReadWindow(windowToSet);
                    }
                }
            }
        }

    /**
     * Read the byte at the indicated register.
     */
    @Override public byte read8(int ireg)
        {
        return this.read(ireg, 1)[0];
        }

    /**
     * Read a contiguous set of registers
     */
    @Override public byte[] read(int ireg, int creg)
        {
        return this.readTimeStamped(ireg, creg).data;
        }

    /**
     * Read a contiguous set of registers.
     *
     * This is the core read routine. Note that the current read window is never
     * adjusted or invalidated by the execution of this function; that helps support
     * concurrent clients.
     */
    @Override public TimestampedData readTimeStamped(int ireg, int creg)
        {
        try
            {
            // Take the readerWriterLock so that others will be able to track when reads and writes have drained
            acquireReaderLockShared();
            try {
                if (!this.openForReading())
                    return makeFakeData(ireg, creg);

                synchronized (this.concurrentClientLock)
                    {
                    synchronized (this.callbackLock)
                        {
                        // Wait until the write cache isn't busy. This honors the visibility semantic
                        // we intend to portray, namely that issuing a read after a write has been
                        // issued will see the state AFTER the write has had a chance to take effect.
                        while (this.writeCacheStatus != WRITE_CACHE_STATUS.IDLE)
                            {
                            this.callbackLock.wait(msCallbackLockWaitQuantum);
                            }

                        // Remember what the read window was on entry so we can restore it later if needed
                        ReadWindow prevReadWindow = this.readWindow;

                        // Is what's in the read cache right now or shortly will be have what we want?
                        if (readCacheValidityCurrentOrImminent() && readWindowActuallyRead != null && readWindowActuallyRead.contains(ireg, creg))
                            {
                            // Ok, we don't have to issue a read, but we may have to wait for validity,
                            // which we we do in a moment down below
                            // log(Log.VERBOSE, String.format("read from cache: (0x%02x,%d)", ireg, creg));
                            }
                        else
                            {
                            // We have to issue a new read. We do so by setting the read window to something
                            // that is readable; this is noticed by the callback which then services the read.

                            // If there's no read window given or what's there either can't service any
                            // more reads or it doesn't contain the required registers, auto-make a new window.
                            boolean readWindowRangeOk = this.readWindow != null && this.readWindow.contains(ireg, creg);

                            if (!readWindowRangeOk || !this.readWindow.isOkToRead() || !this.readWindow.maySwitchToReadMode())
                                {
                                // If we can re-use the window that was there before that will help increase
                                // the chance that we don't need to take the time to switch the controller to
                                // read mode (with a different window) and thus can respond faster.
                                if (readWindowRangeOk)
                                    {
                                    // log(Log.VERBOSE, String.format("reuse window: (0x%02x,%d)", ireg, creg));
                                    setReadWindow(this.readWindow);     // will make a readable copy
                                    }
                                else
                                    {
                                    // Make a one-shot that just covers the data we need right now
                                    // log(Log.VERBOSE, String.format("make one shot: (0x%02x,%d)", ireg, creg));
                                    setReadWindow(new ReadWindow(ireg, creg, READ_MODE.ONLY_ONCE));
                                    }
                                }
                            }

                        // Wait until the read cache is valid
                        while (!readCacheIsValid())
                            {
                            this.callbackLock.wait(msCallbackLockWaitQuantum);
                            }

                        // Extract the data and return!
                        this.readCacheLock.lockInterruptibly();
                        try
                            {
                            assertTrue(!BuildConfig.DEBUG || this.readWindowActuallyRead.contains(this.readWindow));

                            // The data of interest is somewhere in the read window, but not necessarily at the start.
                            int ibFirst            = ireg - this.readWindowActuallyRead.getIregFirst() + dibCacheOverhead;
                            TimestampedData result = new TimestampedData();
                            result.data            = Arrays.copyOfRange(this.readCache, ibFirst, ibFirst + creg);
                            result.nanoTime        = this.nanoTimeReadCacheValid;
                            return result;
                            }
                        finally
                            {
                            this.readCacheLock.unlock();

                            // If that was a one-time read, invalidate the data so we won't read it again a second time.
                            // Note that this is the only place outside of the callback that we ever update
                            // readCacheStatus or writeCacheStatus
                            if (this.readCacheStatus==READ_CACHE_STATUS.VALID_ONLYONCE)
                                this.readCacheStatus=READ_CACHE_STATUS.IDLE;

                            // Restore any read window that we may have disturbed
                            if (this.readWindow != prevReadWindow)
                                {
                                setReadWindowInternal(prevReadWindow);
                                }
                            }
                        }
                    }
                }
            finally
                {
                releaseReaderLockShared();
                }
            }
        catch (InterruptedException e)
            {
            handleCapturedInterrupt(e);

            // Can't return (no data to return!) so we must throw
            throw SwerveRuntimeException.wrap(e);
            }
        }

    boolean openForReading()
        {
        return this.isHooked && this.newReadsAndWritesAllowed();
        }
    boolean openForWriting()
        {
        return this.isHooked && this.newReadsAndWritesAllowed();
        }
    void acquireReaderLockShared() throws InterruptedException
        {
        try {
            this.readerWriterGate.readLock().lockInterruptibly();
            this.readerWriterCount.incrementAndGet();   // for debugging
            }
        catch (InterruptedException e)
            {
            throw e;    // for debugging
            }
        }
    void releaseReaderLockShared()
        {
        this.readerWriterCount.decrementAndGet();       // for debugging
        this.readerWriterGate.readLock().unlock();
        }

    TimestampedData makeFakeData(int ireg, int creg)
        {
        TimestampedData result = new TimestampedData();
        result.data     = new byte[creg];       // all zeros
        result.nanoTime = System.nanoTime();
        return result;
        }

    @Override public TimestampedData readTimeStamped(final int ireg, final int creg, final ReadWindow readWindowNeeded, final ReadWindow readWindowSet)
        {
        ensureReadWindow(readWindowNeeded, readWindowSet);
        return readTimeStamped(ireg, creg);
        }

    private boolean readCacheValidityCurrentOrImminent()
        {
        return this.readCacheStatus != READ_CACHE_STATUS.IDLE && !this.readWindowChanged;
        }
    private boolean readCacheIsValid()
        {
        return this.readCacheStatus.isValid() && !this.readWindowChanged;
        }

    /**
     * Write a byte to the indicated register
     */
    @Override public void write8(int ireg, int data)
        {
        this.write(ireg, new byte[]{(byte) data});
        }
    @Override public void write8(int ireg, int data, boolean waitforCompletion)
        {
        this.write(ireg, new byte[]{(byte) data}, waitforCompletion);
        }

    /**
     * Write data to a set of registers, beginning with the one indicated. The data will be
     * written to the I2C device as expeditiously as possible. This method will not return until
     * the data has been written to the device controller; however, that does not necessarily
     * indicate that the data has been issued in an I2C write transaction, though that ought
     * to happen a short deterministic time later.
     */
    @Override public void write(int ireg, byte[] data)
        {
        write(ireg, data, true);
        }
    @Override public void write(int ireg, byte[] data, boolean waitForCompletion)
        {
        try
            {
            // Take the readerWriterLock so that others will be able to track when reads and writes have drained
            acquireReaderLockShared();
            try {
                if (!openForWriting())
                    return; // Ignore the write

                synchronized (this.concurrentClientLock)
                    {
                    if (data.length > ReadWindow.cregWriteMax)
                        throw new IllegalArgumentException(String.format("write request of %d bytes is too large; max is %d", data.length, ReadWindow.cregWriteMax));

                    synchronized (this.callbackLock)
                        {
                        // If there's already a pending write, can we coalesce?
                        boolean doCoalesce = false;
                        if (this.writeCacheStatus == WRITE_CACHE_STATUS.DIRTY && this.cregWrite + data.length <= ReadWindow.cregWriteMax)
                            {
                            if (ireg + data.length == this.iregWriteFirst)
                                {
                                // New data is immediately before the old data.
                                // leave ireg is unchanged
                                data = Util.concatenateByteArrays(data, readWriteCache());
                                doCoalesce = true;
                                }
                            else if (this.iregWriteFirst + this.cregWrite == ireg)
                                {
                                // New data is immediately after the new data.
                                ireg = this.iregWriteFirst;
                                data = Util.concatenateByteArrays(readWriteCache(), data);
                                doCoalesce = true;
                                }
                            }

                        // if (doCoalesce) this.log(Log.VERBOSE, "coalesced write");

                        // Wait until we can write to the write cache. If we are coalescing, then
                        // we don't ever wait, as we're just modifying what's there
                        while (!doCoalesce && this.writeCacheStatus != WRITE_CACHE_STATUS.IDLE)
                            {
                            this.callbackLock.wait(msCallbackLockWaitQuantum);
                            }

                        // Indicate where we want to write
                        this.iregWriteFirst = ireg;
                        this.cregWrite      = data.length;

                        // Indicate we are dirty so the callback will write us out
                        setWriteCacheStatusIfHooked(WRITE_CACHE_STATUS.DIRTY);

                        // Provide the data we want to write
                        this.writeCacheLock.lock();
                        try
                            {
                            System.arraycopy(data, 0, this.writeCache, dibCacheOverhead, data.length);
                            }
                        finally
                            {
                            this.writeCacheLock.unlock();
                            }

                        // Let the callback know we've got new data for him
                        this.callback.onNewDataToWrite();

                        if (waitForCompletion)
                            {
                            // Wait until the write at least issues to the device controller. This will
                            // help make any delays/sleeps that follow a write() be more deterministically
                            // relative to the actual I2C device write.
                            waitForWriteCompletionInternal();
                            }
                        }
                    }
                }
            finally
                {
                releaseReaderLockShared();
                }
            }
        catch (InterruptedException e)
            {
            handleCapturedInterrupt(e);
            }
        }

    @Override public void waitForWriteCompletions()
        {
        try {
            synchronized (this.concurrentClientLock)
                {
                synchronized (this.callbackLock)
                    {
                    waitForWriteCompletionInternal();
                    }
                }
            }
        catch (InterruptedException e)
            {
            handleCapturedInterrupt(e);
            }
        }

    /** Returns a copy of the user data currently sitting in the write cache */
    private byte[] readWriteCache()
        {
        this.writeCacheLock.lock();
        try {
            return Arrays.copyOfRange(this.writeCache, dibCacheOverhead, dibCacheOverhead + this.cregWrite);
            }
        finally
            {
            this.writeCacheLock.unlock();
            }
        }

    private void waitForWriteCompletionInternal() throws InterruptedException
        {
        while (writeCacheStatus != WRITE_CACHE_STATUS.IDLE)
            {
            this.callbackLock.wait(msCallbackLockWaitQuantum);
            }
        }

    /** set the write cache status, but don't disturb (from idle) if we're not open for business */
    /* TODO: not sure if this is really needed */
    void setWriteCacheStatusIfHooked(WRITE_CACHE_STATUS status)
        {
        if (this.isHooked && this.newReadsAndWritesAllowed())
            this.writeCacheStatus = status;
        }

    @Override public int getI2cCycleCount()
        {
        synchronized (this.concurrentClientLock)
            {
            synchronized (this.callbackLock)
                {
                return this.hardwareCycleCount;
                }
            }
        }
    
    @Override public void setLogging(boolean enabled)
        {
        synchronized (this.concurrentClientLock)
            {
            synchronized (this.callbackLock)
                {
                this.loggingEnabled = enabled;
                }
            }
        }

    @Override public void setLoggingTag(String loggingTag)
        {
        synchronized (this.concurrentClientLock)
            {
            synchronized (this.callbackLock)
                {
                this.loggingTag = loggingTag + "I2C";
                }
            }
        }
    
    @Override public int getHeartbeatInterval()
        {
        synchronized (this.concurrentClientLock)
            {
            synchronized (this.callbackLock)
                {
                return this.msHeartbeatInterval;
                }
            }
        }

    @Override public void setHeartbeatInterval(int msHeartbeatInterval)
        {
        synchronized (this.concurrentClientLock)
            {
            synchronized (this.callbackLock)
                {
                this.msHeartbeatInterval = Math.max(0, msHeartbeatInterval);
                }
            }
        }

    @Override public void setHeartbeatAction(HeartbeatAction action)
        {
        synchronized (this.concurrentClientLock)
            {
            synchronized (this.callbackLock)
                {
                this.heartbeatAction = action;
                }
            }
        }
    
    @Override public HeartbeatAction getHeartbeatAction()
        {
        synchronized (this.concurrentClientLock)
            {
            synchronized (this.callbackLock)
                {
                return this.heartbeatAction;
                }
            }
        }

    private void log(int verbosity, String message)
        {
        switch (verbosity)
            {
        case Log.VERBOSE:   Log.v(loggingTag, message); break;
        case Log.DEBUG:     Log.d(loggingTag, message); break;
        case Log.INFO:      Log.i(loggingTag, message); break;
        case Log.WARN:      Log.w(loggingTag, message); break;
        case Log.ERROR:     Log.e(loggingTag, message); break;
        case Log.ASSERT:    Log.wtf(loggingTag, message); break;
            }
        }
    private void log(int verbosity, String format, Object... args)
        {
        log(verbosity, String.format(format, args));
        }
    
    /** Flag to distinguish state machine updates that are caused by the callback vs state
      * machine updates that are due to application-initiated writes */
    private enum UPDATE_STATE_MACHINE
        {
        FROM_CALLBACK,
        FROM_USER_WRITE
        }

    private class Callback implements I2cController.I2cPortReadyCallback, I2cController.I2cPortReadyBeginEndNotifications, RobotUsbModule.ArmingStateCallback
        {
        //------------------------------------------------------------------------------------------
        // State, kept in member variables so we can divvy the updateStateMachines() logic
        // across multiple function
        //------------------------------------------------------------------------------------------

        boolean setActionFlag     = false;
        boolean queueFullWrite    = false;
        boolean queueRead         = false;
        boolean heartbeatRequired = false;
        boolean enabledReadMode   = false;
        boolean enabledWriteMode  = false;

        READ_CACHE_STATUS  prevReadCacheStatus  = READ_CACHE_STATUS.IDLE;
        WRITE_CACHE_STATUS prevWriteCacheStatus = WRITE_CACHE_STATUS.IDLE;
        MODE_CACHE_STATUS  prevModeCacheStatus  = MODE_CACHE_STATUS.IDLE;

        boolean doModuleIsArmedWorkEnabledWrites = false;
        boolean haveSeenModuleIsArmedWork        = false;

        //------------------------------------------------------------------------------------------
        // I2cController.I2cPortReadyCallback
        //------------------------------------------------------------------------------------------

        @Override public void portIsReady(int port)
        // This is the callback from the device module indicating completion of previously requested work.
        // At the moment we are called, we are assured that the read buffer / write buffer for our port in the
        // USB device is not currently busy.
            {
            updateStateMachines(UPDATE_STATE_MACHINE.FROM_CALLBACK);
            }

        //------------------------------------------------------------------------------------------
        // RobotUsbModule.ArmingStateCallback
        //------------------------------------------------------------------------------------------

        @Override
        public void onModuleStateChange(RobotUsbModule robotUsbModule, RobotUsbModule.ARMINGSTATE armingstate)
            {
            switch (armingstate)
                {
                case ARMED:
                    log(Log.VERBOSE, "onArmed ...");
                    doModuleIsArmedWork(true);
                    log(Log.VERBOSE, "... onArmed");
                    break;
                case PRETENDING:
                    log(Log.VERBOSE, "onPretending ...");
                    doModuleIsArmedWork(false);
                    log(Log.VERBOSE, "... onPretending");
                    break;
                case DISARMED:
                    // Unnecessary: we WILL get the onPortIsReadyCallbacksEnd() notification;
                    break;
                }
            }

        //------------------------------------------------------------------------------------------
        // I2cController.I2cPortReadyBeginEndNotifications
        //------------------------------------------------------------------------------------------

        @Override public void onPortIsReadyCallbacksBegin(int port)
            {
            // We get this callback when we register for portIsReadyCallbacks and our module
            // is either armed or pretending. If it's armed already, we're not going to get
            // a notification that it changes into the armed state. So hook up now! Otherwise,
            // we'll wait until the
            log(Log.VERBOSE, "doPortIsReadyCallbackBeginWork ...");
            try {
                switch (robotUsbModule.getArmingState())
                    {
                    case ARMED:
                        doModuleIsArmedWork(true);
                        break;
                    case PRETENDING:
                        doModuleIsArmedWork(false);
                        break;
                    }
                }
            finally
                {
                log(Log.VERBOSE, "... doPortIsReadyCallbackBeginWork complete");
                }
            }

        void doModuleIsArmedWork(boolean arming)
            {
            try {
                log(Log.VERBOSE, "doModuleIsArmedWork ...");
                synchronized (engagementLock)
                    {
                    disableReadsAndWrites();
                    forceDrainReadersAndWriters();
                    unhook();

                    // REVIEW: what locking is needed for this?
                    I2cDeviceClient.this.attachToController();

                    adjustHooking();

                    // We are a little paranoid here. In theory, we ought to be able to work perfectly
                    // fine against a module that is pretending. But it's more robust of us to just leave
                    // reads and writes disabled at our upper surface rather than relying on that emulation
                    // to work. Moreover, during development, we hit a number of deadlocks when we tried,
                    // THOUGH those deadlocks might actually have been due to use not using onModuleStateChange
                    // to do the work but rather ONLY onPortIsReadyCallbacksBegin. That bug MIGHT have been
                    // the whole story, but it might not, and we're just too worn out to find out. So we take
                    // the robust, easy way out. For now, at least.
                    if (arming)
                        {
                        enableReadsAndWrites();
                        doModuleIsArmedWorkEnabledWrites = true;
                        }
                    else
                        doModuleIsArmedWorkEnabledWrites = false;

                    haveSeenModuleIsArmedWork = true;
                    }
                }
            finally
                {
                log(Log.VERBOSE, "... doModuleIsArmedWork complete");
                }
            }

        @Override public void onPortIsReadyCallbacksEnd(int port)
        // We're being told that we're not going to get any more portIsReady callbacks.
            {
            try {
                log(Log.VERBOSE, "onPortIsReadyCallbacksEnd ...");

                if (closing)
                    return; // ignore

                if (!haveSeenModuleIsArmedWork)
                    return; // ReadWriteRunnable started then suddenly stopped before owner finished arming

                synchronized (engagementLock)
                    {
                    if (doModuleIsArmedWorkEnabledWrites)
                        {
                        disableReadsAndWrites();
                        }

                    forceDrainReadersAndWriters();
                    unhook();
                    assertTrue(!BuildConfig.DEBUG || !openForReading() && !openForWriting());
                    enableReadsAndWrites();

                    haveSeenModuleIsArmedWork = false;
                    }
                }
            finally
                {
                log(Log.VERBOSE, "... onPortIsReadyCallbacksEnd complete");
                }
            }

        //------------------------------------------------------------------------------------------
        // Other entry points
        //------------------------------------------------------------------------------------------

        // The user has new data for us to write. We could do nothing, in which case the data
        // will go out at the next callback cycle just fine, or we could try to push it out
        // more aggressively. Unfortunately, at present there's no way to push it out more quickly,
        // so in effect this is a no-op. But we've left the code we do have here in place as a
        // reminder of our current thoughts about some pieces of how the two modes would interact
        // should that situation change.
        void onNewDataToWrite()
            {
            updateStateMachines(UPDATE_STATE_MACHINE.FROM_USER_WRITE);
            }

        //------------------------------------------------------------------------------------------
        // Update logic
        //------------------------------------------------------------------------------------------

        void startSwitchingToReadMode(ReadWindow window)
            {
            readCacheStatus = READ_CACHE_STATUS.SWITCHINGTOREADMODE;
            i2cDevice.enableI2cReadMode(window.getIregFirst(), window.getCreg());
            enabledReadMode = true;

            // Remember what we actually told the controller
            readWindowSentToController = window;
            readWindowSentToControllerInitialized = true;

            setActionFlag   = true;     // causes an I2C read to happen
            queueFullWrite  = true;     // for just the mode bytes
            queueRead       = true;     // read the mode byte so we can tell when the switch is done

            dirtyModeCacheStatus();
            }

        void issueWrite()
            {
            setWriteCacheStatusIfHooked(WRITE_CACHE_STATUS.QUEUED);
            i2cDevice.enableI2cWriteMode(iregWriteFirst, cregWrite);
            enabledWriteMode = true;

            // This might be only paranoia, but we're not certain. In any case, it's safe.
            readWindowSentToController = null;
            readWindowSentToControllerInitialized = true;

            setActionFlag  = true;      // causes the I2C write to happen
            queueFullWrite = true;      // for the mode byte and the payload
            queueRead      = true;      // read the mode byte too so that isI2cPortInReadMode() will report correctly

            dirtyModeCacheStatus();
            }

        void dirtyModeCacheStatus()
            {
            assertTrue(!BuildConfig.DEBUG || modeCacheStatus == MODE_CACHE_STATUS.IDLE);
            modeCacheStatus = MODE_CACHE_STATUS.DIRTY;
            }

        private void clearActionFlag()
            {
            try {
                writeCacheLock.lock();
                writeCache[ibActionFlag] = 0;
                }
            finally
                {
                writeCacheLock.unlock();
                }
            }

        void updateStateMachines(UPDATE_STATE_MACHINE caller)
        // We've got quite the little state machine here!
            {
            synchronized (callbackLock)
                {
                //----------------------------------------------------------------------------------
                // If we're calling from other than the callback (in which we *know* the port is
                // ready), we need to check whether things are currently busy. We defer until
                // later if they are.
                if (caller==UPDATE_STATE_MACHINE.FROM_USER_WRITE)
                    {
                    if (!i2cDevice.isI2cPortReady())
                        return;

                    // Optimized calling from user mode is not yet implemented
                    return;
                    }

                //----------------------------------------------------------------------------------
                // Some ancillary bookkeeping

                if (caller == UPDATE_STATE_MACHINE.FROM_CALLBACK)
                    {
                    // Update cycle statistics
                    hardwareCycleCount++;
                    }

                //----------------------------------------------------------------------------------
                // Initialize state for managing state transition

                setActionFlag     = false;
                queueFullWrite    = false;
                queueRead         = false;
                heartbeatRequired = (msHeartbeatInterval > 0 && milliseconds(timeSinceLastHeartbeat) >= msHeartbeatInterval);
                enabledReadMode   = false;
                enabledWriteMode  = false;
                
                prevReadCacheStatus  = readCacheStatus;
                prevWriteCacheStatus = writeCacheStatus;
                prevModeCacheStatus  = modeCacheStatus;

                //----------------------------------------------------------------------------------
                // Handle the state machine

                if (caller==UPDATE_STATE_MACHINE.FROM_CALLBACK)
                    {
                    //--------------------------------------------------------------------------
                    // Deal with the fact that we've completed any previous queueing operation

                    if (modeCacheStatus == MODE_CACHE_STATUS.QUEUED)
                        modeCacheStatus = MODE_CACHE_STATUS.IDLE;

                    if (readCacheStatus == READ_CACHE_STATUS.QUEUED || readCacheStatus == READ_CACHE_STATUS.VALID_QUEUED)
                        {
                        readCacheStatus = READ_CACHE_STATUS.QUEUE_COMPLETED;
                        nanoTimeReadCacheValid = System.nanoTime();
                        }

                    if (writeCacheStatus == WRITE_CACHE_STATUS.QUEUED)
                        {
                        writeCacheStatus = WRITE_CACHE_STATUS.IDLE;
                        // Our write mode status should have been reported back to us. And it
                        // always will, so long as our module remains operational.
                        //
                        // But that might not happen *right* at the moment our module disconnects:
                        // we've lost communication; we're not going to see the write mode status
                        // reported back, but the hole module-disconnect-handing sequence has yet to
                        // tear everything down in response.
                        //
                        // There doesn't seem to be any way to reliably do an assert, as that loss
                        // of connection might have not yet got through to *anybody*.
                        //
                        // assertTrue(!BuildConfig.DEBUG || !isHooked || !newReadsAndWritesAllowed() || i2cDevice.isI2cPortInWriteMode());
                        }

                    //--------------------------------------------------------------------------
                    // That limits the number of states the caches can now be in

                    assertTrue(!BuildConfig.DEBUG ||
                                     (readCacheStatus==READ_CACHE_STATUS.IDLE
                                    ||readCacheStatus==READ_CACHE_STATUS.SWITCHINGTOREADMODE
                                    ||readCacheStatus==READ_CACHE_STATUS.VALID_ONLYONCE
                                    ||readCacheStatus==READ_CACHE_STATUS.QUEUE_COMPLETED));
                    assertTrue(!BuildConfig.DEBUG || (writeCacheStatus == WRITE_CACHE_STATUS.IDLE || writeCacheStatus == WRITE_CACHE_STATUS.DIRTY));

                    //--------------------------------------------------------------------------
                    // Complete any read mode switch if there is one

                    if (readCacheStatus == READ_CACHE_STATUS.SWITCHINGTOREADMODE)
                        {
                        // We're trying to switch into read mode. Are we there yet?
                        if (i2cDevice.isI2cPortInReadMode())
                            {
                            // See also below XYZZY
                            readCacheStatus = READ_CACHE_STATUS.QUEUED;
                            setActionFlag   = true;     // actually do an I2C read
                            queueRead       = true;     // read the I2C read results
                            }
                        else
                            {
                            queueRead = true;           // read the mode byte
                            }
                        }

                    //--------------------------------------------------------------------------
                    // If there's a write request pending, and it's ok to issue the write, do so

                    else if (writeCacheStatus == WRITE_CACHE_STATUS.DIRTY)
                        {
                        issueWrite();

                        // Our ordering rules are that any reads after a write have to wait until
                        // the write is actually sent to the hardware, so anything we've read before is junk.
                        // Note that there's an analogous check in read().
                        readCacheStatus = READ_CACHE_STATUS.IDLE;
                        }

                    //--------------------------------------------------------------------------
                    // Initiate reading if we should. Be sure to honor the policy of the read mode.

                    else if (readCacheStatus == READ_CACHE_STATUS.IDLE || readWindowChanged)
                        {
                        boolean issuedRead = false;
                        if (readWindow != null)
                            {
                            // Is the controller already set up to read the data we're now interested
                            // in, so that we can get at it without having to incur the cost of
                            // switching to read mode?
                            boolean readSwitchUnnecessary = (readWindowSentToController != null
                                    && readWindowSentToController.contains(readWindow)
                                    && i2cDevice.isI2cPortInReadMode());

                            if (readWindow.isOkToRead() && (readSwitchUnnecessary || readWindow.maySwitchToReadMode()))
                                {
                                if (readSwitchUnnecessary)
                                    {
                                    // Lucky us! We can go ahead and queue the read right now!
                                    // See also above XYZZY
                                    readWindowActuallyRead = readWindowSentToController;
                                    readCacheStatus = READ_CACHE_STATUS.QUEUED;
                                    setActionFlag   = true;         // actually do an I2C read
                                    queueRead       = true;         // read the results of the read
                                    }
                                else
                                    {
                                    // We'll start switching now, and queue the read later
                                    readWindowActuallyRead = readWindow;
                                    startSwitchingToReadMode(readWindow);
                                    }

                                issuedRead = true;
                                }
                            }

                        if (issuedRead)
                            {
                            // Remember that we've used this window in a read operation. This doesn't
                            // matter for REPEATs, but does for the other modes
                            readWindow.setReadIssued();
                            }
                        else
                            {
                            // Make *sure* that we don't appear to have valid data
                            readCacheStatus = READ_CACHE_STATUS.IDLE;
                            }

                        readWindowChanged = false;
                        }

                    //--------------------------------------------------------------------------
                    // Reissue any previous read if we should. The only way we are here and
                    // see READ_CACHE_STATUS.QUEUE_COMPLETED is if we completed a queuing operation
                    // above.

                    else if (readCacheStatus == READ_CACHE_STATUS.QUEUE_COMPLETED)
                        {
                        if (readWindow != null && readWindow.isOkToRead())
                            {
                            readCacheStatus = READ_CACHE_STATUS.VALID_QUEUED;
                            setActionFlag = true;           // actually do an I2C read
                            queueRead     = true;           // read the results of the read
                            }
                        else
                            {
                            readCacheStatus = READ_CACHE_STATUS.VALID_ONLYONCE;
                            }
                        }

                    //--------------------------------------------------------------------------
                    // Completing the possibilities:

                    else if (readCacheStatus == READ_CACHE_STATUS.VALID_ONLYONCE)
                        {
                        // Just leave it there until someone reads it
                        }

                    //----------------------------------------------------------------------------------
                    // Ok, after all that we finally know what how we're required to
                    // interact with the device controller according to what we've been
                    // asked to read or write. But what, now, about heartbeats?

                    if (!setActionFlag && heartbeatRequired)
                        {
                        if (heartbeatAction != null)
                            {
                            if (readWindowSentToController != null && heartbeatAction.rereadLastRead)
                                {
                                // Controller is in or is switching to read mode. If he's there
                                // yet, then issue an I2C read; if he's not, then he soon will be.
                                if (i2cDevice.isI2cPortInReadMode())
                                    {
                                    setActionFlag = true;       // issue an I2C read
                                    }
                                else
                                    {
                                    assertTrue(!BuildConfig.DEBUG || readCacheStatus==READ_CACHE_STATUS.SWITCHINGTOREADMODE);
                                    }
                                }

                            else if (readWindowSentToControllerInitialized && readWindowSentToController == null && heartbeatAction.rewriteLastWritten)
                                {
                                // Controller is in write mode, and the write cache has what we last wrote
                                queueFullWrite = true;
                                setActionFlag = true;           // issue an I2C write
                                }

                            else if (heartbeatAction.heartbeatReadWindow != null)
                                {
                                // The simplest way to do this is just to do a new read from the outside, as that
                                // means it has literally zero impact here on our state machine. That unfortunately
                                // introduces concurrency where otherwise none might exist, but that's ONLY if you
                                // choose this flavor of heartbeat, so that's a reasonable tradeoff.
                                final ReadWindow window = heartbeatAction.heartbeatReadWindow;   // capture here while we still have the lock
                                try {
                                    if (heartbeatExecutor != null)
                                        {
                                        heartbeatExecutor.submit(new java.lang.Runnable()
                                            {
                                            @Override public void run()
                                                {
                                                try {
                                                    I2cDeviceClient.this.read(window.getIregFirst(), window.getCreg());
                                                    }
                                                catch (Exception e) // paranoia
                                                    {
                                                    // ignored
                                                    }
                                                }
                                            });
                                        }
                                    }
                                catch (RejectedExecutionException e)
                                    {
                                    // ignore: maybe we're racing with disarm
                                    }
                                }
                            }
                        }

                    if (setActionFlag)
                        {
                        // We're about to communicate on I2C right now, so reset the heartbeat.
                        // Note that we reset() *before* we talk to the device so as to do
                        // conservative timing accounting.
                        timeSinceLastHeartbeat.reset();
                        }
                    }

                else if (caller==UPDATE_STATE_MACHINE.FROM_USER_WRITE)
                    {
                    // There's nothing we know to do that would speed things up, so we
                    // just do nothing here and wait until the next portIsReady() callback.
                    }

                //----------------------------------------------------------------------------------
                // Read, set action flag and / or queue to module as requested
                
                if (setActionFlag)
                    i2cDevice.setI2cPortActionFlag();
                else
                    clearActionFlag();

                if (setActionFlag && !queueFullWrite)
                    {
                    i2cDevice.writeI2cPortFlagOnlyToController();
                    }
                else if (queueFullWrite)
                    {
                    i2cDevice.writeI2cCacheToController();
                    //
                    if (modeCacheStatus == MODE_CACHE_STATUS.DIRTY)
                        modeCacheStatus =  MODE_CACHE_STATUS.QUEUED;
                    }

                // Queue a read after queuing any write for a bit of paranoia: if we're mode switching
                // to write, we want that write to go out first, THEN read the mode status. It probably
                // would anyway, but why not...
                if (queueRead)
                    {
                    i2cDevice.readI2cCacheFromController();
                    }

                //----------------------------------------------------------------------------------
                // Do logging

                if (loggingEnabled)
                    {
                    StringBuilder message = new StringBuilder();

                    switch (caller)
                        {
                        case FROM_CALLBACK:     message.append(String.format("cyc %d", hardwareCycleCount)); break;
                        case FROM_USER_WRITE:   message.append(String.format("usr write")); break;
                        }
                    if (setActionFlag)                            message.append("|flag");
                    if (setActionFlag && !queueFullWrite)         message.append("|f");
                    else if (queueFullWrite)                      message.append("|w");
                    else                                          message.append("|.");
                    if (queueRead)                                message.append("|r");
                    if (readCacheStatus != prevReadCacheStatus)   message.append("| R." + prevReadCacheStatus.toString() + "->" + readCacheStatus.toString());
                    if (writeCacheStatus != prevWriteCacheStatus) message.append("| W." + prevWriteCacheStatus.toString() + "->" + writeCacheStatus.toString());
                 // if (modeCacheStatus != prevModeCacheStatus)   message.append("| M." + prevModeCacheStatus.toString() + "->" + modeCacheStatus.toString());
                    if (enabledWriteMode)                         message.append(String.format("| setWrite(0x%02x,%d)", iregWriteFirst, cregWrite));
                    if (enabledReadMode)                          message.append(String.format("| setRead(0x%02x,%d)", readWindow.getIregFirst(), readWindow.getCreg()));

                    log(Log.DEBUG, message.toString());
                    }

                //----------------------------------------------------------------------------------
                // Notify anyone blocked in read() or write()
                callbackLock.notifyAll();
                }
            }
        }
    }
