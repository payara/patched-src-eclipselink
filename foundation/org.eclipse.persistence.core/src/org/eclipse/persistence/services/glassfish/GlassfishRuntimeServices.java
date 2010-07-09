/*******************************************************************************
 * Copyright (c) 1998, 2010 Oracle. All rights reserved.
 * This program and the accompanying materials are made available under the 
 * terms of the Eclipse Public License v1.0 and Eclipse Distribution License v. 1.0 
 * which accompanies this distribution. 
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at 
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * Contributors:
 *     @author  mobrien
 *     @since   EclipseLink 2.1.1 enh# 316512
 *     06/30/2010-2.1.1 Michael O'Brien 
 *       - 316513: Enable JMX MBean functionality for JBoss, Glassfish and WebSphere in addition to WebLogic
 *       Move JMX MBean generic registration code up from specific platforms
 *       see <link>http://wiki.eclipse.org/EclipseLink/DesignDocs/316513</link>        
 ******************************************************************************/  
package org.eclipse.persistence.services.glassfish;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.Vector;
import java.util.regex.PatternSyntaxException;

import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularData;
import javax.management.openmbean.TabularDataSupport;
import javax.management.openmbean.TabularType;

import org.eclipse.persistence.descriptors.ClassDescriptor;
import org.eclipse.persistence.internal.databaseaccess.DatabaseAccessor;
import org.eclipse.persistence.internal.databaseaccess.DatasourcePlatform;
import org.eclipse.persistence.internal.helper.ClassConstants;
import org.eclipse.persistence.internal.helper.Helper;
import org.eclipse.persistence.internal.identitymaps.CacheIdentityMap;
import org.eclipse.persistence.internal.identitymaps.CacheKey;
import org.eclipse.persistence.internal.identitymaps.FullIdentityMap;
import org.eclipse.persistence.internal.identitymaps.HardCacheWeakIdentityMap;
import org.eclipse.persistence.internal.identitymaps.IdentityMap;
import org.eclipse.persistence.internal.identitymaps.NoIdentityMap;
import org.eclipse.persistence.internal.identitymaps.SoftCacheWeakIdentityMap;
import org.eclipse.persistence.internal.identitymaps.SoftIdentityMap;
import org.eclipse.persistence.internal.identitymaps.WeakIdentityMap;
import org.eclipse.persistence.internal.sessions.AbstractSession;
import org.eclipse.persistence.internal.sessions.DatabaseSessionImpl;
import org.eclipse.persistence.logging.AbstractSessionLog;
import org.eclipse.persistence.logging.DefaultSessionLog;
import org.eclipse.persistence.logging.JavaLog;
import org.eclipse.persistence.logging.SessionLog;
import org.eclipse.persistence.platform.server.sunas.SunAS9ServerPlatform;
import org.eclipse.persistence.services.RuntimeServices;
import org.eclipse.persistence.sessions.DatabaseLogin;
import org.eclipse.persistence.sessions.DefaultConnector;
import org.eclipse.persistence.sessions.server.ConnectionPool;
import org.eclipse.persistence.sessions.server.ServerSession;

/**
 * <p>
 * <b>Purpose</b>: Provide a dynamic interface into the EclipseLink Session.
 * <p>
 * <b>Description</b>: This class is meant to provide facilities for managing an EclipseLink session external
 * to EclipseLink over JMX.
 * 
 * @since EclipseLink 2.1.1
 */
public class GlassfishRuntimeServices extends RuntimeServices {

    static {
        PLATFORM_NAME = "Glassfish";
    }

    /**
     * PUBLIC:
     *  Default Constructor
     */
    public GlassfishRuntimeServices() {
        super();
    }

    /**
     *  PUBLIC:
     *  Create an instance of GlassfishRuntimeServices to be associated with the provided session
     *
     *  @param session The session to be used with these RuntimeServices
     */
    public GlassfishRuntimeServices(AbstractSession session) {
        super();
        this.session = session;
        this.updateDeploymentTimeData();
    }

    /**
     *  Create an instance of GlassfishRuntimeServices to be associated with the provided locale
     *
     *  The user must call setSession(Session) afterwards to define the session.
     */
    public GlassfishRuntimeServices(Locale locale) {
    }

    /**
     *  INTERNAL:
     *  Define the session that this instance is providing runtime services for
     *
     *  @param Session session The session to be used with these RuntimeServices
     */
    protected void setSession(AbstractSession newSession) {
        this.session = newSession;
        this.updateDeploymentTimeData();
    }

    /**
     * Answer the name of the EclipseLink session this MBean represents.
     */
    public String getSessionName() {
        return getSession().getName();
    }

    /**
     * Answer the type of the EclipseLink session this MBean represents.
     * Types include: "ServerSession", "DatabaseSession", "SessionBroker"
     */
    public String getSessionType() {
        return Helper.getShortClassName(getSession().getClass());
    }

    /**
     * Provide an instance of 2 Dimensional Array simulating tabular format information about all
     * classes in the session whose class names match the provided filter.
     *
     * The 2 Dimensional array contains each item with values being row object array. Each row object array 
     * represents EclipseLink class details info with respect to below attributes:  
     * ["Class Name", "Parent Class Name",  "Cache Type", "Configured Size", "Current Size"]
     *
     */
    public Object[][] getClassSummaryDetailsUsingFilter(String filter){
        try{
           return  tabularDataTo2DArray(buildClassSummaryDetailsUsingFilter(filter),new String[] {
               "Class Name", "Parent Class Name", "Cache Type", "Configured Size", "Current Size" });
        } catch (Exception exception) {
           AbstractSessionLog.getLog().log(SessionLog.SEVERE, "jmx_enabled_platform_mbean_runtime_exception", PLATFORM_NAME, exception); 
        }
        return null;
    }
    
