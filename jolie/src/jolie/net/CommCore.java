/***************************************************************************
 *   Copyright (C) by Fabrizio Montesi                                     *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU Library General Public License as       *
 *   published by the Free Software Foundation; either version 2 of the    *
 *   License, or (at your option) any later version.                       *
 *                                                                         *
 *   This program is distributed in the hope that it will be useful,       *
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of        *
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the         *
 *   GNU General Public License for more details.                          *
 *                                                                         *
 *   You should have received a copy of the GNU Library General Public     *
 *   License along with this program; if not, write to the                 *
 *   Free Software Foundation, Inc.,                                       *
 *   59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.             *
 *                                                                         *
 *   For details about the authors of this software, see the AUTHORS file. *
 ***************************************************************************/


package jolie.net;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.logging.Logger;

import jolie.Interpreter;
import jolie.net.ext.CommChannelFactory;
import jolie.net.ext.CommListenerFactory;
import jolie.net.ext.CommProtocolFactory;
import jolie.process.Process;
import jolie.runtime.InputOperation;
import jolie.runtime.InvalidIdException;
import jolie.runtime.VariablePath;

/** 
 * Handles the communications mechanisms for an Interpreter instance.
 * 
 * Each CommCore is related to an Interpreter, and each Interpreter owns one and only CommCore instance.
 */
public class CommCore
{
	final private Map< String, CommListener > listenersMap = new HashMap< String, CommListener >();

	final private ThreadGroup threadGroup;

	final private Logger logger = Logger.getLogger( "JOLIE" );

	final private int connectionsLimit;
	final private Interpreter interpreter;

	/**
	 * Returns the Interpreter instance this CommCore refers to.
	 * @return the Interpreter instance this CommCore refers to
	 */
	public Interpreter interpreter()
	{
		return interpreter;
	}

	/**
	 * Constructor.
	 * @param interpreter the Interpreter to refer to for this CommCore operations
	 * @param connectionsLimit if more than zero, specifies an upper bound to the connections handled in parallel.
	 * @throws java.io.IOException
	 */
	public CommCore( Interpreter interpreter, int connectionsLimit )
		throws IOException
	{
		this.interpreter = interpreter;
		this.localListener = new LocalListener( interpreter );
		this.connectionsLimit = connectionsLimit;
		this.threadGroup = new ThreadGroup( "CommCore-" + interpreter.hashCode() );
		if ( connectionsLimit > 0 ) {
			executorService = Executors.newFixedThreadPool( connectionsLimit, new CommThreadFactory() );
		} else {
			executorService = Executors.newCachedThreadPool( new CommThreadFactory() );
		}
				
		//TODO make socket an extension, too?
		CommListenerFactory listenerFactory = new SocketListenerFactory();
		listenerFactory.setCommCore( this );
		listenerFactories.put( "socket", listenerFactory );
		CommChannelFactory channelFactory = new SocketCommChannelFactory();
		channelFactory.setCommCore( this );
		channelFactories.put( "socket", channelFactory );
	}
	
	/**
	 * Returns the Logger used by this CommCore.
	 * @return the Logger used by this CommCore
	 */
	public Logger logger()
	{
		return logger;
	}
	
	/**
	 * Returns the connectionsLimit of this CommCore.
	 * @return the connectionsLimit of this CommCore
	 */
	public int connectionsLimit()
	{
		return connectionsLimit;
	}

	public ThreadGroup threadGroup()
	{
		return threadGroup;
	}
	
	final private Collection< Process > protocolConfigurations = new Vector< Process > ();
	
	public Collection< Process > protocolConfigurations()
	{
		return protocolConfigurations;
	}
	
	public CommListener getListenerByInputPortName( String serviceName )
	{
		return listenersMap.get( serviceName );
	}
	
	final private Map< String, CommChannelFactory > channelFactories = 
						new HashMap< String, CommChannelFactory > ();
	
	public void setCommChannelFactory( String id, CommChannelFactory factory )
	{
		channelFactories.put( id, factory );
	}
	
	public CommChannel createCommChannel( URI uri, OutputPort port )
		throws IOException
	{
		String medium = uri.getScheme();
		CommChannelFactory factory = channelFactories.get( medium );		
		if ( factory == null ) {
			throw new UnsupportedCommMediumException( medium );
		}
		
		return factory.createChannel( uri, port );
	}
	
