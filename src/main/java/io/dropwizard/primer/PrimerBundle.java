/*
 * Copyright 2016 Phaneesh Nagaraja <phaneesh.n@gmail.com>.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.dropwizard.primer;

import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.github.toastshaman.dropwizard.auth.jwt.JsonWebTokenParser;
import com.github.toastshaman.dropwizard.auth.jwt.hmac.HmacSHA512Verifier;
import com.github.toastshaman.dropwizard.auth.jwt.parser.DefaultJsonWebTokenParser;
import feign.Feign;
import feign.Logger;
import feign.Target;
import feign.httpclient.ApacheHttpClient;
import feign.jackson.JacksonDecoder;
import feign.jackson.JacksonEncoder;
import feign.ranger.RangerTarget;
import feign.slf4j.Slf4jLogger;
import io.dropwizard.Configuration;
import io.dropwizard.ConfiguredBundle;
import io.dropwizard.lifecycle.Managed;
import io.dropwizard.primer.auth.PrimerAuthAnnotationFeature;
import io.dropwizard.primer.auth.PrimerAuthorizationRegistry;
import io.dropwizard.primer.auth.authorizer.PrimerAnnotationAuthorizer;
import io.dropwizard.primer.auth.filter.PrimerAuthConfigFilter;
import io.dropwizard.primer.client.PrimerClient;
import io.dropwizard.primer.core.PrimerError;
import io.dropwizard.primer.exception.PrimerException;
import io.dropwizard.primer.exception.PrimerExceptionMapper;
import io.dropwizard.primer.model.PrimerAuthorizationMatrix;
import io.dropwizard.primer.model.PrimerBundleConfiguration;
import io.dropwizard.primer.model.PrimerRangerEndpoint;
import io.dropwizard.primer.model.PrimerSimpleEndpoint;
import io.dropwizard.server.DefaultServerFactory;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryNTimes;
import org.apache.http.Consts;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.config.ConnectionConfig;
import org.apache.http.config.SocketConfig;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.protocol.HTTP;

import java.io.IOException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * @author phaneesh
 */
@Slf4j
public abstract class PrimerBundle<T extends Configuration> implements ConfiguredBundle<T> {

    //private static List<String> whiteList = new ArrayList<>();

    private static PrimerClient primerClient = null;

    public abstract PrimerBundleConfiguration getPrimerConfiguration(T configuration);

    public abstract Set<String> withWhiteList(T configuration);

    public abstract PrimerAuthorizationMatrix withAuthorization(T configuration);

    public abstract PrimerAnnotationAuthorizer authorizer();

    public static PrimerClient getPrimerClient() {
        return primerClient;
    }

    /**
     * Default method which provides a default curator for service discovery to work in case there is no other
     * curator instance available. Override this to supply your own creator
     *
     * @param configuration Application configuration
     * @return CuratorFramework
     */
    public CuratorFramework getCurator(T configuration) {
        final PrimerBundleConfiguration primerBundleConfiguration = getPrimerConfiguration(configuration);
        final val config = (PrimerRangerEndpoint) primerBundleConfiguration.getEndpoint();
        final CuratorFramework curatorFramework = CuratorFrameworkFactory.builder()
                .connectString(config.getZookeeper())
                .namespace(config.getNamespace()).retryPolicy(new RetryNTimes(1000, 500)).build();
        curatorFramework.start();
        return curatorFramework;
    }

    @Override
    public void initialize(Bootstrap<?> bootstrap) {
        bootstrap.getObjectMapper().registerSubtypes(new NamedType(PrimerSimpleEndpoint.class, "simple"));
        bootstrap.getObjectMapper().registerSubtypes(new NamedType(PrimerRangerEndpoint.class, "ranger"));
    }

