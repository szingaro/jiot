/********************************************************************************
 *   Copyright (C) 2008-2017 by Fabrizio Montesi <famontesi@gmail.com>         *
 *   Copyright (C) 2017 by Martin Møller Andersen <maan511@student.sdu.dk>     *
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

import com.google.gwt.user.client.rpc.SerializationException;
import com.google.gwt.user.server.rpc.RPC;
import com.google.gwt.user.server.rpc.RPCRequest;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.MessageToMessageCodec;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpMessage;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.util.AsciiString;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import jolie.Interpreter;
import jolie.js.JsUtils;
import jolie.lang.NativeType;
import jolie.net.http.HttpUtils;
import jolie.net.http.MultiPartFormDataParser;
import jolie.net.ports.Interface;
import jolie.net.protocols.AsyncCommProtocol;
import jolie.runtime.ByteArray;
import jolie.runtime.Value;
import jolie.runtime.ValueVector;
import jolie.runtime.VariablePath;
import jolie.runtime.typing.OneWayTypeDescription;
import jolie.runtime.typing.RequestResponseTypeDescription;
import jolie.runtime.typing.Type;
import jolie.runtime.typing.TypeCastingException;
import jolie.util.LocationParser;
import jolie.xml.XmlUtils;
import joliex.gwt.client.JolieService;
import joliex.gwt.server.JolieGWTConverter;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * HTTP protocol implementation
 *
 * @author Fabrizio Montesi 14 Nov 2012 - Saverio Giallorenzo - Fabrizio
 * Montesi: support for status codes
 */
public class HttpProtocol extends AsyncCommProtocol
{

	private static final HttpResponseStatus DEFAULT_STATUS_CODE = HttpResponseStatus.OK;
	private static final HttpResponseStatus DEFAULT_REDIRECTION_STATUS_CODE = HttpResponseStatus.SEE_OTHER;

	// default content type per RFC 2616#7.2.1
	private static final AsciiString DEFAULT_CONTENT_TYPE = HttpHeaderValues.APPLICATION_OCTET_STREAM;
	private static final String DEFAULT_FORMAT = "xml";
	private static final Map< Integer, String> statusCodeDescriptions = new HashMap<>();
	private static final Set< Integer> locationRequiredStatusCodes = new HashSet<>();

	static {
		locationRequiredStatusCodes.add( 301 );
		locationRequiredStatusCodes.add( 302 );
		locationRequiredStatusCodes.add( 303 );
		locationRequiredStatusCodes.add( 307 );
		locationRequiredStatusCodes.add( 308 );
	}

	static {
		// Initialise the HTTP Status code map.
		statusCodeDescriptions.put( 100, "Continue" );
		statusCodeDescriptions.put( 101, "Switching Protocols" );
		statusCodeDescriptions.put( 102, "Processing" );
		statusCodeDescriptions.put( 200, "OK" );
		statusCodeDescriptions.put( 201, "Created" );
		statusCodeDescriptions.put( 202, "Accepted" );
		statusCodeDescriptions.put( 203, "Non-Authoritative Information" );
		statusCodeDescriptions.put( 204, "No Content" );
		statusCodeDescriptions.put( 205, "Reset Content" );
		statusCodeDescriptions.put( 206, "Partial Content" );
		statusCodeDescriptions.put( 207, "Multi-Status" );
		statusCodeDescriptions.put( 208, "Already Reported" );
		statusCodeDescriptions.put( 226, "IM Used" );
		statusCodeDescriptions.put( 300, "Multiple Choices" );
		statusCodeDescriptions.put( 301, "Moved Permanently" );
		statusCodeDescriptions.put( 302, "Found" );
		statusCodeDescriptions.put( 303, "See Other" );
		statusCodeDescriptions.put( 304, "Not Modified" );
		statusCodeDescriptions.put( 305, "Use Proxy" );
		statusCodeDescriptions.put( 306, "Reserved" );
		statusCodeDescriptions.put( 307, "Temporary Redirect" );
		statusCodeDescriptions.put( 308, "Permanent Redirect" );
		statusCodeDescriptions.put( 400, "Bad Request" );
		statusCodeDescriptions.put( 401, "Unauthorized" );
		statusCodeDescriptions.put( 402, "Payment Required" );
		statusCodeDescriptions.put( 403, "Forbidden" );
		statusCodeDescriptions.put( 404, "Not Found" );
		statusCodeDescriptions.put( 405, "Method Not Allowed" );
		statusCodeDescriptions.put( 406, "Not Acceptable" );
		statusCodeDescriptions.put( 407, "Proxy Authentication Required" );
		statusCodeDescriptions.put( 408, "Request Timeout" );
		statusCodeDescriptions.put( 409, "Conflict" );
		statusCodeDescriptions.put( 410, "Gone" );
		statusCodeDescriptions.put( 411, "Length Required" );
		statusCodeDescriptions.put( 412, "Precondition Failed" );
		statusCodeDescriptions.put( 413, "Request Entity Too Large" );
		statusCodeDescriptions.put( 414, "Request-URI Too Long" );
		statusCodeDescriptions.put( 415, "Unsupported Media Type" );
		statusCodeDescriptions.put( 416, "Requested Range Not Satisfiable" );
		statusCodeDescriptions.put( 417, "Expectation Failed" );
		statusCodeDescriptions.put( 422, "Unprocessable Entity" );
		statusCodeDescriptions.put( 423, "Locked" );
		statusCodeDescriptions.put( 424, "Failed Dependency" );
		statusCodeDescriptions.put( 426, "Upgrade Required" );
		statusCodeDescriptions.put( 427, "Unassigned" );
		statusCodeDescriptions.put( 428, "Precondition Required" );
		statusCodeDescriptions.put( 429, "Too Many Requests" );
		statusCodeDescriptions.put( 430, "Unassigned" );
		statusCodeDescriptions.put( 431, "Request Header Fields Too Large" );
		statusCodeDescriptions.put( 500, "Internal Server Error" );
		statusCodeDescriptions.put( 501, "Not Implemented" );
		statusCodeDescriptions.put( 502, "Bad Gateway" );
		statusCodeDescriptions.put( 503, "Service Unavailable" );
		statusCodeDescriptions.put( 504, "Gateway Timeout" );
		statusCodeDescriptions.put( 505, "HTTP Version Not Supported" );
		statusCodeDescriptions.put( 507, "Insufficient Storage" );
		statusCodeDescriptions.put( 508, "Loop Detected" );
		statusCodeDescriptions.put( 509, "Unassigned" );
		statusCodeDescriptions.put( 510, "Not Extended" );
		statusCodeDescriptions.put( 511, "Network Authentication Required" );
	}

	public HttpProtocol(
		VariablePath configurationPath,
		URI uri,
		boolean inInputPort,
		TransformerFactory transformerFactory,
		DocumentBuilderFactory docBuilderFactory,
		DocumentBuilder docBuilder
	)
		throws TransformerConfigurationException
	{
		super( configurationPath );
		this.uri = uri;
		this.inInputPort = inInputPort;
		this.transformer = transformerFactory.newTransformer();
		this.docBuilderFactory = docBuilderFactory;
		this.docBuilder = docBuilder;

		transformer.setOutputProperty( OutputKeys.OMIT_XML_DECLARATION, "yes" );
		transformer.setOutputProperty( OutputKeys.INDENT, "no" );
	}

	@Override
	public void setupPipeline( ChannelPipeline pipeline )
	{
		if ( inInputPort ) {
			pipeline.addLast( new HttpServerCodec() );
			pipeline.addLast( new HttpContentCompressor() );
		} else {
			pipeline.addLast( new HttpClientCodec() );
			pipeline.addLast( new HttpContentDecompressor() );
		}

		pipeline.addLast( new HttpObjectAggregator( 65536 ) );
		pipeline.addLast( new HttpCommMessageCodec() );
	}

	public class HttpCommMessageCodec extends MessageToMessageCodec< FullHttpMessage, CommMessage>
	{

