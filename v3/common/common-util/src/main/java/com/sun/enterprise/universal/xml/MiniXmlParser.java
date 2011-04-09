/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2008-2011 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package com.sun.enterprise.universal.xml;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.sun.common.util.logging.LoggingConfigImpl;
import com.sun.common.util.logging.LoggingPropertyNames;
import com.sun.enterprise.universal.glassfish.GFLauncherUtils;
import com.sun.enterprise.universal.i18n.LocalStringsImpl;
import com.sun.enterprise.util.HostAndPort;
import com.sun.enterprise.util.StringUtils;
import static javax.xml.stream.XMLStreamConstants.END_DOCUMENT;
import static javax.xml.stream.XMLStreamConstants.END_ELEMENT;
import static javax.xml.stream.XMLStreamConstants.START_ELEMENT;

/**
 * A fairly simple but very specific stax XML Parser. Give it the location of domain.xml and the name of the server
 * instance and it will return JVM options. Currently it is all package private.
 *
 * @author bnevins
 */
public class MiniXmlParser {
    public MiniXmlParser(File domainXml) throws MiniXmlParserException {
        this(domainXml, "server");  // default for a domain
    }

    public MiniXmlParser(File domainXml, String serverName) throws MiniXmlParserException {
        this.serverName = serverName;
        this.domainXml = domainXml;
        try {
            read();
            valid = true;
        }
        catch (EndDocumentException e) {
            throw new MiniXmlParserException(strings.get("enddocument", configRef, serverName));
        }
        catch (Exception e) {
            throw new MiniXmlParserException(strings.get("toplevel", e), e);
        }
        finally {
            try {
                if (parser != null) {
                    parser.close();
                }
            }
            catch (Exception e) {
                // ignore
            }
            try {
                if (reader != null) {
                    reader.close();
                }
            }
            catch (Exception e) {
                // ignore
            }
        }
    }

    public Map<String, String> getJavaConfig() throws MiniXmlParserException {
        if (!valid) {
            throw new MiniXmlParserException(strings.get("invalid"));
        }
        return javaConfig;
    }

    public List<String> getJvmOptions() throws MiniXmlParserException {
        if (!valid) {
            throw new MiniXmlParserException(strings.get("invalid"));
        }
        return jvmOptions;
    }

    public Map<String, String> getProfilerConfig() throws MiniXmlParserException {
        if (!valid) {
            throw new MiniXmlParserException(strings.get("invalid"));
        }
        return profilerConfig;
    }

    public List<String> getProfilerJvmOptions() throws MiniXmlParserException {
        if (!valid) {
            throw new MiniXmlParserException(strings.get("invalid"));
        }
        return profilerJvmOptions;
    }

    public Map<String, String> getProfilerSystemProperties() throws MiniXmlParserException {
        if (!valid) {
            throw new MiniXmlParserException(strings.get("invalid"));
        }
        return profilerSysProps;
    }

    public Map<String, String> getSystemProperties() throws MiniXmlParserException {
        if (!valid) {
            throw new MiniXmlParserException(strings.get("invalid"));
        }
        return sysProps;
    }

    public String getDomainName() {
        return domainName;
    }

    public List<HostAndPort> getAdminAddresses() {
        if (adminAddresses == null || adminAddresses.isEmpty()) {
            String[] listenerNames = getListenerNamesForVS(DEFAULT_ADMIN_VS_ID, vsAttributes);
            if (listenerNames == null || listenerNames.length == 0) {
                listenerNames = getListenerNamesForVS(DEFAULT_VS_ID, vsAttributes); //plan B
            }
            addPortsForListeners(listenerNames);
        }
        return adminAddresses;
    }

    public void setupConfigDir(File configDir, File installDir) {
        loggingConfig.setupConfigDir(configDir, installDir);
    }

