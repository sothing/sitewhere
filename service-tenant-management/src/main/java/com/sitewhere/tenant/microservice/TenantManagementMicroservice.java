/*
 * Copyright (c) SiteWhere, LLC. All rights reserved. http://www.sitewhere.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package com.sitewhere.tenant.microservice;

import java.io.File;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import com.sitewhere.microservice.GlobalMicroservice;
import com.sitewhere.microservice.hazelcast.server.CacheAwareTenantManagement;
import com.sitewhere.server.lifecycle.CompositeLifecycleStep;
import com.sitewhere.spi.SiteWhereException;
import com.sitewhere.spi.microservice.IMicroserviceIdentifiers;
import com.sitewhere.spi.microservice.configuration.model.IConfigurationModel;
import com.sitewhere.spi.microservice.spring.TenantManagementBeans;
import com.sitewhere.spi.server.lifecycle.ICompositeLifecycleStep;
import com.sitewhere.spi.server.lifecycle.ILifecycleProgressMonitor;
import com.sitewhere.spi.tenant.ITenantManagement;
import com.sitewhere.tenant.TenantManagementKafkaTriggers;
import com.sitewhere.tenant.configuration.TenantManagementModelProvider;
import com.sitewhere.tenant.grpc.TenantManagementGrpcServer;
import com.sitewhere.tenant.kafka.TenantBootstrapModelConsumer;
import com.sitewhere.tenant.kafka.TenantModelProducer;
import com.sitewhere.tenant.spi.grpc.ITenantManagementGrpcServer;
import com.sitewhere.tenant.spi.kafka.ITenantBootstrapModelConsumer;
import com.sitewhere.tenant.spi.kafka.ITenantModelProducer;
import com.sitewhere.tenant.spi.microservice.ITenantManagementMicroservice;
import com.sitewhere.tenant.spi.templates.ITenantTemplateManager;

/**
 * Microservice that provides tenant management functionality.
 * 
 * @author Derek
 */
public class TenantManagementMicroservice extends GlobalMicroservice implements ITenantManagementMicroservice {

    /** Static logger instance */
    private static Logger LOGGER = LogManager.getLogger();

    /** Microservice name */
    private static final String NAME = "Tenant Management";

    /** Configuration model */
    private IConfigurationModel configurationModel = new TenantManagementModelProvider().buildModel();

    /** Tenant management configuration file name */
    private static final String TENANT_MANAGEMENT_CONFIGURATION = IMicroserviceIdentifiers.TENANT_MANAGEMENT + ".xml";

    /** List of configuration paths required by microservice */
    private static final String[] CONFIGURATION_PATHS = { TENANT_MANAGEMENT_CONFIGURATION };

    /** Root folder for instance templates */
    private static final String TEMPLATES_ROOT = "/templates";

    /** Responds to tenant management GRPC requests */
    private ITenantManagementGrpcServer tenantManagementGrpcServer;

    /** Tenant management persistence API */
    private ITenantManagement tenantManagement;

    /** Tenant template manager */
    @Autowired
    private ITenantTemplateManager tenantTemplateManager;

    /** Reflects tenant model updates to Kafka topic */
    private ITenantModelProducer tenantModelProducer;

    /** Watches tenant model updates and bootstraps new tenants */
    private ITenantBootstrapModelConsumer tenantBootstrapModelConsumer;

