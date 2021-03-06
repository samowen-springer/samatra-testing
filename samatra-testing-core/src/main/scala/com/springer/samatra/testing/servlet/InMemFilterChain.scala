package com.springer.samatra.testing.servlet

import java.io.{ByteArrayOutputStream, PrintStream}
import java.security.Principal
import java.util
import java.util.Collections
import java.util.concurrent.{CopyOnWriteArrayList, CountDownLatch, TimeUnit}

import javax.servlet._
import javax.servlet.http.{HttpServletRequest, HttpServletResponse}

import scala.collection.JavaConverters._
import scala.collection.mutable

object InMemFilterChain {

  def apply(request: HttpServletRequest, response: HttpServletResponse, filters: Seq[(String, Filter, Option[Principal])], contextAndServlet: Option[(Servlet, ServletContext, Map[String, String])], asyncLatch: CountDownLatch, asyncListeners: CopyOnWriteArrayList[AsyncListener]): Unit = {
    try {
      contextAndServlet.foreach { case (s, c, ip) => s.init(new ServletConfig {
        override def getServletContext: ServletContext = c
        override def getServletName: String = s"in-mem/${s.hashCode()}"
        override def getInitParameterNames: util.Enumeration[String] = Collections.enumeration(ip.keySet.asJava)
        override def getInitParameter(name: String): String = ip.getOrElse(name, null)
      })
      }
      new InMemFilterChain(filters, request.getRequestURL.toString, contextAndServlet.map(_._1)).doFilter(request, response)

      if (request.isAsyncStarted) {
        if (!asyncLatch.await(request.getAsyncContext.getTimeout, TimeUnit.MILLISECONDS)) {
          asyncListeners.asScala.foreach(_.onTimeout(new AsyncEvent(request.getAsyncContext)))
        }
      }
    } catch {
      case t: Throwable =>
        asyncListeners.asScala.foreach(_.onError(new AsyncEvent(request.getAsyncContext, t)))
        request.setAttribute("javax.servlet.error.exception", t)
        throw t
    } finally {
      Option(request.getAttribute("javax.servlet.error.exception")).foreach { t =>
        if (!response.isCommitted) {
          val out = new ByteArrayOutputStream()
          t.asInstanceOf[Throwable].printStackTrace(new PrintStream(out))
          response.getOutputStream.write(out.toByteArray)
        }
      }
    }
  }
}

class InMemFilterChain(filters: Seq[(String, Filter, Option[Principal])], path: String, servlet: Option[Servlet]) extends FilterChain {
  //noinspection ScalaDeprecation
  val fs: mutable.Stack[(Filter, Option[Principal])] = applicableFilters(filters, path)

  override def doFilter(req: ServletRequest, res: ServletResponse): Unit = {
    if (fs.nonEmpty) {
      val (f, user) = fs.pop
      user.foreach { u =>
        req.asInstanceOf[InMemHttpServletRequest].setUserPrincipal(u)
      }

      f.doFilter(req, res, this)
    }
    else if (servlet.isDefined) {
      servlet.get.service(req, res)
    }
    else res.asInstanceOf[HttpServletResponse].setStatus(404)
  }

  private def applicableFilters[T](filters: Seq[(String, Filter, Option[Principal])], path: String) = {
    mutable.Stack(filters.collect {
      case (p, f, u) if path.startsWith(path.substring(p.length - 2)) => f -> u
    }: _*)
  }
}
