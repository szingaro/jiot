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

package jolie;

import jolie.lang.Constants;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.JarURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import jolie.net.CommCore;
import jolie.net.ext.CommChannelFactory;
import jolie.net.ext.CommListenerFactory;
import jolie.net.ext.CommProtocolFactory;
import jolie.runtime.AndJarDeps;
import jolie.runtime.CanUseJars;
import jolie.runtime.JavaService;

/**
 * JolieClassLoader is used to resolve the loading of JOLIE extensions and external libraries.
 * @author Fabrizio Montesi
 */
public class JolieClassLoader extends URLClassLoader
{
	final private static Pattern extensionSplitPattern = Pattern.compile( ":" );

	final private Map< String, String > channelExtensionClassNames = new HashMap< String, String >();
	final private Map< String, String > listenerExtensionClassNames = new HashMap< String, String >();
	final private Map< String, String > protocolExtensionClassNames = new HashMap< String, String >();

	private void init( URL[] urls, Interpreter interpreter )
		throws IOException
	{
		for( URL url : urls ) {
			if ( "jar".equals( url.getProtocol() ) ) {
				checkJarForJolieExtensions( (JarURLConnection)url.openConnection(), interpreter );
			}
		}
	}
	
	public JolieClassLoader( URL[] urls, Interpreter interpreter, ClassLoader parent )
		throws IOException
	{
		super( urls, parent );
		init( urls, interpreter );
	}

	@Override
	public Class<?> loadClass( String className )
		throws ClassNotFoundException
	{
		Class<?> c = super.loadClass( className );
		if ( JavaService.class.isAssignableFrom( c ) ) {
			checkForJolieAnnotations( c );
		}
		return c;
	}

	private void checkForJolieAnnotations( Class<?> c )
	{
		AndJarDeps needsJars = c.getAnnotation( AndJarDeps.class );
		if ( needsJars != null ) {
			for( String filename : needsJars.value() ) {
				/*
				 * TODO jar unloading when service is unloaded?
				 * Consider other services needing the same jars in that.
				 */
				try {
					addJarResource( filename );
				} catch( MalformedURLException e ) {
				} catch( IOException e ) {
				}
			}
		}
		CanUseJars canUseJars = c.getAnnotation( CanUseJars.class );
		if ( canUseJars != null ) {
			for( String filename : canUseJars.value() ) {
				/*
				 * TODO jar unloading when service is unloaded?
				 * Consider other services needing the same jars in that.
				 */
				try {
					addJarResource( filename );
				} catch( Exception e ) {}
			}
		}
	}

	private Class<?> loadExtensionClass( String className )
		throws ClassNotFoundException
	{
		Class<?> c = super.loadClass( className );
		checkForJolieAnnotations( c );
		return c;
	}

	public CommChannelFactory createCommChannelFactory( String name, CommCore commCore )
		throws IOException
	{
		CommChannelFactory factory = null;
		String className = channelExtensionClassNames.get( name );
		if ( className != null ) {
			try {
				Class<?> c = loadExtensionClass( className );
				if ( CommChannelFactory.class.isAssignableFrom( c ) ) {
					Class< ? extends CommChannelFactory > fClass = (Class< ? extends CommChannelFactory >)c;
					factory = fClass.getConstructor( CommCore.class ).newInstance( commCore );
				}
			} catch( ClassNotFoundException e ) {
				throw new IOException( e );
			} catch( InstantiationException e ) {
				throw new IOException( e );
			} catch( IllegalAccessException e ) {
				throw new IOException( e );
			} catch( NoSuchMethodException e ) {
				throw new IOException( e );
			} catch( InvocationTargetException e ) {
				throw new IOException( e );
			}
		}

		return factory;
	}
	