    /** 
     * loggingConfig will return an IOException if there is no
     * logging properties file.
     * 
     * @return the log filename if available, otherwise return null
     */
    public String getLogFilename() {
        String logFilename = null;
        try {
            Map<String, String> map = loggingConfig.getLoggingProperties();
            if (map != null) {
                logFilename = map.get(LoggingPropertyNames.file);
            }
        }
        catch (Exception e) {
            // just return null
        }
        return logFilename;
    }

    public boolean isMonitoringEnabled() {
        return monitoringEnabled;
    }

    public boolean hasNetworkConfig() {
        return sawNetworkConfig;
    }

    public boolean hasDefaultConfig() {
        return sawDefaultConfig;
    }

    /////////////////////  all private below  /////////////////////////
    private void read() throws XMLStreamException, EndDocumentException, FileNotFoundException {
        createParser();
        getConfigRefName();
        try {
            // this will fail if config is above servers in domain.xml!
            getConfig(); // might throw
            findDomainNameAndEnd();
            return;
        }
        catch (EndDocumentException ex) {
            createParser();
            skipRoot("domain");
            getConfig();
            findDomainNameAndEnd();
            Logger.getLogger(MiniXmlParser.class.getName()).log(
                    Level.FINE, strings.get("secondpass"));
        }
    }

    private void createParser() throws FileNotFoundException, XMLStreamException {
        reader = new InputStreamReader(new FileInputStream(domainXml));
        parser = getXmlInputFactory().createXMLStreamReader(
                domainXml.toURI().toString(), reader);
    }

    // In JDK 1.6, StAX is part of JRE, so we use no argument variant of
    // newInstance(), where as on JDK 1.5, we use two argument version of
    // newInstance() so that we can pass the classloader that loads
    // XMLInputFactory to load the factory, otherwise by default StAX uses
    // Thread's context class loader to locate the factory. See:
    // https://glassfish.dev.java.net/issues/show_bug.cgi?id=6428
    // 
    private XMLInputFactory getXmlInputFactory() {
        Class clazz = XMLInputFactory.class;
        ClassLoader cl = clazz.getClassLoader();

        // jdk6+
        if (cl == null)
            return XMLInputFactory.newInstance();

        // jdk5
        return XMLInputFactory.newInstance(clazz.getName(), cl);
    }

    private void getConfigRefName() throws XMLStreamException, EndDocumentException {
        if (configRef != null) {
            return;
        }   // second pass!
        skipRoot("domain");
        // complications -- look for this element as a child of Domain...
        // <property name="administrative.domain.name" value="domain1"/>
        while (true) {
            skipTo("servers", "property");
            String name = parser.getLocalName();
            if ("servers".equals(name)) {
                break;
            }
            parseDomainName(); // maybe it is the domain name?
        }
        // the cursor is at the start-element of <servers>
        while (true) {
            // get to first <server> element
            skipNonStartElements();
            String localName = parser.getLocalName();
            if (!"server".equals(localName)) {
                throw new XMLStreamException(strings.get("noserver", serverName));
            }
            // get the attributes for this <server>
            Map<String, String> map = parseAttributes();
            String thisName = map.get("name");
            if (serverName.equals(thisName)) {
                configRef = map.get("config-ref");
                parseSysPropsFromServer();
                skipToEnd("servers");
                return;
            }
            else
                skipToEnd("server");
        }
    }

    private void getConfig() throws XMLStreamException, EndDocumentException {
        // complications -- look for this element as a child of Domain...
        // <property name="administrative.domain.name" value="domain1"/>
        while (true) {
            skipTo("configs", "property");
            String name = parser.getLocalName();
            if ("configs".equals(name)) {
                break;
            }
            parseDomainName(); // maybe it is the domain name?
        }
        while (skipToButNotPast("configs", "config")) {
            // get the attributes for this <config>
            Map<String, String> map = parseAttributes();
            String thisName = map.get("name");
            if ("default-config".equals(thisName))
                sawDefaultConfig = true;
            if (configRef.equals(thisName)) {
                parseConfig();
            }
            else {
                skipTree("config");
            }
        }
    }