		@Override
		protected void encode( ChannelHandlerContext ctx, CommMessage message, List< Object> out )
			throws Exception
		{
			setSendExecutionThread( message.id() );
			FullHttpMessage msg = buildHttpMessage( message );
			out.add( msg );
		}

		@Override
		protected void decode( ChannelHandlerContext ctx, FullHttpMessage msg, List<Object> out )
			throws Exception
		{
			CommMessage message = recv_internal( msg );
//			System.out.println( Interpreter.getInstance().programFilename() + " received: " + message.toPrettyString() );
			out.add( message );
		}

	}

	private static class Parameters
	{

		private static final String ADD_HEADERS = "addHeader";
		private static final String ALIAS = "alias";
		private static final String CACHE_CONTROL = "cacheControl";
		private static final String CHARSET = "charset";
		private static final String COMPRESSION = "compression";
		private static final String COMPRESSION_TYPES = "compressionTypes";
		private static final String CONCURRENT = "concurrent";
		private static final String CONTENT_DISPOSITION = "contentDisposition";
		private static final String CONTENT_TRANSFER_ENCODING = "contentTransferEncoding";
		private static final String CONTENT_TYPE = "contentType";
		private static final String COOKIES = "cookies";
		private static final String DEBUG = "debug";
		private static final String DEFAULT_OPERATION = "default";
		private static final String DROP_URI_PATH = "dropURIPath";
		private static final String FORMAT = "format";
		private static final String HEADER_USER = "headers";
		private static final String HEADERS = "headers";
		private static final String HOST = "host";
		private static final String JSON_ENCODING = "json_encoding";
		private static final String KEEP_ALIVE = "keepAlive";
		private static final String METHOD = "method";
		private static final String MULTIPART_HEADERS = "multipartHeaders";
		private static final String REDIRECT = "redirect";
		private static final String REQUEST_COMPRESSION = "requestCompression";
		private static final String REQUEST_USER = "request";
		private static final String RESPONSE_USER = "response";
		private static final String RESPONSE_HEADER = "responseHeaders";
		private static final String STATUS_CODE = "statusCode";
		private static final String USER_AGENT = "userAgent";

		private static class MultiPartHeaders
		{

			private static final String FILENAME = "filename";
		}
	}

	private static class Headers
	{

		private static final String JOLIE_MESSAGE_ID = "X-Jolie-MessageID";
	}

	private static class ContentTypes
	{

		private static final AsciiString APPLICATION_JSON = new AsciiString( "application/json" );
		private static final AsciiString TEXT_HTML = new AsciiString( "text/html" );
		private static final AsciiString TEXT_XML = new AsciiString( "text/xml" );
		private static final AsciiString TEXT_GWT_RPC = new AsciiString( "text/x-gwt-rpc" );
	}

	private String inputId = null;
	private final Transformer transformer;
	private final DocumentBuilderFactory docBuilderFactory;
	private final DocumentBuilder docBuilder;
	private final URI uri;
	private final boolean inInputPort;
	private MultiPartFormDataParser multiPartFormDataParser = null;

	@Override
	public String name()
	{
		return "http";
	}

	@Override
	public boolean isThreadSafe()
	{
		return 
			checkBooleanParameter( Parameters.CONCURRENT, false ) &&
			checkBooleanParameter( Parameters.KEEP_ALIVE, true  ); // if the channel is set to be closed, then it is not threadSafe
	}

	public String getMultipartHeaderForPart( String operationName, String partName )
	{
		if ( hasOperationSpecificParameter( operationName, Parameters.MULTIPART_HEADERS ) ) {
			Value v = getOperationSpecificParameterFirstValue( operationName, Parameters.MULTIPART_HEADERS );
			if ( v.hasChildren( partName ) ) {
				v = v.getFirstChild( partName );
				if ( v.hasChildren( Parameters.MultiPartHeaders.FILENAME ) ) {
					v = v.getFirstChild( Parameters.MultiPartHeaders.FILENAME );
					return v.strValue();
				}
			}
		}
		return null;
	}

	private final static String BOUNDARY = "----jol13h77p77bound4r155";

	private void send_appendCookies( CommMessage message, String hostname, HttpHeaders headers )
	{
		Value cookieParam = null;
		if ( hasOperationSpecificParameter( message.operationName(), Parameters.COOKIES ) ) {
			cookieParam = getOperationSpecificParameterFirstValue( message.operationName(), Parameters.COOKIES );
		} else if ( hasParameter( Parameters.COOKIES ) ) {
			cookieParam = getParameterFirstValue( Parameters.COOKIES );
		}

		if ( cookieParam != null ) {
			Value cookieConfig;
			String domain;
			StringBuilder cookieSB = new StringBuilder();
			for( Entry< String, ValueVector> entry : cookieParam.children().entrySet() ) {
				cookieConfig = entry.getValue().first();
				if ( message.value().hasChildren( cookieConfig.strValue() ) ) {
					domain = cookieConfig.hasChildren( "domain" ) ? cookieConfig.getFirstChild( "domain" ).strValue() : "";
					if ( domain.isEmpty() || hostname.endsWith( domain ) ) {
						cookieSB.append(
							ServerCookieEncoder.STRICT.encode(
								entry.getKey(),
								message.value().getFirstChild( cookieConfig.strValue() ).strValue()
							)
						).append( ";" );
					}
				}
			}
			if ( cookieSB.length() > 0 ) {
				headers.add( HttpHeaderNames.COOKIE, cookieSB.toString() );
			}
		}
	}

	private void send_appendSetCookieHeader( CommMessage message, HttpHeaders headers )
	{
		Value cookieParam = null;
		if ( hasOperationSpecificParameter( message.operationName(), Parameters.COOKIES ) ) {
			cookieParam = getOperationSpecificParameterFirstValue( message.operationName(), Parameters.COOKIES );
		} else if ( hasParameter( Parameters.COOKIES ) ) {
			cookieParam = getParameterFirstValue( Parameters.COOKIES );
		}
		if ( cookieParam != null ) {
			Value cookieConfig;
			for( Entry< String, ValueVector> entry : cookieParam.children().entrySet() ) {
				cookieConfig = entry.getValue().first();
				if ( message.value().hasChildren( cookieConfig.strValue() ) ) {
					Cookie cookie = new DefaultCookie(
						entry.getKey(),
						message.value().getFirstChild( cookieConfig.strValue() ).strValue()
					);

					if ( cookieConfig.hasChildren( "domain" ) ) {
						cookie.setDomain( cookieConfig.getFirstChild( "domain" ).strValue() );
					}
					if ( cookieConfig.hasChildren( "path" ) ) {
						cookie.setPath( cookieConfig.getFirstChild( "path" ).strValue() );
					}
					if ( cookieConfig.hasChildren( "expires" ) ) {
						cookie.setMaxAge( cookieConfig.getFirstChild( "expires" ).longValue() );
					}
					if ( cookieConfig.hasChildren( "secure" ) && cookieConfig.getFirstChild( "secure" ).intValue() > 0 ) {
						cookie.setSecure( true );
					}

					headers.add( HttpHeaderNames.SET_COOKIE, ServerCookieEncoder.STRICT.encode( cookie ) );
				}
			}
		}
	}

	private String encoding = null;
	private String responseFormat = null;
	//private final boolean headRequest = false;

	private static void send_appendQuerystring( Value value, StringBuilder headerBuilder )
		throws IOException
	{
		if ( !value.children().isEmpty() ) {
			headerBuilder.append( '?' );
			for( Entry< String, ValueVector> entry : value.children().entrySet() ) {
				for( Value v : entry.getValue() ) {
					headerBuilder
						.append( URLEncoder.encode( entry.getKey(), HttpUtils.URL_DECODER_ENC ) )
						.append( '=' )
						.append( URLEncoder.encode( v.strValue(), HttpUtils.URL_DECODER_ENC ) )
						.append( '&' );
				}
			}
		}
	}

