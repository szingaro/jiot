/*******************************************************************************
 *   Copyright (C) 2017 by Martin Møller Andersen <maan511@student.sdu.dk>     *
 *   Copyright (C) 2017 by Fabrizio Montesi <famontesi@gmail.com>              *
 *   Copyright (C) 2017 by Saverio Giallorenzo <saverio.giallorenzo@gmail.com> *
 *                                                                             *
 *   This program is free software; you can redistribute it and/or modify      *
 *   it under the terms of the GNU Library General Public License as           *
 *   published by the Free Software Foundation; either version 2 of the        *
 *   License, or (at your option) any later version.                           *
 *                                                                             *
 *   This program is distributed in the hope that it will be useful,           *
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of            *
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the             *
 *   GNU General Public License for more details.                              *
 *                                                                             *
 *   You should have received a copy of the GNU Library General Public         *
 *   License along with this program; if not, write to the                     *
 *   Free Software Foundation, Inc.,                                           *
 *   59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.                 *
 *                                                                             *
 *   For details about the authors of this software, see the AUTHORS file.     *
 *******************************************************************************/
package jolie.net;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;
import jolie.net.ports.InputPort;
import jolie.net.ports.OutputPort;
import jolie.net.ports.Port;
import jolie.net.protocols.AsyncCommProtocol;

public class NioSocketCommChannel extends StreamingCommChannel
{

	public final static String CHANNEL_HANDLER_NAME = "STREAMING-CHANNEL-HANDLER";

	private Bootstrap bootstrap;
	private static final int SO_LINGER = 10000;
	protected CompletableFuture<CommMessage> waitingForMsg = null;
	protected StreamingCommChannelHandler commChannelHandler;
	private ChannelPipeline pipeline;

	public NioSocketCommChannel( URI location, AsyncCommProtocol protocol )
	{
		super( location, protocol );
		this.commChannelHandler = new StreamingCommChannelHandler( this );
	}

	public static NioSocketCommChannel createChannel( URI location, AsyncCommProtocol protocol, EventLoopGroup workerGroup, Port port )
	{
		NioSocketCommChannel channel = new NioSocketCommChannel( location, protocol );
		channel.bootstrap = new Bootstrap();
		channel.bootstrap.group( workerGroup )
			.channel( NioSocketChannel.class )
			.option( ChannelOption.SO_LINGER, SO_LINGER )
			.handler( new ChannelInitializer()
			{
				@Override
				protected void initChannel( Channel ch ) throws Exception
				{
					ChannelPipeline p = ch.pipeline();
					channel.setChannelPipeline( p );
					if ( port instanceof InputPort ) {
						channel.setParentInputPort( (InputPort) port );
					}
					if ( port instanceof OutputPort ) {
						channel.setParentOutputPort( (OutputPort) port );
					}
					protocol.setChannel( channel );
					protocol.setupPipeline( p );
					p.addLast( CHANNEL_HANDLER_NAME, channel.commChannelHandler );
				}
			}
			);
		return channel;
	}

	public ChannelFuture connect( URI location ) throws InterruptedException
	{
		return bootstrap
			.connect( new InetSocketAddress( location.getHost(), location.getPort() ) );
	}

	public ChannelFuture initChannel()
	{
		return bootstrap.register();
	}

	@Override
	protected CommMessage recvImpl() throws IOException
	{
		// This is blocking to integrate with existing CommCore and ExecutionThreads.
		try {
			if ( waitingForMsg != null ) {
				throw new UnsupportedOperationException( "Waiting for multiple messages is currently not supported!" );
			}
			waitingForMsg = new CompletableFuture<>();
			CommMessage msg = waitingForMsg.get();
			waitingForMsg = null;
			return msg;
		} catch( InterruptedException | ExecutionException ex ) {
			Logger.getLogger( NioSocketCommChannel.class.getName() ).log( Level.SEVERE, null, ex );
		}
		return null;
	}

	protected void completeRead( CommMessage message )
	{
		while( waitingForMsg == null ) {
			// spinlock
		}
		if ( waitingForMsg == null ) {
			throw new IllegalStateException( "No pending read to complete!" );
		} else {
			waitingForMsg.complete( message );
		}
	}

	@Override
	protected void sendImpl( CommMessage message ) throws IOException
	{
		try {
			commChannelHandler.write( message ).sync();
		} catch( InterruptedException ex ) {
			throw new IOException( ex );
		}
	}

	@Override
	protected void closeImpl() throws IOException
	{
		try {
			commChannelHandler.close().sync();
		} catch( InterruptedException ex ) {
			throw new IOException( ex );
		}
	}

	/**
	Return the current working pipeline of this (netty) channel.
	@return the current pipeline
	 */
	public ChannelPipeline getChannelPipeline()
	{
		return this.pipeline;
	}

	/**
	Set the current channel pipeline to one passed as a parameter from the user.
	@param newPipeline 
	 */
	public void setChannelPipeline( ChannelPipeline newPipeline )
	{
		this.pipeline = newPipeline;
	}

	@Override
	public StreamingCommChannelHandler getChannelHandler()
	{
		return commChannelHandler;
	}

}