    private void parseConfig() throws XMLStreamException, EndDocumentException {
        // cursor --> <config>
        // as we cruise through the section pull off any found <system-property>
        // I.e. <system-property> AND <java-config> are both children of <config>
        // Note that if the system-property already exists -- we do NOT override it.
        // the <server> system-property takes precedence
        // bnevins - 3/20/08 added support for log-service
        while (true) {
            int event = next();
            // return when we get to the </config>
            if (event == END_ELEMENT) {
                if ("config".equals(parser.getLocalName())) {
                    return;
                }
            }
            else if (event == START_ELEMENT) {
                String name = parser.getLocalName();
                if ("system-property".equals(name)) {
                    parseSystemPropertyNoOverride();
                }
                else if ("java-config".equals(name)) {
                    parseJavaConfig();
                }
                else if ("http-service".equals(name)) {
                    parseHttpService();
                }
                else if ("network-config".equals(name)) {
                    sawNetworkConfig = true;
                    parseNetworkConfig();
                }
                else if ("monitoring-service".equals(name)) {
                    parseMonitoringService();
                }
                else {
                    skipTree(name);
                }
            }
        }
    }

    private void parseNetworkConfig()
            throws XMLStreamException, EndDocumentException {
        // cursor --> <network-config>
        while (true) {
            int event = next();
            // return when we get to the </network-config>
            if (event == END_ELEMENT) {
                if ("network-config".equals(parser.getLocalName())) {
                    return;
                }
            }
            else if (event == START_ELEMENT) {
                String name = parser.getLocalName();
                if ("protocols".equals(name)) {
                    parseProtocols();
                }
                else if ("network-listeners".equals(name)) {
                    parseListeners();
                }
                else
                    skipTree(name);
            }
        }
    }

    private void parseSysPropsFromServer() throws XMLStreamException, EndDocumentException {
        // cursor --> <server>
        // these are the system-properties that OVERRIDE the ones in the <config>
        // This code executes BEFORE the <config> is read so we can just add them to the Map here
        // w/o doing anything special.
        while (true) {
            int event = next();
            // return when we get to the </config>
            if (event == END_ELEMENT) {
                if ("server".equals(parser.getLocalName())) {
                    return;
                }
            }
            else if (event == START_ELEMENT) {
                String name = parser.getLocalName();
                if ("system-property".equals(name)) {
                    parseSystemPropertyWithOverride();
                }
                else {
                    skipTree(name);
                }
            }
        }
    }

    private void parseSystemPropertyNoOverride() {
        parseSystemProperty(false);
    }

    private void parseSystemPropertyWithOverride() {
        parseSystemProperty(true);
    }

    private void parseSystemProperty(boolean override) {
        // cursor --> <system-property>
        Map<String, String> map = parseAttributes();
        String name = map.get("name");
        String value = map.get("value");
        if (name != null) {
            if (override || !sysProps.containsKey(name)) {
                sysProps.put(name, value);
            }
        }
    }

    private void parseJavaConfig() throws XMLStreamException, EndDocumentException {
        // cursor --> <java-config>
        // get the attributes for <java-config>
        javaConfig = parseAttributes();
        parseJvmAndProfilerOptions();
    }

    private void parseJvmAndProfilerOptions() throws XMLStreamException, EndDocumentException {
        while (skipToButNotPast("java-config", "jvm-options", "profiler")) {
            if ("jvm-options".equals(parser.getLocalName())) {
                jvmOptions.add(parser.getElementText());
            }
            else {// profiler
                parseProfiler();
            }
        }
    }

    private void parseProfiler() throws XMLStreamException, EndDocumentException {
        // cursor --> START_ELEMENT of profiler
        // it has attributes and <jvm-options>'s and <property>'s
        profilerConfig = parseAttributes();
        while (skipToButNotPast("profiler", "jvm-options", "property")) {
            if ("jvm-options".equals(parser.getLocalName())) {
                profilerJvmOptions.add(parser.getElementText());
            }
            else {
                parseProperty(profilerSysProps);
            }
        }
    }

