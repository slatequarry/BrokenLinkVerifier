import scala.App

import scala.collection._
import scala.collection.JavaConverters._

import scala.concurrent.{Future, ExecutionContext, Await}
import scala.concurrent.duration.Duration

import java.net.URL

import java.util.concurrent.{ThreadPoolExecutor, ForkJoinPool, Executors, LinkedBlockingQueue, TimeUnit}

import org.jsoup.{Jsoup, UnsupportedMimeTypeException}
import org.jsoup.nodes.Element

import org.apache.commons.mail.HtmlEmail

case class Config( url :URL = null, mail :Boolean = false, host :String = "", port :Int = 25, to :Seq[String] = Seq(), from :String = "" )
    
object Main extends App {

  val parser = new scopt.OptionParser[Config]("blv") {
    head("BrokenLinkVerifier", "0.9")
    note("search brocken links on a html page.")
    help("help") text("prints this usage text")
    arg[String]("<url>") unbounded()  action { (x, c) => c.copy(url = new URL(x)) } text("url to verify")
    opt[Unit]('m',"mail") action { (_, c) => c.copy(mail = true) } text("send error report mail")    
    opt[String]('h',"host") action { (x, c) => c.copy(host = x) } text("smtp host")
    opt[Int]('p',"port") action { (x, c) => c.copy(port = x) } text("smtp port")
    opt[Seq[String]]('t',"to") valueName("<to>,<to>...") action { (x, c) => c.copy(to = x) } text("mail to")
    opt[String]('f',"from") action { (x, c) => c.copy(from = x) } text("mail from")
    checkConfig { c =>
      if ( (!c.mail) || 
         ( (!c.host.isEmpty()) && (!c.to.isEmpty) && (!c.from.isEmpty()) ) ) 
        success
        else failure("mail needs to set host, to and from options")
    }
  }

  parser.parse(args, Config()) match {
    case Some(config) =>
      verify( config )
  
    case None =>
      System.exit(-1)
  }

  def verify(config :Config) = {
    try {    
      println( s"start crawling ${config.url} ...\n" )
      val t0 = System.nanoTime()
  
      lazy val procs = Runtime.getRuntime().availableProcessors();    
      lazy val tpe = new ThreadPoolExecutor(procs, procs, 
  						       1000L, TimeUnit.MILLISECONDS,
  						       new LinkedBlockingQueue[Runnable]());
      lazy val ec = ExecutionContext.fromExecutor(tpe)
      
//      lazy val fjp = new ForkJoinPool()
//      lazy val ec = ExecutionContext.fromExecutor(fjp)
      
      case class Link (ok :Boolean, root :URL, tag :Element, ex :Exception) 
      
      val urls :concurrent.Map[URL, Future[Link]] = new concurrent.TrieMap[URL, Future[Link]]
      
      def verifyUrl(url :URL, root :URL, tag :Element) :Unit = {
        
        lazy val future = {
          Future ({
            try {
              Jsoup.connect(url.toString())
                .get()
                .getElementsByTag("a").asScala.toList
                .foreach { tag =>
                  verifyUrl(new URL(tag.absUrl("href")),url,tag)
                }
              Link(true, root, tag, null)  
            } catch {
              case umte :org.jsoup.UnsupportedMimeTypeException =>
                Link(true, root, tag, null)  
              case ex :Exception => 
                Link(false, root, tag, ex)  
            }
          }) (ec)
        }      
        urls.putIfAbsent(url, future )    
        
      }
      
      verifyUrl(config.url, null, null)
      
      while (tpe.getActiveCount > 0) {}
      tpe shutdown 

//      while (fjp.getQueuedTaskCount > 0) {}
//      fjp shutdown 
      
      println( "stop crawling.\n" )
  
      val bads = urls mapValues { Await.result(_, Duration.Inf) } filter { url => !url._2.ok }

      val t = (System.nanoTime()-t0)/1000/1000 
      
      println( s"${urls.size} links verified in $t ms !\n" )
      
      val res =
        bads map { url =>
            s"${url._1} => ${if (url._2.ok) "Ok" else "broken link !"}\n" +
            s"  root = ${url._2.root}\n" +
            s"  tag = ${url._2.tag.text()}" +
            ( if (url._2.ex != null) s"\n  error = ${url._2.ex}" else "" )
        } mkString("\n\n")
        
      if (bads.size==0)
        println( "no broken link found :)")
      else {
        println( s"${bads.size} broken link${if(bads.size>1)"s"else""} found :(\n" )
      
        println( res ) 
        
        if (config.mail) {
          new HtmlEmail {
            setHostName(config.host)
            setSmtpPort(config.port)
            config.to foreach addTo
            setFrom(config.from)
            setSubject( s"Brocken Link Verifier - ${config.url} - ${bads.size} broken link${if(bads.size>1)"s"else""} found :(" )
            setHtmlMsg( s"""<html
              Root-Link: $config.url<br><br>
              ${urls.size} links verified in $t ms !<br><br>
              <font size="5" color="red"><b>${bads.size} broken link${if(bads.size>1)"s"else""} found :(</b></font><br><br>
              ${res.replace("\n", "<br>")}
              </html>""")
            send
          }
          println( s"\nerror mail send to ${config.to mkString ","}." ) 
        }
      }
  
    } catch {
      case t :Throwable => {
        System.err.println( s"unhandled exception raised ! $t" )
        System.exit(1)
      }
    }      
  }
}