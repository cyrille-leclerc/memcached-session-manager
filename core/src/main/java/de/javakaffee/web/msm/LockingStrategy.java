/*
 * Copyright 2011 Martin Grotzke
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an &quot;AS IS&quot; BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package de.javakaffee.web.msm;

import static de.javakaffee.web.msm.SessionValidityInfo.createValidityInfoKeyName;
import static de.javakaffee.web.msm.SessionValidityInfo.decode;
import static de.javakaffee.web.msm.SessionValidityInfo.encode;
import static de.javakaffee.web.msm.Statistics.StatsType.*;
import static java.lang.Math.min;
import static java.lang.Thread.sleep;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import net.spy.memcached.MemcachedClient;

import org.apache.catalina.connector.Request;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

import de.javakaffee.web.msm.BackupSessionService.SimpleFuture;
import de.javakaffee.web.msm.BackupSessionTask.BackupResult;
import de.javakaffee.web.msm.MemcachedBackupSessionManager.LockStatus;
import de.javakaffee.web.msm.SessionTrackerValve.SessionBackupService.BackupResultStatus;

/**
 * Represents the session locking hooks that must be implemented by the various locking strategies.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public abstract class LockingStrategy {

    public static enum LockingMode {
        /** Sessions are never locked. */
        NONE,
        /** Sessions are locked for each request. */
        ALL,
        /** Readonly requests are tracked and for requests that modify the session the session is locked. */
        AUTO,
        /** The application explicitely manages locks */
        APP,
        /** The session is locked for configured request patterns **/
        URI_PATTERN
    }

    protected static final String LOCK_VALUE = "locked";
    protected static final int LOCK_RETRY_INTERVAL = 10;
    protected static final int LOCK_MAX_RETRY_INTERVAL = 500;
    protected static final int LOCK_TIMEOUT = 2000;

    protected final Log _log = LogFactory.getLog( getClass() );

    protected MemcachedBackupSessionManager _manager;
    protected final MemcachedClient _memcached;
    protected LRUCache<String, Boolean> _missingSessionsCache;
    protected final SessionIdFormat _sessionIdFormat;
    protected final InheritableThreadLocal<Request> _requestsThreadLocal;
    private final ExecutorService _executor;
    private final boolean _storeSecondaryBackup;
    protected final Statistics _stats;

    protected LockingStrategy( @Nonnull final MemcachedBackupSessionManager manager,
            @Nonnull final MemcachedClient memcached,
            @Nonnull final LRUCache<String, Boolean> missingSessionsCache, final boolean storeSecondaryBackup,
            @Nonnull final Statistics stats ) {
        _manager = manager;
        _memcached = memcached;
        _missingSessionsCache = missingSessionsCache;
        _sessionIdFormat = new SessionIdFormat();
        _requestsThreadLocal = new InheritableThreadLocal<Request>();
        _storeSecondaryBackup = storeSecondaryBackup;
        _stats = stats;
        _executor = Executors.newFixedThreadPool( Runtime.getRuntime().availableProcessors() );
    }

    /**
     * Creates the appropriate {@link LockingStrategy} for the given {@link LockingMode}.
     */
    @CheckForNull
    public static LockingStrategy create( @Nullable final LockingMode lockingMode, @Nullable final Pattern uriPattern,
            @Nonnull final MemcachedClient memcached, @Nonnull final MemcachedBackupSessionManager manager,
            @Nonnull final LRUCache<String, Boolean> missingSessionsCache, final boolean storeSecondaryBackup,
            @Nonnull final Statistics stats ) {
        if ( lockingMode == null ) {
            return null;
        }
        switch ( lockingMode ) {
        case ALL:
            return new LockingStrategyAll( manager, memcached, missingSessionsCache, storeSecondaryBackup, stats );
        case AUTO:
            return new LockingStrategyAuto( manager, memcached, missingSessionsCache, storeSecondaryBackup, stats );
        case URI_PATTERN:
            return new LockingStrategyUriPattern( manager, uriPattern, memcached, missingSessionsCache, storeSecondaryBackup,
                    stats );
        case NONE:
            return new LockingStrategyNone( manager, memcached, missingSessionsCache, storeSecondaryBackup, stats );
        default:
            throw new IllegalArgumentException( "LockingMode not yet supported: " + lockingMode );
        }
    }

    /**
     * Shutdown this lockingStrategy, which frees all resources / releases threads.
     */
    public void shutdown() {
        _executor.shutdown();
    }

    protected LockStatus lock( final String sessionId ) {
        return lock( sessionId, LOCK_TIMEOUT, TimeUnit.MILLISECONDS );
    }

    protected LockStatus lock( final String sessionId, final long timeout, final TimeUnit timeUnit ) {
        if ( _log.isDebugEnabled() ) {
            _log.debug( "Locking session " + sessionId );
        }
        final long start = System.currentTimeMillis();
        try {
            acquireLock( sessionId, LOCK_RETRY_INTERVAL, LOCK_MAX_RETRY_INTERVAL, timeUnit.toMillis( timeout ),
                    System.currentTimeMillis() );
            _stats.registerSince( ACQUIRE_LOCK, start );
            if ( _log.isDebugEnabled() ) {
                _log.debug( "Locked session " + sessionId );
            }
            return LockStatus.LOCKED;
        } catch ( final TimeoutException e ) {
            _log.warn( "Reached timeout when trying to aquire lock for session " + sessionId
                    + ". Will use this session without this lock." );
            _stats.registerSince( ACQUIRE_LOCK_FAILURE, start );
            return LockStatus.COULD_NOT_AQUIRE_LOCK;
        } catch ( final InterruptedException e ) {
            Thread.currentThread().interrupt();
            throw new RuntimeException( "Got interrupted while trying to lock session.", e );
        } catch ( final ExecutionException e ) {
            _log.warn( "An exception occurred when trying to aquire lock for session " + sessionId );
            _stats.registerSince( ACQUIRE_LOCK_FAILURE, start );
            return LockStatus.COULD_NOT_AQUIRE_LOCK;
        }
    }

    protected void acquireLock( @Nonnull final String sessionId, final long retryInterval, final long maxRetryInterval,
            final long timeout, final long start ) throws InterruptedException, ExecutionException, TimeoutException {
        final Future<Boolean> result = _memcached.add( _sessionIdFormat.createLockName( sessionId ), 5, LOCK_VALUE );
        if ( result.get().booleanValue() ) {
            if ( _log.isDebugEnabled() ) {
                _log.debug( "Locked session " + sessionId );
            }
            return;
        }
        else {
            checkTimeoutAndWait( sessionId, retryInterval, maxRetryInterval, timeout, start );
            acquireLock( sessionId, retryInterval * 2, maxRetryInterval, timeout, start );
        }
    }

    protected void checkTimeoutAndWait( @Nonnull final String sessionId, final long retryInterval,
            final long maxRetryInterval, final long timeout, final long start ) throws TimeoutException,
            InterruptedException {
        if ( System.currentTimeMillis() >= start + timeout ) {
            throw new TimeoutException( "Reached timeout when trying to aquire lock for session " + sessionId );
        }
        final long timeToWait = min( retryInterval, maxRetryInterval );
        if ( _log.isDebugEnabled() ) {
            _log.debug( "Could not aquire lock for session " + sessionId + ", waiting " + timeToWait + " millis now..." );
        }
        sleep( timeToWait );
    }

    protected void releaseLock( @Nonnull final String sessionId ) {
        try {
            if ( _log.isDebugEnabled() ) {
                _log.debug( "Releasing lock for session " + sessionId );
            }
            final long start = System.currentTimeMillis();
            _memcached.delete( _sessionIdFormat.createLockName( sessionId ) );
            _stats.registerSince( RELEASE_LOCK, start );
        } catch ( final Exception e ) {
            _log.warn( "Caught exception when trying to release lock for session " + sessionId );
        }
    }

    /**
     * Is invoked after the backup of the session is initiated, it's represented by the provided backupResult. The
     * requestId is identifying the request.
     */
    protected void onAfterBackupSession( @Nonnull final MemcachedBackupSession session, final boolean backupWasForced,
            @Nonnull final Future<BackupResult> result, @Nonnull final String requestId,
            @Nonnull final BackupSessionService backupSessionService ) {

        try {

            final long start = System.currentTimeMillis();

            if ( !backupWasForced ) {
                pingSessionIfBackupWasSkipped( session, result, backupSessionService );
            }

            final byte[] validityData = encode( session.getMaxInactiveInterval(), session.getLastAccessedTimeInternal(),
                    session.getThisAccessedTimeInternal() );
            final String validityKey = createValidityInfoKeyName( session.getIdInternal() );
            _memcached.set( validityKey, session.getMaxInactiveInterval(), validityData );
            if ( _log.isDebugEnabled() ) {
                _log.debug( "Stored session validity info for session " + session.getIdInternal() );
            }

            /*
             * For non-sticky sessions we store a backup of the session in a secondary memcached node (under a special key
             * that's resolved by the SuffixBasedNodeLocator), but only when we have more than 1 memcached node configured...
             */
            if ( _storeSecondaryBackup ) {
                try {
                    final Callable<?> backupSessionTask = new StoreNonStickySessionBackupTask( session, result );
                    _executor.submit( backupSessionTask );

                    final String backupValidityKey = _sessionIdFormat.createBackupKey( validityKey );
                    _memcached.set( backupValidityKey, session.getMaxInactiveInterval(), validityData );
                } catch( final NodeFailureException e ) {
                    // handle an unavailable secondary/backup node (fix for issue #83)
                    _log.info( "Secondary/backup node "+ e.getNodeId() +" not available, skipping additional backup of session " + session.getIdInternal() );
                } catch( final RuntimeException e ) {
                    _log.info( "Could not store secondary backup of session " + session.getIdInternal(), e );
                }
            }

            _stats.registerSince( NON_STICKY_AFTER_BACKUP, start );

        } catch( final Throwable e ) {
            _log.warn( "An error occurred during onAfterBackupSession.", e );
        }

    }

    private void pingSessionIfBackupWasSkipped( @Nonnull final MemcachedBackupSession session,
            @Nonnull final Future<BackupResult> result, @Nonnull final BackupSessionService backupSessionService ) {
        final Callable<Void> task = new Callable<Void>() {

            @Override
            public Void call() {
                try {
                    if ( result.get().getStatus() == BackupResultStatus.SKIPPED ) {
                        pingSession( session, backupSessionService );
                    }
                } catch ( final Exception e ) {
                    _log.warn( "An exception occurred during backup.", e );
                }
                return null;
            }

        };
        /*
         * A simple future does not need to go through the executor, but we can process the result right now.
         */
        if ( result instanceof SimpleFuture ) {
            try {
                task.call();
            } catch ( final Exception e ) { /* caught in the callable */
            }
        }
        else {
            _executor.submit( task );
        }
    }

    /**
     * Is used to determine if this thread / the current request already hit the application or if this method
     * invocation comes from the container.
     */
    protected final boolean isContainerSessionLookup() {
        return _requestsThreadLocal.get() == null;
    }

    @CheckForNull
    protected SessionValidityInfo loadSessionValidityInfo( @Nonnull final String id ) {
        final byte[] validityInfo = (byte[]) _memcached.get( createValidityInfoKeyName( id ) );
        return validityInfo != null ? decode( validityInfo ) : null;
    }

    @CheckForNull
    protected SessionValidityInfo loadBackupSessionValidityInfo( @Nonnull final String id ) {
        final String key = createValidityInfoKeyName( id );
        final String backupKey = _sessionIdFormat.createBackupKey( key );
        final byte[] validityInfo = (byte[]) _memcached.get( backupKey );
        return validityInfo != null ? decode( validityInfo ) : null;
    }

    /**
     * Invoked before the session for this sessionId is loaded from memcached.
     */
    @CheckForNull
    protected abstract LockStatus onBeforeLoadFromMemcached( @Nonnull String sessionId ) throws InterruptedException,
            ExecutionException;

    /**
     * Invoked after a non-sticky session is loaded from memcached, can be used to update some session fields based on
     * separately stored information (e.g. session validity info).
     *
     * @param lockStatus
     *            the {@link LockStatus} that was returned from {@link #onBeforeLoadFromMemcached(String)}.
     */
    protected void onAfterLoadFromMemcached( @Nonnull final MemcachedBackupSession session,
            @Nullable final LockStatus lockStatus ) {
        session.setLockStatus( lockStatus );

        final long start = System.currentTimeMillis();
        final SessionValidityInfo info = loadSessionValidityInfo( session.getIdInternal() );
        if ( info != null ) {
            _stats.registerSince( NON_STICKY_AFTER_LOAD_FROM_MEMCACHED, start );
            session.setLastAccessedTimeInternal( info.getLastAccessedTime() );
            session.setThisAccessedTimeInternal( info.getThisAccessedTime() );
        }
        else {
            _log.warn( "No validity info available for session " + session.getIdInternal() );
        }
    }

    /**
     * Invoked after a non-sticky session is removed from memcached.
     */
    protected void onAfterDeleteFromMemcached( @Nonnull final String sessionId ) {
        final long start = System.currentTimeMillis();

        _memcached.delete( _sessionIdFormat.createBackupKey( sessionId ) );

        final String validityInfoKey = createValidityInfoKeyName( sessionId );
        _memcached.delete( validityInfoKey );
        _memcached.delete( _sessionIdFormat.createBackupKey( validityInfoKey ) );

        _stats.registerSince( NON_STICKY_AFTER_DELETE_FROM_MEMCACHED, start );
    }

    protected final void onRequestStart( final Request request ) {
        _requestsThreadLocal.set( request );
    }

    protected final void onRequestFinished() {
        _requestsThreadLocal.set( null );
    }

    private void pingSession( @Nonnull final MemcachedBackupSession session,
            @Nonnull final BackupSessionService backupSessionService ) throws InterruptedException {
        final Future<Boolean> touchResult = _memcached.add( session.getIdInternal(), session.getMaxInactiveInterval(), 1 );
        try {
            _log.debug( "Got ping result " + touchResult.get() );
            if ( touchResult.get() ) {
                _stats.nonStickySessionsPingFailed();
                _log.warn( "The session " + session.getIdInternal()
                        + " should be touched in memcached, but it seemed to be"
                        + " not existing anymore. Will store in memcached again." );
                updateSession( session, backupSessionService );
            }
        } catch ( final ExecutionException e ) {
            _log.warn( "An exception occurred when trying to ping session " + session.getIdInternal(), e );
        }
    }

    private void updateSession( @Nonnull final MemcachedBackupSession session,
            @Nonnull final BackupSessionService backupSessionService ) throws InterruptedException {
        final Future<BackupResult> result = backupSessionService.backupSession( session, true );
        try {
            if ( result.get().getStatus() != BackupResultStatus.SUCCESS ) {
                _log.warn( "Update for session (after unsuccessful ping) did not return SUCCESS, but " + result.get() );
            }
        } catch ( final ExecutionException e ) {
            _log.warn( "An exception occurred when trying to update session " + session.getIdInternal(), e );
        }
    }

    private final class StoreNonStickySessionBackupTask implements Callable<Void> {

        private final MemcachedBackupSession _session;
        private final Future<BackupResult> _result;

        private StoreNonStickySessionBackupTask( final MemcachedBackupSession session, final Future<BackupResult> result ) {
            _session = session;
            _result = result;
        }

        @Override
        public Void call() throws Exception {
            if ( _log.isDebugEnabled() ) {
                _log.debug( "Storing backup in secondary memcached for non-sticky session " + _session.getId() );
            }
            final BackupResult backupResult = _result.get();
            if ( backupResult.getStatus() != BackupResultStatus.SKIPPED ) {
                final byte[] data = backupResult.getData();
                if ( data != null ) {
                    final String key = _sessionIdFormat.createBackupKey( _session.getId() );
                    _memcached.set( key, _session.getMemcachedExpirationTimeToSet(), data );
                }
                else {
                    _log.warn( "No data set for backupResultStatus " + backupResult.getStatus() + " for sessionId "
                            + _session.getIdInternal() + ", skipping backup"
                            + " of non-sticky session in secondary memcached." );
                }
            }
            else {
                pingSessionBackup( _session );
            }
            return null;
        }

        private void pingSessionBackup( @Nonnull final MemcachedBackupSession session ) throws InterruptedException {
            final String key = _sessionIdFormat.createBackupKey( session.getId() );
            final Future<Boolean> touchResultFuture = _memcached.add( key, session.getMaxInactiveInterval(), 1 );
            try {
                final boolean touchResult = touchResultFuture.get(200, TimeUnit.MILLISECONDS);
                _log.debug( "Got backup ping result " + touchResult );
                if ( touchResult ) {
                    _log.warn( "The secondary backup for session " + session.getIdInternal()
                            + " should be touched in memcached, but it seemed to be"
                            + " not existing. Will store in memcached again." );
                    updateSessionBackup( session, key );
                }
            } catch ( final TimeoutException e ) {
                _log.warn( "The secondary backup for session " + session.getIdInternal()
                        + " could not be completed within 200 millis, was cancelled now." );
            } catch ( final ExecutionException e ) {
                _log.warn( "An exception occurred when trying to ping session " + session.getIdInternal(), e );
            }
        }

        public void updateSessionBackup( @Nonnull final MemcachedBackupSession session, @Nonnull final String key )
                throws InterruptedException {
            try {
                final byte[] data = _manager.serialize( session );
                final Future<Boolean> backupResult = _memcached.set( key, session.getMemcachedExpirationTimeToSet(), data );
                if ( !backupResult.get().booleanValue() ) {
                    _log.warn( "Update for secondary backup of session "+ session.getIdInternal() +" (after unsuccessful ping) did not return sucess." );
                }
            } catch ( final ExecutionException e ) {
                _log.warn( "An exception occurred when trying to update session " + session.getIdInternal(), e );
            }
        }
    }

}