    /**
     * Provide a list of instance of ClassSummaryDetail containing information about the
     * classes in the session whose class names match the provided filter.
     *
     * ClassSummaryDetail is a model specific class that can be used internally by the Portable JMX Framework to
     * convert class attribute to JMX required open type, it has:-
     *    1. model specific type that needs to be converted : ["Class Name", "Parent Class Name",  "Cache Type", "Configured Size", "Current Size"]
     *    2. convert methods.  
     *
     * @param filter A comma separated list of strings to match against.
     * @return A ArrayList of instance of ClassSummaryDetail containing class information for the class names that match the filter.
     */
    public ArrayList <ClassSummaryDetail>  getClassSummaryDetailsUsingFilterArray(String filter) {
        // if the filter is null, return all the details
        if (filter == null) {
            return getClassSummaryDetailsArray();
        }

        try {
            Vector mappedClassNames = getMappedClassNamesUsingFilter(filter);
            String mappedClassName;
            ArrayList classSummaryDetails = new ArrayList<ClassSummaryDetail>();
            // Check if there aren't any classes mapped
            if (mappedClassNames.size() == 0) {
                return null;
            }

            // get details for each class, and add the details to the summary
            for (int index = 0; index < mappedClassNames.size(); index++) {
                mappedClassName = (String)mappedClassNames.elementAt(index);
                classSummaryDetails.add(buildLowlevelDetailsFor(mappedClassName));
            }
            return classSummaryDetails;
        } catch (Exception openTypeException) {
            AbstractSessionLog.getLog().log(SessionLog.SEVERE, "jmx_enabled_platform_mbean_runtime_exception", PLATFORM_NAME, openTypeException);            
            openTypeException.printStackTrace();
        }

        // wait to get requirements from EM
        return null;
    }

    /**
     * Provide a list of instance of ClassSummaryDetail containing information about all
     * classes in the session.
     *
     * ClassSummaryDetail is a model specific class that can be used internally by the Portable JMX Framework to
     * convert class attribute to JMX required open type, it has:-
     *    1. model specific type that needs to be converted : ["Class Name", "Parent Class Name",  "Cache Type", "Configured Size", "Current Size"]
     *    2. convert methods.  
     *
     * @return A ArrayList of instance of ClassSummaryDetail containing class information for the class names that match the filter.
     */
    public ArrayList <ClassSummaryDetail> getClassSummaryDetailsArray() {
        try {            
            Vector mappedClassNames = getMappedClassNames();
            ArrayList classSummaryDetails = new ArrayList<ClassSummaryDetail>();            
            // Check if there aren't any classes mapped
            if (mappedClassNames.size() == 0) {
                return null;
            }

            // get details for each class, and add the details to the summary
            for (int index = 0; index < mappedClassNames.size(); index++) {
                String mappedClassName = (String)mappedClassNames.elementAt(index);
                classSummaryDetails.add(buildLowlevelDetailsFor(mappedClassName));
            }

            return classSummaryDetails;
        } catch (Exception openTypeException) {
            AbstractSessionLog.getLog().log(SessionLog.SEVERE, "jmx_enabled_platform_mbean_runtime_exception", PLATFORM_NAME, openTypeException);
            openTypeException.printStackTrace();
        }

        // wait to get requirements from EM
        return null;
    }
    
    /**
     * PUBLIC: Provide an instance of 2 Dimensional Array simulating tabular format information about all
     * classes in the session.
     *
     * The 2 Dimensional array contains each item with values being row object array. Each row object array 
     * represents EclipseLink class details info with respect to below attributes:  
     * ["Class Name", "Parent Class Name",  "Cache Type", "Configured Size", "Current Size"]
     *
     */
    public Object[][] getClassSummaryDetails() {
        try{
           return tabularDataTo2DArray(buildClassSummaryDetails(),new String[] {
               "Class Name", "Parent Class Name", "Cache Type", "Configured Size", "Current Size" });
        } catch (Exception exception){
            AbstractSessionLog.getLog().log(SessionLog.SEVERE, "jmx_enabled_platform_mbean_runtime_exception", PLATFORM_NAME, exception);
        }
        return null;
    }
    
    /**
     * INTERNAL:
     * Provide an instance of TabularData containing information about the
     * classes in the session whose class names match the provided filter.
     *
     * The TabularData contains rowData with values being CompositeData(s)
     *
     * CompositeData has:
     *    CompositeType: column names are ["Class Name", "Parent Class Name",  "Cache Type", "Configured Size", "Current Size"]
     *
     *  Each CompositeData can have get(myColumnName) sent to it.
     *
     *
     * @param filter A comma separated list of strings to match against.
     * @return A TabularData of information for the class names that match the filter.
     */
    private TabularData buildClassSummaryDetailsUsingFilter(String filter) {
        // if the filter is null, return all the details
        if (filter == null) {
            return buildClassSummaryDetails();
        }

        try {
            Vector mappedClassNames = getMappedClassNamesUsingFilter(filter);
            String mappedClassName;
            TabularDataSupport rowData = new TabularDataSupport(buildTabularTypeForClassSummaryDetails());
            // Check if there aren't any classes mapped
            if (mappedClassNames.size() == 0) {
                return null;
            }

            // get details for each class, and add the details to the summary
            for (int index = 0; index < mappedClassNames.size(); index++) {
                mappedClassName = (String)mappedClassNames.elementAt(index);
                String[] key = new String[] { mappedClassName };
                rowData.put(key, buildDetailsFor(mappedClassName, rowData.getTabularType().getRowType()));
            }
            return rowData;
        } catch (Exception exception) {
            AbstractSessionLog.getLog().log(SessionLog.SEVERE, "jmx_enabled_platform_mbean_runtime_exception", PLATFORM_NAME, exception);
        }

        // wait to get requirements from EM
        return null;
    }