	private void send_appendJsonQueryString( CommMessage message, StringBuilder headerBuilder )
		throws IOException
	{
		if ( message.value().isDefined() || message.value().hasChildren() ) {
			headerBuilder.append( "?" );
			StringBuilder builder = new StringBuilder();
			JsUtils.valueToJsonString( message.value(), true, getSendType( message ), builder );
			headerBuilder.append( URLEncoder.encode( builder.toString(), HttpUtils.URL_DECODER_ENC ) );
		}
	}

	private static void send_appendParsedAlias( String alias, Value value, StringBuilder headerBuilder )
		throws IOException
	{
		int offset = 0;
		List< String> aliasKeys = new ArrayList<>();
		String currStrValue;
		String currKey;
		StringBuilder result = new StringBuilder( alias );
		Matcher m = Pattern.compile( "%(!)?\\{[^\\}]*\\}" ).matcher( alias );

		while( m.find() ) {
			if ( m.group( 1 ) == null ) { // ! is missing after %: We have to use URLEncoder
				currKey = alias.substring( m.start() + 2, m.end() - 1 );
				if ( "$".equals( currKey ) ) {
					currStrValue = URLEncoder.encode( value.strValue(), HttpUtils.URL_DECODER_ENC );
				} else {
					currStrValue = URLEncoder.encode( value.getFirstChild( currKey ).strValue(), HttpUtils.URL_DECODER_ENC );
					aliasKeys.add( currKey );
				}
			} else { // ! is given after %: We have to insert the string raw
				currKey = alias.substring( m.start() + 3, m.end() - 1 );
				if ( "$".equals( currKey ) ) {
					currStrValue = value.strValue();
				} else {
					currStrValue = value.getFirstChild( currKey ).strValue();
					aliasKeys.add( currKey );
				}
			}

			result.replace(
				m.start() + offset, m.end() + offset,
				currStrValue
			);
			offset += currStrValue.length() - 3 - currKey.length();
		}
		// removing used keys
		for( String aliasKey : aliasKeys ) {
			value.children().remove( aliasKey );
		}
		headerBuilder.append( result );
	}

	private String send_getFormat()
	{
		String format = DEFAULT_FORMAT;
		if ( inInputPort && responseFormat != null ) {
			format = responseFormat;
			responseFormat = null;
		} else if ( hasParameter( Parameters.FORMAT ) ) {
			format = getStringParameter( Parameters.FORMAT );
		}
		return format;
	}

	private static class EncodedContent
	{

		private ByteArray content = null;
		private AsciiString contentType = DEFAULT_CONTENT_TYPE;
		private String contentDisposition = "";
	}

	private EncodedContent send_encodeContent( CommMessage message, HttpMethod method, String charset, String format )
		throws IOException
	{
		EncodedContent ret = new EncodedContent();
		if ( inInputPort == false && method == HttpMethod.GET ) {
			// We are building a GET request
			return ret;
		}

		if ( null != format ) {
			switch( format ) {
				case "xml":
					ret.contentType = ContentTypes.TEXT_XML;
					Document doc = docBuilder.newDocument();
					Element root = doc.createElement( message.operationName() + ((inInputPort) ? "Response" : "") );
					doc.appendChild( root );
					if ( message.isFault() ) {
						Element faultElement = doc.createElement( message.fault().faultName() );
						root.appendChild( faultElement );
						XmlUtils.valueToDocument( message.fault().value(), faultElement, doc );
					} else {
						XmlUtils.valueToDocument( message.value(), root, doc );
					}
					Source src = new DOMSource( doc );
					ByteArrayOutputStream tmpStream = new ByteArrayOutputStream();
					Result dest = new StreamResult( tmpStream );
					transformer.setOutputProperty( OutputKeys.ENCODING, charset );
					try {
						transformer.transform( src, dest );
					} catch( TransformerException e ) {
						throw new IOException( e );
					}
					ret.content = new ByteArray( tmpStream.toByteArray() );
					break;
				case "binary":
					ret.contentType = HttpHeaderValues.APPLICATION_OCTET_STREAM;
					ret.content = message.value().byteArrayValue();
					break;
				case "html":
					ret.contentType = ContentTypes.TEXT_HTML;
					if ( message.isFault() ) {
						StringBuilder builder = new StringBuilder();
						builder.append( "<html><head><title>" );
						builder.append( message.fault().faultName() );
						builder.append( "</title></head><body>" );
						builder.append( message.fault().value().strValue() );
						builder.append( "</body></html>" );
						ret.content = new ByteArray( builder.toString().getBytes( charset ) );
					} else {
						ret.content = new ByteArray( message.value().strValue().getBytes( charset ) );
					}
					break;
				case "multipart/form-data": {
					ret.contentType = HttpHeaderValues.MULTIPART_FORM_DATA.concat( new AsciiString( "; boundary=" + BOUNDARY ) );
					ByteArrayOutputStream bStream = new ByteArrayOutputStream();
					StringBuilder builder = new StringBuilder();
					for( Entry< String, ValueVector> entry : message.value().children().entrySet() ) {
						if ( !entry.getKey().startsWith( "@" ) ) {
							builder.append( "--" ).append( BOUNDARY ).append( HttpUtils.CRLF );
							builder.append( "Content-Disposition: form-data; name=\"" ).append( entry.getKey() ).append( '\"' );
							boolean isBinary = false;
							if ( hasOperationSpecificParameter( message.operationName(), Parameters.MULTIPART_HEADERS ) ) {
								Value specOpParam = getOperationSpecificParameterFirstValue(
									message.operationName(),
									Parameters.MULTIPART_HEADERS
								);
								if ( specOpParam.hasChildren( "partName" ) ) {
									ValueVector partNames = specOpParam.getChildren( "partName" );
									for( int p = 0; p < partNames.size(); p++ ) {
										if ( partNames.get( p ).hasChildren( "part" ) ) {
											if ( partNames.get( p ).getFirstChild( "part" ).strValue().equals( entry.getKey() ) ) {
												isBinary = true;
												if ( partNames.get( p ).hasChildren( "filename" ) ) {
													builder.append( "; filename=\"" )
														.append( partNames.get( p ).getFirstChild( "filename" )
															.strValue() ).append( "\"" );
												}
												if ( partNames.get( p ).hasChildren( "contentType" ) ) {
													builder.append( HttpUtils.CRLF ).append( "Content-Type:" )
														.append( partNames.get( p ).getFirstChild( "contentType" ).strValue() );
												}
											}
										}
									}
								}
							}

							builder.append( HttpUtils.CRLF ).append( HttpUtils.CRLF );
							if ( isBinary ) {
								bStream.write( builder.toString().getBytes( charset ) );
								bStream.write( entry.getValue().first().byteArrayValue().getBytes() );
								builder.delete( 0, builder.length() - 1 );
								builder.append( HttpUtils.CRLF );
							} else {
								builder.append( entry.getValue().first().strValue() ).append( HttpUtils.CRLF );
							}
						}
					}
					builder.append( "--" + BOUNDARY + "--" );
					bStream.write( builder.toString().getBytes( charset ) );
					ret.content = new ByteArray( bStream.toByteArray() );
					break;
				}
				case "x-www-form-urlencoded": {
					ret.contentType = HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED;
					Iterator< Entry< String, ValueVector>> it
						= message.value().children().entrySet().iterator();
					StringBuilder builder = new StringBuilder();
					if ( message.isFault() ) {
						builder.append( "faultName=" );
						builder.append( URLEncoder.encode( message.fault().faultName(), HttpUtils.URL_DECODER_ENC ) );
						builder.append( "&data=" );
						builder.append( URLEncoder.encode( message.fault().value().strValue(), HttpUtils.URL_DECODER_ENC ) );
					} else {
						Entry< String, ValueVector> entry;
						while( it.hasNext() ) {
							entry = it.next();
							builder.append( URLEncoder.encode( entry.getKey(), HttpUtils.URL_DECODER_ENC ) )
								.append( "=" )
								.append( URLEncoder.encode( entry.getValue().first().strValue(), HttpUtils.URL_DECODER_ENC ) );
							if ( it.hasNext() ) {
								builder.append( '&' );
							}
						}
					}
					ret.content = new ByteArray( builder.toString().getBytes( charset ) );
					break;
				}
				case "text/x-gwt-rpc":
					ret.contentType = ContentTypes.TEXT_GWT_RPC;
					try {
						if ( inInputPort ) { // It's a response
							if ( message.isFault() ) {
								ret.content = new ByteArray(
									RPC.encodeResponseForFailure(
										JolieService.class.getMethods()[ 0 ],
										JolieGWTConverter.jolieToGwtFault( message.fault() )
									).getBytes( charset )
								);
							} else {
								joliex.gwt.client.Value v = new joliex.gwt.client.Value();
								JolieGWTConverter.jolieToGwtValue( message.value(), v );
								ret.content = new ByteArray(
									RPC.encodeResponseForSuccess( JolieService.class.getMethods()[ 0 ], v ).getBytes( charset )
								);
							}
						} else { // It's a request
							throw new IOException( "Sending requests to a GWT server is currently unsupported." );
						}
					} catch( SerializationException e ) {
						throw new IOException( e );
					}
					break;
				case "json":
					ret.contentType = ContentTypes.APPLICATION_JSON;
					StringBuilder jsonStringBuilder = new StringBuilder();
					if ( message.isFault() ) {
						Value error = message.value().getFirstChild( "error" );
						error.getFirstChild( "code" ).setValue( -32000 );
						error.getFirstChild( "message" ).setValue( message.fault().faultName() );
						error.getChildren( "data" ).set( 0, message.fault().value() );
						JsUtils.faultValueToJsonString( message.value(), getSendType( message ), jsonStringBuilder );
					} else {
						JsUtils.valueToJsonString( message.value(), true, getSendType( message ), jsonStringBuilder );
					}
					ret.content = new ByteArray( jsonStringBuilder.toString().getBytes( charset ) );
					break;
				case "raw":
					ret.contentType = HttpHeaderValues.TEXT_PLAIN;
					if ( message.isFault() ) {
						ret.content = new ByteArray( message.fault().value().strValue().getBytes( charset ) );
					} else {
						ret.content = new ByteArray( message.value().strValue().getBytes( charset ) );
					}
					break;
				default:
					break;
			}
		}
		return ret;
	}

