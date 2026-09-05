package pm

import java.time.{Instant, LocalDate, ZoneId, ZonedDateTime}
import java.time.format.DateTimeFormatter
import scala.util.Try

/** Shared helpers: escaping, dates, misc formatting. */
object Util {
  /** HTML-escape text for element or attribute content. */
  def esc(s: String): String = {
    val sb = new StringBuilder(s.length + 16)
    var i = 0
    while (i < s.length) {
      s.charAt(i) match {
        case '&'  => sb.append("&amp;")
        case '<'  => sb.append("&lt;")
        case '>'  => sb.append("&gt;")
        case '"'  => sb.append("&quot;")
        case '\'' => sb.append("&#39;")
        case c    => sb.append(c)
      }
      i += 1
    }
    sb.toString
  }

  def escOpt(s: Option[String]): String = s.map(esc).getOrElse("")

  def nowMillis: Long = System.currentTimeMillis()

  def isoDate(d: LocalDate): String = d.toString

  def parseDate(s: String): Option[LocalDate] =
    Option(s).map(_.trim).filter(_.nonEmpty).flatMap(x => Try(LocalDate.parse(x)).toOption)

  def humanDate(s: String): String =
    parseDate(s).map(d => d.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))).getOrElse("—")

  def humanDateTime(millis: Long): String =
    ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
      .format(DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm"))

  /** Relative human timestamp used server-side (JS upgrades to live values). */
  def relTime(millis: Long): String = {
    val diff = math.max(0L, System.currentTimeMillis() - millis)
    val min = 60000L; val hour = 3600000L; val day = 86400000L
    if (diff < min) "just now"
    else if (diff < hour) s"${diff / min}m ago"
    else if (diff < day) s"${diff / hour}h ago"
    else if (diff < 7 * day) s"${diff / day}d ago"
    else humanDate(LocalDate.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()).toString)
  }

  def initials(name: String): String = {
    val parts = name.trim.split("\\s+").filter(_.nonEmpty).toList
    parts match {
      case Nil          => "?"
      case a :: Nil     => a.take(1).toUpperCase
      case a :: b :: _  => (a.take(1) + b.take(1)).toUpperCase
    }
  }

  def fmtMinutes(total: Int): String = {
    val h = total / 60; val m = total % 60
    if (h == 0) s"${m}m"
    else if (m == 0) s"${h}h"
    else s"${h}h ${m}m"
  }

  def fmtMinutesShort(total: Int): String = {
    val h = total / 60; val m = total % 60
    if (h == 0) s"${m}m" else if (m == 0) s"${h}h" else s"${h}h${m}m"
  }

  /** Signed sha-256 hex (first n chars) — used for CSRF binding. */
  def hmacHex(secret: String, value: String, n: Int = 32): String = {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    md.update(secret.getBytes("UTF-8"))
    md.update(value.getBytes("UTF-8"))
    md.digest().map(b => f"${b & 0xff}%02x").mkString.take(n)
  }

  def randomHex(bytes: Int): String = {
    val arr = new Array[Byte](bytes)
    new java.security.SecureRandom().nextBytes(arr)
    arr.map(b => f"${b & 0xff}%02x").mkString
  }

  def urlEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
  def urlDecode(s: String): String =
    Try(java.net.URLDecoder.decode(s, "UTF-8")).getOrElse(s)

  def queryParam(params: Map[String, String], key: String): Option[String] =
    params.get(key).flatMap(v => Option(v).map(_.trim).filter(_.nonEmpty))

  def intParam(params: Map[String, String], key: String): Option[Int] =
    params.get(key).flatMap(v => Try(v.trim.toInt).toOption)
}
