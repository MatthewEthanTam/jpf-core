import java.util.*;

class DatabaseConnection {
	public DatabaseConnection(String driver, String url, 
            String user, String password, String name) {
		// just do nothing here
	}
	
	// needs to throw the exception nondetreministically
	public void realClose() throws DatabaseException {
    }
	
	// return nondeterministic true/false
	public boolean isRealClosed() {
		return false;
	}
	
	// return nondeterministic true/false	
	 public boolean isValid() {
	    return true;
	 }
}

class DatabaseException extends Exception {
	
}

/*
 * Environment needs to acquire and release connections nondeterministically
 */

class DatabaseConnectionPool {
    
    String _name, _url, _user, _password;
   
    // shared connection list (available, waiting) lock
    Object _connectionPoolLock = new Object();
 
    // queue of available (not-in-use) connections
    List _connections; // of DatabaseConnection
    // queue of threads waiting for a connection
    List _waiting;     // of DatabaseConnectionWrapper

    // current pool statistics
    int availableConnections = 0;
    int _poolMaxSize = 0;        
    int _poolMinSize = 0;        
       
    DatabaseConnectionPoolMonitor _monitor;    

    private class DatabaseConnectionWrapper
    {
        private DatabaseConnection _connection = null;
        private boolean _isWaiting = false;

        public DatabaseConnection waitForConnection( long timeout ) {
            synchronized( this ) {
                // short-circuit if already own a connection
                if( _connection != null ) {
                    return _connection;
                }
                _isWaiting = true;
                long waitStart = System.currentTimeMillis();
                while( _isWaiting ) {
                    try {
                        // already got a connection, done waiting
                        if( _connection != null ) {
                             _isWaiting = false;
                             break;
                        }
                        else if (timeout > 0 && (System.currentTimeMillis() - waitStart > timeout )) {
                            // timeout period expired, done waiting
                            _isWaiting = false;
                            break;
                        }
                        // time to wait
                        this.wait( timeout );
                    } catch ( InterruptedException ie ) {
                        // ignore - will recheck for already got connection or timeout expired on next iteration
                    }
                }
            }
            return _connection;
        }

        // assign connection if this wrapper is waiting for one
        public boolean setConnection( DatabaseConnection connection ) {
            synchronized( this ) {
                if( _isWaiting && _connection == null ) {
                    // wrapper is waiting and does not have a connection yet
                    _connection = connection;
                    return true; 
                }
                else {
                    return false;
                }
            }
        }
    }
    
    private class DatabaseConnectionPoolMonitor implements Runnable
    {
        DatabaseConnectionPool _pool;        // DatabaseConnectionPool to monitor
        long _monitorInterval = 5000;        // current backoff interval
        long _monitorBaseInterval = 5000;    // baseline interval
        long _monitorMaxInterval = 120000;   // maximum interval
        long _monitorIntervalIncrement = 5000;  // amount interval increases by on successive attempts        
        long _monitorReportInterval = 60000;    // approximate period between status log entries
        boolean _connected = false;
        boolean _active = false;
        
        DatabaseConnectionPoolMonitor( DatabaseConnectionPool pool ) {
            _pool = pool;
            _connected = false;            
            _active = true;
            Thread th = new Thread(this);
            th.setName("dbpool-mon");
            th.setDaemon(true);
            th.start();
        }
        
        void stop() {
            _active=false;
        }
        