	private static boolean isLocationNeeded( int statusCode )
	{
		return locationRequiredStatusCodes.contains( statusCode );
	}

	private void send_appendResponseUserHeader( CommMessage message, HttpHeaders headers )
	{
		Value responseHeaderParameters = null;
		if ( hasOperationSpecificParameter( message.operationName(), Parameters.RESPONSE_USER ) ) {
			responseHeaderParameters = getOperationSpecificParameterFirstValue(
				message.operationName(),
				Parameters.RESPONSE_USER
			);
			if ( (responseHeaderParameters != null)
				&& (responseHeaderParameters.hasChildren( Parameters.HEADER_USER )) ) {
				for( Entry< String, ValueVector> entry : responseHeaderParameters
					.getFirstChild( Parameters.HEADER_USER ).children().entrySet() ) {
					headers.add( entry.getKey(), entry.getValue().first().strValue() );
				}
			}
		}

		responseHeaderParameters = null;
		if ( hasParameter( Parameters.RESPONSE_USER ) ) {
			responseHeaderParameters = getParameterFirstValue( Parameters.RESPONSE_USER );

			if ( (responseHeaderParameters != null)
				&& (responseHeaderParameters.hasChildren( Parameters.HEADER_USER )) ) {
				for( Entry< String, ValueVector> entry : responseHeaderParameters
					.getFirstChild( Parameters.HEADER_USER ).children().entrySet() ) {
					headers.add( entry.getKey(), entry.getValue().first().strValue() );
				}
			}
		}
	}

	private HttpResponseStatus send_getResponseStatus( CommMessage message )
	{
		HttpResponseStatus statusCode = DEFAULT_STATUS_CODE;
		if ( hasParameter( Parameters.STATUS_CODE ) ) {
			statusCode = HttpResponseStatus.valueOf( getIntParameter( Parameters.STATUS_CODE ) );

			/*
			if ( !statusCodeDescriptions.containsKey( statusCode ) ) {
				Interpreter.getInstance().logWarning( "HTTP protocol for operation " +
					message.operationName() +
					" is sending a message with status code " +
					statusCode +
					", which is not in the HTTP specifications."
				);
				statusDescription = "Internal Server Error";
			} else */
			if ( isLocationNeeded( statusCode.code() ) && !hasParameter( Parameters.REDIRECT ) ) {
				// if statusCode is a redirection code, location parameter is needed
				Interpreter.getInstance().logWarning( "HTTP protocol for operation "
					+ message.operationName()
					+ " is sending a message with status code "
					+ statusCode
					+ ", which expects a redirect parameter but the latter is not set."
				);
			}
		} else if ( hasParameter( Parameters.REDIRECT ) ) {
			statusCode = DEFAULT_REDIRECTION_STATUS_CODE;
		}
		return statusCode;
	}

	private void send_appendResponseHeaders( CommMessage message, HttpHeaders headers )
	{
		// if redirect has been set, the redirect location parameter is set
		if ( hasParameter( Parameters.REDIRECT ) ) {
			headers.add( HttpHeaderNames.LOCATION, getStringParameter( Parameters.REDIRECT ) );
		}

		send_appendSetCookieHeader( message, headers );
		headers.add( HttpHeaderNames.SERVER, "Jolie" );

		StringBuilder cacheControlHeader = new StringBuilder();
		if ( hasParameter( Parameters.CACHE_CONTROL ) ) {
			Value cacheControl = getParameterFirstValue( Parameters.CACHE_CONTROL );
			if ( cacheControl.hasChildren( "maxAge" ) ) {
				cacheControlHeader.append( "max-age=" ).append( cacheControl.getFirstChild( "maxAge" ).intValue() );
			}
		}
		if ( cacheControlHeader.length() > 0 ) {
			headers.add( HttpHeaderNames.CACHE_CONTROL, cacheControlHeader.toString() );
		}
	}

	private static void send_appendAuthorizationHeader( CommMessage message, HttpHeaders headers )
	{
		if ( message.value().hasChildren( jolie.lang.Constants.Predefined.HTTP_BASIC_AUTHENTICATION.token().content() ) ) {
			Value v = message.value().getFirstChild( jolie.lang.Constants.Predefined.HTTP_BASIC_AUTHENTICATION.token().content() );
			//String realm = v.getFirstChild( "realm" ).strValue();
			String userpass = v.getFirstChild( "userid" ).strValue() + ":" + v.getFirstChild( "password" ).strValue();
			Base64.Encoder encoder = Base64.getEncoder();
			userpass = encoder.encodeToString( userpass.getBytes() );
			headers.add( HttpHeaderNames.AUTHORIZATION, "Basic " + userpass );
		}
	}

	private void send_appendRequestUserHeader( CommMessage message, HttpHeaders headers )
	{
		Value responseHeaderParameters = null;
		if ( hasOperationSpecificParameter( message.operationName(), Parameters.REQUEST_USER ) ) {
			responseHeaderParameters = getOperationSpecificParameterFirstValue( message.operationName(), Parameters.RESPONSE_USER );
			if ( (responseHeaderParameters != null)
				&& responseHeaderParameters.hasChildren( Parameters.HEADER_USER ) ) {
				for( Entry< String, ValueVector> entry
					: responseHeaderParameters.getFirstChild( Parameters.HEADER_USER ).children().entrySet() ) {
					headers.add( entry.getKey(), entry.getValue().first().strValue() );
				}
			}
		}

		responseHeaderParameters = null;
		if ( hasParameter( Parameters.RESPONSE_USER ) ) {
			responseHeaderParameters = getParameterFirstValue( Parameters.REQUEST_USER );
			if ( (responseHeaderParameters != null) && (responseHeaderParameters.hasChildren( Parameters.HEADER_USER )) ) {
				for( Entry< String, ValueVector> entry
					: responseHeaderParameters.getFirstChild( Parameters.HEADER_USER ).children().entrySet() ) {
					headers.add( entry.getKey(), entry.getValue().first().strValue() );
				}
			}
		}
	}

