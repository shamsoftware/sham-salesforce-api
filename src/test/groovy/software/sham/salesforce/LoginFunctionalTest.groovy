package software.sham.salesforce

import groovy.xml.StreamingMarkupBuilder
import org.junit.Test
import org.springframework.core.io.ClassPathResource

import javax.ws.rs.client.Entity
import javax.ws.rs.core.Response

class LoginFunctionalTest extends AbstractFunctionalTest {

    @Test
    void shouldAcceptAnyLoginCredentialsByDefault() {
        def root = new XmlSlurper().parse(new ClassPathResource('login-request.xml').inputStream)
        root.Body.login.username = 'arbitrary@confluex.com'
        root.Body.login.password = 'yrartibraAPIKEY'
        String request = new StreamingMarkupBuilder().bind { mkp.yield root }

        Response response = sslClient.target('https://localhost:8081/services/Soap/u/28.0').request()
            .post(Entity.entity(request, 'text/xml; charset=UTF-8'))

        def responseBody = response.readEntity(String)

        assert response.status == 200
        assert MockSalesforceApiServer.DEFAULT_USER_ID == evalXpath('/env:Envelope/env:Body/sf:loginResponse/sf:result/sf:userId', responseBody)
        assert MockSalesforceApiServer.DEFAULT_ORG_ID + 'MAC' == evalXpath('/env:Envelope/env:Body/sf:loginResponse/sf:result/sf:userInfo/sf:organizationId', responseBody)
        assert MockSalesforceApiServer.DEFAULT_SESSION_ID == evalXpath('/env:Envelope/env:Body/sf:loginResponse/sf:result/sf:sessionId', responseBody)

        URL metadataUrl = new URL(evalXpath('/env:Envelope/env:Body/sf:loginResponse/sf:result/sf:metadataServerUrl', responseBody))
        assert 'localhost' == metadataUrl.host
        assert 8081 == metadataUrl.port
        assert metadataUrl.path ==~ /${MockSalesforceApiServer.METADATA_PATH_PREFIX}.*/
        assert metadataUrl.path ==~ /.*${MockSalesforceApiServer.DEFAULT_ORG_ID}/

        URL serverUrl = new URL(evalXpath('//env:Envelope/env:Body/sf:loginResponse/sf:result/sf:serverUrl', responseBody))
        assert 'localhost' == serverUrl.host
        assert 8081 == serverUrl.port
        assert serverUrl.path ==~ /${MockSalesforceApiServer.PATH_PREFIX}.*/
        assert serverUrl.path ==~ /.*${MockSalesforceApiServer.DEFAULT_ORG_ID}/
    }
}
