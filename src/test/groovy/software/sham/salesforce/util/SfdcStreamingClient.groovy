package software.sham.salesforce.util

import org.cometd.bayeux.Message
import org.cometd.bayeux.client.ClientSessionChannel
import org.cometd.client.BayeuxClient
import org.cometd.client.transport.LongPollingTransport
import org.eclipse.jetty.client.HttpClient
import org.eclipse.jetty.client.api.Request
import org.eclipse.jetty.util.ssl.SslContextFactory
import software.sham.http.MockHttpsServer

import javax.ws.rs.client.Client
import javax.ws.rs.client.ClientBuilder
import javax.ws.rs.client.Entity
import javax.ws.rs.core.Response
import groovy.util.logging.Slf4j
import groovy.xml.StreamingMarkupBuilder

import org.springframework.core.io.ClassPathResource

import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory


@Slf4j
class SfdcStreamingClient {
    final String username
    final String password
    final String securityToken
    final String url
    String streamingUrl
    String sessionId
    BayeuxClient bayeuxClient


    public SfdcStreamingClient(String username, String password, String securityToken, String url) {
        this.username = username
        this.password = password
        this.securityToken = securityToken
        this.url = url
    }

    void stop() {
        bayeuxClient.disconnect()
    }

    void subscribeTopic(String topic, final Closure listener) {
        if (! sessionId) login()
        if (! sessionId) throw new RuntimeException("Unable to login in order to subscribe")
        if (! bayeuxClient) bayeuxClient = initBayeuxClient()
        if (! bayeuxClient.handshook) {
            bayeuxClient.handshake()
            waitForHandshake(bayeuxClient)
        }
        bayeuxClient.getChannel("/topic/$topic").subscribe(new ClientSessionChannel.MessageListener() {
            @Override
            void onMessage(ClientSessionChannel clientSessionChannel, Message message) {
                listener.call(message)
            }
        })
    }

    void login() {
        def root = new XmlSlurper().parse(new ClassPathResource('login-request.xml').inputStream)
        root.Body.login.username = username
        root.Body.login.password = password + securityToken
        String request = new StreamingMarkupBuilder().bind { mkp.yield root }

        Client sslClient = ClientBuilder.newBuilder().sslContext(MockHttpsServer.clientSslContext).build()
        Response response = sslClient.target(url)
                .request()
                .header('SOAPAction', '""')
                .post(Entity.entity(request, 'text/xml; charset=UTF-8'))

        def responseBody = response.readEntity(String)
        log.debug "Login response: $responseBody"
        assert response.status == 200
        sessionId = evalXpath('/Envelope/Body/loginResponse/result/sessionId', responseBody)
        streamingUrl = getStreamingApiUrl(evalXpath('/Envelope/Body/loginResponse/result/serverUrl', responseBody))
        log.info "Login successful. Session ID: $sessionId"
        log.debug "Streaming URL: $streamingUrl"
    }

    private BayeuxClient initBayeuxClient() {
        new BayeuxClient(streamingUrl, initTransport())
    }

    private LongPollingTransport initTransport() {
        final def timeout = 110 * 1000
        def httpClient = new HttpClient(new SslContextFactory(true))
        httpClient.connectTimeout = timeout
        httpClient.start()
        new LongPollingTransport(
                [timeout: timeout],
                httpClient) {
            @Override
            protected void customize(Request request) {
                super.customize(request)
                request.headers.add("Authorization", "OAuth $sessionId")
            }
        }
    }

    private def getStreamingApiUrl(url) {
        def instanceUrl = new URL(url)
        def portClause = instanceUrl.getPort() > 0 ? ":${instanceUrl.port}" : ""
        "https://${instanceUrl.host}$portClause/cometd/26.0"
    }

    def evalXpath(String xpath, String xml) {
        def evaluator = XPathFactory.newInstance().newXPath()
        def builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        def rootElement = builder.parse(new ByteArrayInputStream(xml.bytes)).documentElement
        evaluator.evaluate(xpath, rootElement, XPathConstants.STRING)
    }

    private boolean waitForHandshake(BayeuxClient client) {
        waitForHandshake(client, 8, 100)
    }
    private boolean waitForHandshake(BayeuxClient client, int retries, int delay) {
        log.debug "Checking for Bayeux handshake"
        if (client.handshook) return true
        if (retries == 0) return false
        Thread.sleep delay
        return waitForHandshake(client, retries - 1, delay * 2)
    }

}