    /**
     * INTERNAL: 
     * Provide an instance of TabularData containing information about all
     * classes in the session.
     *
     * The TabularData contains rowData with values being CompositeData(s)
     *
     * CompositeData has:
     *    CompositeType: column names are ["Class Name", "Parent Class Name",  "Cache Type", "Configured Size", "Current Size"]
     *
     *  Each CompositeData can have get(myColumnName) sent to it.
     *
     */
    private TabularData buildClassSummaryDetails() {
        try {
            Vector mappedClassNames = getMappedClassNames();
            String mappedClassName;
            TabularDataSupport rowData = new TabularDataSupport(buildTabularTypeForClassSummaryDetails());
            // Check if there aren't any classes mapped
            if (mappedClassNames.size() == 0) {
                return null;
            }

            // get details for each class, and add the details to the summary
            for (int index = 0; index < mappedClassNames.size(); index++) {
                mappedClassName = (String)mappedClassNames.elementAt(index);
                String[] key = new String[] { mappedClassName };
                rowData.put(key, buildDetailsFor(mappedClassName, rowData.getTabularType().getRowType()));
            }

            return rowData;
        } catch (Exception exception) {
            AbstractSessionLog.getLog().log(SessionLog.SEVERE, "jmx_enabled_platform_mbean_runtime_exception", PLATFORM_NAME, exception);
        }

        // wait to get requirements from EM
        return null;
    }

    /**
     * INTERNAL:
     * Answer the fully qualified names of the classes mapped in the session.
     * This uses the mappedClass from the CMPPolicy.
     *
     * @return java.util.Vector
     */
    
    private Vector getMappedClassNames() {
        Hashtable alreadyAdded = new Hashtable();
        Vector mappedClassNames = new Vector();
        String mappedClassName = null;

        Iterator descriptorsIterator = getSession().getProject().getDescriptors()
                .values().iterator();
        while (descriptorsIterator.hasNext()) {
            ClassDescriptor nextDescriptor = (ClassDescriptor) descriptorsIterator.next();

            // differentiate between a generated class and not, by comparing the descriptor's Java class
            if (nextDescriptor.getCMPPolicy() != null) {
                if (nextDescriptor.getCMPPolicy().getMappedClass() != null) {
                    mappedClassName = nextDescriptor.getCMPPolicy().getMappedClass().getName();
                }
            }

            if (mappedClassName == null) {
                mappedClassName = nextDescriptor.getJavaClassName();
            }
            if (alreadyAdded.get(mappedClassName) == null) {
                alreadyAdded.put(mappedClassName, Boolean.TRUE);
                mappedClassNames.addElement(mappedClassName);
            }
            mappedClassName = null;
        }
        return mappedClassNames;
    }

    /**
    *  INTERNAL:
    *  This method traverses the EclipseLink descriptors and returns a Vector of the descriptor's
    *   reference class names that match the provided filter. The filter is a comma separated
    *   list of strings to match against.
    *
    *   @param filter A comma separated list of strings to match against.
    *   @return A Vector of class names that match the filter.
    */
    public Vector getMappedClassNamesUsingFilter(String filter) {
        //Output Vector
        Vector outputVector = new Vector();

        //Input mapped class names
        Vector mappedClassNames = getMappedClassNames();

        //Input filter values
        ArrayList filters = new ArrayList();
        StringTokenizer lineTokens = new StringTokenizer(filter, ",");
        while (lineTokens.hasMoreTokens()) {
            filters.add(lineTokens.nextToken());
        }
        for (int i = 0; i < mappedClassNames.size(); i++) {
            String className = (String)mappedClassNames.get(i);
            String classNameLowerCase = ((String)mappedClassNames.get(i)).toLowerCase();
            for (int j = 0; j < filters.size(); j++) {
                String filterValue = (Helper.rightTrimString((String)filters.get(j)).trim()).toLowerCase();
                if (filterValue.indexOf('*') == 0) {
                    filterValue = filterValue.substring(1);
                }
                try {
                    //Note: String.matches(String regex) since jdk1.4
                    if (classNameLowerCase.matches(new StringBuffer().append("^.*").append(filterValue).append(".*$").toString())) {
                        if (!outputVector.contains(className)) {
                            outputVector.add(className);
                        }
                    }
                } catch (PatternSyntaxException exception) {
                    //regular expression syntax error
                    AbstractSessionLog.getLog().log(SessionLog.FINEST, "pattern_syntax_error", exception);
                }
            }
        }
        Collections.sort(outputVector);
        return outputVector;
    }

