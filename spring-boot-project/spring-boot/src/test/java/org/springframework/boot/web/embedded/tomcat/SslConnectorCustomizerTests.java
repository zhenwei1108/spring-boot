/*
 * Copyright 2012-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.web.embedded.tomcat;

import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.Set;

import org.apache.catalina.LifecycleState;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.springframework.boot.testsupport.system.CapturedOutput;
import org.springframework.boot.testsupport.system.OutputCaptureExtension;
import org.springframework.boot.testsupport.web.servlet.DirtiesUrlFactories;
import org.springframework.boot.web.embedded.test.MockPkcs11Security;
import org.springframework.boot.web.embedded.test.MockPkcs11SecurityProvider;
import org.springframework.boot.web.server.Ssl;
import org.springframework.boot.web.server.SslStoreProvider;
import org.springframework.boot.web.server.WebServerSslBundle;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link SslConnectorCustomizer}
 *
 * @author Brian Clozel
 * @author Andy Wilkinson
 * @author Scott Frederick
 * @author Cyril Dangerville
 */
@SuppressWarnings("removal")
@ExtendWith(OutputCaptureExtension.class)
@DirtiesUrlFactories
@MockPkcs11Security
class SslConnectorCustomizerTests {

	private final Log logger = LogFactory.getLog(SslConnectorCustomizerTests.class);

	private Tomcat tomcat;

	@BeforeEach
	void setup() {
		this.tomcat = new Tomcat();
		Connector connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
		connector.setPort(0);
		this.tomcat.setConnector(connector);
	}

	@AfterEach
	void stop() throws Exception {
		System.clearProperty("javax.net.ssl.trustStorePassword");
		this.tomcat.stop();
	}

	@Test
	void sslCiphersConfiguration() throws Exception {
		Ssl ssl = new Ssl();
		ssl.setKeyStore("classpath:test.jks");
		ssl.setKeyStorePassword("secret");
		ssl.setCiphers(new String[] { "ALPHA", "BRAVO", "CHARLIE" });
		Connector connector = this.tomcat.getConnector();
		SslConnectorCustomizer customizer = new SslConnectorCustomizer(this.logger, connector, ssl.getClientAuth());
		customizer.customize(WebServerSslBundle.get(ssl));
		this.tomcat.start();
		SSLHostConfig[] sslHostConfigs = connector.getProtocolHandler().findSslHostConfigs();
		assertThat(sslHostConfigs[0].getCiphers()).isEqualTo("ALPHA:BRAVO:CHARLIE");
	}

	@Test
	void sslEnabledMultipleProtocolsConfiguration() throws Exception {
		Ssl ssl = new Ssl();
		ssl.setKeyPassword("password");
		ssl.setKeyStore("src/test/resources/test.jks");
		ssl.setEnabledProtocols(new String[] { "TLSv1.1", "TLSv1.2" });
		ssl.setCiphers(new String[] { "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256", "BRAVO" });
		Connector connector = this.tomcat.getConnector();
		SslConnectorCustomizer customizer = new SslConnectorCustomizer(this.logger, connector, ssl.getClientAuth());
		customizer.customize(WebServerSslBundle.get(ssl));
		this.tomcat.start();
		SSLHostConfig sslHostConfig = connector.getProtocolHandler().findSslHostConfigs()[0];
		assertThat(sslHostConfig.getSslProtocol()).isEqualTo("TLS");
		assertThat(sslHostConfig.getEnabledProtocols()).containsExactlyInAnyOrder("TLSv1.1", "TLSv1.2");
	}

	@Test
	void sslEnabledProtocolsConfiguration() throws Exception {
		Ssl ssl = new Ssl();
		ssl.setKeyPassword("password");
		ssl.setKeyStore("src/test/resources/test.jks");
		ssl.setEnabledProtocols(new String[] { "TLSv1.2" });
		ssl.setCiphers(new String[] { "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256", "BRAVO" });
		Connector connector = this.tomcat.getConnector();
		SslConnectorCustomizer customizer = new SslConnectorCustomizer(this.logger, connector, ssl.getClientAuth());
		customizer.customize(WebServerSslBundle.get(ssl));
		this.tomcat.start();
		SSLHostConfig sslHostConfig = connector.getProtocolHandler().findSslHostConfigs()[0];
		assertThat(sslHostConfig.getSslProtocol()).isEqualTo("TLS");
		assertThat(sslHostConfig.getEnabledProtocols()).containsExactly("TLSv1.2");
	}