    @Override
    public void run(T configuration, Environment environment) {
        final val primerConfig = getPrimerConfiguration(configuration);
        final JsonWebTokenParser tokenParser = new DefaultJsonWebTokenParser();
        final byte[] secretKey = primerConfig.getPrivateKey().getBytes(StandardCharsets.UTF_8);
        final HmacSHA512Verifier tokenVerifier = new HmacSHA512Verifier(secretKey);

        initializeAuthorization(configuration, tokenParser, tokenVerifier);
        final JacksonDecoder decoder = new JacksonDecoder();
        final JacksonEncoder encoder = new JacksonEncoder();
        final Slf4jLogger logger = new Slf4jLogger();
        final int clientConnectionPool = configuration.getServerFactory() instanceof DefaultServerFactory ?
                ((DefaultServerFactory)configuration.getServerFactory()).getMaxThreads() : 128;
        environment.lifecycle().manage(new Managed() {

            @Override
            public void start() {

                // Create socket configuration
                SocketConfig socketConfig = SocketConfig.custom()
                        .setTcpNoDelay(true)
                        .setSoKeepAlive(true)
                        .build();

                // Create connection configuration
                ConnectionConfig connectionConfig = ConnectionConfig.custom()
                        .setMalformedInputAction(CodingErrorAction.IGNORE)
                        .setUnmappableInputAction(CodingErrorAction.IGNORE)
                        .setCharset(Consts.UTF_8)
                        .build();

                PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
                connectionManager.setDefaultMaxPerRoute(clientConnectionPool);
                connectionManager.setMaxTotal(clientConnectionPool);
                connectionManager.setDefaultMaxPerRoute(clientConnectionPool);
                connectionManager.setValidateAfterInactivity(30000);
                connectionManager.setDefaultSocketConfig(socketConfig);
                connectionManager.setDefaultConnectionConfig(connectionConfig);

                // Create global request configuration
                RequestConfig defaultRequestConfig = RequestConfig.custom()
                        .setAuthenticationEnabled(false)
                        .setRedirectsEnabled(false)
                        .setConnectTimeout(Integer.MAX_VALUE)
                        .setConnectionRequestTimeout(Integer.MAX_VALUE)
                        .build();


                final HttpClientBuilder client = HttpClients.custom()
                        .addInterceptorFirst((HttpRequestInterceptor) (httpRequest, httpContext) -> httpRequest.removeHeaders(HTTP.CONTENT_LEN))
                        .setConnectionManager(connectionManager)
                        .setDefaultRequestConfig(defaultRequestConfig);

                primerClient = Feign.builder()
                        .decoder(decoder)
                        .encoder(encoder)
                        .errorDecoder((methodKey, response) -> {
                            try {
                                final PrimerError error = environment.getObjectMapper().readValue(response.body().asInputStream(), PrimerError.class);
                                return PrimerException.builder()
                                        .message(error.getMessage())
                                        .errorCode(error.getErrorCode())
                                        .status(response.status())
                                        .build();
                            } catch (IOException e) {
                                return PrimerException.builder()
                                        .status(response.status())
                                        .errorCode("PR000")
                                        .message(e.getMessage()).build();
                            }
                        })
                        .client(new ApacheHttpClient(client.build()))
                        .logger(logger)
                        .logLevel(Logger.Level.BASIC)
                        .target(getPrimerTarget(configuration, environment));
            }

            @Override
            public void stop() {

            }
        });
        environment.jersey().register(new PrimerExceptionMapper());
        environment.jersey().register(PrimerAuthConfigFilter.builder()
                .configuration(getPrimerConfiguration(configuration))
                .objectMapper(environment.getObjectMapper())
                .build());

        environment.jersey().register(PrimerAuthAnnotationFeature.builder()
                .authorizer(authorizer())
                .configuration(primerConfig)
                .mapper(environment.getObjectMapper())
                .build());
    }

    private Target<PrimerClient> getPrimerTarget(T configuration, Environment environment) {
        final val primerConfig = getPrimerConfiguration(configuration);
        switch (primerConfig.getEndpoint().getType()) {
            case "simple":
                final val endpoint = (PrimerSimpleEndpoint) primerConfig.getEndpoint();
                return new Target.HardCodedTarget<>(PrimerClient.class,
                        String.format("http://%s:%d", endpoint.getHost(), endpoint.getPort()));
            case "ranger":
                final val config = (PrimerRangerEndpoint) primerConfig.getEndpoint();
                try {
                    return new RangerTarget<>(PrimerClient.class, config.getEnvironment(), config.getNamespace(),
                            config.getService(), getCurator(configuration), false, environment.getObjectMapper());
                } catch (Exception e) {
                    log.error("Error creating ranger endpoint for primer", e);
                    return null;
                }
            default:
                throw new IllegalArgumentException("unknown primer target type specified");
        }
    }

    private void initializeAuthorization(T configuration, JsonWebTokenParser tokenParser, HmacSHA512Verifier tokenVerifier) {
        final val primerConfig = getPrimerConfiguration(configuration);
        final Set<String> whiteListUrls = new HashSet<>();
        final Set<String> dynamicWhiteList = withWhiteList(configuration);
        if(dynamicWhiteList != null) {
            whiteListUrls.addAll(dynamicWhiteList);
        }
        if(primerConfig.getWhileListUrl() != null) {
            whiteListUrls.addAll(primerConfig.getWhileListUrl());
        }
        PrimerAuthorizationMatrix permissionMatrix = primerConfig.getAuthorizations();
        //If no authorizations are provided in config then just get authorizations programmatically
        if(permissionMatrix == null) {
            permissionMatrix = withAuthorization(configuration);
        } else { //Else needs to merge both the authorizations
            val dynamicAuthMatrix = withAuthorization(configuration);
            if(permissionMatrix.getAuthorizations() == null) {
                permissionMatrix.setAuthorizations(dynamicAuthMatrix.getAuthorizations());
            } else {
                permissionMatrix.getAuthorizations().addAll(dynamicAuthMatrix.getAuthorizations());
            }
            if(permissionMatrix.getAutoAuthorizations() == null) {
                permissionMatrix.setAutoAuthorizations(dynamicAuthMatrix.getAutoAuthorizations());
            } else {
                permissionMatrix.getAutoAuthorizations().addAll(dynamicAuthMatrix.getAutoAuthorizations());
            }
            if(permissionMatrix.getStaticAuthorizations() == null) {
                permissionMatrix.setStaticAuthorizations(dynamicAuthMatrix.getStaticAuthorizations());
            } else {
                permissionMatrix.getStaticAuthorizations().addAll(dynamicAuthMatrix.getStaticAuthorizations());
            }
        }
        PrimerAuthorizationRegistry.init(permissionMatrix, whiteListUrls, primerConfig, tokenParser, tokenVerifier);
    }
}
