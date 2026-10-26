package io.stridehub.app.nativeui

import org.junit.Assert.*
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class NativeApiResponseTest {
    @Test fun htmlLoginResponseIdentifiesProxyRoutingWithoutLeakingHtmlOrSecrets() {
        val body="<html><title>Panel</title>https://private.example/session?token=secret shmobile_confidential</html>"
        val failure=assertThrows(ApiFailure::class.java) {NativeApiResponse.read(200,"text/html; charset=utf-8",body,login=true)}
        assertTrue(failure.message!!.contains("HTTP 200"))
        assertTrue(failure.message!!.contains("公网代理"))
        assertTrue(failure.message!!.contains("移动"))
        listOf("<html>","Panel","private.example","secret","shmobile_").forEach {assertFalse(failure.message!!.contains(it))}
    }
    @Test fun jsonMimeTypeCannotHideAnHtmlPageOrMalformedOrArrayPayload() {
        listOf("<!DOCTYPE html><html>Panel</html>","{\"token\":\"sensitive\"}garbage","[]","").forEach {body->
            val error=assertThrows(ApiFailure::class.java) {NativeApiResponse.read(200,"application/json",body,login=true)}
            assertTrue(error.message!!.contains("JSON"))
            assertFalse(error.message!!.contains(body.takeIf {it.isNotEmpty()} ?: "sensitive"))
        }
        assertThrows(ApiFailure::class.java) {NativeApiResponse.read(200,null,"{\"token\":\"secret\"}",login=true)}
    }
    @Test fun loginPasswordFailureIsDistinctFromExpiredBusinessSession() {
        val body="{\"error\":\"invalid_credentials\",\"message\":\"Invalid username or password.\"}"
        val login=assertThrows(ApiFailure::class.java) {NativeApiResponse.read(401,"application/json",body,login=true)}
        val expired=assertThrows(ApiFailure::class.java) {NativeApiResponse.read(401,"application/json",body)}
        assertEquals(401,login.status)
        assertTrue(login.message!!.contains("用户名或密码"))
        assertFalse(login.message!!.contains("过期"))
        assertTrue(expired.message!!.contains("过期"))
    }
    @Test fun genuineServer500AndSafeReasonRemainVisibleWhileSensitiveContextIsRedacted() {
        val body="""{"message":"Database unavailable at https://private.example:1999/install?token=secret; Authorization: Bearer shmobile_abc123"}"""
        val failure=assertThrows(ApiFailure::class.java) {NativeApiResponse.read(500,"application/json",body,login=true)}
        assertEquals(500,failure.status)
        assertTrue(failure.message!!.contains("HTTP 500"))
        assertTrue(failure.message!!.contains("Database unavailable"))
        listOf("private.example","secret","shmobile_abc123").forEach {assertFalse(failure.message!!.contains(it))}
    }
    @Test fun safeServerReasonDoesNotExposeBareHostsAddressesOrCredentialFields() {
        val body="""{"message":"Connection refused at private.example:1999 and 192.168.31.100:8039 and [fd00::1]:8039; token=confidential password=sample"}"""
        val failure=assertThrows(ApiFailure::class.java) {NativeApiResponse.read(500,"application/json",body,login=true)}
        assertTrue(failure.message!!.contains("Connection refused"))
        listOf("private.example","192.168.31.100","fd00::1","confidential","sample").forEach {assertFalse(failure.message!!.contains(it))}
    }
    @Test fun redirectsAndMissingLoginRoutesExplainAddressProxyAndVersionChecks() {
        val redirect=assertThrows(ApiFailure::class.java) {NativeApiResponse.read(302,"text/html","<html>https://secret.example</html>",login=true)}
        assertTrue(redirect.message!!.contains("HTTP 302"))
        assertTrue(redirect.message!!.contains("重定向"))
        assertFalse(redirect.message!!.contains("secret.example"))
        val missing=assertThrows(ApiFailure::class.java) {NativeApiResponse.read(404,"application/json","{\"message\":\"Not found\"}",login=true)}
        assertTrue(missing.message!!.contains("公网代理"))
        assertTrue(missing.message!!.contains("版本"))
    }
    @Test fun validJsonAndBodylessRevocationAndRealExportsKeepTheirContracts() {
        val body="{\"apiVersion\":1}"
        assertEquals(body,NativeApiResponse.read(200,"Application/JSON; charset=utf-8",body))
        assertEquals(body,NativeApiResponse.read(200,"application/problem+json",body))
        assertEquals("",NativeApiResponse.read(204,null,""))
        val gpx="<?xml version=\"1.0\"?><gpx></gpx>"
        assertEquals(gpx,NativeApiResponse.read(200,"application/gpx+xml",gpx,expectJson=false))
        assertThrows(ApiFailure::class.java) {NativeApiResponse.read(200,"text/html","<html>Panel</html>",expectJson=false)}
    }
    @Test fun transportErrorsDescribeActualFailureCategoryWithoutEchoingAddresses() {
        val cases=listOf(
            UnknownHostException("private.example") to "解析",
            SSLHandshakeException("https://private.example secret") to "证书",
            ConnectException("connect private.example:1999") to "连接",
            SocketTimeoutException("private.example secret") to "超时"
        )
        cases.forEach {(source,expected)->val failure=NativeApiResponse.networkFailure(source);assertTrue(failure.message!!.contains(expected));assertFalse(failure.message!!.contains("private.example"));assertFalse(failure.message!!.contains("secret"))}
    }
}