	private void send_appendHeader( HttpHeaders headers )
	{
		Value v = getParameterFirstValue( Parameters.ADD_HEADERS );
		if ( v != null ) {
			if ( v.hasChildren( "header" ) ) {
				for( Value head : v.getChildren( "header" ) ) {
					headers.add( head.strValue(), head.getFirstChild( "value" ).strValue() );
				}
			}
		}
	}

	private HttpMethod send_getRequestMethod( CommMessage message )
		throws IOException
	{
		HttpMethod method
			= hasOperationSpecificParameter( message.operationName(), Parameters.METHOD )
			? HttpMethod.valueOf( getOperationSpecificStringParameter( message.operationName(), Parameters.METHOD ) )
			: hasParameterValue( Parameters.METHOD )
			? HttpMethod.valueOf( getStringParameter( Parameters.METHOD ) )
			: HttpMethod.POST;
		return method;
	}

	private String send_getUri( CommMessage message, HttpMethod method, String qsFormat )
		throws IOException
	{
		String path = uri.getRawPath();
		StringBuilder sb = new StringBuilder();
		if ( path == null || path.isEmpty() || checkBooleanParameter( Parameters.DROP_URI_PATH, false ) ) {
			sb.append( '/' );
		} else {
			if ( path.charAt( 0 ) != '/' ) {
				sb.append( '/' );
			}
			sb.append( path );
		}

		if ( hasOperationSpecificParameter( message.operationName(), Parameters.ALIAS ) ) {
			String alias = getOperationSpecificStringParameter( message.operationName(), Parameters.ALIAS );
			send_appendParsedAlias( alias, message.value(), sb );
		} else {
			sb.append( message.operationName() );
		}

		if ( method == HttpMethod.GET ) {
			if ( qsFormat.equals( "json" ) ) {
				send_appendJsonQueryString( message, sb );
			} else {
				send_appendQuerystring( message.value(), sb );
			}
		}
		return sb.toString();
	}

	private void send_appendRequestHeaders( CommMessage message, HttpHeaders headers )
		throws IOException
	{
		headers.add( HttpHeaderNames.HOST, uri.getHost() );
		send_appendCookies( message, uri.getHost(), headers );
		send_appendAuthorizationHeader( message, headers );
		if ( checkBooleanParameter( Parameters.COMPRESSION, false ) ) {
			String requestCompression = getStringParameter( Parameters.REQUEST_COMPRESSION );
			if ( requestCompression.equals( "gzip" ) || requestCompression.equals( "deflate" ) ) {
				encoding = requestCompression;
				headers.add( HttpHeaderNames.ACCEPT_ENCODING, encoding );
			} else {
				headers.add( HttpHeaderNames.ACCEPT_ENCODING, "gzip, deflate" );
			}
		}
		send_appendHeader( headers );
	}

	private void send_appendGenericHeaders(
		CommMessage message,
		EncodedContent encodedContent,
		String charset,
		HttpHeaders headers
	)
		throws IOException
	{
		if ( !checkBooleanParameter( Parameters.KEEP_ALIVE, true ) ) {
			channel().setToBeClosed( true );
			headers.add( HttpHeaderNames.CONNECTION, "close" );
		} else {
			channel().setToBeClosed( false );
		}

		if ( isThreadSafe() ) {
			headers.add( Headers.JOLIE_MESSAGE_ID, message.id() );
		}
		
		AsciiString contentType = new AsciiString( getStringParameter( Parameters.CONTENT_TYPE ) );
		if ( contentType.length() > 0 ) {
			encodedContent.contentType = contentType.toLowerCase();
		}
		if ( charset != null ) {
			encodedContent.contentType.concat( new AsciiString( "charset=" + charset.toLowerCase() ) );
		}
		headers.add( HttpHeaderNames.CONTENT_TYPE, encodedContent.contentType );

		if ( encodedContent.content != null ) {
			String transferEncoding = getStringParameter( Parameters.CONTENT_TRANSFER_ENCODING );
			if ( transferEncoding.length() > 0 ) {
				headers.add( HttpHeaderNames.CONTENT_TRANSFER_ENCODING, transferEncoding );
			}

			String contentDisposition = getStringParameter( Parameters.CONTENT_DISPOSITION );
			if ( contentDisposition.length() > 0 ) {
				encodedContent.contentDisposition = contentDisposition;
				headers.add( HttpHeaderNames.CONTENT_DISPOSITION, encodedContent.contentDisposition );
			}

			boolean compression = encoding != null && checkBooleanParameter( Parameters.COMPRESSION, false );
			String compressionTypes = getStringParameter(
				Parameters.COMPRESSION_TYPES,
				"text/html text/css text/plain text/xml text/x-js text/x-gwt-rpc application/json "
				+ "application/javascript application/x-www-form-urlencoded application/xhtml+xml "
				+ "application/xml"
			).toLowerCase();
			if ( compression && !compressionTypes.equals( "*" ) && !compressionTypes.contains( encodedContent.contentType ) ) {
				compression = false;
			}
			if ( compression ) {
				//encodedContent.content = HttpUtils.encode( encoding, encodedContent.content, headers );
				// RFC 7231 section-5.3.4 introduced the "*" (any) option, we opt for gzip as a sane default
				if ( encoding.contains( "gzip" ) || encoding.contains( "*" ) ) {
					headers.add( HttpHeaderNames.CONTENT_ENCODING, "gzip" );
				} else if ( encoding.contains( "deflate" ) ) {
					headers.add( HttpHeaderNames.CONTENT_ENCODING, "deflate" );
				}
			}

			headers.add( HttpHeaderNames.CONTENT_LENGTH, encodedContent.content.size() );
		} else {
			headers.add( HttpHeaderNames.CONTENT_LENGTH, 0 );
		}
	}

	private void send_logDebugInfo( HttpHeaders headers, EncodedContent encodedContent, String charset )
		throws IOException
	{
		if ( checkBooleanParameter( Parameters.DEBUG ) ) {
			StringBuilder debugSB = new StringBuilder();
			debugSB.append( "[HTTP debug] Sending:\n" );
			for( Entry< String, String> header : headers.entries() ) {
				debugSB.append( header.getKey() ).append( ": " ).append( header.getValue() ).append( "\n" );
			}
			if ( getParameterVector( Parameters.DEBUG ).first().getFirstChild( "showContent" ).intValue() > 0
				&& encodedContent.content != null ) {
				debugSB.append( encodedContent.content.toString( charset ) );
			}
			Interpreter.getInstance().logInfo( debugSB.toString() );
		}
	}