	final private Map< String, CommProtocolFactory > protocolFactories = 
						new HashMap< String, CommProtocolFactory > ();
	
	public void setCommProtocolFactory( String id, CommProtocolFactory factory )
	{
		protocolFactories.put( id, factory );
	}
	
	public CommProtocol createCommProtocol( String protocolId, VariablePath configurationPath, URI uri )
		throws IOException
	{
		CommProtocolFactory factory = protocolFactories.get( protocolId );		
		if ( factory == null ) {
			throw new UnsupportedCommProtocolException( protocolId );
		}
		
		return factory.createProtocol( configurationPath, uri );
	}
	
	final private Map< String, CommListenerFactory > listenerFactories = 
						new HashMap< String, CommListenerFactory > ();
	
	public void setCommListenerFactory( String id, CommListenerFactory factory )
	{
		listenerFactories.put( id, factory );
	}
	
	final private LocalListener localListener;
	
	public LocalListener localListener()
	{
		return localListener;
	}
	
	public void addLocalInputPort(
				String inputPortName,
				Collection< String > operationNames,
				Map< String, OutputPort > redirectionMap
			)
		throws IOException
	{
		localListener.addOperationNames( operationNames );
		localListener.addRedirections( redirectionMap );
		listenersMap.put( inputPortName, localListener );
	}
	
	public void addInputPort(
				String inputPortName,
				URI uri,
				Collection< String > operationNames,
				CommProtocol protocol,
				Process protocolConfigurationProcess,
				Map< String, OutputPort > redirectionMap
			
			)
		throws IOException
	{
		if ( protocolConfigurationProcess != null )
			protocolConfigurations.add( protocolConfigurationProcess );

		CommListener listener = null;
		String medium = uri.getScheme();
		CommListenerFactory factory = listenerFactories.get( medium );
		if ( factory == null ) {
			throw new UnsupportedCommMediumException( medium );
		}

		listener = factory.createListener( interpreter, protocol, operationNames, redirectionMap, uri );
		listenersMap.put( inputPortName, listener );
	}
	
	final private ExecutorService executorService;
	
	private class CommThreadFactory implements ThreadFactory {
		public Thread newThread( Runnable r )
		{
			return new CommChannelHandler( interpreter, r );
		}
	}

	private class CommChannelHandlerRunnable implements Runnable {
		final private CommChannel channel;
		final private CommListener listener;
		
		public CommChannelHandlerRunnable( CommChannel channel, CommListener listener )
		{
			this.channel = channel;
			this.listener = listener;
		}
		
		private void redirectMessage( CommMessage message )
			throws IOException
		{
			channel.redirectionChannel().send( message );
		}
		
		private void handleMessage( CommMessage message )
			throws IOException
		{
			try {
				String[] ss = message.resourcePath().split( "/" );
				if ( listener != null && ss.length > 1 ) {
					CommChannelHandler.currentThread().setExecutionThread( interpreter.mainThread() );
					// We should check for redirection
					OutputPort port = listener.redirectionMap().get( ss[1] );
					if ( port == null ) {
						String error = "Discarded a message for resource " + ss[1] +
								", not specified in the appropriate redirection table.";
						Interpreter.getInstance().logger().warning( error );
						throw new IOException( error );
					}
					CommChannel oChannel = port.getCommChannel();
					String rPath = new String();
					if ( ss.length <= 2 )
						rPath = "/";
					else {
						for( int i = 2; i < ss.length; i++ ) {
							rPath += "/" + ss[ i ];
						}
					}
					CommMessage rMessage =
								new CommMessage(
										message.operationName(),
										rPath,
										message.value(),
										message.fault()
								);
					oChannel.setRedirectionChannel( channel );
					oChannel.send( rMessage );
					oChannel.setToBeClosed( false );
					oChannel.disposeForInput();
				} else {
					InputOperation operation =
						interpreter.getInputOperation( message.operationName() );
					if ( listener != null && !listener.canHandleInputOperation( operation ) ) {
						Interpreter.getInstance().logger().warning(
								"Discarded a message for operation " + operation.id() +
								", not specified in an input port at the receiving service."
							);
					} else {
						operation.recvMessage( channel, message );
					}
				}
			} catch( InvalidIdException iie ) {
				iie.printStackTrace();
			} catch( URISyntaxException e ) {
				e.printStackTrace();
			}
		}
		