    private void parseProperty(Map<String, String> map) {
        // cursor --> START_ELEMENT of property
        // it has 2 attributes:  name and value
        Map<String, String> prop = parseAttributes();
        String name = prop.get("name");
        String value = prop.get("value");
        if (name != null) {
            map.put(name, value);
        }
    }

    private void skipNonStartElements() throws XMLStreamException, EndDocumentException {
        while (true) {
            int event = next();
            if (event == START_ELEMENT) {
                return;
            }
        }
    }

    private void skipRoot(String name) throws XMLStreamException, EndDocumentException {
        // The cursor is pointing at the start of the document
        // Move to the first 'top-level' element under name
        // Return with cursor pointing to first sub-element
        while (true) {
            int event = next();
            if (event == START_ELEMENT) {
                if (!name.equals(parser.getLocalName())) {
                    throw new XMLStreamException("Unknown Domain XML Layout");
                }
                return;
            }
        }
    }

    /**
     * The cursor will be pointing at the START_ELEMENT of name1 or name2 when it returns note that skipTree must be
     * called.  Otherwise we could be fooled by a sub-element with the same name as an outer element
     *
     * @param name1 first eligible Element to skip to
     * @param name2 second eligible Element to skip to
     *
     * @throws XMLStreamException
     */
    private void skipTo(String name1, String name2) throws XMLStreamException, EndDocumentException {
        while (true) {
            skipNonStartElements();
            // cursor is at a START_ELEMENT
            String localName = parser.getLocalName();
            if (name1.equals(localName) || name2.equals(localName)) {
                return;
            }
            else {
                skipTree(localName);
            }
        }
    }

    /**
     * The cursor will be pointing at the START_ELEMENT of name when it returns note that skipTree must be called.
     * Otherwise we could be fooled by a sub-element with the same name as an outer element Multiple startNames are
     * accepted.
     *
     * @param endName the Element to skip to
     *
     * @throws XMLStreamException
     */
    private boolean skipToButNotPast(String endName, String... startNames)
            throws XMLStreamException, EndDocumentException {
        while (true) {
            int event = next();
            if (event == START_ELEMENT) {
                for (String s : startNames) {
                    if (parser.getLocalName().equals(s)) {
                        return true;
                    }
                }
            }
            if (event == END_ELEMENT) {
                if (parser.getLocalName().equals(endName)) {
                    return false;
                }
            }
        }
    }

    private void skipTree(String name) throws XMLStreamException, EndDocumentException {
        // The cursor is pointing at the start-element of name.
        // throw everything in this element away and return with the cursor
        // pointing at its end-element.
        while (true) {
            int event = next();
            if (event == END_ELEMENT && name.equals(parser.getLocalName())) {
                //System.out.println("END: " + parser.getLocalName());
                return;
            }
        }
    }

    private void skipToEnd(String name) throws XMLStreamException, EndDocumentException {
        // The cursor is pointing who-knows-where
        // throw everything away and return with the cursor
        // pointing at the end-element.
        while (true) {
            int event = next();
            if (event == END_ELEMENT && name.equals(parser.getLocalName())) {
                return;
            }
        }
    }

    private int next() throws XMLStreamException, EndDocumentException {
        int event = parser.next();
        if (event == END_DOCUMENT) {
            parser.close();
            throw new EndDocumentException();
        }
        return event;
    }

    private void findDomainNameAndEnd() {
        try {
            // find the domain name, if it is there
            // If we bump into the domain end tag first -- no sweat
            while (skipToButNotPast("domain", "property")) {
                parseDomainName(); // property found -- maybe it is the domain name?
            }
            if (domainName == null) {
                Logger.getLogger(MiniXmlParser.class.getName()).log(
                        Level.INFO, strings.get("noDomainName"));

            }
        }
        catch (Exception e) {
            throw new RuntimeException(strings.get("noDomainEnd"));
        }
    }