        public void run() {           
            long nextStatusTime = 0;
            while( _active ) {
                try {
                    // current status - report every _monitorReportInterval
                    long now = System.currentTimeMillis();
                    if( now > nextStatusTime ) {                       
                        nextStatusTime = now + _monitorReportInterval;
                    }
                    // check connection pool available size
                    if( _pool.availableConnections < _pool._poolMinSize && _pool._poolMaxSize > 0 ) {
                        // pool connection count has dropped below _poolMinSize
                        _pool.refresh( false );
                        // check result of refresh
                        if( _pool.availableConnections != _pool._poolMaxSize ) {
                            // refresh wasn't able to recreate the pool - might be a DB down situation
                            //  so back off using current backoff interval to avoid hammering uselessly
                            try {
                                Thread.sleep( _monitorInterval ); 
                            } catch (InterruptedException ie) {}                            
                            // adjust interval - additive instead of geometric, since the interval should 
                            //  never go to a very large timeout
                            if( _monitorInterval + _monitorIntervalIncrement <= _monitorMaxInterval ) {
                                _monitorInterval += _monitorIntervalIncrement;
                            }
                        } else {
                            // refresh was successful - reset interval backoff
                            _monitorInterval = _monitorBaseInterval;
                        }
                    } else {
                        // some connections still available, so wait for normal monitoring interval
                        try {
                            Thread.sleep( _monitorBaseInterval ); 
                        } catch (InterruptedException ie) {}                         
                    }
                    
                }
                catch ( Throwable th ) {
                    // attempt to keep running even in face of any exceptions encountered in the body of the loop
                }
            }
        }        
    }

    DatabaseConnectionPool(String name, String url, String user, String password, int poolMaxSize, int poolMinSize)
        throws DatabaseException
    {
        _name = name;
        _url = url;
        _user = user;
        _password = password;
        _poolMaxSize = poolMaxSize;
        _poolMinSize = poolMinSize;
 
        _connections = new ArrayList( _poolMaxSize );
        _waiting = new ArrayList( _poolMaxSize );
        
        // monitor will trigger a refresh
        _monitor = new DatabaseConnectionPoolMonitor( this );        
    }

    // wait up to timeout ms to acquire connection else return null to indicate timeout occurred
    //  timeout of 0 means wait forever
    DatabaseConnection acquireConnection( long timeout ) {
        if (_poolMaxSize <= 0) {
            try {
                return new DatabaseConnection(null, _url, _user, _password, _name);
            } catch (Exception e) {
                return null;
            }
        }

        DatabaseConnection result = null;

        DatabaseConnectionWrapper wrapper = new DatabaseConnectionWrapper();

        synchronized (wrapper)
        {
        	boolean needWait = true;
        	try{ 
            	long t1 = 0, t2 = 0;
	            synchronized (_connectionPoolLock)
	            {
	                // a no connections available condition may exist in the pool
	                //  if we have to wait, but the monitor thread will manage
	                //  refreshing the pool if required, as well as coordinating
	                //  an appropriate backoff so that it doesn't just hammer away
	                //  at a nonexistent database host.
	                // in any event, if that situation occurs, not much you can do
	                //  right now except wait..                                
	
	                if ((_connections.size() > 0) && (_waiting.size() == 0))
	                {
	                    // go ahead and grab one from the pool
	                    needWait = false;
	                    result = (DatabaseConnection) _connections.remove(0);
	                }
	                else
	                {
	                    // need to wait in line -- releaser will wake us up when it's our turn
	                    _waiting.add(wrapper);
	                }
	            }
        
	            if (needWait) {
	                result = wrapper.waitForConnection(timeout);
	            }
            }finally{
            	if (needWait) {
	                // remove the wrapper from the list
	                synchronized (_connectionPoolLock) {
	                    _waiting.remove(wrapper);
	                }
            	}
            }
        }

        return result;
    }

    void releaseConnection(DatabaseConnection c) 
    {
        if (c == null) return;

        // If connection is already closed, don't do anything 
        if (c.isRealClosed()) {
            return;
        }
    	// Discard the connection if connection has failed
        if (!c.isValid()) 
        {
            try {
                c.realClose();
            } catch (Exception e) {
            }
            
            if (_poolMaxSize > 0)
            {
                availableConnections--;
                // warn if active connections drop below _poolMinSize
                // (refresh should kick in and try to help out, but warn of this anyway)

                // a no connections available condition may exist in the pool
                //  at this point, but the monitor thread will manage
                //  refreshing the pool if required, as well as coordinating
                //  an appropriate backoff so that it doesn't just hammer away
                //  at a nonexistent database host.
            }

            return;
        }

        if (_poolMaxSize <= 0) return;

        distributeConnection(c);
    }

