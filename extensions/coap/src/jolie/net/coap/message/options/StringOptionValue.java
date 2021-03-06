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
package jolie.net.coap.message.options;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Locale;
import jolie.net.coap.message.CoapMessage;

public class StringOptionValue extends OptionValue<String>
{

	/**
	 * @param optionNumber the option number of the {@link StringOptionValue} to
	 * be created
	 * @param value the value of the {@link StringOptionValue} to be created
	 *
	 * @throws java.lang.IllegalArgumentException if the given option number is
	 * unknown, or if the given value is either the default value or exceeds the
	 * defined length limits for options with the given option number
	 */
	public StringOptionValue( int optionNumber, byte[] value )
		throws IllegalArgumentException
	{
		this( optionNumber, value, false );
	}

	/**
	 * @param optionNumber the option number of the {@link StringOptionValue} to
	 * be created
	 * @param value the value of the {@link StringOptionValue} to be created
	 * @param allowDefault if set to <code>true</code> no
	 * {@link IllegalArgumentException} is thrown if the given value is the
	 * default value
	 *
	 * @throws java.lang.IllegalArgumentException if the given option number is
	 * unknown, or if the given value is either the default value or exceeds the
	 * defined length limits for options with the given option number
	 */
	public StringOptionValue( int optionNumber, byte[] value,
		boolean allowDefault ) throws IllegalArgumentException
	{
		super( optionNumber, value, allowDefault );
	}

	/**
	 * Creates an instance of {@link StringOptionValue} according to the rules
	 * defined for CoAP. The pre-processing of the given value before encoding
	 * using {@link de.uzl.itm.ncoap.message.CoapMessage#CHARSET} depends on the
	 * given option number:
	 *
	 * <ul>
	 * <li> {@link Option#URI_HOST}: convert to lower case and remove percent
	 * encoding (if present)
	 * </li>
	 * <li> {@link Option#URI_PATH} and {@link Option#URI_QUERY}: remove percent
	 * encoding (if present)
	 * </li>
	 * <li>
	 * others: no pre-processing.
	 * </li>
	 * </ul>
	 *
	 * @param optionNumber the option number of the {@link StringOptionValue} to
	 * be created
	 * @param value the value of the {@link StringOptionValue} to be created
	 *
	 * @throws java.lang.IllegalArgumentException if the given option number is
	 * unknown, or if the given value is either the default value or exceeds the
	 * defined length limits for options with the given option number
	 */
	public StringOptionValue( int optionNumber, String value )
		throws IllegalArgumentException
	{

		this( optionNumber, optionNumber == Option.URI_HOST
			? convertToByteArrayWithoutPercentEncoding(
				value.toLowerCase( Locale.ENGLISH ) )
			: ((optionNumber == Option.URI_PATH
			|| optionNumber == Option.URI_QUERY)
				? convertToByteArrayWithoutPercentEncoding( value )
				: value.getBytes( CoapMessage.CHARSET )) );
	}

	/**
	 * Returns the decoded value of this option assuming the byte array returned
	 * by {@link #getValue()} is an encoded String using
	 * {@link CoapMessage#CHARSET}.
	 *
	 * @return the decoded value of this option assuming the byte array returned
	 * by {@link #getValue()} is an encoded String using
	 * {@link CoapMessage#CHARSET}.
	 */
	@Override
	public String getDecodedValue()
	{
		return new String( value, CoapMessage.CHARSET );
	}

	@Override
	public int hashCode()
	{
		return getDecodedValue().hashCode();
	}

	@Override
	public boolean equals( Object object )
	{
		if ( !(object instanceof StringOptionValue) ) {
			return false;
		}

		StringOptionValue other = (StringOptionValue) object;
		return Arrays.equals( this.getValue(), other.getValue() );
	}

	/**
	 * Replaces percent-encoding from the given {@link String} and returns a byte
	 * array containing the encoded string using {@link CoapMessage#CHARSET}.
	 *
	 * @param s the {@link String} to be encoded
	 *
	 * @return a byte array containing the encoded string using
	 * {@link CoapMessage#CHARSET} without percent-encoding.
	 */
	public static byte[] convertToByteArrayWithoutPercentEncoding( String s )
		throws IllegalArgumentException
	{

		ByteArrayInputStream in = new ByteArrayInputStream( s.getBytes( CoapMessage.CHARSET ) );
		ByteArrayOutputStream out = new ByteArrayOutputStream();

		int i;

		do {
			i = in.read();
			if ( i == -1 ) {
				break;
			}
			if ( i == 0x25 ) {
				int d1 = Character.digit( in.read(), 16 );
				int d2 = Character.digit( in.read(), 16 );

				if ( d1 == -1 || d2 == -1 ) {
					throw new IllegalArgumentException( "Invalid percent "
						+ "encoding in: " + s );
				}

				out.write( (d1 << 4) | d2 );
			} else {
				out.write( i );
			}

		} while( true );

		byte[] result = out.toByteArray();

		return result;
	}
}
