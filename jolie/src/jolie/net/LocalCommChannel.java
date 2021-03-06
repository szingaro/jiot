/***************************************************************************
 *   Copyright (C) 2008-2015 by Fabrizio Montesi <famontesi@gmail.com>     *
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
import java.net.URI;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import jolie.Interpreter;

/**
 * An in-memory channel that can be used to communicate directly with a specific <code>Interpreter</code> instance.
 */
public class LocalCommChannel extends CommChannel implements PollableCommChannel
{
	private final URI location;

	@Override
	public URI getLocation()
	{
		return this.location;
	}

	@Override
	protected boolean isThreadSafe()
	{
		return true;
	}

	private static class CoLocalCommChannel extends CommChannel
	{
		private CommMessage request;
		private final LocalCommChannel senderChannel;

		private CoLocalCommChannel( LocalCommChannel senderChannel, CommMessage request )
		{
			this.senderChannel = senderChannel;
			this.request = request;
		}

		@Override
		protected CommMessage recvImpl()
			throws IOException
		{
			if ( request == null ) {
				throw new IOException( "Unsupported operation" );
			}
			CommMessage r = request;
			request = null;
			return r;
		}

		@Override
		protected void sendImpl( CommMessage message )
			throws IOException
		{
			if( senderChannel.responseInterpreter != null ){
				senderChannel.responseInterpreter.commCore().receiveResponse( message );
			} else {
				CompletableFuture< CommMessage > f = senderChannel.responseWaiters.get( message.id() );
				if ( f == null ) {
					throw new IOException( "Unexpected response message with id " + message.id() + " for operation " + message.operationName() + " in local channel" );
				}
				f.complete( message );
			}
		}

		@Override
		public CommMessage recvResponseFor( CommMessage request )
			throws IOException
		{
			throw new IOException( "Unsupported operation" );
		}

		@Override
		protected void disposeForInputImpl()
			throws IOException
		{
		}

		@Override
		protected void closeImpl()
		{
		}

		@Override
		public URI getLocation()
		{
			throw new UnsupportedOperationException( "LocalCommChannels are not supposed to be queried on their location." );
		}

		@Override
		protected boolean isThreadSafe()
		{
			throw new UnsupportedOperationException( "LocalCommChannels are not supposed to be queried on whether they are threadsafe or not." );
		}
	}

	private final Interpreter interpreter;
	private final Interpreter responseInterpreter;
	private final CommListener listener;
	private final Map< Long, CompletableFuture< CommMessage>> responseWaiters = new ConcurrentHashMap<>();

	public LocalCommChannel( Interpreter interpreter, CommListener listener )
	{
		this.interpreter = interpreter;
		this.listener = listener;
		this.location = listener.inputPort().location();
		this.responseInterpreter = null;
	}
	
	public LocalCommChannel( Interpreter interpreter, CommListener listener, Interpreter responseInterpreter ){
		this.interpreter = interpreter;
		this.listener = listener;
		this.location = listener.inputPort().location();
		this.responseInterpreter = responseInterpreter;
	}

	@Override
	public CommChannel createDuplicate()
	{
		return new LocalCommChannel( interpreter, listener );
	}

	public Interpreter interpreter()
	{
		return interpreter;
	}

	@Override
	protected CommMessage recvImpl()
		throws IOException
	{
		throw new IOException( "Unsupported operation" );
	}

	@Override
	protected void sendImpl( CommMessage message )
	{
		if ( this.responseInterpreter == null ){
			responseWaiters.put( message.id(), new CompletableFuture<>() );
		}
		interpreter.commCore().scheduleReceive( new CoLocalCommChannel( this, message ), listener.inputPort() );
	}

	@Override
	public CommMessage recvResponseFor( CommMessage request )
		throws IOException
	{
		final CompletableFuture< CommMessage> f = responseWaiters.get( request.id() );
		final CommMessage m;

		try {
			m = f.get();
		} catch( ExecutionException | InterruptedException e ) {
			throw new IOException( e );
		} finally {
			responseWaiters.remove( request.id() );
		}

		return m;
	}

	@Override
	public boolean isReady()
	{
		return responseWaiters.isEmpty() == false;
	}

	@Override
	protected void disposeForInputImpl()
		throws IOException
	{
		Interpreter.getInstance().commCore().registerForPolling( this );
	}

	@Override
	protected void closeImpl()
	{
	}
}
