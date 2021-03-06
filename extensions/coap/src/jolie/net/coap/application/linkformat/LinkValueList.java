/**********************************************************************************
 *   Copyright (C) 2016, Oliver Kleine, University of Luebeck                     *
 *   Copyright (C) 2018 by Stefano Pio Zingaro <stefanopio.zingaro@unibo.it>      *
 *                                                                                *
 *   This program is free software; you can redistribute it and/or modify         *
 *   it under the terms of the GNU Library General Public License as              *
 *   published by the Free Software Foundation; either version 2 of the           *
 *   License, or (at your option) any later version.                              *
 *                                                                                *
 *   This program is distributed in the hope that it will be useful,              *
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of               *
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the                *
 *   GNU General Public License for more details.                                 *
 *                                                                                *
 *   You should have received a copy of the GNU Library General Public            *
 *   License along with this program; if not, write to the                        *
 *   Free Software Foundation, Inc.,                                              *
 *   59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.                    *
 *                                                                                *
 *   For details about the authors of this software, see the AUTHORS file.        *
 **********************************************************************************/
package jolie.net.coap.application.linkformat;

import java.util.*;

/**
 * <p>
 * A {@link LinkValueList} is a representation of the string that is contained
 * in a {@link CoapMessage} with content type
 * {@link ContentFormat#APP_LINK_FORMAT}.</p>
 *
 * <p>
 * A {@link LinkValueList} contains zero or more instances of
 * {@link LinkValue}.</p>
 *
 * <p>
 * The notations are taken from RFC 6690</p>
 *
 * @author Oliver Kleine
 */
public class LinkValueList
{

	//*******************************************************************************************
	// static methods
	//*******************************************************************************************
	/**
	 * Decodes a serialized link-value-list, e.g. the payload (content) of a
	 * {@link CoapMessage} with content type {@link ContentFormat#APP_LINK_FORMAT}
	 * and returns a corresponding {@link LinkValueList} instance.
	 *
	 * @param linkValueList the serialized link-value-list
	 *
	 * @return A {@link LinkValueList} instance corresponsing to the given
	 * serialization
	 */
	public static LinkValueList decode( String linkValueList )
	{
		LinkValueList result = new LinkValueList();
		Collection<String> linkValues = getLinkValues( linkValueList );
		for( String linkValue : linkValues ) {
			result.addLinkValue( LinkValue.decode( linkValue ) );
		}
		return result;
	}

	private static Collection<String> getLinkValues( String linkValueList )
	{
		List<String> linkValues = new ArrayList<>();
		Collections.addAll( linkValues, linkValueList.split( "," ) );
		return linkValues;
	}

	//******************************************************************************************
	// instance related fields and methods
	//******************************************************************************************
	private Collection<LinkValue> linkValues;

	private LinkValueList()
	{
		this.linkValues = new TreeSet<>( new Comparator<LinkValue>()
		{
			@Override
			public int compare( LinkValue linkValue1, LinkValue linkValue2 )
			{
				return linkValue1.getUriReference().compareTo( linkValue2.getUriReference() );
			}
		} );
	}

	/**
	 * Creates a new instance of {@link LinkValueList}
	 *
	 * @param linkValues the {@link LinkValue}s to be contained in the
	 * {@link LinkValueList} to be created
	 */
	public LinkValueList( LinkValue... linkValues )
	{
		this.linkValues = new ArrayList<>( Arrays.asList( linkValues ) );
	}

	/**
	 * Adds an instance of {@link LinkValue} to this {@link LinkValueList}.
	 *
	 * @param linkValue the {@link LinkValue} to be added
	 */
	public void addLinkValue( LinkValue linkValue )
	{
		this.linkValues.add( linkValue );
	}

	public boolean removeLinkValue( String uriReference )
	{
		for( LinkValue linkValue : this.linkValues ) {
			if ( linkValue.getUriReference().equals( uriReference ) ) {
				this.linkValues.remove( linkValue );
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns all URI references contained in this {@link LinkValueList}
	 *
	 * @return all URI references contained in this {@link LinkValueList}
	 */
	public List<String> getUriReferences()
	{
		List<String> result = new ArrayList<>( linkValues.size() );
		for( LinkValue linkValue : linkValues ) {
			result.add( linkValue.getUriReference() );
		}
		return result;
	}

	/**
	 * Returns the URI references that match the given criterion, i.e. contain a
	 * {@link LinkParam} with the given pair of keyname and value.
	 *
	 * @param key the {@link LinkParam.Key} to match
	 * @param value the value to match
	 *
	 * @return the URI references that match the given criterion
	 */
	public Set<String> getUriReferences( LinkParam.Key key, String value )
	{
		Set<String> result = new HashSet<>();
		for( LinkValue linkValue : linkValues ) {
			if ( linkValue.containsLinkParam( key, value ) ) {
				result.add( linkValue.getUriReference() );
			}
		}
		return result;
	}

	/**
	 * Returns the {@link LinkParam}s for the given URI reference.
	 *
	 * @param uriReference the URI reference to lookup the {@link LinkParam}s for
	 *
	 * @return the {@link LinkParam}s for the given URI reference
	 */
	public Collection<LinkParam> getLinkParams( String uriReference )
	{
		List<LinkParam> result = new ArrayList<>();
		for( LinkValue linkValue : this.linkValues ) {
			if ( linkValue.getUriReference().equals( uriReference ) ) {
				return linkValue.getLinkParams();
			}
		}
		return null;
	}

	public LinkValueList filter( LinkParam.Key key, String value )
	{
		LinkValueList result = new LinkValueList();
		for( LinkValue linkValue : this.linkValues ) {
			if ( linkValue.containsLinkParam( key, value ) ) {
				result.addLinkValue( linkValue );
			}
		}
		return result;
	}

	public LinkValueList filter( String hrefValue )
	{
		if ( hrefValue.endsWith( "*" ) ) {
			return filterByUriPrefix( hrefValue.substring( 0, hrefValue.length() - 1 ) );
		} else {
			return filterByUriReference( hrefValue );
		}
	}

	private LinkValueList filterByUriPrefix( String prefix )
	{
		LinkValueList result = new LinkValueList();
		for( LinkValue linkValue : this.linkValues ) {
			if ( linkValue.getUriReference().startsWith( prefix ) ) {
				result.addLinkValue( linkValue );
			}
		}
		return result;
	}

	private LinkValueList filterByUriReference( String uriReference )
	{
		LinkValueList result = new LinkValueList();
		for( LinkValue linkValue : this.linkValues ) {
			if ( linkValue.getUriReference().endsWith( uriReference ) ) {
				result.addLinkValue( linkValue );
				return result;
			}
		}
		return result;
	}

	/**
	 * Returns a string representation of this {@link LinkValueList}, i.e. the
	 * reversal of {@link #decode(String)}
	 *
	 * @return a string representation of this {@link LinkValueList}, i.e. the
	 * reversal of {@link #decode(String)}
	 */
	public String encode()
	{
		StringBuilder builder = new StringBuilder();
		for( LinkValue linkValue : this.linkValues ) {
			builder.append( linkValue.toString() );
			builder.append( "," );
		}
		if ( builder.length() > 0 ) {
			builder.deleteCharAt( builder.length() - 1 );
		}
		return builder.toString();
	}

	/**
	 * Returns a string representation of this {@link LinkValueList} (same as
	 * {@link #encode()}
	 *
	 * @return a string representation of this {@link LinkValueList} (same as
	 * {@link #encode()}
	 */
	@Override
	public String toString()
	{
		return this.encode();
	}
}