	@Test
	@Deprecated(since = "3.1.0", forRemoval = true)
	void customizeWhenSslStoreProviderProvidesOnlyKeyStoreShouldUseDefaultTruststore() throws Exception {
		Ssl ssl = new Ssl();
		ssl.setKeyPassword("password");
		ssl.setTrustStore("src/test/resources/test.jks");
		SslStoreProvider sslStoreProvider = mock(SslStoreProvider.class);
		KeyStore keyStore = loadStore();
		given(sslStoreProvider.getKeyStore()).willReturn(keyStore);
		Connector connector = this.tomcat.getConnector();
		SslConnectorCustomizer customizer = new SslConnectorCustomizer(this.logger, connector, ssl.getClientAuth());
		customizer.customize(WebServerSslBundle.get(ssl, null, sslStoreProvider));
		this.tomcat.start();
		SSLHostConfig sslHostConfig = connector.getProtocolHandler().findSslHostConfigs()[0];
		SSLHostConfig sslHostConfigWithDefaults = new SSLHostConfig();
		assertThat(sslHostConfig.getTruststoreFile()).isEqualTo(sslHostConfigWithDefaults.getTruststoreFile());
		Set<SSLHostConfigCertificate> certificates = sslHostConfig.getCertificates();
		assertThat(certificates).hasSize(1);
		assertThat(certificates.iterator().next().getCertificateKeystore()).isEqualTo(keyStore);
	}

	@Test
	@Deprecated(since = "3.1.0", forRemoval = true)
	void customizeWhenSslStoreProviderProvidesOnlyTrustStoreShouldUseDefaultKeystore() throws Exception {
		Ssl ssl = new Ssl();
		ssl.setKeyPassword("password");
		ssl.setKeyStore("src/test/resources/test.jks");
		SslStoreProvider sslStoreProvider = mock(SslStoreProvider.class);
		KeyStore trustStore = loadStore();
		given(sslStoreProvider.getTrustStore()).willReturn(trustStore);
		Connector connector = this.tomcat.getConnector();
		SslConnectorCustomizer customizer = new SslConnectorCustomizer(this.logger, connector, ssl.getClientAuth());
		customizer.customize(WebServerSslBundle.get(ssl, null, sslStoreProvider));
		this.tomcat.start();
		SSLHostConfig sslHostConfig = connector.getProtocolHandler().findSslHostConfigs()[0];
		assertThat(sslHostConfig.getTruststore()).isEqualTo(trustStore);
	}

	@Test
	@Deprecated(since = "3.1.0", forRemoval = true)
	void customizeWhenSslStoreProviderPresentShouldIgnorePasswordFromSsl(CapturedOutput output) throws Exception {
		System.setProperty("javax.net.ssl.trustStorePassword", "trustStoreSecret");
		Ssl ssl = new Ssl();
		ssl.setKeyPassword("password");
		ssl.setKeyStorePassword("secret");
		SslStoreProvider sslStoreProvider = mock(SslStoreProvider.class);
		given(sslStoreProvider.getTrustStore()).willReturn(loadStore());
		given(sslStoreProvider.getKeyStore()).willReturn(loadStore());
		Connector connector = this.tomcat.getConnector();
		SslConnectorCustomizer customizer = new SslConnectorCustomizer(this.logger, connector, ssl.getClientAuth());
		customizer.customize(WebServerSslBundle.get(ssl, null, sslStoreProvider));
		this.tomcat.start();
		assertThat(connector.getState()).isEqualTo(LifecycleState.STARTED);
		assertThat(output).doesNotContain("Password verification failed");
	}

	@Test
	void customizeWhenSslIsEnabledWithNoKeyStoreAndNotPkcs11ThrowsException() {
		assertThatIllegalStateException().isThrownBy(() -> {
			SslConnectorCustomizer customizer = new SslConnectorCustomizer(this.logger, this.tomcat.getConnector(),
					Ssl.ClientAuth.NONE);
			customizer.customize(WebServerSslBundle.get(new Ssl()));
		}).withMessageContaining("SSL is enabled but no trust material is configured");
	}

	@Test
	void customizeWhenSslIsEnabledWithPkcs11AndKeyStoreThrowsException() {
		Ssl ssl = new Ssl();
		ssl.setKeyStoreType("PKCS11");
		ssl.setKeyStoreProvider(MockPkcs11SecurityProvider.NAME);
		ssl.setKeyStore("src/test/resources/test.jks");
		ssl.setKeyPassword("password");
		assertThatIllegalStateException().isThrownBy(() -> {
			SslConnectorCustomizer customizer = new SslConnectorCustomizer(this.logger, this.tomcat.getConnector(),
					ssl.getClientAuth());
			customizer.customize(WebServerSslBundle.get(ssl));
		}).withMessageContaining("must be empty or null for PKCS11 hardware key stores");
	}

	@Test
	void customizeWhenSslIsEnabledWithPkcs11AndKeyStoreProvider() {
		Ssl ssl = new Ssl();
		ssl.setKeyStoreType("PKCS11");
		ssl.setKeyStoreProvider(MockPkcs11SecurityProvider.NAME);
		ssl.setKeyStorePassword("1234");
		SslConnectorCustomizer customizer = new SslConnectorCustomizer(this.logger, this.tomcat.getConnector(),
				ssl.getClientAuth());
		assertThatNoException().isThrownBy(() -> customizer.customize(WebServerSslBundle.get(ssl)));
	}

	private KeyStore loadStore() throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException {
		KeyStore keyStore = KeyStore.getInstance("JKS");
		Resource resource = new ClassPathResource("test.jks");
		try (InputStream stream = resource.getInputStream()) {
			keyStore.load(stream, "secret".toCharArray());
			return keyStore;
		}
	}

}