	public FullHttpMessage buildHttpMessage( CommMessage message )
		throws IOException
	{
		HttpMethod method = send_getRequestMethod( message );
		String charset = HttpUtils.getCharset( getStringParameter( Parameters.CHARSET, "iso-8859-1" ), null );
		String format = send_getFormat();
		AsciiString contentType = null;
		// EncodedContent encodedContent = send_encodeContent( message, method, charset, format );

		FullHttpMessage httpMessage;
		if ( inInputPort ) {
			// We're responding to a request
			FullHttpResponse response = new DefaultFullHttpResponse(
				HttpVersion.HTTP_1_1, send_getResponseStatus( message ) );

			send_appendResponseHeaders( message, response.headers() );
			send_appendResponseUserHeader( message, response.headers() );
			httpMessage = response;
		} else {
			// We're sending a notification or a solicit
			String qsFormat = "";
			if ( method == HttpMethod.GET && getParameterFirstValue( Parameters.METHOD ).hasChildren( "queryFormat" ) ) {
				if ( getParameterFirstValue( Parameters.METHOD ).getFirstChild( "queryFormat" ).strValue().equals( "json" ) ) {
					qsFormat = format = "json";
					contentType = ContentTypes.APPLICATION_JSON;
				}
			}
			FullHttpRequest request = new DefaultFullHttpRequest( HttpVersion.HTTP_1_1, method, send_getUri( message, method, qsFormat ) );
			send_appendRequestHeaders( message, request.headers() );
			send_appendRequestUserHeader( message, request.headers() );
			httpMessage = request;

		}

		EncodedContent encodedContent = send_encodeContent( message, method, charset, format );
		if ( contentType != null ) {
			encodedContent.contentType = contentType;
		}

		send_appendGenericHeaders( message, encodedContent, charset, httpMessage.headers() );

		send_logDebugInfo( httpMessage.headers(), encodedContent, charset );
		inputId = message.operationName();

		httpMessage.content().writeBytes( encodedContent.content.getBytes() );

		//ostream.write( headerBuilder.toString().getBytes( HttpUtils.URL_DECODER_ENC ) );
		//if ( encodedContent.content != null && !headRequest ) {
		//	ostream.write( encodedContent.content.getBytes() );
		//}
		//headRequest = false;
		return httpMessage;
	}

	private void parseXML( FullHttpMessage message, Value value, String charset )
		throws IOException
	{
		try {
			if ( message.content().readableBytes() > 0 ) {
				DocumentBuilder builder = docBuilderFactory.newDocumentBuilder();
				// TODO this updates readerindex
				InputSource src = new InputSource( new ByteBufInputStream( message.content() ) );
				src.setEncoding( charset );
				Document doc = builder.parse( src );
				XmlUtils.documentToValue( doc, value,false );
			}
		} catch( ParserConfigurationException | SAXException pce ) {
			throw new IOException( pce );
		}
	}

	private static void parseJson( FullHttpMessage message, Value value, boolean strictEncoding, String charset )
		throws IOException
	{
		JsUtils.parseJsonIntoValue( new InputStreamReader( new ByteBufInputStream( message.content() ), charset ), value, strictEncoding );
	}

	private static void parseForm( FullHttpMessage message, Value value, String charset )
		throws IOException
	{
		String content = message.content().toString( Charset.forName( charset ) );
		QueryStringDecoder decoder = new QueryStringDecoder( content, false );
		for( Entry<String, List<String>> parameter : decoder.parameters().entrySet() ) {
			for( String parameterValue : parameter.getValue() ) {
				value.getChildren( parameter.getKey() ).first().setValue( parameterValue );
			}
		}
	}

	private void parseMultiPartFormData( FullHttpMessage message, Value value, String charset )
		throws IOException
	{
		multiPartFormDataParser = new MultiPartFormDataParser( message, value );
		multiPartFormDataParser.parse();
	}

	private static String parseGWTRPC( FullHttpMessage message, Value value, String charset )
		throws IOException
	{
		RPCRequest request = RPC.decodeRequest( message.content().toString( Charset.forName( charset ) ) );
		String operationName = (String) request.getParameters()[ 0 ];
		joliex.gwt.client.Value requestValue = (joliex.gwt.client.Value) request.getParameters()[ 1 ];
		JolieGWTConverter.gwtToJolieValue( requestValue, value );
		return operationName;
	}

	private void recv_checkForSetCookie( FullHttpResponse message, Value value )
		throws IOException
	{
		if ( hasParameter( Parameters.COOKIES ) ) {
			String type;
			Value cookies = getParameterFirstValue( Parameters.COOKIES );
			Value cookieConfig;
			Value v;
			ServerCookieDecoder decoder = ServerCookieDecoder.STRICT;
			for( Cookie cookie : decoder.decode( message.headers().get( HttpHeaderNames.SET_COOKIE, "" ) ) ) {
				if ( cookies.hasChildren( cookie.name() ) ) {
					cookieConfig = cookies.getFirstChild( cookie.name() );
					if ( cookieConfig.isString() ) {
						v = value.getFirstChild( cookieConfig.strValue() );
						type
							= cookieConfig.hasChildren( "type" )
							? cookieConfig.getFirstChild( "type" ).strValue()
							: "string";
						recv_assignCookieValue( cookie.value(), v, type );
					}
				}

				/*currValue = Value.create();
				currValue.getNewChild( "expires" ).setValue( cookie.expirationDate() );
				currValue.getNewChild( "path" ).setValue( cookie.path() );
				currValue.getNewChild( "name" ).setValue( cookie.name() );
				currValue.getNewChild( "value" ).setValue( cookie.value() );
				currValue.getNewChild( "domain" ).setValue( cookie.domain() );
				currValue.getNewChild( "secure" ).setValue( (cookie.secure() ? 1 : 0) );
				cookieVec.add( currValue );*/
			}
		}
	}

	private static void recv_assignCookieValue( String cookieValue, Value value, String typeKeyword )
		throws IOException
	{
		NativeType type = NativeType.fromString( typeKeyword );
		if ( NativeType.INT == type ) {
			try {
				value.setValue( new Integer( cookieValue ) );
			} catch( NumberFormatException e ) {
				throw new IOException( e );
			}
		} else if ( NativeType.LONG == type ) {
			try {
				value.setValue( new Long( cookieValue ) );
			} catch( NumberFormatException e ) {
				throw new IOException( e );
			}
		} else if ( NativeType.STRING == type ) {
			value.setValue( cookieValue );
		} else if ( NativeType.DOUBLE == type ) {
			try {
				value.setValue( new Double( cookieValue ) );
			} catch( NumberFormatException e ) {
				throw new IOException( e );
			}
		} else if ( NativeType.BOOL == type ) {
			value.setValue( Boolean.valueOf( cookieValue ) );
		} else {
			value.setValue( cookieValue );
		}
	}

	private void recv_checkForCookies( FullHttpRequest message, DecodedMessage decodedMessage )
		throws IOException
	{
		Value cookies = null;
		if ( hasOperationSpecificParameter( decodedMessage.operationName, Parameters.COOKIES ) ) {
			cookies = getOperationSpecificParameterFirstValue( decodedMessage.operationName, Parameters.COOKIES );
		} else if ( hasParameter( Parameters.COOKIES ) ) {
			cookies = getParameterFirstValue( Parameters.COOKIES );
		}
		if ( cookies != null ) {
			Value v;
			String type;
			for( Cookie cookie : ServerCookieDecoder.STRICT.decode( message.headers().get( HttpHeaderNames.COOKIE ) ) ) {
				if ( cookies.hasChildren( cookie.name() ) ) {
					Value cookieConfig = cookies.getFirstChild( cookie.name() );
					if ( cookieConfig.isString() ) {
						v = decodedMessage.value.getFirstChild( cookieConfig.strValue() );
						if ( cookieConfig.hasChildren( "type" ) ) {
							type = cookieConfig.getFirstChild( "type" ).strValue();
						} else {
							type = "string";
						}
						recv_assignCookieValue( cookie.value(), v, type );
					}
				}
			}
		}
	}

	private void recv_checkForGenericHeader( FullHttpRequest message, DecodedMessage decodedMessage )
		throws IOException
	{
		Value headers = null;
		if ( hasOperationSpecificParameter( decodedMessage.operationName, Parameters.HEADERS ) ) {
			headers = getOperationSpecificParameterFirstValue( decodedMessage.operationName, Parameters.HEADERS );
		} else if ( hasParameter( Parameters.HEADERS ) ) {
			headers = getParameterFirstValue( Parameters.HEADERS );
		}
		if ( headers != null ) {
			for( String headerName : headers.children().keySet() ) {
				String headerAlias = headers.getFirstChild( headerName ).strValue();
				headerName = headerName.replace( "_", "-" );
				String value = message.headers().get( headerName );
				decodedMessage.value.getFirstChild( headerAlias ).setValue( value == null ? "" : value );
			}
		}
	}