	private void checkForChannelExtension( Attributes attrs )
		throws IOException
	{
		String extension = attrs.getValue( Constants.Manifest.ChannelExtension );
		if ( extension != null ) {
			String[] pair = extensionSplitPattern.split( extension );
			if ( pair.length == 2 ) {
				channelExtensionClassNames.put( pair[0], pair[1] );
			} else {
				throw new IOException( "Invalid extension definition found in manifest file: " + extension );
			}
		}
		/*String className = attrs.getValue( Constants.Manifest.ChannelExtension );
		if ( className != null ) {
			try {
				Class<?> c = loadExtensionClass( className );
				if ( CommChannelFactory.class.isAssignableFrom( c ) ) {
					Class< ? extends CommChannelFactory > fClass = (Class< ? extends CommChannelFactory >)c;
					Identifier pId = c.getAnnotation( Identifier.class );
					if ( pId == null ) {
						throw new IOException( "Class " + fClass.getName() + " does not specify a protocol identifier." );
					}
					CommChannelFactory factory = fClass.getConstructor( CommCore.class ).newInstance( interpreter.commCore() );
					interpreter.commCore().setCommChannelFactory( pId.value(), factory );
				}
			} catch( ClassNotFoundException e ) {
				throw new IOException( e );
			} catch( InstantiationException e ) {
				throw new IOException( e );
			} catch( IllegalAccessException e ) {
				throw new IOException( e );
			} catch( NoSuchMethodException e ) {
				throw new IOException( e );
			} catch( InvocationTargetException e ) {
				throw new IOException( e );
			}
		}*/
	}

	public CommListenerFactory createCommListenerFactory( String name, CommCore commCore )
		throws IOException
	{
		CommListenerFactory factory = null;
		String className = listenerExtensionClassNames.get( name );
		if ( className != null ) {
			try {
				Class<?> c = loadExtensionClass( className );
				if ( CommListenerFactory.class.isAssignableFrom( c ) ) {
					Class< ? extends CommListenerFactory > fClass = (Class< ? extends CommListenerFactory >)c;
					factory = fClass.getConstructor( CommCore.class ).newInstance( commCore );
				}
			} catch( ClassNotFoundException e ) {
				throw new IOException( e );
			} catch( InstantiationException e ) {
				throw new IOException( e );
			} catch( IllegalAccessException e ) {
				throw new IOException( e );
			} catch( NoSuchMethodException e ) {
				throw new IOException( e );
			} catch( InvocationTargetException e ) {
				throw new IOException( e );
			}
		}

		return factory;
	}

		private void checkForListenerExtension( Attributes attrs )
		throws IOException
	{
		String extension = attrs.getValue( Constants.Manifest.ListenerExtension );
		if ( extension != null ) {
			String[] pair = extensionSplitPattern.split( extension );
			if ( pair.length == 2 ) {
				listenerExtensionClassNames.put( pair[0], pair[1] );
			} else {
				throw new IOException( "Invalid extension definition found in manifest file: " + extension );
			}
		}
		/*String className = attrs.getValue( Constants.Manifest.ListenerExtension );
		if ( className != null ) {
			try {
				Class<?> c = loadExtensionClass( className );
				if ( CommListenerFactory.class.isAssignableFrom( c ) ) {
					Class< ? extends CommListenerFactory > fClass = (Class< ? extends CommListenerFactory >)c;
					Identifier pId = c.getAnnotation( Identifier.class );
					if ( pId == null ) {
						throw new IOException( "Class " + fClass.getName() + " does not specify a protocol identifier." );
					}
					CommListenerFactory factory = fClass.getConstructor( CommCore.class ).newInstance( interpreter.commCore() );
					interpreter.commCore().setCommListenerFactory( pId.value(), factory );
				}
			} catch( ClassNotFoundException e ) {
				throw new IOException( e );
			} catch( InstantiationException e ) {
				throw new IOException( e );
			} catch( IllegalAccessException e ) {
				throw new IOException( e );
			} catch( NoSuchMethodException e ) {
				throw new IOException( e );
			} catch( InvocationTargetException e ) {
				throw new IOException( e );
			}
		}*/
	}

