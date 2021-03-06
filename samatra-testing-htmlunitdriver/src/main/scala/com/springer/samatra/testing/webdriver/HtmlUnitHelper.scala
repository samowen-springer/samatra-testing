package com.springer.samatra.testing.webdriver

import java.net.URL
import java.net.URLEncoder.encode
import java.util
import java.util.Date

import com.gargoylesoftware.htmlunit.HttpMethod._
import com.gargoylesoftware.htmlunit._
import com.gargoylesoftware.htmlunit.util.{Cookie, NameValuePair}
import io.netty.handler.codec.http.cookie.DefaultCookie
import org.apache.http.HttpStatus
import org.asynchttpclient.{AsyncHttpClient, Response}
import org.openqa.selenium.WebDriver
import org.openqa.selenium.htmlunit.HtmlUnitDriver

import scala.collection.JavaConverters._


object HtmlUnitHelper {
  implicit class SamatraHtmlDriverWrapper(http: AsyncHttpClient) {
    def driver: WebDriver = new SamatraHtmlDriver(http)
  }

  class AsyncHttpWebConnection(http: AsyncHttpClient, client: WebClient) extends WebConnection {
    def close(): Unit = ()

    override def getResponse(request: WebRequest): WebResponse = {
      val uri = request.getUrl.toURI
      val qs = Option(uri.getRawQuery)

      def execute(path: String, method: HttpMethod) = {
        val pathAndQuery = s"$path${qs.map(q => s"?$q").getOrElse("")}"

        val httpReq = method match {
          case HEAD => http.prepareHead(pathAndQuery)
          case PATCH => http.preparePatch(pathAndQuery)
          case TRACE => http.prepareTrace(pathAndQuery)
          case GET => http.prepareGet(pathAndQuery)
          case POST => http.preparePost(pathAndQuery)
          case PUT => http.preparePut(pathAndQuery).setBody(request.getRequestBody)
          case DELETE => http.prepareDelete(pathAndQuery)
          case OPTIONS => http.prepareOptions(pathAndQuery)
        }

        request.getAdditionalHeaders.asScala.foreach {
          case (n, v) => httpReq.setHeader(n, v)
        }

        if (!request.getRequestParameters.isEmpty) {
          httpReq.setHeader("Content-Type", "application/x-www-form-urlencoded")
          httpReq.setBody(request.getRequestParameters.asScala.map(p => s"${encode(p.getName, "UTF-8")}=${encode(p.getValue, "UTF-8")}").mkString("&"))
        }

        client.getCookieManager.getCookies.asScala.foreach { cookie =>
          httpReq.addCookie(new DefaultCookie(cookie.getName, cookie.getValue))
        }

        val resp = httpReq.execute().get
        resp.getCookies.asScala.foreach{ c =>
          val date = if (c.maxAge() > 0) new Date(new Date().getTime + (c.maxAge() * 1000L)) else new Date(new Date().getTime + 1000*60*60*24*365L)
          client.getCookieManager.addCookie(new Cookie("samatra-webdriver", c.name(), c.value(), c.path(), date, c.isSecure, c.isHttpOnly))
        }
        resp
      }

      val path = uri.getPath
      var resp: Response = execute(path, request.getHttpMethod)

      while (resp.getStatusCode > 300 && resp.getStatusCode < 400 && resp.getHeader("Location") != null) {
        resp = execute(resp.getHeader("Location"), GET)
      }

      new WebResponse(getWebResponseData(resp), buildWebRequest(new URL(resp.getUri.toUrl)), 0)
    }

    private def getWebResponseData(response: Response): WebResponseData = {
      val content: Array[Byte] = TextUtil.stringToByteArray(response.getResponseBody, "UTF-8")
      val compiledHeaders: util.List[NameValuePair] = new util.ArrayList[NameValuePair]
      response.getHeaders.asScala.foreach { header =>
        compiledHeaders.add(new NameValuePair(header.getKey, header.getValue))
      }
      new WebResponseData(content, HttpStatus.SC_OK, "OK", compiledHeaders)
    }

    private def buildWebRequest(originatingURL: URL): WebRequest = {
      val webRequest: WebRequest = new WebRequest(originatingURL, HttpMethod.GET)
      webRequest.setCharset("UTF-8")
      webRequest
    }
  }

  class SamatraHtmlDriver(http: AsyncHttpClient) extends HtmlUnitDriver() {
    self =>

    override def get(url: String): Unit = if (WebClient.URL_ABOUT_BLANK.toString == url)
      super.get(url)
    else
      super.get(new URL(s"http://samatra-webdriver$url"))

    class RelativeUrlNav extends WebDriver.Navigation {
      override def forward(): Unit = SamatraHtmlDriver.super.navigate().forward()
      override def refresh(): Unit = SamatraHtmlDriver.super.navigate().refresh()
      override def back(): Unit = SamatraHtmlDriver.super.navigate().back()
      override def to(s: String): Unit = to(new URL(s"http://samatra-webdriver$s"))
      override def to(url: URL): Unit = SamatraHtmlDriver.super.navigate().to(url)
    }

    override def navigate(): WebDriver.Navigation = new RelativeUrlNav()

    override def modifyWebClient(client: WebClient): WebClient = {
      client.setWebConnection(new AsyncHttpWebConnection(http, client))
      client.getCookieManager.setCookiesEnabled(true)
      client
    }
  }
}