    /**
     * INTERNAL:
     * Answer the TabularType describing the TabularData that we return from
     * getCacheSummaryDetails() and getCacheSummaryDetails(String filter)
     *
     * This is mostly for the client side to see what kind of information is returned.
     *
     * @return javax.management.openmbean.TabularType
     */
    private TabularType buildTabularTypeForClassSummaryDetails() throws OpenDataException {
        return new TabularType(getSessionName(), "Session description", buildCompositeTypeForClassSummaryDetails(),
                new String[] { "Class Name" });
    }

    /**
     * INTERNAL:
     * Answer the CompositeType describing the CompositeData that we return for
     * each IdentityMap (or subclass).
     *
     * This is mostly for the client side to see what kind of information is returned.
     * @return javax.management.openmbean.CompositeType
     */
    private CompositeType buildCompositeTypeForClassSummaryDetails() throws OpenDataException {
        return new CompositeType("Class Details", "Details of class for Class Summary", new String[] { 
                "Class Name", "Parent Class Name", "Cache Type", "Configured Size", "Current Size" }, new String[] { 
                "Class Name", "Parent Class Name", "Cache Type", "Configured Size", "Current Size" }, new OpenType[] { 
                SimpleType.STRING, SimpleType.STRING, SimpleType.STRING, SimpleType.STRING, SimpleType.STRING });
    }

    /**
     * INTERNAL:
     * Answer the CompositeData containing the cache details for the given mappedClassName
     * This uses a CompositeDataSupport, which implements CompositeData
     *
     * @param String mappedClassName: fullyQualified class name of the class
     * @param CompositeType detailsType: describes the format of the returned CompositeData

     * @return javax.management.openmbean.CompositeData
     */
    private CompositeData buildDetailsFor(String mappedClassName, CompositeType detailsType) throws Exception {
        return new CompositeDataSupport(detailsType, buildLowlevelDetailsFor(mappedClassName));
    }

    /**
     * INTERNAL:
     * Helper to build a HashMap to help in the construction of a CompositeData
     *
     * @param String mappedClassName: fullyQualified class name of the class

     * @return HashMap
     */
    private HashMap buildLowlevelDetailsFor(String mappedClassName) {
        Class mappedClass = (Class)getSession().getDatasourcePlatform().getConversionManager().convertObject(mappedClassName, ClassConstants.CLASS);
        IdentityMap identityMap = getSession().getIdentityMapAccessorInstance().getIdentityMap(mappedClass);
        ClassDescriptor descriptor = getSession().getProject().getDescriptor(mappedClass);

        String cacheType = getCacheTypeFor(identityMap.getClass());
        String configuredSize = "" + identityMap.getMaxSize();
        String currentSize = "";

        //show the current size, including subclasses 
        currentSize = "" + identityMap.getSize(mappedClass, true);

        String parentClassName = "";

        boolean isChildDescriptor = descriptor.isChildDescriptor();

        HashMap details = new HashMap();

        details.put("Class Name", mappedClassName);
        details.put("Cache Type", (isChildDescriptor ? "" : cacheType));
        details.put("Configured Size", (isChildDescriptor ? "" : configuredSize));
        details.put("Current Size", currentSize);
        //If I have a parent class name, get it. Otherwise, leave blank
        if (descriptor.hasInheritance()) {
            if (descriptor.getInheritancePolicy().getParentDescriptor() != null) {
                parentClassName = descriptor.getInheritancePolicy().getParentClassName();
            }
        }
        details.put("Parent Class Name", parentClassName);

        return details;
    }

    /**
     * INTERNAL:
     * Helper to build a HashMap to help in the construction of a CompositeData
     *
     * @param String mappedClassName: fullyQualified class name of the class

     * @return HashMap
     */
    private ClassSummaryDetail buildLowlevelDetailsForNew(String mappedClassName) {
        Class mappedClass = (Class)getSession().getDatasourcePlatform().getConversionManager().convertObject(mappedClassName, ClassConstants.CLASS);
        IdentityMap identityMap = getSession().getIdentityMapAccessorInstance().getIdentityMap(mappedClass);
        ClassDescriptor descriptor = getSession().getProject().getDescriptor(mappedClass);

        String cacheType = getCacheTypeFor(identityMap.getClass());
        String configuredSize = "" + identityMap.getMaxSize();
        String currentSize = "";

        //show the current size, including subclasses 
        currentSize = "" + identityMap.getSize(mappedClass, true);

        String parentClassName = "";

        boolean isChildDescriptor = descriptor.isChildDescriptor();

        ClassSummaryDetail details = new ClassSummaryDetail(
                mappedClassName,
                (isChildDescriptor ? "" : cacheType),
                (isChildDescriptor ? "" : configuredSize),
                currentSize,
                parentClassName);

        return details;
    }
    
    /**
     * INTERNAL:
     * getCacheTypeFor: Give a more UI-friendly version of the cache type
     */
    private String getCacheTypeFor(Class identityMapClass) {
        if (identityMapClass == CacheIdentityMap.class) {
            return "Cache";
        } else if (identityMapClass == FullIdentityMap.class) {
            return "Full";
        } else if (identityMapClass == HardCacheWeakIdentityMap.class) {
            return "HardWeak";
        } else if (identityMapClass == NoIdentityMap.class) {
            return "None";
        } else if (identityMapClass == SoftCacheWeakIdentityMap.class) {
            return "SoftWeak";
        } else if (identityMapClass == WeakIdentityMap.class) {
            return "Weak";
        } else if (identityMapClass == SoftIdentityMap.class) {
            return "Soft";
        }
        return "N/A";
    }

