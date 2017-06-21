/*
 * Copyright (C) 2017 stefanopiozingaro
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package jolie.net;

import java.io.IOException;
import java.net.URI;
import jolie.net.ext.CommProtocolFactory;
import jolie.net.protocols.CommProtocol;
import jolie.runtime.VariablePath;

/**
 *
 * @author stefanopiozingaro
 */
public class MqttProtocolFactory extends CommProtocolFactory {

    public MqttProtocolFactory(CommCore commCore) {
        super(commCore);
    }

    @Override
    public CommProtocol createInputProtocol(VariablePath configurationPath, URI location) 
            throws IOException {
        return new MqttProtocol(Boolean.TRUE, location, configurationPath);
    }

    @Override
    public CommProtocol createOutputProtocol(VariablePath configurationPath, URI location) 
            throws IOException {
        return new MqttProtocol(Boolean.FALSE, location, configurationPath);
    }
}