    void distributeConnection(DatabaseConnection c)
    {
        DatabaseConnectionWrapper wrapper = null;
        
        long t1 = 0, t2 = 0;
        synchronized (_connectionPoolLock)
        {
            // if there's someone waiting, we'll give the connection to them
            // otherwise we'll add it to the queue of available connections
            while( _waiting.size() > 0 ) {
                // wake up thread that's been waiting the longest
                wrapper = (DatabaseConnectionWrapper) _waiting.remove(0);
                if( wrapper.setConnection( c ) == true ) {
                    // DatabaseConnectionWrapper.setConnection() is true if the wrapper was waiting for the connection
                    break;
                }
                wrapper = null;
            }
            if( wrapper == null ) {
                // no wrapper waiting - add to head of list of available connections
                _connections.add(0,c);
            }
        }
       
        // wrapper is non-null if we found a wrapper waiting for connection 
        if (wrapper != null) {
            synchronized( wrapper ) {
                wrapper.notifyAll();
            }
        }
    }
    
    synchronized void refresh( boolean resetAll ) {
        if( resetAll ) {
            synchronized(_connectionPoolLock) {
                // close any open connections if resetAll requested
                Iterator iter = _connections.iterator();
                while (iter.hasNext())
                {
                    DatabaseConnection dc = (DatabaseConnection) iter.next();
                    try {
                        dc.realClose();
                    } catch (Exception e) {
                    }
                }
    
                availableConnections = 0;
                _connections.clear();
            }
        }

        // refresh to try to get to poolMaxSize connections
        //  -bail on connect exception, since if connection N fails, connection N+1 is probably
        //  going to fail also..
        int i=availableConnections;
        DatabaseConnection dc = null;
        try {
            for (; i<_poolMaxSize; i++, dc = null) {
                dc = new DatabaseConnection(null, _url, _user, _password, _name);
                availableConnections++;
                distributeConnection(dc);
            } 
        } 
        catch (Exception e) { 
            // close connection on partial error (bad login credential, etc)
            if( dc != null ) {
                try {
                    dc.realClose();
                } catch ( DatabaseException dbe ) {}
                availableConnections--;  // non-null dc means the exception came from distributeConnection()
            }
        }

    }

}

public class DBDriverMatthewTam {
    /**
     * @param args
     * @throws DatabaseException
     */
    public static void main (String[] args) throws DatabaseException {
        // DatabaseConnectionPool Pool1 = new DatabaseConnectionPool("pool1", "jdbc:oracle:thin:@localhost:1521:orcl", "scott", "tiger", 10, 5);
        // DatabaseConnection dc1 = new DatabaseConnection("driver1", "url1", "user1", "password1", "name1");
        // DatabaseConnection dc2 = new DatabaseConnection("driver2", "url2", "user2", "password2", "name2");
        // Pool1.distributeConnection(dc1);
        // Pool1.distributeConnection(dc2);
        // DatabaseConnection acPool = Pool1.acquireConnection(200);
        // Pool1.releaseConnection(acPool);
        // Pool1.refresh(true);
        // Pool1._monitor = null;
        DatabaseConnectionPool Pool1 = new DatabaseConnectionPool("pool1", "jdbc:oracle:thin:@localhost:1521:orcl", "scott", "tiger", 10, 5);
        DatabaseConnection dc1 = new DatabaseConnection("driver1", "url1", "user1", "password1", "name1");
        // Pool1.distributeConnection(dc1);
        // DatabaseConnection acPool = Pool1.acquireConnection(200);
        // Pool1.releaseConnection(acPool);
        // Pool1.refresh(true);
        // Pool1.refresh(false);
    }
}