    /**
     * getModuleName(): Answer the name of the context-root of the application that this session is associated with.
     * Answer "unknown" if there is no module name available.
     * Default behavior is to return "unknown"
     */
    public String getModuleName() {
        return ((DatabaseSessionImpl)getSession())
            .getServerPlatform().getModuleName();
    }

    
    /**
     * getApplicationName(): Answer the name of the module (EAR name) that this session is associated with.
     * Answer "unknown" if there is no application name available.
     * Default behavior is to return "unknown"
     */
    public String getApplicationName() {
        return ((SunAS9ServerPlatform)((DatabaseSessionImpl)getSession())
                .getServerPlatform()).getApplicationName();
    }

    /**
     *     Method returns if all Parameters should be bound or not
     */
    public Boolean getShouldBindAllParameters() {
        if (!(getSession().getDatasourceLogin() instanceof DatabaseLogin)) {
            return Boolean.FALSE;
        }
        return Boolean.valueOf(((DatabaseLogin)getSession().getDatasourceLogin()).shouldBindAllParameters());
    }

    /**
      *     Return the size of strings after which will be bound into the statement
      *     If we are not using a DatabaseLogin, or we're not using string binding,
      *     answer 0 (zero).
      */
    public Integer getStringBindingSize() {
        if (!(getSession().getDatasourceLogin() instanceof DatabaseLogin)) {
            return Integer.valueOf(0);
        }
        if (!((DatabaseLogin)getSession().getDatasourceLogin()).getPlatform().usesStringBinding()) {
            return Integer.valueOf(0);
        }
        return Integer.valueOf(((DatabaseLogin)getSession().getDatasourceLogin()).getStringBindingSize());
    }

    /**
      *        This method will return if batchWriting is in use or not.
      */
    public Boolean getUsesBatchWriting() {
        return Boolean.valueOf(getSession().getDatasourceLogin().getPlatform().usesBatchWriting());
    }

    /**
      *        This method will return a long indicating the exact time in Milliseconds that the
      *   session connected to the database.
      */
    public Long getTimeConnectionEstablished() {
        return Long.valueOf(((DatabaseSessionImpl)getSession()).getConnectedTime());
    }

    /**
      *        This method will return if batchWriting is in use or not.
      */
    public Boolean getUsesJDBCBatchWriting() {
        return Boolean.valueOf(getSession().getDatasourceLogin().getPlatform().usesJDBCBatchWriting());
    }

    /**
      *     Shows if Byte Array Binding is turned on or not
      */
    public Boolean getUsesByteArrayBinding() {
        return Boolean.valueOf(getSession().getDatasourceLogin().getPlatform().usesByteArrayBinding());
    }

    /**
      *     Shows if native SQL is being used
      */
    public Boolean getUsesNativeSQL() {
        return Boolean.valueOf(getSession().getDatasourceLogin().getPlatform().usesNativeSQL());
    }

    /**
      *     This method indicates if streams are being used for binding
      */
    public Boolean getUsesStreamsForBinding() {
        return Boolean.valueOf(getSession().getDatasourceLogin().getPlatform().usesStreamsForBinding());
    }

    /**
      *     This method indicates if Strings are being bound
      */
    public Boolean getUsesStringBinding() {
        if (!(getSession().getDatasourceLogin() instanceof DatabaseLogin)) {
            return Boolean.FALSE;
        }
        return Boolean.valueOf(((DatabaseLogin)getSession().getDatasourceLogin()).getPlatform().usesStringBinding());
    }

    /**
    *     Returns if statements should be cached or not
    */
    public boolean getShouldCacheAllStatements() {
        if (!(getSession().getDatasourceLogin() instanceof DatabaseLogin)) {
            return Boolean.FALSE;
        }
        return Boolean.valueOf(((DatabaseLogin)getSession().getDatasourceLogin()).shouldCacheAllStatements());
    }

    /**
    *        Returns the statement cache size.  Only valid if statements are being cached
    */
    public int getStatementCacheSize() {
        if (!(getSession().getDatasourceLogin() instanceof DatabaseLogin)) {
            return 0;
        }
        return Integer.valueOf(((DatabaseLogin)getSession().getDatasourceLogin()).getStatementCacheSize());
    }

    /**
    *     Used to clear the statement cache. Only valid if statements are being cached
    */
    public synchronized void clearStatementCache() {
        if (!(getSession().getDatasourceLogin() instanceof DatabaseLogin)) {
            return;
        }
        ((DatabaseAccessor)getSession().getAccessor()).clearStatementCache(getSession());
        ((AbstractSession)session).log(SessionLog.INFO, SessionLog.SERVER, "jmx_mbean_runtime_services_statement_cache_cleared");        
        
    }

    /**
    *        Method returns the value of the Sequence Preallocation size
    */
    public int getSequencePreallocationSize() {
        if (!(getSession().getDatasourceLogin() instanceof DatabaseLogin)) {
            return 0;
        }
        return ((DatasourcePlatform)getSession().getDatasourcePlatform()).getSequencePreallocationSize();        
    }

    /**
    *     This method will print the available Connection pools to the SessionLog.
    * @return void
    */
    public void printAvailableConnectionPools() {
        if (ClassConstants.ServerSession_Class.isAssignableFrom(getSession().getClass())) {
            Map pools = ((ServerSession)getSession()).getConnectionPools();
            Iterator poolNames = pools.keySet().iterator();
            while (poolNames.hasNext()) {
                String poolName = poolNames.next().toString();
                ((AbstractSession)session).log(SessionLog.INFO, SessionLog.SERVER, "jmx_mbean_runtime_services_pool_name", poolName);
            }
        } else {
            ((AbstractSession)session).log(SessionLog.INFO, SessionLog.SERVER, "jmx_mbean_runtime_services_no_connection_pools_available");            
            
        }
    }