		public void run()
		{
			try {
				CommMessage message = channel.recv();
				if ( channel.redirectionChannel() == null )
					handleMessage( message );
				else
					redirectMessage( message );
			} catch( IOException e ) {
				e.printStackTrace();
			}
		}
	}
	
	public void scheduleReceive( CommChannel channel, CommListener listener )
	{
		executorService.execute( new CommChannelHandlerRunnable( channel, listener ) );
	}
	
	/**
	 * Initializes the communication core, starting its communication listeners.
	 * 
	 * @see CommListener
	 */
	public void init()
		throws IOException
	{		
		for( Entry< String, CommListener > entry : listenersMap.entrySet() )
			entry.getValue().start();
	}
	
	private PollingThread pollingThread = null;
	
	private PollingThread pollingThread()
	{
		if ( pollingThread == null ) {
			pollingThread = new PollingThread();
			pollingThread.start();
		}
		return pollingThread;
	}
	
	private class PollingThread extends Thread {
		final private Set< CommChannel > channels = new HashSet< CommChannel >();
		
		@Override
		public void run()
		{
			Iterator< CommChannel > it;
			CommChannel channel;
			while( true ) {
				synchronized( this ) {
					if ( channels.isEmpty() ) {
						// Do not busy-wait for no reason
						try {
							this.wait();
						} catch( InterruptedException e ) {}
					}
					it = channels.iterator();
					while( it.hasNext() ) {
						channel = it.next();
						if ( ((PollableCommChannel)channel).isReady() ) {
							it.remove();
							scheduleReceive( channel, channel.parentListener() );
						}
					}
				}
				try {
					Thread.sleep( 50 ); // msecs
				} catch( InterruptedException e ) {}
			}
		}
		
		public void register( CommChannel channel )
			throws IOException
		{
			if ( !(channel instanceof PollableCommChannel) ) {
				throw new IOException( "Channels registering for polling must implement PollableCommChannel interface");
			}
			
			synchronized( this ) {
				channels.add( channel );
				if ( channels.size() == 1 ) { // set was empty
					this.notify();
				}
			}
		}
	}
	
	public void registerForPolling( CommChannel channel )
		throws IOException
	{
		pollingThread().register( channel );
	}
	
	private SelectorThread selectorThread = null;
	
	private SelectorThread selectorThread()
		throws IOException
	{
		if ( selectorThread == null ) {
			selectorThread = new SelectorThread();
			selectorThread.start();
		}
		return selectorThread;
	}
	
	private class SelectorThread extends Thread {
		private Selector selector;
		public SelectorThread()
			throws IOException
		{
			this.selector = Selector.open();
		}
		
		@Override
		public void run()
		{
			SelectableStreamingCommChannel channel;
			InputStream stream;
			while( true ) {
				try {
					selector.select();
					synchronized( this ) {
						for( SelectionKey key : selector.selectedKeys() ) {
							channel = (SelectableStreamingCommChannel)key.attachment();
							key.cancel();
							key.channel().configureBlocking( true );
							stream = channel.inputStream();
							stream.mark( 1 );
							// It could just be a closing read. If not, receive it.
							if ( stream.read() != -1 ) {
								stream.reset();
								scheduleReceive( channel, channel.parentListener() );
							}
						}
					}
				} catch( IOException ioe ) {
					// TODO Handle this properly
					//ioe.printStackTrace();
				}
			}
		}
		
		public void register( SelectableStreamingCommChannel channel )
		{
			SelectableChannel c;
			try {
				synchronized( this ) {
					selector.wakeup();
					c = channel.selectableChannel();
					c.configureBlocking( false );
					c.register( selector, SelectionKey.OP_READ, channel );
				}
			} catch( ClosedChannelException cce ) {}
			catch( IOException ioe ) {}
		}
	}
	
	public void registerForSelection( SelectableStreamingCommChannel channel )
		throws IOException
	{
		selectorThread().register( channel );
	}

	/** Shutdowns the communication core, interrupting every communication-related thread. */
	public void shutdown()
	{
		for( Entry< String, CommListener > entry : listenersMap.entrySet() ) {
			entry.getValue().shutdown();
		}
		threadGroup.interrupt();
	}
}