	public CommProtocolFactory createCommProtocolFactory( String name, CommCore commCore )
		throws IOException
	{
		CommProtocolFactory factory = null;
		String className = protocolExtensionClassNames.get( name );
		if ( className != null ) {
			try {
				Class<?> c = loadExtensionClass( className );
				if ( CommProtocolFactory.class.isAssignableFrom( c ) ) {
					Class< ? extends CommProtocolFactory > fClass = (Class< ? extends CommProtocolFactory >)c;
					factory = fClass.getConstructor( CommCore.class ).newInstance( commCore );
				}
			} catch( ClassNotFoundException e ) {
				throw new IOException( e );
			} catch( InstantiationException e ) {
				throw new IOException( e );
			} catch( IllegalAccessException e ) {
				throw new IOException( e );
			} catch( NoSuchMethodException e ) {
				throw new IOException( e );
			} catch( InvocationTargetException e ) {
				throw new IOException( e );
			}
		}

		return factory;
	}

	private void checkForProtocolExtension( Attributes attrs )
		throws IOException
	{
		String extension = attrs.getValue( Constants.Manifest.ProtocolExtension );
		if ( extension != null ) {
			String[] pair = extensionSplitPattern.split( extension );
			if ( pair.length == 2 ) {
				protocolExtensionClassNames.put( pair[0], pair[1] );
			} else {
				throw new IOException( "Invalid extension definition found in manifest file: " + extension );
			}
		}
		/*String className = attrs.getValue( Constants.Manifest.ProtocolExtension );
		if ( className != null ) {
			try {
				Class<?> c = loadExtensionClass( className );
				if ( CommProtocolFactory.class.isAssignableFrom( c ) ) {
					Class< ? extends CommProtocolFactory > fClass = (Class< ? extends CommProtocolFactory >)c;
					Identifier pId = c.getAnnotation( Identifier.class );
					if ( pId == null ) {
						throw new IOException( "Class " + fClass.getName() + " does not specify a protocol identifier." );
					}
					interpreter.commCore().setCommProtocolFactory(
						pId.value(),
						fClass.getConstructor( CommCore.class ).newInstance( interpreter.commCore() )
					);
				}
			} catch( ClassNotFoundException e ) {
				throw new IOException( e );
			} catch( InstantiationException e ) {
				throw new IOException( e );
			} catch( IllegalAccessException e ) {
				throw new IOException( e );
			} catch( NoSuchMethodException e ) {
				throw new IOException( e );
			} catch( InvocationTargetException e ) {
				throw new IOException( e );
			}
		}*/
	}
	
	private void checkJarForJolieExtensions( JarURLConnection jarConnection, Interpreter interpreter )
		throws IOException
	{
		Manifest manifest = jarConnection.getManifest();
		if ( manifest != null ) {
			Attributes attrs = manifest.getMainAttributes();
			checkForChannelExtension( attrs );
			checkForListenerExtension( attrs );
			checkForProtocolExtension( attrs );
		}
	}
	
	/**
	 * Adds a Jar file to the pool of resource to look into for extensions.
	 * @param jarName the Jar filename
	 * @throws java.net.MalformedURLException
	 * @throws java.io.IOException if the Jar file could not be found or if jarName does not refer to a Jar file
	 */
	public void addJarResource( String jarName )
		throws MalformedURLException, IOException
	{
		URL url = findResource( jarName );
		if ( url == null ) {
			throw new IOException( "Resource not found: " + jarName );
		}
		addURL( new URL( "jar:" + url + "!/" ) );
		/*URLConnection urlConn = url.openConnection();
		if ( urlConn instanceof JarURLConnection ) {				
			checkJarJolieExtensions( (JarURLConnection)urlConn, null );
		} else {
			throw new IOException( "Jar file not found: " + jarName );
		}*/
	}
}