    private void parseDomainName() {
        // cursor --> pointing at "property" element that is a child of "domain" element
        // <property name="administrative.domain.name" value="domain1"/>
        if (domainName != null) {
            return; // found it already
        }
        Map<String, String> map = parseAttributes();
        String name = map.get("name");
        String value = map.get("value");
        if (name == null || value == null) {
            return;
        }
        if ("administrative.domain.name".equals(name)) {
            domainName = value;
        }
    }

    private void parseMonitoringService() {
        // The default is, by definition, true.
        // Here are all the possibilities and their resolution:
        // 1. Attribute is not present  --> true
        // 2. Attribute is present and set to the exact string "false" --> false
        // 3. Attribute is present and set to anything except "false"  --> true
        String s = parseAttributes().get("monitoring-enabled");
        if (s == null) {
            monitoringEnabled = true;  // case 1
        }
        else if ("false".equals(s)) {
            monitoringEnabled = false; // case 2
        }
        else {
            monitoringEnabled = true;  // case 3
        }
    }

    private void parseHttpService() throws XMLStreamException, EndDocumentException {
        // cursor --> <http-service> in <config>
        // we are looking for the virtual server: "DEFAULT_ADMIN_VS_ID".
        // inside it will be a ref. to a listener.  We get the port from the listener.
        // So -- squirrel away a copy of all the listeners and all the virt. servers --
        //then post-process.
        // Load the collections with both kinds of elements' attributes
        while (true) {
            skipToButNotPast("http-service", "http-listener", "virtual-server");
            String name = parser.getLocalName();
            if ("http-listener".equals(name)) {
                listenerAttributes.add(parseAttributes());
            }
            else if ("virtual-server".equals(name)) {
                vsAttributes.add(parseAttributes());
            }
            else if ("http-service".equals(name)) {
                break;
            }
        }
        String[] listenerNames = getListenerNamesForVS(DEFAULT_ADMIN_VS_ID, vsAttributes);
        if (listenerNames == null || listenerNames.length == 0) {
            listenerNames = getListenerNamesForVS(DEFAULT_VS_ID, vsAttributes); //plan B
        }
        if (listenerNames == null || listenerNames.length <= 0) {
            return; // can not find ports
        }
        addPortsForListeners(listenerNames);
    }

    private void parseListeners() throws XMLStreamException, EndDocumentException {
        // cursor --> START_ELEMENT of network-listeners
        while (true) {
            skipToButNotPast("network-listeners", "network-listener");
            final String name = parser.getLocalName();
            if ("network-listener".equals(name)) {
                listenerAttributes.add(parseAttributes());
            }
            else if ("network-listeners".equals(name)) {
                break;
            }
        }
    }

    private void parseProtocols() throws XMLStreamException, EndDocumentException {
        // cursor --> START_ELEMENT of protocols
        while (true) {
            skipToButNotPast("protocols", "protocol");
            final String name = parser.getLocalName();
            if ("protocol".equals(name)) {
                protocolAttributes.add(parseAttributes());
            }
            else if ("protocols".equals(name)) {
                break;
            }
        }
    }

    private String[] getListenerNamesForVS(String vsid, List<Map<String, String>> vsAttributes) {
        String listeners = null;
        String[] listenerArray = null;
        // find the virtual server
        for (Map<String, String> atts : vsAttributes) {
            String id = atts.get("id");
            if (id != null && id.equals(vsid)) {
                listeners = atts.get("network-listeners");
                if (listeners == null) {
                    listeners = atts.get("http-listeners");
                }
                break;
            }
        }
        // make sure the "http-listeners" is kosher
        if (GFLauncherUtils.ok(listeners)) {
            listenerArray = listeners.split(",");
            if (listenerArray != null && listenerArray.length <= 0) {
                listenerArray = null;
            }
        }
        if (listenerArray == null) {
            listenerArray = new String[0];
        }
        return listenerArray;
    }