    /**
    *     This method will retrieve the max size of a particular connection pool
    * @param poolName the name of the pool to get the max size for
    * @return Integer for the max size of the pool. Return -1 if pool doesn't exist.
    */
    public Integer getMaxSizeForPool(String poolName) {
        if (ClassConstants.ServerSession_Class.isAssignableFrom(getSession().getClass())) {
            ConnectionPool connectionPool = ((ServerSession)getSession()).getConnectionPool(poolName);
            if (connectionPool != null) {
                return Integer.valueOf(connectionPool.getMaxNumberOfConnections());
            }
        }
        return Integer.valueOf(-1);
    }

    /**
    *     This method will retrieve the min size of a particular connection pool
    * @param poolName the name of the pool to get the min size for
    * @return Integer for the min size of the pool. Return -1 if pool doesn't exist.
    */
    public Integer getMinSizeForPool(String poolName) {
        if (ClassConstants.ServerSession_Class.isAssignableFrom(getSession().getClass())) {
            ConnectionPool connectionPool = ((ServerSession)getSession()).getConnectionPool(poolName);
            if (connectionPool != null) {
                return Integer.valueOf(connectionPool.getMinNumberOfConnections());
            }
        }
        return Integer.valueOf(-1);
    }

    /**
    * This method is used to reset connections from the session to the database.  Please
    * Note that this will not work with a SessionBroker at this time
    */
    public synchronized void resetAllConnections() {
        if (ClassConstants.ServerSession_Class.isAssignableFrom(getSession().getClass())) {
            Iterator enumtr = ((ServerSession)getSession()).getConnectionPools().values().iterator();
            while (enumtr.hasNext()) {
                ConnectionPool pool = (ConnectionPool)enumtr.next();
                pool.shutDown();
                pool.startUp();
            }
        } else if (ClassConstants.PublicInterfaceDatabaseSession_Class.isAssignableFrom(getSession().getClass())) {
            getSession().getAccessor().reestablishConnection(getSession());
        }
    }

    /**
    *        This method is used to output those Class Names that have identity Maps in the Session.
    * Please note that SubClasses and aggregates will be missing from this list as they do not have
    * separate identity maps.
    * @return void
    */
    public void printClassesInSession() {
        Vector classes = getSession().getIdentityMapAccessorInstance().getIdentityMapManager().getClassesRegistered();
        int index;
        if (classes.isEmpty()) {
            ((AbstractSession)session).log(SessionLog.INFO, SessionLog.SERVER, "jmx_mbean_runtime_services_no_classes_in_session");            
            return;
        }

        for (index = 0; index < classes.size(); index++) {
            getSession().getSessionLog().log(SessionLog.FINEST, (String)classes.elementAt(index));
        }
    }

    /**
    *        This method will log the objects in the Identity Map.
    * There is no particular order to these objects.
    * @param className the fully qualified classname identifying the identity map
    * @exception  thrown then the IdentityMap for that class name could not be found
    */
    public void printObjectsInIdentityMap(String className) throws ClassNotFoundException {
        Class classWithMap = (Class)getSession().getDatasourcePlatform().getConversionManager().convertObject(className, ClassConstants.CLASS);
        IdentityMap map = getSession().getIdentityMapAccessorInstance().getIdentityMap(classWithMap);

        //check if the identity map exists
        if (null == map) {
            ((AbstractSession)session).log(SessionLog.INFO, SessionLog.SERVER, "jmx_mbean_runtime_services_identity_map_non_existent", className);
            return;
        }

        //check if there are any objects in the identity map. Print if so.
        Enumeration objects = map.keys();
        if (!objects.hasMoreElements()) {
            ((AbstractSession)session).log(SessionLog.INFO, SessionLog.SERVER, "jmx_mbean_runtime_services_identity_map_empty", className);
        }

        CacheKey cacheKey;
        while (objects.hasMoreElements()) {
            cacheKey = (CacheKey)objects.nextElement();
            ((AbstractSession)session).log(SessionLog.INFO, SessionLog.SERVER, "jmx_mbean_runtime_services_print_cache_key_value", 
                    cacheKey.getKey().toString(), cacheKey.getObject().toString());
        }
    }

    /**
    *        This method will log the types of Identity Maps in the session.
    */
    public void printAllIdentityMapTypes() {
        Vector classesRegistered = getSession().getIdentityMapAccessorInstance().getIdentityMapManager().getClassesRegistered();
        String registeredClassName;
        Class registeredClass;

        //Check if there aren't any classes registered
        if (classesRegistered.size() == 0) {
            ((AbstractSession)session).log(SessionLog.INFO, SessionLog.SERVER, "jmx_mbean_runtime_services_no_identity_maps_in_session");
            return;
        }

        //get each identity map, and log the type
        for (int index = 0; index < classesRegistered.size(); index++) {
            registeredClassName = (String)classesRegistered.elementAt(index);
            registeredClass = (Class)getSession().getDatasourcePlatform().getConversionManager().convertObject(registeredClassName, ClassConstants.CLASS);
            IdentityMap map = getSession().getIdentityMapAccessorInstance().getIdentityMap(registeredClass);
            ((AbstractSession)session).log(SessionLog.INFO, SessionLog.SERVER, "jmx_mbean_runtime_services_identity_map_class", 
                    registeredClassName, map.getClass());
        }
    }