	private static void recv_parseQueryString( FullHttpRequest message, Value value, String contentType, boolean strictEncoding )
		throws IOException
	{
		if ( message.method() == HttpMethod.GET && contentType.equals( ContentTypes.APPLICATION_JSON.toString() ) ) {
			recv_parseJsonQueryString( message, value, strictEncoding );
		} else {
			QueryStringDecoder decoder = new QueryStringDecoder( message.uri() );
			for( Entry<String, List<String>> parameter : decoder.parameters().entrySet() ) {
				int i = 0;
				for( String parameterValue : parameter.getValue() ) {
					value.getChildren( parameter.getKey() ).get( i++ ).setValue( parameterValue );
				}
			}
		}
	}

	private static void recv_parseJsonQueryString( FullHttpRequest message, Value value, boolean strictEncoding )
		throws IOException
	{
		String queryString = message.uri();
		String[] kv = queryString.split( "\\?", 2 );
		if ( kv.length > 1 ) {
			// the query string was already URL decoded by the HttpParser
			JsUtils.parseJsonIntoValue( new StringReader( kv[ 1 ] ), value, strictEncoding );
		}
	}

	/*
	 * Prints debug information about a received message
	 */
	private void recv_logDebugInfo( FullHttpMessage message, String charset )
		throws IOException
	{
		StringBuilder debugSB = new StringBuilder();
		debugSB.append( "[HTTP debug] Receiving:\n" );
		if ( message instanceof FullHttpResponse ) {
			debugSB.append( "HTTP Code: " ).append( ((FullHttpResponse) message).status().code() ).append( "\n" );
		}
		if ( message instanceof FullHttpRequest ) {
			debugSB.append( "Resource: " ).append( ((FullHttpRequest) message).uri() ).append( "\n" );
		}
		debugSB.append( "--> Header properties\n" );
		for( String header : message.headers().names() ) {
			debugSB.append( '\t' ).append( header ).append( ": " );
			for( String value : message.headers().getAllAsString( header ) ) {
				debugSB.append( value ).append( ' ' );
			}
			debugSB.append( '\n' );
		}

		if ( getParameterFirstValue( Parameters.DEBUG ).getFirstChild( "showContent" ).intValue() > 0
			&& message.content().readableBytes() > 0 ) {
			debugSB.append( "--> Message content\n" );
			ByteBuf buf = message.content();
			debugSB.append( buf.toString( buf.readerIndex(), buf.readableBytes(), Charset.forName( charset ) ) );
		}
		Interpreter.getInstance().logInfo( debugSB.toString() );
	}

	private void recv_parseRequestFormat( String type )
		throws IOException
	{
		responseFormat = null;

		if ( "text/xml".equals( type ) ) {
			responseFormat = "xml";
		} else if ( "text/x-gwt-rpc".equals( type ) ) {
			responseFormat = "text/x-gwt-rpc";
		} else if ( ContentTypes.APPLICATION_JSON.equals( type ) ) {
			responseFormat = "json";
		}
	}

	private void recv_parseMessage( FullHttpMessage message, DecodedMessage decodedMessage, String type, String charset )
		throws IOException
	{
		if ( "text/html".equals( type ) ) {
			decodedMessage.value.setValue( message.content().toString( Charset.forName( charset ) ) );
		} else if ( "application/x-www-form-urlencoded".equals( type ) ) {
			parseForm( message, decodedMessage.value, charset );
		} else if ( "text/xml".equals( type ) || type.contains( "xml" ) ) {
			parseXML( message, decodedMessage.value, charset );
		} else if ( "text/x-gwt-rpc".equals( type ) ) {
			decodedMessage.operationName = parseGWTRPC( message, decodedMessage.value, charset );
		} else if ( "multipart/form-data".equals( type ) ) {
			parseMultiPartFormData( message, decodedMessage.value, charset );
		} else if ( "application/octet-stream".equals( type )
			|| type.startsWith( "image/" )
			|| "application/zip".equals( type ) ) {
			byte[] bytes = new byte[ message.content().readableBytes() ];
			message.content().readBytes( bytes );
			decodedMessage.value.setValue( new ByteArray( bytes ) );
		} else if ( ContentTypes.APPLICATION_JSON.toString().equals( type ) || type.contains( "json" ) ) {
			boolean strictEncoding = checkStringParameter( Parameters.JSON_ENCODING, "strict" );
			parseJson( message, decodedMessage.value, strictEncoding, charset );
		} else {
			decodedMessage.value.setValue( message.content().toString( Charset.forName( charset ) ) );
		}
	}

	private String getDefaultOperation( HttpMethod t )
	{
		if ( hasParameter( Parameters.DEFAULT_OPERATION ) ) {
			Value dParam = getParameterFirstValue( Parameters.DEFAULT_OPERATION );
			String method = t.name().toLowerCase(); // TODO does this need to be lower case?
			if ( method == null || dParam.hasChildren( method ) == false ) {
				return dParam.strValue();
			} else {
				return dParam.getFirstChild( method ).strValue();
			}
		}

		return null;
	}

	private void recv_checkReceivingOperation( FullHttpRequest message, DecodedMessage decodedMessage )
	{
		if ( decodedMessage.operationName == null ) {
			String requestPath = message.uri().split( "\\?", 2 )[ 0 ];
			decodedMessage.operationName = requestPath.substring( 1 );
			Matcher m = LocationParser.RESOURCE_SEPARATOR_PATTERN.matcher( decodedMessage.operationName );
			if ( m.find() ) {
				int resourceStart = m.end();
				if ( m.find() ) {
					decodedMessage.resourcePath = requestPath.substring( resourceStart - 1, m.start() );
					decodedMessage.operationName = requestPath.substring( m.end(), requestPath.length() );
				}
			}
		}

		if ( decodedMessage.resourcePath.equals( "/" ) && !channel().parentInputPort()
			.canHandleInputOperation( decodedMessage.operationName ) ) {
			String defaultOpId = getDefaultOperation( message.method() );
			if ( defaultOpId != null ) {
				Value body = decodedMessage.value;
				decodedMessage.value = Value.create();
				decodedMessage.value.getChildren( "data" ).add( body );
				decodedMessage.value.getFirstChild( "operation" ).setValue( decodedMessage.operationName );
				decodedMessage.value.setFirstChild( "requestUri", message.uri() );
				if ( message.headers().contains( HttpHeaderNames.USER_AGENT ) ) {
					decodedMessage.value.getFirstChild( Parameters.USER_AGENT )
						.setValue( message.headers().get( HttpHeaderNames.USER_AGENT ) );
				}
				Value cookies = decodedMessage.value.getFirstChild( "cookies" );
				ServerCookieDecoder decoder = ServerCookieDecoder.STRICT;
				if ( message.headers().contains( HttpHeaderNames.COOKIE ) ) {
					for( Cookie cookie : decoder.decode( message.headers().get( HttpHeaderNames.COOKIE ) ) ) {
						cookies.getFirstChild( cookie.name() ).setValue( cookie.value() );
					}
				}
				decodedMessage.operationName = defaultOpId;
			}
		}
	}

	private void recv_checkForMultiPartHeaders( DecodedMessage decodedMessage )
	{
		if ( multiPartFormDataParser != null ) {
			String target;
			for( Entry< String, MultiPartFormDataParser.PartProperties> entry
				: multiPartFormDataParser.getPartPropertiesSet() ) {
				if ( entry.getValue().filename() != null ) {
					target = getMultipartHeaderForPart( decodedMessage.operationName, entry.getKey() );
					if ( target != null ) {
						decodedMessage.value.getFirstChild( target ).setValue( entry.getValue().filename() );
					}
				}
			}
			multiPartFormDataParser = null;
		}
	}