    private void addPortsForListeners(String[] listenerNames) {
        // get the addresses and port numbers for all the listeners
        // normally there is one listener
        if (listenerNames != null && listenerNames.length > 0) {
            for (Map<String, String> atts : listenerAttributes) {
                String id = atts.get("name");
                if (id == null) {
                    id = atts.get("id");
                }
                if (id != null) {
                    for (String listenerName : listenerNames) {
                        if (id.equals(listenerName)) {
                            int port = getPort(atts.get("port"));
                            if (port >= 0) {
                                String addr = atts.get("address");
                                if (!GFLauncherUtils.ok(addr))
                                    addr = "localhost";
                                if (StringUtils.isToken(addr))
                                    addr = sysProps.get(
                                            StringUtils.stripToken(addr));
                                boolean secure = false;
                                String protocol = atts.get("protocol");
                                atts = getProtocolByName(protocol);
                                if (atts != null) {
                                    String sec = atts.get("security-enabled");
                                    secure = sec != null
                                            && "true".equalsIgnoreCase(sec);
                                }
                                if (GFLauncherUtils.ok(addr))
                                    adminAddresses.add(
                                            new HostAndPort(addr, port, secure));
                            }
                            break;
                        }
                    }
                }
            }
        }
    }

    private int getPort(String portString) {
        int port = -1;
        try {
            port = Integer.parseInt(portString);
        }
        catch (Exception e) {
            // HEY!  Why are you not checking BEFORE the Exception?
            // Well, it might be slower to call isToken() on strings that consist
            // of just numbers.  We just do this stuff if necessary...
            try {
                portString = sysProps.get(StringUtils.stripToken(portString));

                if (portString != null && portString.length() > 0) {
                    port = Integer.parseInt(portString);
                }
            }
            catch (Exception e2) {
                // GI but not GO !
            }
        }
        return port;
    }

    Map<String, String> getProtocolByName(String name) {
        for (Map<String, String> atts : protocolAttributes) {
            String id = atts.get("name");
            if (id == null) {
                id = atts.get("id");
            }
            if (id != null && id.equals(name))
                return atts;
        }
        return null;
    }

    private Map<String, String> parseAttributes() {
        int num = parser.getAttributeCount();
        Map<String, String> map = new HashMap<String, String>();
        for (int i = 0; i < num; i++) {
            map.put(parser.getAttributeName(i).getLocalPart(), parser.getAttributeValue(i));
        }
        return map;
    }

    // this is so we can return from arbitrarily nested calls
    private static class EndDocumentException extends Exception {
        EndDocumentException() {
        }
    }
    private static final String DEFAULT_ADMIN_VS_ID = "__asadmin";
    private static final String DEFAULT_VS_ID = "server";
    private LoggingConfigImpl loggingConfig = new LoggingConfigImpl();
    private File domainXml;
    private XMLStreamReader parser;
    private InputStreamReader reader;
    private String serverName;
    private String configRef;
    private List<String> jvmOptions = new ArrayList<String>();
    private List<String> profilerJvmOptions = new ArrayList<String>();
    private Map<String, String> javaConfig;
    private Map<String, String> profilerConfig = Collections.emptyMap();
    private Map<String, String> sysProps = new HashMap<String, String>();
    private Map<String, String> profilerSysProps = new HashMap<String, String>();
    private boolean valid = false;
    private List<HostAndPort> adminAddresses = new ArrayList<HostAndPort>();
    private String domainName;
    private static final LocalStringsImpl strings = new LocalStringsImpl(MiniXmlParser.class);
    private boolean monitoringEnabled = true; // Issue 12762 Absent <monitoring-service /> element means monitoring-enabled=true by default
    private List<Map<String, String>> vsAttributes = new ArrayList<Map<String, String>>();
    private List<Map<String, String>> listenerAttributes = new ArrayList<Map<String, String>>();
    private List<Map<String, String>> protocolAttributes = new ArrayList<Map<String, String>>();
    private boolean sawNetworkConfig;
    private boolean sawDefaultConfig;
}