    /**
    *        This method will log all objects in all Identity Maps in the session.
    */
    public void printObjectsInIdentityMaps() {
        Vector classesRegistered = getSession().getIdentityMapAccessorInstance().getIdentityMapManager().getClassesRegistered();
        String registeredClassName;

        //Check if there aren't any classes registered
        if (classesRegistered.size() == 0) {
            ((AbstractSession)session).log(SessionLog.INFO, SessionLog.SERVER, "jmx_mbean_runtime_services_no_identity_maps_in_session");
            return;
        }

        //get each identity map, and log the type
        for (int index = 0; index < classesRegistered.size(); index++) {
            registeredClassName = (String)classesRegistered.elementAt(index);
            try {
                this.printObjectsInIdentityMap(registeredClassName);
            } catch (ClassNotFoundException classNotFound) {
                //we are enumerating registered classes, so this shouldn't happen. Print anyway
                classNotFound.printStackTrace();
                AbstractSessionLog.getLog().log(SessionLog.SEVERE, "jmx_enabled_platform_mbean_runtime_exception", PLATFORM_NAME, classNotFound);
            }
        }
    }

    /**
    *        This method is used to return the number of objects in a particular Identity Map
    * @param className the fully qualified name of the class to get number of instances of.
    * @exception  thrown then the IdentityMap for that class name could not be found
    */
    public Integer getNumberOfObjectsInIdentityMap(String className) throws ClassNotFoundException {
        //BUG 3982060: Always use the root class in combination with the identity map's getSize(class, true) to get an accurate count
        Class classWithIdentityMap = (Class)getSession().getDatasourcePlatform().getConversionManager().convertObject(className, ClassConstants.CLASS);
        Class rootClass = null;

        ClassDescriptor descriptor = getSession().getDescriptor(classWithIdentityMap);
        ClassDescriptor rootDescriptor;

        if (descriptor.hasInheritance()) {
            rootDescriptor = descriptor.getInheritancePolicy().getRootParentDescriptor();
        } else {
            rootDescriptor = descriptor;
        }
        if (rootDescriptor.getCMPPolicy() != null) {
            if (rootDescriptor.getCMPPolicy().getMappedClass() != null) {
                rootClass = rootDescriptor.getCMPPolicy().getMappedClass();
            }
        }

        if (rootClass == null) {
            rootClass = rootDescriptor.getJavaClass();
        }

        return Integer.valueOf(getSession().getIdentityMapAccessorInstance().getIdentityMap(rootClass).getSize(rootClass, true));
    }

    /**
    *        This method will SUM and return the number of objects in all Identity Maps in the session.
    */
    public Integer getNumberOfObjectsInAllIdentityMaps() {
        Vector classesRegistered = getSession().getIdentityMapAccessorInstance().getIdentityMapManager().getClassesRegistered();
        String registeredClassName;
        int sum = 0;

        //Check if there aren't any classes registered
        if (classesRegistered.size() == 0) {
            ((AbstractSession)session).log(SessionLog.INFO, SessionLog.SERVER, "jmx_mbean_runtime_services_no_identity_maps_in_session");
            return Integer.valueOf(0);
        }

        //get each identity map, and log the size
        for (int index = 0; index < classesRegistered.size(); index++) {
            registeredClassName = (String)classesRegistered.elementAt(index);
            try {
                sum += this.getNumberOfObjectsInIdentityMap(registeredClassName).intValue();
            } catch (ClassNotFoundException classNotFound) {
                //we are enumerating registered classes, so this shouldn't happen. Print anyway
                classNotFound.printStackTrace();
                AbstractSessionLog.getLog().log(SessionLog.SEVERE, "jmx_enabled_platform_mbean_runtime_exception", PLATFORM_NAME, classNotFound);
            }
        }

        return Integer.valueOf(sum);
    }

    /**
     * This method will answer the number of persistent classes contained in the session.
     * This does not include aggregates.
     */
    public Integer getNumberOfPersistentClasses() {
        Hashtable classesTable = new Hashtable();
        ClassDescriptor currentDescriptor;

        //use a table to eliminate duplicate classes. Ignore Aggregates
        Iterator descriptors = getSession().getProject().getDescriptors().values().iterator();
        while (descriptors.hasNext()) {
            currentDescriptor = (ClassDescriptor)descriptors.next();
            if (!currentDescriptor.isAggregateDescriptor()) {
                classesTable.put(currentDescriptor.getJavaClassName(), Boolean.TRUE);
            }
        }

        return Integer.valueOf(classesTable.size());
    }


    /**
    * Return the log type, either "EclipseLink",  "Java" or the simple name of the logging class used.  
    *
    * @return the log type
    */
    public String getLogType() {
        if (this.getSession().getSessionLog().getClass() == JavaLog.class) {
            return "Java";
        } else if (this.getSession().getSessionLog().getClass() == DefaultSessionLog.class) {
            return EclipseLink_Product_Name;
        } else {
            return this.getSession().getSessionLog().getClass().getSimpleName();
        }
    }

    /**
    * Return the database platform used by the DatabaseSession.
    *
    * @return String databasePlatform
    */
    public String getDatabasePlatform() {
        return getSession().getDatasourcePlatform().getClass().getName();
    }

    /**
    *        Return JDBCConnection detail information. This includes URL and datasource information.
    */
    public synchronized String getJdbcConnectionDetails() {
        return getSession().getLogin().getConnector().getConnectionDetails();
    }