	private void recv_checkForMessageProperties( FullHttpRequest message, DecodedMessage decodedMessage )
		throws IOException
	{
		recv_checkForCookies( message, decodedMessage );
		recv_checkForGenericHeader( message, decodedMessage );
		recv_checkForMultiPartHeaders( decodedMessage );
		if ( message.headers().contains( HttpHeaderNames.USER_AGENT )
			&& hasParameter( Parameters.USER_AGENT ) ) {
			getParameterFirstValue( Parameters.USER_AGENT )
				.setValue( message.headers().get( HttpHeaderNames.USER_AGENT ) );
		}

		if ( getParameterVector( Parameters.HOST ) != null ) {
			String host = message.headers().get( HttpHeaderNames.HOST );
			getParameterFirstValue( Parameters.HOST ).setValue( host != null ? host : "" );
		}
	}

	private static class DecodedMessage
	{

		private String operationName = null;
		private Value value = Value.create();
		private String resourcePath = "/";
		private long id = CommMessage.GENERIC_ID;
	}

	private void recv_checkForStatusCode( FullHttpMessage message )
	{
		if ( message instanceof FullHttpResponse && hasParameter( Parameters.STATUS_CODE ) ) {
			getParameterFirstValue( Parameters.STATUS_CODE )
				.setValue( ((FullHttpResponse) message).status().code() );
		}
	}

	public CommMessage recv_internal( FullHttpMessage message )
		throws IOException
	{
		
		String charset = HttpUtils.getCharset( null, message );
		DecodedMessage decodedMessage = new DecodedMessage();

		String messageId = message.headers().get( Headers.JOLIE_MESSAGE_ID );
		if ( messageId != null ) {
			try {
				decodedMessage.id = Long.parseLong( messageId );
				setReceiveExecutionThread( decodedMessage.id );
			} catch( NullPointerException e ) {
				Interpreter.getInstance().logWarning( "Could not correlate message number " + messageId + " with any existing instance, trying correlating on channel" );
				setReceiveExecutionThread( channel() );
			}
		} else {
			setReceiveExecutionThread( channel() );

			try {
				if ( isThreadSafe() ) {
					// the receiver is threadSafe, so we add a fresh id to the message
					decodedMessage.id = CommMessage.getNewMessageId();
				}
			} catch ( NullPointerException e ){
				throw new IOException( "Could not correlate incoming message"
					+ "\n=========================================\n"
					+ message.toString()
					+ "\n=========================================\n"
					+ "with any thread (no ID, no channel association). Check protocol settings (concurrent) between client and server." );
			}
		}

		HttpUtils.recv_checkForChannelClosing( message, channel() );

		if ( checkBooleanParameter( Parameters.DEBUG ) ) {
			recv_logDebugInfo( message, charset );
		}

		recv_checkForStatusCode( message );

		encoding = message.headers().get( HttpHeaderNames.ACCEPT_ENCODING );

		String contentType = DEFAULT_CONTENT_TYPE.toString();
		if ( message.headers().contains( HttpHeaderNames.CONTENT_TYPE ) ) {
			contentType = message.headers().get( HttpHeaderNames.CONTENT_TYPE ).split( ";", 2 )[ 0 ].toLowerCase();
		}

		// URI parameter parsing
		if ( message instanceof FullHttpRequest ) {
			boolean strictEncoding = checkStringParameter( Parameters.JSON_ENCODING, "strict" );
			recv_parseQueryString( (FullHttpRequest) message, decodedMessage.value, contentType, strictEncoding );
		}

		recv_parseRequestFormat( contentType );

		/* https://tools.ietf.org/html/rfc7231#section-4.3 */
		HttpMethod method = null;
		if ( message instanceof FullHttpRequest ) {
			method = ((FullHttpRequest) message).method();
		}

		if ( method != HttpMethod.GET && method != HttpMethod.HEAD && method != HttpMethod.DELETE ) {
			// body parsing
			if ( message.content().readableBytes() > 0 ) {
				recv_parseMessage( message, decodedMessage, contentType, charset );
			}
		}

//		if ( !isThreadSafe() ) {
//			decodedMessage.id = CommMessage.GENERIC_ID;
//		}

		if ( message instanceof FullHttpResponse ) {
			
			if ( !isThreadSafe() ){
				CommMessage request = retrieveSynchonousRequest( channel() );
				decodedMessage.id = request.id();
				decodedMessage.operationName = request.operationName();
			} else {
				decodedMessage.operationName = retrieveAsynchronousRequest( decodedMessage.id );
			}
			
			String responseHeader = "";
			if ( hasParameter( Parameters.RESPONSE_HEADER )
				|| hasOperationSpecificParameter( inputId, Parameters.RESPONSE_HEADER ) ) {
				if ( hasOperationSpecificParameter( inputId, Parameters.RESPONSE_HEADER ) ) {
					responseHeader = getOperationSpecificStringParameter( inputId, Parameters.RESPONSE_HEADER );
				} else {
					responseHeader = getStringParameter( Parameters.RESPONSE_HEADER );
				}
				for( Entry<String, String> param : message.headers() ) {
					decodedMessage.value.getFirstChild( responseHeader )
						.getFirstChild( param.getKey() ).setValue( param.getValue() );
				}
				decodedMessage.value.getFirstChild( responseHeader )
					.getFirstChild( Parameters.STATUS_CODE )
					.setValue( ((FullHttpResponse) message).status().code() );
			}
			recv_checkForSetCookie( (FullHttpResponse) message, decodedMessage.value );

		} else {
			/* message.isError() == false */ // TODO message 
			recv_checkReceivingOperation( (FullHttpRequest) message, decodedMessage );
			recv_checkForMessageProperties( (FullHttpRequest) message, decodedMessage );
		}
		
		CommMessage retVal = new CommMessage(
			decodedMessage.id,
			decodedMessage.operationName,
			decodedMessage.resourcePath,
			decodedMessage.value, null );

		if ( /*retVal != null && */ "/".equals( retVal.resourcePath() )
			&& ((channel().parentPort() != null
			&& channel().parentPort().getInterface().containsOperation( retVal.operationName() ))
			|| (channel().parentInputPort() != null
			&& channel().parentInputPort().getAggregatedOperation( retVal.operationName() ) != null)) ) {
			try {
				// The message is for this service
				boolean hasInput = false;
				OneWayTypeDescription oneWayTypeDescription = null;
				if ( channel().parentInputPort() != null ) {
					if ( channel().parentInputPort().getAggregatedOperation( retVal.operationName() ) != null ) {
						oneWayTypeDescription = channel().parentInputPort()
							.getAggregatedOperation( retVal.operationName() )
							.getOperationTypeDescription().asOneWayTypeDescription();
						hasInput = true;
					}
				}
				if ( !hasInput ) {
					Interface iface = channel().parentPort().getInterface();
					oneWayTypeDescription = iface.oneWayOperations().get( retVal.operationName() );
				}

				if ( oneWayTypeDescription != null ) {
					// We are receiving a One-Way message
					oneWayTypeDescription.requestType().cast( retVal.value() );
				} else {
					hasInput = false;
					RequestResponseTypeDescription rrTypeDescription = null;
					if ( channel().parentInputPort() != null ) {
						if ( channel().parentInputPort().getAggregatedOperation( retVal.operationName() ) != null ) {
							rrTypeDescription = channel().parentInputPort()
								.getAggregatedOperation( retVal.operationName() )
								.getOperationTypeDescription().asRequestResponseTypeDescription();
							hasInput = true;
						}
					}

					if ( !hasInput ) {
						Interface iface = channel().parentPort().getInterface();
						rrTypeDescription = iface.requestResponseOperations().get( retVal.operationName() );
					}

					if ( retVal.isFault() ) {
						Type faultType = rrTypeDescription.faults().get( retVal.fault().faultName() );
						if ( faultType != null ) {
							faultType.cast( retVal.value() );
						}
					} else if ( message instanceof FullHttpResponse ) {
						rrTypeDescription.responseType().cast( retVal.value() );
					} else {
						rrTypeDescription.requestType().cast( retVal.value() );
					}
				}
			} catch( TypeCastingException e ) {
				// TODO: do something here?
			}
		}
		
		return retVal;

	}

	@Override
	public String getConfigurationHash()
	{
		return name();
	}

}
