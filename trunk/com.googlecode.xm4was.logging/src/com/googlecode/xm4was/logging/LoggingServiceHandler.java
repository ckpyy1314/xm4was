package com.googlecode.xm4was.logging;

import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import com.googlecode.xm4was.commons.TrConstants;
import com.googlecode.xm4was.logging.resources.Messages;
import com.ibm.ejs.csi.DefaultComponentMetaData;
import com.ibm.ejs.ras.Tr;
import com.ibm.ejs.ras.TraceComponent;
import com.ibm.websphere.logging.WsLevel;
import com.ibm.ws.logging.TraceLogFormatter;
import com.ibm.ws.runtime.deploy.DeployedObject;
import com.ibm.ws.runtime.deploy.DeployedObjectEvent;
import com.ibm.ws.runtime.deploy.DeployedObjectListener;
import com.ibm.ws.runtime.metadata.ApplicationMetaData;
import com.ibm.ws.runtime.metadata.ComponentMetaData;
import com.ibm.ws.runtime.metadata.MetaData;
import com.ibm.ws.runtime.metadata.ModuleMetaData;
import com.ibm.ws.threadContext.ComponentMetaDataAccessorImpl;
import com.ibm.ws.util.ThreadPool;
import com.ibm.wsspi.webcontainer.metadata.WebComponentMetaData;
import com.ibm.wsspi.webcontainer.servlet.IServletConfig;

public class LoggingServiceHandler extends Handler implements DeployedObjectListener {
    private static final TraceComponent TC = Tr.register(LoggingServiceHandler.class, TrConstants.GROUP, Messages.class.getName());
    
    private final ComponentMetaDataAccessorImpl cmdAccessor = ComponentMetaDataAccessorImpl.getComponentMetaDataAccessor();
    private final Map<ClassLoader,MetaData> classLoaderMap = new IdentityHashMap<ClassLoader,MetaData>();
    private final LogMessage[] buffer = new LogMessage[1024];
    private int head;
    // We start at System.currentTimeMillis to make sure that the sequence is strictly increasing
    // even across a server restarts
    private final long initialSequence;
    private long nextSequence;
    
    public LoggingServiceHandler() {
        initialSequence = System.currentTimeMillis();
        nextSequence = initialSequence;
    }
    
    public void stateChanged(DeployedObjectEvent event) {
        DeployedObject deployedObject = event.getDeployedObject();
        ClassLoader classLoader = deployedObject.getClassLoader();
        if (classLoader != null) {
            String state = (String)event.getNewValue();
            MetaData metaData = deployedObject.getMetaData();
            if (state.equals("STARTING")) {
                if (!classLoaderMap.containsKey(classLoader)) {
                    if (TC.isDebugEnabled()) {
                        Tr.debug(TC, "Adding class loader mapping for component {0}:{1}", new Object[] { metaData.getName(), classLoader });
                    }
                    classLoaderMap.put(classLoader, metaData);
                }
            } else if (state.equals("STOPPED")) {
                MetaData existingMetaData = classLoaderMap.get(classLoader);
                if (existingMetaData == metaData) {
                    if (TC.isDebugEnabled()) {
                        Tr.debug(TC, "Removing class loader mapping for component {0}:{1}", new Object[] { metaData.getName(), classLoader });
                    }
                    classLoaderMap.remove(classLoader);
                }
            }
        }
    }