    /**
    *        Return connection pool type. Values include: "Internal", "External" and "N/A".
    */
    public synchronized String getConnectionPoolType() {
        if (getSession().getLogin().shouldUseExternalConnectionPooling()) {
            return "External";
        } else {
            return "N/A";
        }
    }

    /**
    *        Return db driver class name. This only applies to DefaultConnector. Return "N/A" otherwise.
    */
    public synchronized String getDriver() {
        if (getSession().getLogin().getConnector() instanceof DefaultConnector) {
            return getSession().getLogin().getDriverClassName();
        }
        return "N/A";
    }

    /**
    * Return the log filename. This returns the fully qualified path of the log file when
    * EclipseLink DefaultSessionLog instance is used. Null is returned otherwise.
    *
    * @return String logFilename
    */
    public String getLogFilename() {
        // returns String or null.
        if ( session.getSessionLog() instanceof DefaultSessionLog) {
                return ((DefaultSessionLog)session.getSessionLog()).getWriterFilename();
        } else {
            return null;
        }
    }

    /**
    *    This method is used to initialize the identity maps in the session.
    */
    public synchronized void initializeAllIdentityMaps() {
        getSession().getIdentityMapAccessor().initializeAllIdentityMaps();
    }

    /**
    *    This method is used to initialize the identity maps specified by the Vector of classNames.
    *
    * @param classNames String[] of fully qualified classnames identifying the identity maps to initialize
    */
    public synchronized void initializeIdentityMaps(String[] classNames) throws ClassNotFoundException {
        for (int index = 0; index < classNames.length; index++) {
            initializeIdentityMap(classNames[index]);
        }
    }

    /**
    *    This method is used to invalidate the identity maps in the session.
    */
    public synchronized void invalidateAllIdentityMaps() {
        Vector classesRegistered = getSession().getIdentityMapAccessorInstance().getIdentityMapManager().getClassesRegistered();
        String registeredClassName;
        Class registeredClass;

        if (classesRegistered.isEmpty()) {
            getSession().getSessionLog().info("There are no Identity Maps in this session");
            ((AbstractSession)session).log(SessionLog.INFO, SessionLog.SERVER, "jmx_mbean_runtime_services_no_identity_maps_in_session");            
        }

        //get each identity map, and invalidate
        for (int index = 0; index < classesRegistered.size(); index++) {
            registeredClassName = (String)classesRegistered.elementAt(index);
            registeredClass = (Class)getSession().getDatasourcePlatform().getConversionManager()
                .convertObject(registeredClassName, ClassConstants.CLASS);
            getSession().getIdentityMapAccessor().invalidateClass(registeredClass);
            ((AbstractSession)session).log(SessionLog.INFO, SessionLog.SERVER, "jmx_mbean_runtime_services_identity_map_invalidated", registeredClassName);
        }
    }

    /**
    *    This method is used to invalidate the identity maps specified by the String[] of classNames.
    *
    * @param classNames String[] of fully qualified classnames identifying the identity maps to invalidate
    * @param recurse    Boolean indicating if we want to invalidate the children identity maps too
    */
    public synchronized void invalidateIdentityMaps(String[] classNamesParam, Boolean recurse) throws ClassNotFoundException {
        String[] classNames = classNamesParam;
        for (int index = 0; index < classNames.length; index++) {
            invalidateIdentityMap(classNames[index], recurse);
        }
    }

    /**
    *    This method is used to invalidate the identity maps specified by className. This does not
    * invalidate the children identity maps
    *
    * @param className the fully qualified classname identifying the identity map to invalidate
    */
    public synchronized void invalidateIdentityMap(String className) throws ClassNotFoundException {
        this.invalidateIdentityMap(className, Boolean.FALSE);
    }

    /**
    *    This method is used to invalidate the identity maps specified by className.
    *
    * @param className the fully qualified classname identifying the identity map to invalidate
    * @param recurse    Boolean indicating if we want to invalidate the children identity maps too
    */
    public synchronized void invalidateIdentityMap(String className, Boolean recurse) throws ClassNotFoundException {
        Class registeredClass;

        //get identity map, and invalidate
        registeredClass = (Class)getSession().getDatasourcePlatform().getConversionManager()
            .convertObject(className, ClassConstants.CLASS);
        getSession().getIdentityMapAccessor().invalidateClass(registeredClass);
        ((AbstractSession)session).log(SessionLog.INFO, SessionLog.SERVER, "jmx_mbean_runtime_services_identity_map_invalidated", className);
    }

    /**
     * 
    * INTERNAL:
     * Convert the TabularData to a two-dimensional array
     * @param tdata the TabularData to be converted
     * @param names the order of the columns
     * @return a two-dimensional array
     * @throws Exception
     */
    private Object[][] tabularDataTo2DArray(TabularData tdata, String[] names) throws Exception {
        if(tdata==null){
            return null;
        }
        Object[] rows = tdata.values().toArray();
        Object[][] data = new Object[rows.length][];

        for (int i=0; i<rows.length; i++) {
            data[i] = ((CompositeData) rows[i]).getAll(names);
        }
        return data;
    }
    
    
    /**
     * Return whether this session is an EclipseLink JPA session.
     * The absence of this function or a value of false will signify that the session
     * belongs to a provider other than EclipseLink.  
     * @return
     */
    public boolean isJPASession() {
        return true;
    }
}
