package com.grahamcrockford.oco.core;

import com.google.common.util.concurrent.Service;
import com.google.inject.AbstractModule;
import com.google.inject.assistedinject.FactoryModuleBuilder;
import com.google.inject.multibindings.Multibinder;
import com.grahamcrockford.oco.WebResource;
import com.grahamcrockford.oco.api.TradeServiceFactory;

public class CoreModule extends AbstractModule {
  @Override
  protected void configure() {

    install(new FactoryModuleBuilder().build(JobExecutor.Factory.class));

    bind(TradeServiceFactory.class).to(LiveTradeServiceFactory.class);

    Multibinder.newSetBinder(binder(), Service.class).addBinding().toProvider(JobKeepAlive.ProviderA.class);
    Multibinder.newSetBinder(binder(), Service.class).addBinding().toProvider(JobKeepAlive.ProviderB.class);
    Multibinder.newSetBinder(binder(), WebResource.class).addBinding().to(JobResource.class);
    Multibinder.newSetBinder(binder(), WebResource.class).addBinding().to(ExchangeResource.class);
  }
}