    @Override
    public void publish(LogRecord record) {
        int level = record.getLevel().intValue();
        if (level >= WsLevel.AUDIT.intValue()) {
            try {
                final ComponentMetaData componentMetaData;
                final ModuleMetaData moduleMetaData;
                final ApplicationMetaData applicationMetaData;
                MetaData metaData = cmdAccessor.getComponentMetaData();
                if (metaData instanceof DefaultComponentMetaData) {
                    metaData = null;
                }
                if (metaData == null) {
                    // Attempt to determine the application or module based on the thread context
                    // class loader. We don't do this for threads belonging to WebSphere thread
                    // pools because
                    //  * these are always managed threads and therefore should have a J2EE context;
                    //  * sometimes the thread context class loader is set incorrectly on a thread pool.
                    Thread thread = Thread.currentThread();
                    if (!(thread instanceof ThreadPool.WorkerThread)) {
                        metaData = classLoaderMap.get(thread.getContextClassLoader());
                    }
                }
                if (metaData instanceof ModuleMetaData) {
                    // We get here in two cases:
                    //  * The log event was emitted by an unmanaged thread and the metadata was
                    //    identified using the thread context class loader.
                    //  * For servlet context listeners, the component meta data is the same as the
                    //    module meta data. If we are in this case, we leave the component name empty.
                    componentMetaData = null;
                    moduleMetaData = (ModuleMetaData)metaData;
                    applicationMetaData = moduleMetaData.getApplicationMetaData();
                } else if (metaData instanceof ComponentMetaData) {
                    ComponentMetaData cmd = (ComponentMetaData)metaData;
                    if (cmd instanceof WebComponentMetaData) {
                        IServletConfig config = ((WebComponentMetaData)cmd).getServletConfig();
                        // Don't set the component for static web resources (config == null; the name would be "Static File")
                        // and JSPs (config.getFileName != null). This is especially important for log events generated
                        // by servlet filters.
                        if (config == null || config.getFileName() != null) {
                            componentMetaData = null;
                        } else {
                            componentMetaData = cmd;
                        }
                    } else {
                        componentMetaData = cmd;
                    }
                    moduleMetaData = cmd.getModuleMetaData();
                    applicationMetaData = moduleMetaData.getApplicationMetaData();
                } else if (metaData instanceof ApplicationMetaData) {
                    componentMetaData = null;
                    moduleMetaData = null;
                    applicationMetaData = (ApplicationMetaData)metaData;
                } else {
                    componentMetaData = null;
                    moduleMetaData = null;
                    applicationMetaData = null;
                }
                LogMessage message = new LogMessage(level, record.getMillis(),
                        record.getLoggerName(),
                        applicationMetaData == null ? null : applicationMetaData.getName(),
                        moduleMetaData == null ? null : moduleMetaData.getName(),
                        componentMetaData == null ? null : componentMetaData.getName(),
                        TraceLogFormatter.formatMessage(record, Locale.ENGLISH, TraceLogFormatter.UNUSED_PARM_HANDLING_APPEND_WITH_NEWLINE),
                        record.getThrown());
                synchronized (this) {
                    message.setSequence(nextSequence++);
                    buffer[head++] = message;
                    if (head == buffer.length) {
                        head = 0;
                    }
                }
            } catch (Throwable ex) {
                System.out.println("OOPS! Exception caught in logging handler");
                ex.printStackTrace(System.out);
            }
        }
    }

    public long getNextSequence() {
        long result;
        synchronized (this) {
            result = nextSequence;
        }
        if (TC.isDebugEnabled()) {
            Tr.debug(TC, "getNextSequence returning " + result);
        }
        return result;
    }
    
    public String[] getMessages(long startSequence) {
        return getMessages(startSequence, -1);
    }
    
    public String[] getMessages(long startSequence, int maxMessageSize) {
        if (TC.isDebugEnabled()) {
            Tr.debug(TC, "Entering getMessages with startSequence = " + startSequence);
        }
        LogMessage[] messages;
        synchronized (this) {
            if (startSequence < initialSequence) {
                startSequence = initialSequence;
            }
            int bufferSize = buffer.length;
            int position;
            long longCount = nextSequence-startSequence;
            int count;
            if (longCount > bufferSize) {
                position = head;
                count = bufferSize;
            } else {
                count = (int)longCount;
                position = (head+bufferSize-count) % bufferSize;
            }
            messages = new LogMessage[count];
            for (int i=0; i<count; i++) {
                messages[i] = buffer[position++];
                if (position == bufferSize) {
                    position = 0;
                }
            }
        }
        String[] formattedMessages = new String[messages.length];
        for (int i=0; i<messages.length; i++) {
            formattedMessages[i] = messages[i].format(maxMessageSize);
        }
        if (TC.isDebugEnabled()) {
            if (messages.length == 0) {
                Tr.debug(TC, "No messages returned");
            } else {
                Tr.debug(TC, "Returning " + messages.length + " messages (" + messages[0].getSequence() + "..." + messages[messages.length-1].getSequence() + ")");
            }
        }
        return formattedMessages;
    }
    
    @Override
    public void flush() {
    }

    @Override
    public void close() throws SecurityException {
    }
}