    /*
     * (non-Javadoc)
     * 
     * @see com.sitewhere.microservice.spi.IMicroservice#getName()
     */
    @Override
    public String getName() {
	return NAME;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.sitewhere.microservice.spi.IMicroservice#getIdentifier()
     */
    @Override
    public String getIdentifier() {
	return IMicroserviceIdentifiers.TENANT_MANAGEMENT;
    }

    /*
     * @see com.sitewhere.spi.microservice.IMicroservice#getConfigurationModel()
     */
    @Override
    public IConfigurationModel getConfigurationModel() {
	return configurationModel;
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.sitewhere.microservice.spi.IGlobalMicroservice#getConfigurationPaths( )
     */
    @Override
    public String[] getConfigurationPaths() throws SiteWhereException {
	return CONFIGURATION_PATHS;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.sitewhere.microservice.spi.IGlobalMicroservice#
     * initializeFromSpringContexts(org.springframework.context. ApplicationContext,
     * java.util.Map)
     */
    @Override
    public void initializeFromSpringContexts(ApplicationContext global, Map<String, ApplicationContext> contexts)
	    throws SiteWhereException {
	this.tenantModelProducer = new TenantModelProducer(this);
	this.tenantBootstrapModelConsumer = new TenantBootstrapModelConsumer(this);

	ApplicationContext context = contexts.get(TENANT_MANAGEMENT_CONFIGURATION);
	this.tenantManagement = initializeTenantManagement(context);
	this.tenantManagementGrpcServer = new TenantManagementGrpcServer(this, getTenantManagement());
    }

    /**
     * Initialize tenant management implementation from context bean and wrap it
     * with triggers to broadcast model updates via Kafka.
     * 
     * @param context
     * @return
     * @throws SiteWhereException
     */
    protected ITenantManagement initializeTenantManagement(ApplicationContext context) throws SiteWhereException {
	try {
	    ITenantManagement bean = (ITenantManagement) context.getBean(TenantManagementBeans.BEAN_TENANT_MANAGEMENT);
	    ITenantManagement cached = new CacheAwareTenantManagement(bean, this);
	    return new TenantManagementKafkaTriggers(cached, getTenantModelProducer());
	} catch (NoSuchBeanDefinitionException e) {
	    throw new SiteWhereException("Tenant management bean not found.", e);
	}
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.sitewhere.microservice.spi.IGlobalMicroservice#microserviceInitialize
     * (com.sitewhere.spi.server.lifecycle.ILifecycleProgressMonitor)
     */
    @Override
    public void microserviceInitialize(ILifecycleProgressMonitor monitor) throws SiteWhereException {
	// Composite step for initializing microservice.
	ICompositeLifecycleStep init = new CompositeLifecycleStep("Initialize " + getName());

	// Initialize discoverable lifecycle components.
	init.addStep(initializeDiscoverableBeans(getTenantManagementApplicationContext(), monitor));

	// Initialize tenant management implementation.
	init.addInitializeStep(this, getTenantManagement(), true);

	// Initialize tenant template manager.
	init.addInitializeStep(this, getTenantTemplateManager(), true);

	// Initialize tenant management GRPC server.
	init.addInitializeStep(this, getTenantManagementGrpcServer(), true);

	// Initialize tenant model producer.
	init.addInitializeStep(this, getTenantModelProducer(), true);

	// Initialize tenant bootstrap model consumer.
	init.addInitializeStep(this, getTenantBootstrapModelConsumer(), true);

	// Execute initialization steps.
	init.execute(monitor);
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.sitewhere.microservice.spi.IGlobalMicroservice#microserviceStart(com.
     * sitewhere.spi.server.lifecycle.ILifecycleProgressMonitor)
     */
    @Override
    public void microserviceStart(ILifecycleProgressMonitor monitor) throws SiteWhereException {
	// Composite step for starting microservice.
	ICompositeLifecycleStep start = new CompositeLifecycleStep("Start " + getComponentName());

	// Start discoverable lifecycle components.
	start.addStep(startDiscoverableBeans(getTenantManagementApplicationContext(), monitor));

	// Start tenant mangement persistence.
	start.addStartStep(this, getTenantManagement(), true);

	// Start tenant template manager.
	start.addStartStep(this, getTenantTemplateManager(), true);

	// Start GRPC server.
	start.addStartStep(this, getTenantManagementGrpcServer(), true);

	// Start tenant model producer.
	start.addStartStep(this, getTenantModelProducer(), true);

	// Start tenant bootstrap model consumer.
	start.addStartStep(this, getTenantBootstrapModelConsumer(), true);

	// Execute initialization steps.
	start.execute(monitor);
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.sitewhere.microservice.spi.IGlobalMicroservice#microserviceStop(com.
     * sitewhere.spi.server.lifecycle.ILifecycleProgressMonitor)
     */
    @Override
    public void microserviceStop(ILifecycleProgressMonitor monitor) throws SiteWhereException {
	// Composite step for stopping microservice.
	ICompositeLifecycleStep stop = new CompositeLifecycleStep("Stop " + getName());

	// Stop tenant bootstrap model consumer.
	stop.addStopStep(this, getTenantBootstrapModelConsumer());

	// Stop tenant model producer.
	stop.addStopStep(this, getTenantModelProducer());

	// Stop GRPC manager.
	stop.addStopStep(this, getTenantManagementGrpcServer());

	// Stop tenant template manager.
	stop.addStopStep(this, getTenantTemplateManager());

	// Stop tenant management persistence.
	stop.addStopStep(this, getTenantManagement());

	// Stop discoverable lifecycle components.
	stop.addStep(stopDiscoverableBeans(getTenantManagementApplicationContext(), monitor));

	// Execute shutdown steps.
	stop.execute(monitor);
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.sitewhere.tenant.spi.microservice.ITenantManagementMicroservice#
     * getTenantTemplatesRoot()
     */
    @Override
    public File getTenantTemplatesRoot() throws SiteWhereException {
	File templates = new File(TEMPLATES_ROOT);
	if (!templates.exists()) {
	    throw new SiteWhereException("Templates folder not found in Docker image.");
	}
	return templates;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.sitewhere.tenant.spi.microservice.ITenantManagement#
     * getTenantManagementGrpcServer()
     */
    @Override
    public ITenantManagementGrpcServer getTenantManagementGrpcServer() {
	return tenantManagementGrpcServer;
    }

    public void setTenantManagementGrpcServer(ITenantManagementGrpcServer tenantManagementGrpcServer) {
	this.tenantManagementGrpcServer = tenantManagementGrpcServer;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.sitewhere.tenant.spi.microservice.ITenantManagementMicroservice#
     * getTenantManagement()
     */
    @Override
    public ITenantManagement getTenantManagement() {
	return tenantManagement;
    }

    public void setTenantManagement(ITenantManagement tenantManagement) {
	this.tenantManagement = tenantManagement;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.sitewhere.tenant.spi.microservice.ITenantManagementMicroservice#
     * getTenantTemplateManager()
     */
    @Override
    public ITenantTemplateManager getTenantTemplateManager() {
	return tenantTemplateManager;
    }

    public void setTenantTemplateManager(ITenantTemplateManager tenantTemplateManager) {
	this.tenantTemplateManager = tenantTemplateManager;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.sitewhere.tenant.spi.microservice.ITenantManagementMicroservice#
     * getTenantModelProducer()
     */
    @Override
    public ITenantModelProducer getTenantModelProducer() {
	return tenantModelProducer;
    }

    public void setTenantModelProducer(ITenantModelProducer tenantModelProducer) {
	this.tenantModelProducer = tenantModelProducer;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.sitewhere.tenant.spi.microservice.ITenantManagementMicroservice#
     * getTenantBootstrapModelConsumer()
     */
    @Override
    public ITenantBootstrapModelConsumer getTenantBootstrapModelConsumer() {
	return tenantBootstrapModelConsumer;
    }

    public void setTenantBootstrapModelConsumer(ITenantBootstrapModelConsumer tenantBootstrapModelConsumer) {
	this.tenantBootstrapModelConsumer = tenantBootstrapModelConsumer;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.sitewhere.spi.server.lifecycle.ILifecycleComponent#getLogger()
     */
    @Override
    public Logger getLogger() {
	return LOGGER;
    }

    protected ApplicationContext getTenantManagementApplicationContext() {
	return getGlobalContexts().get(TENANT_MANAGEMENT_CONFIGURATION);
    }
}